/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef DEX_BUILDER_H_
#define DEX_BUILDER_H_

#include <map>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "slicer/dex_ir.h"
#include "slicer/writer.h"

namespace startop {
namespace dex {

// TODO: remove this once the dex generation code is complete.
void WriteTestDexFile(const std::string& filename);

//////////////////////////
// Forward declarations //
//////////////////////////
class DexBuilder;

// Our custom allocator for dex::Writer
//
// This keeps track of all allocations and ensures they are freed when
// TrackingAllocator is destroyed. Pointers to memory allocated by this
// allocator must not outlive the allocator.
class TrackingAllocator : public ::dex::Writer::Allocator {
 public:
  virtual void* Allocate(size_t size);
  virtual void Free(void* ptr);

 private:
  std::unordered_map<void*, std::unique_ptr<uint8_t[]>> allocations_;
};

// Represents a DEX type descriptor.
//
// TODO: add a way to create a descriptor for a reference of a class type.
class TypeDescriptor {
 public:
  // Named constructors for base type descriptors.
  static const TypeDescriptor Int();
  static const TypeDescriptor Void();

  // Creates a type descriptor from a fully-qualified class name. For example, it turns the class
  // name java.lang.Object into the descriptor Ljava/lang/Object.
  static TypeDescriptor FromClassname(const std::string& name);

  // Return the full descriptor, such as I or Ljava/lang/Object
  const std::string& descriptor() const { return descriptor_; }
  // Return the shorty descriptor, such as I or L
  std::string short_descriptor() const { return descriptor().substr(0, 1); }

  bool operator<(const TypeDescriptor& rhs) const { return descriptor_ < rhs.descriptor_; }

 private:
  TypeDescriptor(std::string descriptor) : descriptor_{descriptor} {}

  const std::string descriptor_;
};

// Defines a function signature. For example, Prototype{TypeDescriptor::VOID, TypeDescriptor::Int}
// represents the function type (Int) -> Void.
class Prototype {
 public:
  template <typename... TypeDescriptors>
  Prototype(TypeDescriptor return_type, TypeDescriptors... param_types)
      : return_type_{return_type}, param_types_{param_types...} {}

  // Encode this prototype into the dex file.
  ir::Proto* Encode(DexBuilder* dex) const;

  // Get the shorty descriptor, such as VII for (Int, Int) -> Void
  std::string Shorty() const;

  bool operator<(const Prototype& rhs) const {
    return std::make_tuple(return_type_, param_types_) <
           std::make_tuple(rhs.return_type_, rhs.param_types_);
  }

 private:
  const TypeDescriptor return_type_;
  const std::vector<TypeDescriptor> param_types_;
};

// Represents a DEX register or constant. We separate regular registers and parameters
// because we will not know the real parameter id until after all instructions
// have been generated.
class Value {
 public:
  static constexpr Value Local(size_t id) { return Value{id, Kind::kLocalRegister}; }
  static constexpr Value Parameter(size_t id) { return Value{id, Kind::kParameter}; }
  static constexpr Value Immediate(size_t value) { return Value{value, Kind::kImmediate}; }

  bool is_register() const { return kind_ == Kind::kLocalRegister; }
  bool is_parameter() const { return kind_ == Kind::kParameter; }
  bool is_immediate() const { return kind_ == Kind::kImmediate; }

  size_t value() const { return value_; }

 private:
  enum class Kind { kLocalRegister, kParameter, kImmediate };

  const size_t value_;
  const Kind kind_;

  constexpr Value(size_t value, Kind kind) : value_{value}, kind_{kind} {}
};

// A virtual instruction. We convert these to real instructions in MethodBuilder::Encode.
// Virtual instructions are needed to keep track of information that is not known until all of the
// code is generated. This information includes things like how many local registers are created and
// branch target locations.
class Instruction {
 public:
  // The operation performed by this instruction. These are virtual instructions that do not
  // correspond exactly to DEX instructions.
  enum class Op { kReturn, kMove, kInvokeVirtual };

  ////////////////////////
  // Named Constructors //
  ////////////////////////

  // For instructions with no return value and no arguments.
  static inline Instruction OpNoArgs(Op opcode) {
    return Instruction{opcode, /*method_id*/ 0, /*dest*/ {}};
  }
  // For most instructions, which take some number of arguments and have an optional return value.
  template <typename... T>
  static inline Instruction OpWithArgs(Op opcode, std::optional<const Value> dest, T... args) {
    return Instruction{opcode, /*method_id*/ 0, dest, args...};
  }
  // For method calls.
  template <typename... T>
  static inline Instruction InvokeVirtual(size_t method_id, std::optional<const Value> dest,
                                          Value this_arg, T... args) {
    return Instruction{Op::kInvokeVirtual, method_id, dest, this_arg, args...};
  }

  ///////////////
  // Accessors //
  ///////////////

  Op opcode() const { return opcode_; }
  size_t method_id() const { return method_id_; }
  const std::optional<const Value>& dest() const { return dest_; }
  const std::vector<const Value>& args() const { return args_; }

 private:
  inline Instruction(Op opcode, size_t method_id, std::optional<const Value> dest)
      : opcode_{opcode}, method_id_{method_id}, dest_{dest}, args_{} {}

