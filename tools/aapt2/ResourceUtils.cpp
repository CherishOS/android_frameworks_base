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

#include "ResourceUtils.h"

#include <sstream>

#include "androidfw/ResourceTypes.h"
#include "androidfw/ResourceUtils.h"

#include "NameMangler.h"
#include "SdkConstants.h"
#include "flatten/ResourceTypeExtensions.h"
#include "util/Files.h"
#include "util/Util.h"

using android::StringPiece;
using android::StringPiece16;

namespace aapt {
namespace ResourceUtils {

Maybe<ResourceName> ToResourceName(
    const android::ResTable::resource_name& name_in) {
  ResourceName name_out;
  if (!name_in.package) {
    return {};
  }

  name_out.package =
      util::Utf16ToUtf8(StringPiece16(name_in.package, name_in.packageLen));

  const ResourceType* type;
  if (name_in.type) {
    type = ParseResourceType(
        util::Utf16ToUtf8(StringPiece16(name_in.type, name_in.typeLen)));
  } else if (name_in.type8) {
    type = ParseResourceType(StringPiece(name_in.type8, name_in.typeLen));
  } else {
    return {};
  }

  if (!type) {
    return {};
  }

  name_out.type = *type;

  if (name_in.name) {
    name_out.entry =
        util::Utf16ToUtf8(StringPiece16(name_in.name, name_in.nameLen));
  } else if (name_in.name8) {
    name_out.entry.assign(name_in.name8, name_in.nameLen);
  } else {
    return {};
  }
  return name_out;
}

bool ParseResourceName(const StringPiece& str, ResourceNameRef* out_ref,
                       bool* out_private) {
  if (str.empty()) {
    return false;
  }

  size_t offset = 0;
  bool priv = false;
  if (str.data()[0] == '*') {
    priv = true;
    offset = 1;
  }

  StringPiece package;
  StringPiece type;
  StringPiece entry;
  if (!android::ExtractResourceName(str.substr(offset, str.size() - offset), &package, &type,
                                    &entry)) {
    return false;
  }

  const ResourceType* parsed_type = ParseResourceType(type);
  if (!parsed_type) {
    return false;
  }

  if (entry.empty()) {
    return false;
  }

  if (out_ref) {
    out_ref->package = package;
    out_ref->type = *parsed_type;
    out_ref->entry = entry;
  }

  if (out_private) {
    *out_private = priv;
  }
  return true;
}

bool ParseReference(const StringPiece& str, ResourceNameRef* out_ref,
                    bool* out_create, bool* out_private) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str.empty()) {
    return false;
  }

  bool create = false;
  bool priv = false;
  if (trimmed_str.data()[0] == '@') {
    size_t offset = 1;
    if (trimmed_str.data()[1] == '+') {
      create = true;
      offset += 1;
    }

    ResourceNameRef name;
    if (!ParseResourceName(
            trimmed_str.substr(offset, trimmed_str.size() - offset), &name,
            &priv)) {
      return false;
    }

    if (create && priv) {
      return false;
    }

    if (create && name.type != ResourceType::kId) {
      return false;
    }

    if (out_ref) {
      *out_ref = name;
    }

    if (out_create) {
      *out_create = create;
    }

    if (out_private) {
      *out_private = priv;
    }
    return true;
  }
  return false;
}

bool IsReference(const StringPiece& str) {
  return ParseReference(str, nullptr, nullptr, nullptr);
}

bool ParseAttributeReference(const StringPiece& str, ResourceNameRef* out_ref) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str.empty()) {
    return false;
  }

  if (*trimmed_str.data() == '?') {
    StringPiece package;
    StringPiece type;
    StringPiece entry;
    if (!android::ExtractResourceName(trimmed_str.substr(1, trimmed_str.size() - 1), &package,
                                      &type, &entry)) {
      return false;
    }

    if (!type.empty() && type != "attr") {
      return false;
    }

    if (entry.empty()) {
      return false;
    }

    if (out_ref) {
      out_ref->package = package;
      out_ref->type = ResourceType::kAttr;
      out_ref->entry = entry;
    }
    return true;
  }
  return false;
}

