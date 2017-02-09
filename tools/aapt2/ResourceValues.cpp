/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "ResourceValues.h"

#include <algorithm>
#include <limits>
#include <set>

#include "androidfw/ResourceTypes.h"

#include "Resource.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "util/Util.h"

namespace aapt {

template <typename Derived>
void BaseValue<Derived>::Accept(RawValueVisitor* visitor) {
  visitor->Visit(static_cast<Derived*>(this));
}

template <typename Derived>
void BaseItem<Derived>::Accept(RawValueVisitor* visitor) {
  visitor->Visit(static_cast<Derived*>(this));
}

RawString::RawString(const StringPool::Ref& ref) : value(ref) {}

bool RawString::Equals(const Value* value) const {
  const RawString* other = ValueCast<RawString>(value);
  if (!other) {
    return false;
  }
  return *this->value == *other->value;
}

RawString* RawString::Clone(StringPool* new_pool) const {
  RawString* rs = new RawString(new_pool->MakeRef(*value));
  rs->comment_ = comment_;
  rs->source_ = source_;
  return rs;
}

bool RawString::Flatten(android::Res_value* out_value) const {
  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

void RawString::Print(std::ostream* out) const {
  *out << "(raw string) " << *value;
}

Reference::Reference() : reference_type(Type::kResource) {}

Reference::Reference(const ResourceNameRef& n, Type t)
    : name(n.ToResourceName()), reference_type(t) {}

Reference::Reference(const ResourceId& i, Type type)
    : id(i), reference_type(type) {}

Reference::Reference(const ResourceNameRef& n, const ResourceId& i)
    : name(n.ToResourceName()), id(i), reference_type(Type::kResource) {}

bool Reference::Equals(const Value* value) const {
  const Reference* other = ValueCast<Reference>(value);
  if (!other) {
    return false;
  }
  return reference_type == other->reference_type &&
         private_reference == other->private_reference && id == other->id &&
         name == other->name;
}

bool Reference::Flatten(android::Res_value* out_value) const {
  out_value->dataType = (reference_type == Reference::Type::kResource)
                            ? android::Res_value::TYPE_REFERENCE
                            : android::Res_value::TYPE_ATTRIBUTE;
  out_value->data = util::HostToDevice32(id ? id.value().id : 0);
  return true;
}

Reference* Reference::Clone(StringPool* /*new_pool*/) const {
  return new Reference(*this);
}

void Reference::Print(std::ostream* out) const {
  *out << "(reference) ";
  if (reference_type == Reference::Type::kResource) {
    *out << "@";
    if (private_reference) {
      *out << "*";
    }
  } else {
    *out << "?";
  }

  if (name) {
    *out << name.value();
  }

  if (id && !Res_INTERNALID(id.value().id)) {
    *out << " " << id.value();
  }
}

bool Id::Equals(const Value* value) const {
  return ValueCast<Id>(value) != nullptr;
}

bool Id::Flatten(android::Res_value* out) const {
  out->dataType = android::Res_value::TYPE_INT_BOOLEAN;
  out->data = util::HostToDevice32(0);
  return true;
}

Id* Id::Clone(StringPool* /*new_pool*/) const { return new Id(*this); }

void Id::Print(std::ostream* out) const { *out << "(id)"; }

String::String(const StringPool::Ref& ref) : value(ref) {}

bool String::Equals(const Value* value) const {
  const String* other = ValueCast<String>(value);
  if (!other) {
    return false;
  }

  if (this->value != other->value) {
    return false;
  }

  if (untranslatable_sections.size() != other->untranslatable_sections.size()) {
    return false;
  }

  auto other_iter = other->untranslatable_sections.begin();
  for (const UntranslatableSection& this_section : untranslatable_sections) {
    if (this_section != *other_iter) {
      return false;
    }
    ++other_iter;
  }
  return true;
}

bool String::Flatten(android::Res_value* out_value) const {
  // Verify that our StringPool index is within encode-able limits.
  if (value.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

String* String::Clone(StringPool* new_pool) const {
  String* str = new String(new_pool->MakeRef(*value));
  str->comment_ = comment_;
  str->source_ = source_;
  str->untranslatable_sections = untranslatable_sections;
  return str;
}

void String::Print(std::ostream* out) const {
  *out << "(string) \"" << *value << "\"";
}

StyledString::StyledString(const StringPool::StyleRef& ref) : value(ref) {}

bool StyledString::Equals(const Value* value) const {
  const StyledString* other = ValueCast<StyledString>(value);
  if (!other) {
    return false;
  }

  if (this->value != other->value) {
    return false;
  }

  if (untranslatable_sections.size() != other->untranslatable_sections.size()) {
    return false;
  }

  auto other_iter = other->untranslatable_sections.begin();
  for (const UntranslatableSection& this_section : untranslatable_sections) {
    if (this_section != *other_iter) {
      return false;
    }
    ++other_iter;
  }
  return true;
}

bool StyledString::Flatten(android::Res_value* out_value) const {
  if (value.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

StyledString* StyledString::Clone(StringPool* new_pool) const {
  StyledString* str = new StyledString(new_pool->MakeRef(value));
  str->comment_ = comment_;
  str->source_ = source_;
  str->untranslatable_sections = untranslatable_sections;
  return str;
}

void StyledString::Print(std::ostream* out) const {
  *out << "(styled string) \"" << *value->str << "\"";
  for (const StringPool::Span& span : value->spans) {
    *out << " " << *span.name << ":" << span.first_char << ","
         << span.last_char;
  }
}

FileReference::FileReference(const StringPool::Ref& _path) : path(_path) {}

bool FileReference::Equals(const Value* value) const {
  const FileReference* other = ValueCast<FileReference>(value);
  if (!other) {
    return false;
  }
  return path == other->path;
}

bool FileReference::Flatten(android::Res_value* out_value) const {
  if (path.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = util::HostToDevice32(static_cast<uint32_t>(path.index()));
  return true;
}

FileReference* FileReference::Clone(StringPool* new_pool) const {
  FileReference* fr = new FileReference(new_pool->MakeRef(*path));
  fr->file = file;
  fr->comment_ = comment_;
  fr->source_ = source_;
  return fr;
}

void FileReference::Print(std::ostream* out) const {
  *out << "(file) " << *path;
}

BinaryPrimitive::BinaryPrimitive(const android::Res_value& val) : value(val) {}

BinaryPrimitive::BinaryPrimitive(uint8_t dataType, uint32_t data) {
  value.dataType = dataType;
  value.data = data;
}

bool BinaryPrimitive::Equals(const Value* value) const {
  const BinaryPrimitive* other = ValueCast<BinaryPrimitive>(value);
  if (!other) {
    return false;
  }
  return this->value.dataType == other->value.dataType &&
         this->value.data == other->value.data;
}

bool BinaryPrimitive::Flatten(android::Res_value* out_value) const {
  out_value->dataType = value.dataType;
  out_value->data = util::HostToDevice32(value.data);
  return true;
}

BinaryPrimitive* BinaryPrimitive::Clone(StringPool* /*new_pool*/) const {
  return new BinaryPrimitive(*this);
}

void BinaryPrimitive::Print(std::ostream* out) const {
  switch (value.dataType) {
    case android::Res_value::TYPE_NULL:
      *out << "(null)";
      break;
    case android::Res_value::TYPE_INT_DEC:
      *out << "(integer) " << static_cast<int32_t>(value.data);
      break;
    case android::Res_value::TYPE_INT_HEX:
      *out << "(integer) 0x" << std::hex << value.data << std::dec;
      break;
    case android::Res_value::TYPE_INT_BOOLEAN:
      *out << "(boolean) " << (value.data != 0 ? "true" : "false");
      break;
    case android::Res_value::TYPE_INT_COLOR_ARGB8:
    case android::Res_value::TYPE_INT_COLOR_RGB8:
    case android::Res_value::TYPE_INT_COLOR_ARGB4:
    case android::Res_value::TYPE_INT_COLOR_RGB4:
      *out << "(color) #" << std::hex << value.data << std::dec;
      break;
    default:
      *out << "(unknown 0x" << std::hex << (int)value.dataType << ") 0x"
           << std::hex << value.data << std::dec;
      break;
  }
}

Attribute::Attribute(bool w, uint32_t t)
    : type_mask(t),
      min_int(std::numeric_limits<int32_t>::min()),
      max_int(std::numeric_limits<int32_t>::max()) {
  weak_ = w;
}

template <typename T>
T* addPointer(T& val) {
  return &val;
}

bool Attribute::Equals(const Value* value) const {
  const Attribute* other = ValueCast<Attribute>(value);
  if (!other) {
    return false;
  }

  if (symbols.size() != other->symbols.size()) {
    return false;
  }

  if (type_mask != other->type_mask || min_int != other->min_int ||
      max_int != other->max_int) {
    return false;
  }

  std::vector<const Symbol*> sorted_a;
  std::transform(symbols.begin(), symbols.end(), std::back_inserter(sorted_a),
                 addPointer<const Symbol>);
  std::sort(sorted_a.begin(), sorted_a.end(),
            [](const Symbol* a, const Symbol* b) -> bool {
              return a->symbol.name < b->symbol.name;
            });

  std::vector<const Symbol*> sorted_b;
  std::transform(other->symbols.begin(), other->symbols.end(),
                 std::back_inserter(sorted_b), addPointer<const Symbol>);
  std::sort(sorted_b.begin(), sorted_b.end(),
            [](const Symbol* a, const Symbol* b) -> bool {
              return a->symbol.name < b->symbol.name;
            });

  return std::equal(sorted_a.begin(), sorted_a.end(), sorted_b.begin(),
                    [](const Symbol* a, const Symbol* b) -> bool {
                      return a->symbol.Equals(&b->symbol) &&
                             a->value == b->value;
                    });
}

Attribute* Attribute::Clone(StringPool* /*new_pool*/) const {
  return new Attribute(*this);
}

void Attribute::PrintMask(std::ostream* out) const {
  if (type_mask == android::ResTable_map::TYPE_ANY) {
    *out << "any";
    return;
  }

  bool set = false;
  if ((type_mask & android::ResTable_map::TYPE_REFERENCE) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "reference";
  }

  if ((type_mask & android::ResTable_map::TYPE_STRING) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "string";
  }

  if ((type_mask & android::ResTable_map::TYPE_INTEGER) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "integer";
  }

  if ((type_mask & android::ResTable_map::TYPE_BOOLEAN) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "boolean";
  }

  if ((type_mask & android::ResTable_map::TYPE_COLOR) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "color";
  }

  if ((type_mask & android::ResTable_map::TYPE_FLOAT) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "float";
  }

  if ((type_mask & android::ResTable_map::TYPE_DIMENSION) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "dimension";
  }

  if ((type_mask & android::ResTable_map::TYPE_FRACTION) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "fraction";
  }

  if ((type_mask & android::ResTable_map::TYPE_ENUM) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "enum";
  }

  if ((type_mask & android::ResTable_map::TYPE_FLAGS) != 0) {
    if (!set) {
      set = true;
    } else {
      *out << "|";
    }
    *out << "flags";
  }
}

void Attribute::Print(std::ostream* out) const {
  *out << "(attr) ";
  PrintMask(out);

  if (!symbols.empty()) {
    *out << " [" << util::Joiner(symbols, ", ") << "]";
  }

  if (min_int != std::numeric_limits<int32_t>::min()) {
    *out << " min=" << min_int;
  }

  if (max_int != std::numeric_limits<int32_t>::max()) {
    *out << " max=" << max_int;
  }

  if (IsWeak()) {
    *out << " [weak]";
  }
}

static void BuildAttributeMismatchMessage(DiagMessage* msg,
                                          const Attribute* attr,
                                          const Item* value) {
  *msg << "expected";
  if (attr->type_mask & android::ResTable_map::TYPE_BOOLEAN) {
    *msg << " boolean";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_COLOR) {
    *msg << " color";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_DIMENSION) {
    *msg << " dimension";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_ENUM) {
    *msg << " enum";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_FLAGS) {
    *msg << " flags";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_FLOAT) {
    *msg << " float";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_FRACTION) {
    *msg << " fraction";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_INTEGER) {
    *msg << " integer";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_REFERENCE) {
    *msg << " reference";
  }

  if (attr->type_mask & android::ResTable_map::TYPE_STRING) {
    *msg << " string";
  }

  *msg << " but got " << *value;
}

bool Attribute::Matches(const Item* item, DiagMessage* out_msg) const {
  android::Res_value val = {};
  item->Flatten(&val);

  // Always allow references.
  const uint32_t mask = type_mask | android::ResTable_map::TYPE_REFERENCE;
  if (!(mask & ResourceUtils::AndroidTypeToAttributeTypeMask(val.dataType))) {
    if (out_msg) {
      BuildAttributeMismatchMessage(out_msg, this, item);
    }
    return false;

  } else if (ResourceUtils::AndroidTypeToAttributeTypeMask(val.dataType) &
             android::ResTable_map::TYPE_INTEGER) {
    if (static_cast<int32_t>(util::DeviceToHost32(val.data)) < min_int) {
      if (out_msg) {
        *out_msg << *item << " is less than minimum integer " << min_int;
      }
      return false;
    } else if (static_cast<int32_t>(util::DeviceToHost32(val.data)) > max_int) {
      if (out_msg) {
        *out_msg << *item << " is greater than maximum integer " << max_int;
      }
      return false;
    }
  }
  return true;
}

bool Style::Equals(const Value* value) const {
  const Style* other = ValueCast<Style>(value);
  if (!other) {
    return false;
  }
  if (bool(parent) != bool(other->parent) ||
      (parent && other->parent &&
       !parent.value().Equals(&other->parent.value()))) {
    return false;
  }

  if (entries.size() != other->entries.size()) {
    return false;
  }

  std::vector<const Entry*> sorted_a;
  std::transform(entries.begin(), entries.end(), std::back_inserter(sorted_a),
                 addPointer<const Entry>);
  std::sort(sorted_a.begin(), sorted_a.end(),
            [](const Entry* a, const Entry* b) -> bool {
              return a->key.name < b->key.name;
            });

  std::vector<const Entry*> sorted_b;
  std::transform(other->entries.begin(), other->entries.end(),
                 std::back_inserter(sorted_b), addPointer<const Entry>);
  std::sort(sorted_b.begin(), sorted_b.end(),
            [](const Entry* a, const Entry* b) -> bool {
              return a->key.name < b->key.name;
            });

  return std::equal(sorted_a.begin(), sorted_a.end(), sorted_b.begin(),
                    [](const Entry* a, const Entry* b) -> bool {
                      return a->key.Equals(&b->key) &&
                             a->value->Equals(b->value.get());
                    });
}

Style* Style::Clone(StringPool* new_pool) const {
  Style* style = new Style();
  style->parent = parent;
  style->parent_inferred = parent_inferred;
  style->comment_ = comment_;
  style->source_ = source_;
  for (auto& entry : entries) {
    style->entries.push_back(
        Entry{entry.key, std::unique_ptr<Item>(entry.value->Clone(new_pool))});
  }
  return style;
}

void Style::Print(std::ostream* out) const {
  *out << "(style) ";
  if (parent && parent.value().name) {
    if (parent.value().private_reference) {
      *out << "*";
    }
    *out << parent.value().name.value();
  }
  *out << " [" << util::Joiner(entries, ", ") << "]";
}

static ::std::ostream& operator<<(::std::ostream& out,
                                  const Style::Entry& value) {
  if (value.key.name) {
    out << value.key.name.value();
  } else if (value.key.id) {
    out << value.key.id.value();
  } else {
    out << "???";
  }
  out << " = ";
  value.value->Print(&out);
  return out;
}

bool Array::Equals(const Value* value) const {
  const Array* other = ValueCast<Array>(value);
  if (!other) {
    return false;
  }

  if (items.size() != other->items.size()) {
    return false;
  }

  return std::equal(items.begin(), items.end(), other->items.begin(),
                    [](const std::unique_ptr<Item>& a,
                       const std::unique_ptr<Item>& b) -> bool {
                      return a->Equals(b.get());
                    });
}

Array* Array::Clone(StringPool* new_pool) const {
  Array* array = new Array();
  array->comment_ = comment_;
  array->source_ = source_;
  for (auto& item : items) {
    array->items.emplace_back(std::unique_ptr<Item>(item->Clone(new_pool)));
  }
  return array;
}

void Array::Print(std::ostream* out) const {
  *out << "(array) [" << util::Joiner(items, ", ") << "]";
}

bool Plural::Equals(const Value* value) const {
  const Plural* other = ValueCast<Plural>(value);
  if (!other) {
    return false;
  }

  if (values.size() != other->values.size()) {
    return false;
  }

  return std::equal(values.begin(), values.end(), other->values.begin(),
                    [](const std::unique_ptr<Item>& a,
                       const std::unique_ptr<Item>& b) -> bool {
                      if (bool(a) != bool(b)) {
                        return false;
                      }
                      return bool(a) == bool(b) || a->Equals(b.get());
                    });
}

Plural* Plural::Clone(StringPool* new_pool) const {
  Plural* p = new Plural();
  p->comment_ = comment_;
  p->source_ = source_;
  const size_t count = values.size();
  for (size_t i = 0; i < count; i++) {
    if (values[i]) {
      p->values[i] = std::unique_ptr<Item>(values[i]->Clone(new_pool));
    }
  }
  return p;
}

void Plural::Print(std::ostream* out) const {
  *out << "(plural)";
  if (values[Zero]) {
    *out << " zero=" << *values[Zero];
  }

  if (values[One]) {
    *out << " one=" << *values[One];
  }

  if (values[Two]) {
    *out << " two=" << *values[Two];
  }

  if (values[Few]) {
    *out << " few=" << *values[Few];
  }

  if (values[Many]) {
    *out << " many=" << *values[Many];
  }
}

static ::std::ostream& operator<<(::std::ostream& out,
                                  const std::unique_ptr<Item>& item) {
  return out << *item;
}

bool Styleable::Equals(const Value* value) const {
  const Styleable* other = ValueCast<Styleable>(value);
  if (!other) {
    return false;
  }

  if (entries.size() != other->entries.size()) {
    return false;
  }

  return std::equal(entries.begin(), entries.end(), other->entries.begin(),
                    [](const Reference& a, const Reference& b) -> bool {
                      return a.Equals(&b);
                    });
}

Styleable* Styleable::Clone(StringPool* /*new_pool*/) const {
  return new Styleable(*this);
}

void Styleable::Print(std::ostream* out) const {
  *out << "(styleable) "
       << " [" << util::Joiner(entries, ", ") << "]";
}

bool operator<(const Reference& a, const Reference& b) {
  int cmp = a.name.value_or_default({}).compare(b.name.value_or_default({}));
  if (cmp != 0) return cmp < 0;
  return a.id < b.id;
}

bool operator==(const Reference& a, const Reference& b) {
  return a.name == b.name && a.id == b.id;
}

bool operator!=(const Reference& a, const Reference& b) {
  return a.name != b.name || a.id != b.id;
}

struct NameOnlyComparator {
  bool operator()(const Reference& a, const Reference& b) const {
    return a.name < b.name;
  }
};

void Styleable::MergeWith(Styleable* other) {
  // Compare only names, because some References may already have their IDs
  // assigned
  // (framework IDs that don't change).
  std::set<Reference, NameOnlyComparator> references;
  references.insert(entries.begin(), entries.end());
  references.insert(other->entries.begin(), other->entries.end());
  entries.clear();
  entries.reserve(references.size());
  entries.insert(entries.end(), references.begin(), references.end());
}

}  // namespace aapt