  template <typename... T>
  inline constexpr Instruction(Op opcode, size_t method_id, std::optional<const Value> dest,
                               T... args)
      : opcode_{opcode}, method_id_{method_id}, dest_{dest}, args_{args...} {}

  const Op opcode_;
  // The index of the method to invoke, for kInvokeVirtual and similar opcodes.
  const size_t method_id_{0};
  const std::optional<const Value> dest_;
  const std::vector<const Value> args_;
};

// Needed for CHECK_EQ, DCHECK_EQ, etc.
std::ostream& operator<<(std::ostream& out, const Instruction::Op& opcode);

// Tools to help build methods and their bodies.
class MethodBuilder {
 public:
  MethodBuilder(DexBuilder* dex, ir::Class* class_def, ir::MethodDecl* decl);

  // Encode the method into DEX format.
  ir::EncodedMethod* Encode();

  // Create a new register to be used to storing values. Note that these are not SSA registers, like
  // might be expected in similar code generators. This does no liveness tracking or anything, so
  // it's up to the caller to reuse registers as appropriate.
  Value MakeRegister();

  /////////////////////////////////
  // Instruction builder methods //
  /////////////////////////////////

  void AddInstruction(Instruction instruction);

  // return-void
  void BuildReturn();
  void BuildReturn(Value src);
  // const/4
  void BuildConst4(Value target, int value);

  // TODO: add builders for more instructions

 private:
  void EncodeInstructions();
  void EncodeInstruction(const Instruction& instruction);
  void EncodeReturn(const Instruction& instruction);
  void EncodeMove(const Instruction& instruction);
  void EncodeInvokeVirtual(const Instruction& instruction);

  // Converts a register or parameter to its DEX register number.
  size_t RegisterValue(Value value) const;

  DexBuilder* dex_;
  ir::Class* class_;
  ir::MethodDecl* decl_;

  // A list of the instructions we will eventually encode.
  std::vector<Instruction> instructions_;

  // A buffer to hold instructions that have been encoded.
  std::vector<::dex::u2> buffer_;

  // How many registers we've allocated
  size_t num_registers_{0};
};

// A helper to build class definitions.
class ClassBuilder {
 public:
  ClassBuilder(DexBuilder* parent, const std::string& name, ir::Class* class_def);

  void set_source_file(const std::string& source);

  // Create a method with the given name and prototype. The returned MethodBuilder can be used to
  // fill in the method body.
  MethodBuilder CreateMethod(const std::string& name, Prototype prototype);

 private:
  DexBuilder* const parent_;
  const TypeDescriptor type_descriptor_;
  ir::Class* const class_;
};

// Keeps track of information needed to manipulate or call a method.
struct MethodDeclData {
  size_t id;
  ir::MethodDecl* decl;
};

// Builds Dex files from scratch.
class DexBuilder {
 public:
  DexBuilder();

  // Create an in-memory image of the DEX file that can either be loaded directly or written to a
  // file.
  slicer::MemView CreateImage();

  template <typename T>
  T* Alloc() {
    return dex_file_->Alloc<T>();
  }

  // Find the ir::String that matches the given string, creating it if it does not exist.
  ir::String* GetOrAddString(const std::string& string);
  // Create a new class of the given name.
  ClassBuilder MakeClass(const std::string& name);

  // Add a type for the given descriptor, or return the existing one if it already exists.
  // See the TypeDescriptor class for help generating these. GetOrAddType can be used to declare
  // imported classes.
  ir::Type* GetOrAddType(const std::string& descriptor);

  // Returns the method id for the method, creating it if it has not been created yet.
  const MethodDeclData& GetOrDeclareMethod(TypeDescriptor type, const std::string& name,
                                           Prototype prototype);

 private:
  // Looks up the ir::Proto* corresponding to this given prototype, or creates one if it does not
  // exist.
  ir::Proto* GetOrEncodeProto(Prototype prototype);

  std::shared_ptr<ir::DexFile> dex_file_;

  // allocator_ is needed to be able to encode the image.
  TrackingAllocator allocator_;

  // We'll need to allocate buffers for all of the encoded strings we create. This is where we store
  // all of them.
  std::vector<std::unique_ptr<uint8_t[]>> string_data_;

  // Keep track of what types we've defined so we can look them up later.
  std::unordered_map<std::string, ir::Type*> types_by_descriptor_;

  struct MethodDescriptor {
    TypeDescriptor type;
    std::string name;
    Prototype prototype;

    inline bool operator<(const MethodDescriptor& rhs) const {
      return std::make_tuple(type, name, prototype) <
             std::make_tuple(rhs.type, rhs.name, rhs.prototype);
    }
  };

  // Maps method declarations to their method index. This is needed to encode references to them.
  // When we go to actually write the DEX file, slicer will re-assign these after correctly sorting
  // the methods list.
  std::map<MethodDescriptor, MethodDeclData> method_id_map_;

  // Keep track of what strings we've defined so we can look them up later.
  std::unordered_map<std::string, ir::String*> strings_;

  // Keep track of already-encoded protos.
  std::map<Prototype, ir::Proto*> proto_map_;
};

}  // namespace dex
}  // namespace startop

#endif  // DEX_BUILDER_H_