bool IsAttributeReference(const StringPiece& str) {
  return ParseAttributeReference(str, nullptr);
}

/*
 * Style parent's are a bit different. We accept the following formats:
 *
 * @[[*]package:][style/]<entry>
 * ?[[*]package:]style/<entry>
 * <[*]package>:[style/]<entry>
 * [[*]package:style/]<entry>
 */
Maybe<Reference> ParseStyleParentReference(const StringPiece& str,
                                           std::string* out_error) {
  if (str.empty()) {
    return {};
  }

  StringPiece name = str;

  bool has_leading_identifiers = false;
  bool private_ref = false;

  // Skip over these identifiers. A style's parent is a normal reference.
  if (name.data()[0] == '@' || name.data()[0] == '?') {
    has_leading_identifiers = true;
    name = name.substr(1, name.size() - 1);
  }

  if (name.data()[0] == '*') {
    private_ref = true;
    name = name.substr(1, name.size() - 1);
  }

  ResourceNameRef ref;
  ref.type = ResourceType::kStyle;

  StringPiece type_str;
  android::ExtractResourceName(name, &ref.package, &type_str, &ref.entry);
  if (!type_str.empty()) {
    // If we have a type, make sure it is a Style.
    const ResourceType* parsed_type = ParseResourceType(type_str);
    if (!parsed_type || *parsed_type != ResourceType::kStyle) {
      std::stringstream err;
      err << "invalid resource type '" << type_str << "' for parent of style";
      *out_error = err.str();
      return {};
    }
  }

  if (!has_leading_identifiers && ref.package.empty() && !type_str.empty()) {
    std::stringstream err;
    err << "invalid parent reference '" << str << "'";
    *out_error = err.str();
    return {};
  }

  Reference result(ref);
  result.private_reference = private_ref;
  return result;
}

Maybe<Reference> ParseXmlAttributeName(const StringPiece& str) {
  StringPiece trimmed_str = util::TrimWhitespace(str);
  const char* start = trimmed_str.data();
  const char* const end = start + trimmed_str.size();
  const char* p = start;

  Reference ref;
  if (p != end && *p == '*') {
    ref.private_reference = true;
    start++;
    p++;
  }

  StringPiece package;
  StringPiece name;
  while (p != end) {
    if (*p == ':') {
      package = StringPiece(start, p - start);
      name = StringPiece(p + 1, end - (p + 1));
      break;
    }
    p++;
  }

  ref.name = ResourceName(package, ResourceType::kAttr, name.empty() ? trimmed_str : name);
  return Maybe<Reference>(std::move(ref));
}

std::unique_ptr<Reference> TryParseReference(const StringPiece& str,
                                             bool* out_create) {
  ResourceNameRef ref;
  bool private_ref = false;
  if (ParseReference(str, &ref, out_create, &private_ref)) {
    std::unique_ptr<Reference> value = util::make_unique<Reference>(ref);
    value->private_reference = private_ref;
    return value;
  }

  if (ParseAttributeReference(str, &ref)) {
    if (out_create) {
      *out_create = false;
    }
    return util::make_unique<Reference>(ref, Reference::Type::kAttribute);
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseNullOrEmpty(const StringPiece& str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  android::Res_value value = {};
  if (trimmed_str == "@null") {
    // TYPE_NULL with data set to 0 is interpreted by the runtime as an error.
    // Instead we set the data type to TYPE_REFERENCE with a value of 0.
    value.dataType = android::Res_value::TYPE_REFERENCE;
  } else if (trimmed_str == "@empty") {
    // TYPE_NULL with value of DATA_NULL_EMPTY is handled fine by the runtime.
    value.dataType = android::Res_value::TYPE_NULL;
    value.data = android::Res_value::DATA_NULL_EMPTY;
  } else {
    return {};
  }
  return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> TryParseEnumSymbol(const Attribute* enum_attr,
                                                    const StringPiece& str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  for (const Attribute::Symbol& symbol : enum_attr->symbols) {
    // Enum symbols are stored as @package:id/symbol resources,
    // so we need to match against the 'entry' part of the identifier.
    const ResourceName& enum_symbol_resource_name = symbol.symbol.name.value();
    if (trimmed_str == enum_symbol_resource_name.entry) {
      android::Res_value value = {};
      value.dataType = android::Res_value::TYPE_INT_DEC;
      value.data = symbol.value;
      return util::make_unique<BinaryPrimitive>(value);
    }
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseFlagSymbol(const Attribute* flag_attr,
                                                    const StringPiece& str) {
  android::Res_value flags = {};
  flags.dataType = android::Res_value::TYPE_INT_HEX;
  flags.data = 0u;

  if (util::TrimWhitespace(str).empty()) {
    // Empty string is a valid flag (0).
    return util::make_unique<BinaryPrimitive>(flags);
  }

  for (StringPiece part : util::Tokenize(str, '|')) {
    StringPiece trimmed_part = util::TrimWhitespace(part);

    bool flag_set = false;
    for (const Attribute::Symbol& symbol : flag_attr->symbols) {
      // Flag symbols are stored as @package:id/symbol resources,
      // so we need to match against the 'entry' part of the identifier.
      const ResourceName& flag_symbol_resource_name =
          symbol.symbol.name.value();
      if (trimmed_part == flag_symbol_resource_name.entry) {
        flags.data |= symbol.value;
        flag_set = true;
        break;
      }
    }

    if (!flag_set) {
      return {};
    }
  }
  return util::make_unique<BinaryPrimitive>(flags);
}

static uint32_t ParseHex(char c, bool* out_error) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  } else if (c >= 'a' && c <= 'f') {
    return c - 'a' + 0xa;
  } else if (c >= 'A' && c <= 'F') {
    return c - 'A' + 0xa;
  } else {
    *out_error = true;
    return 0xffffffffu;
  }
}

std::unique_ptr<BinaryPrimitive> TryParseColor(const StringPiece& str) {
  StringPiece color_str(util::TrimWhitespace(str));
  const char* start = color_str.data();
  const size_t len = color_str.size();
  if (len == 0 || start[0] != '#') {
    return {};
  }

  android::Res_value value = {};
  bool error = false;
  if (len == 4) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_RGB4;
    value.data = 0xff000000u;
    value.data |= ParseHex(start[1], &error) << 20;
    value.data |= ParseHex(start[1], &error) << 16;
    value.data |= ParseHex(start[2], &error) << 12;
    value.data |= ParseHex(start[2], &error) << 8;
    value.data |= ParseHex(start[3], &error) << 4;
    value.data |= ParseHex(start[3], &error);
  } else if (len == 5) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB4;
    value.data |= ParseHex(start[1], &error) << 28;
    value.data |= ParseHex(start[1], &error) << 24;
    value.data |= ParseHex(start[2], &error) << 20;
    value.data |= ParseHex(start[2], &error) << 16;
    value.data |= ParseHex(start[3], &error) << 12;
    value.data |= ParseHex(start[3], &error) << 8;
    value.data |= ParseHex(start[4], &error) << 4;
    value.data |= ParseHex(start[4], &error);
  } else if (len == 7) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_RGB8;
    value.data = 0xff000000u;
    value.data |= ParseHex(start[1], &error) << 20;
    value.data |= ParseHex(start[2], &error) << 16;
    value.data |= ParseHex(start[3], &error) << 12;
    value.data |= ParseHex(start[4], &error) << 8;
    value.data |= ParseHex(start[5], &error) << 4;
    value.data |= ParseHex(start[6], &error);
  } else if (len == 9) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB8;
    value.data |= ParseHex(start[1], &error) << 28;
    value.data |= ParseHex(start[2], &error) << 24;
    value.data |= ParseHex(start[3], &error) << 20;
    value.data |= ParseHex(start[4], &error) << 16;
    value.data |= ParseHex(start[5], &error) << 12;
    value.data |= ParseHex(start[6], &error) << 8;
    value.data |= ParseHex(start[7], &error) << 4;
    value.data |= ParseHex(start[8], &error);
  } else {
    return {};
  }
  return error ? std::unique_ptr<BinaryPrimitive>()
               : util::make_unique<BinaryPrimitive>(value);
}

Maybe<bool> ParseBool(const StringPiece& str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str == "true" || trimmed_str == "TRUE" || trimmed_str == "True") {
    return Maybe<bool>(true);
  } else if (trimmed_str == "false" || trimmed_str == "FALSE" ||
             trimmed_str == "False") {
    return Maybe<bool>(false);
  }
  return {};
}

Maybe<uint32_t> ParseInt(const StringPiece& str) {
  std::u16string str16 = util::Utf8ToUtf16(str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return value.data;
  }
  return {};
}

Maybe<ResourceId> ParseResourceId(const StringPiece& str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));

  std::u16string str16 = util::Utf8ToUtf16(trimmed_str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    if (value.dataType == android::Res_value::TYPE_INT_HEX) {
      ResourceId id(value.data);
      if (id.is_valid()) {
        return id;
      }
    }
  }
  return {};
}

Maybe<int> ParseSdkVersion(const StringPiece& str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));

  std::u16string str16 = util::Utf8ToUtf16(trimmed_str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return static_cast<int>(value.data);
  }

  // Try parsing the code name.
  std::pair<StringPiece, int> entry = GetDevelopmentSdkCodeNameAndVersion();
  if (entry.first == trimmed_str) {
    return entry.second;
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseBool(const StringPiece& str) {
  if (Maybe<bool> maybe_result = ParseBool(str)) {
    android::Res_value value = {};
    value.dataType = android::Res_value::TYPE_INT_BOOLEAN;

    if (maybe_result.value()) {
      value.data = 0xffffffffu;
    } else {
      value.data = 0;
    }
    return util::make_unique<BinaryPrimitive>(value);
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseInt(const StringPiece& str) {
  std::u16string str16 = util::Utf8ToUtf16(str);
  android::Res_value value;
  if (!android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return {};
  }
  return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> TryParseFloat(const StringPiece& str) {
  std::u16string str16 = util::Utf8ToUtf16(str);
  android::Res_value value;
  if (!android::ResTable::stringToFloat(str16.data(), str16.size(), &value)) {
    return {};
  }
  return util::make_unique<BinaryPrimitive>(value);
}

uint32_t AndroidTypeToAttributeTypeMask(uint16_t type) {
  switch (type) {
    case android::Res_value::TYPE_NULL:
    case android::Res_value::TYPE_REFERENCE:
    case android::Res_value::TYPE_ATTRIBUTE:
    case android::Res_value::TYPE_DYNAMIC_REFERENCE:
      return android::ResTable_map::TYPE_REFERENCE;

    case android::Res_value::TYPE_STRING:
      return android::ResTable_map::TYPE_STRING;

    case android::Res_value::TYPE_FLOAT:
      return android::ResTable_map::TYPE_FLOAT;

    case android::Res_value::TYPE_DIMENSION:
      return android::ResTable_map::TYPE_DIMENSION;

    case android::Res_value::TYPE_FRACTION:
      return android::ResTable_map::TYPE_FRACTION;

    case android::Res_value::TYPE_INT_DEC:
    case android::Res_value::TYPE_INT_HEX:
      return android::ResTable_map::TYPE_INTEGER |
             android::ResTable_map::TYPE_ENUM |
             android::ResTable_map::TYPE_FLAGS;

    case android::Res_value::TYPE_INT_BOOLEAN:
      return android::ResTable_map::TYPE_BOOLEAN;

    case android::Res_value::TYPE_INT_COLOR_ARGB8:
    case android::Res_value::TYPE_INT_COLOR_RGB8:
    case android::Res_value::TYPE_INT_COLOR_ARGB4:
    case android::Res_value::TYPE_INT_COLOR_RGB4:
      return android::ResTable_map::TYPE_COLOR;

    default:
      return 0;
  };
}

std::unique_ptr<Item> TryParseItemForAttribute(
    const StringPiece& value, uint32_t type_mask,
    const std::function<void(const ResourceName&)>& on_create_reference) {
  std::unique_ptr<BinaryPrimitive> null_or_empty = TryParseNullOrEmpty(value);
  if (null_or_empty) {
    return std::move(null_or_empty);
  }

  bool create = false;
  std::unique_ptr<Reference> reference = TryParseReference(value, &create);
  if (reference) {
    if (create && on_create_reference) {
      on_create_reference(reference->name.value());
    }
    return std::move(reference);
  }

  if (type_mask & android::ResTable_map::TYPE_COLOR) {
    // Try parsing this as a color.
    std::unique_ptr<BinaryPrimitive> color = TryParseColor(value);
    if (color) {
      return std::move(color);
    }
  }

  if (type_mask & android::ResTable_map::TYPE_BOOLEAN) {
    // Try parsing this as a boolean.
    std::unique_ptr<BinaryPrimitive> boolean = TryParseBool(value);
    if (boolean) {
      return std::move(boolean);
    }
  }

  if (type_mask & android::ResTable_map::TYPE_INTEGER) {
    // Try parsing this as an integer.
    std::unique_ptr<BinaryPrimitive> integer = TryParseInt(value);
    if (integer) {
      return std::move(integer);
    }
  }

  const uint32_t float_mask = android::ResTable_map::TYPE_FLOAT |
                              android::ResTable_map::TYPE_DIMENSION |
                              android::ResTable_map::TYPE_FRACTION;
  if (type_mask & float_mask) {
    // Try parsing this as a float.
    std::unique_ptr<BinaryPrimitive> floating_point = TryParseFloat(value);
    if (floating_point) {
      if (type_mask &
          AndroidTypeToAttributeTypeMask(floating_point->value.dataType)) {
        return std::move(floating_point);
      }
    }
  }
  return {};
}

/**
 * We successively try to parse the string as a resource type that the Attribute
 * allows.
 */
std::unique_ptr<Item> TryParseItemForAttribute(
    const StringPiece& str, const Attribute* attr,
    const std::function<void(const ResourceName&)>& on_create_reference) {
  const uint32_t type_mask = attr->type_mask;
  std::unique_ptr<Item> value =
      TryParseItemForAttribute(str, type_mask, on_create_reference);
  if (value) {
    return value;
  }

  if (type_mask & android::ResTable_map::TYPE_ENUM) {
    // Try parsing this as an enum.
    std::unique_ptr<BinaryPrimitive> enum_value = TryParseEnumSymbol(attr, str);
    if (enum_value) {
      return std::move(enum_value);
    }
  }

  if (type_mask & android::ResTable_map::TYPE_FLAGS) {
    // Try parsing this as a flag.
    std::unique_ptr<BinaryPrimitive> flag_value = TryParseFlagSymbol(attr, str);
    if (flag_value) {
      return std::move(flag_value);
    }
  }
  return {};
}

std::string BuildResourceFileName(const ResourceFile& res_file,
                                  const NameMangler* mangler) {
  std::stringstream out;
  out << "res/" << res_file.name.type;
  if (res_file.config != ConfigDescription{}) {
    out << "-" << res_file.config;
  }
  out << "/";

  if (mangler && mangler->ShouldMangle(res_file.name.package)) {
    out << NameMangler::MangleEntry(res_file.name.package, res_file.name.entry);
  } else {
    out << res_file.name.entry;
  }
  out << file::GetExtension(res_file.source.path);
  return out.str();
}

}  // namespace ResourceUtils
}  // namespace aapt
