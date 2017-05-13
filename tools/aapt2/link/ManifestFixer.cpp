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

#include "link/ManifestFixer.h"

#include <unordered_set>

#include "android-base/logging.h"

#include "ResourceUtils.h"
#include "util/Util.h"
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"

using android::StringPiece;

namespace aapt {

// This is how PackageManager builds class names from AndroidManifest.xml entries.
static bool NameIsJavaClassName(xml::Element* el, xml::Attribute* attr,
                                SourcePathDiagnostics* diag) {
  // We allow unqualified class names (ie: .HelloActivity)
  // Since we don't know the package name, we can just make a fake one here and
  // the test will be identical as long as the real package name is valid too.
  Maybe<std::string> fully_qualified_class_name =
      util::GetFullyQualifiedClassName("a", attr->value);

  StringPiece qualified_class_name = fully_qualified_class_name
                                         ? fully_qualified_class_name.value()
                                         : attr->value;

  if (!util::IsJavaClassName(qualified_class_name)) {
    diag->Error(DiagMessage(el->line_number)
                << "attribute 'android:name' in <" << el->name
                << "> tag must be a valid Java class name");
    return false;
  }
  return true;
}

static bool OptionalNameIsJavaClassName(xml::Element* el, SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    return NameIsJavaClassName(el, attr, diag);
  }
  return true;
}

static bool RequiredNameIsJavaClassName(xml::Element* el, SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    return NameIsJavaClassName(el, attr, diag);
  }
  diag->Error(DiagMessage(el->line_number)
              << "<" << el->name << "> is missing attribute 'android:name'");
  return false;
}

static bool RequiredNameIsJavaPackage(xml::Element* el, SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    return util::IsJavaPackageName(attr->value);
  }
  diag->Error(DiagMessage(el->line_number)
              << "<" << el->name << "> is missing attribute 'android:name'");
  return false;
}

static xml::XmlNodeAction::ActionFuncWithDiag RequiredAndroidAttribute(const std::string& attr) {
  return [=](xml::Element* el, SourcePathDiagnostics* diag) -> bool {
    if (el->FindAttribute(xml::kSchemaAndroid, attr) == nullptr) {
      diag->Error(DiagMessage(el->line_number)
                  << "<" << el->name << "> is missing required attribute 'android:" << attr << "'");
      return false;
    }
    return true;
  };
}

static bool AutoGenerateIsFeatureSplit(xml::Element* el, SourcePathDiagnostics* diag) {
  constexpr const char* kFeatureSplit = "featureSplit";
  constexpr const char* kIsFeatureSplit = "isFeatureSplit";

  xml::Attribute* attr = el->FindAttribute({}, kFeatureSplit);
  if (attr != nullptr) {
    // Rewrite the featureSplit attribute to be "split". This is what the
    // platform recognizes.
    attr->name = "split";

    // Now inject the android:isFeatureSplit="true" attribute.
    xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, kIsFeatureSplit);
    if (attr != nullptr) {
      if (!ResourceUtils::ParseBool(attr->value).value_or_default(false)) {
        // The isFeatureSplit attribute is false, which conflicts with the use
        // of "featureSplit".
        diag->Error(DiagMessage(el->line_number)
                    << "attribute 'featureSplit' used in <manifest> but 'android:isFeatureSplit' "
                       "is not 'true'");
        return false;
      }

      // The attribute is already there and set to true, nothing to do.
    } else {
      el->attributes.push_back(xml::Attribute{xml::kSchemaAndroid, kIsFeatureSplit, "true"});
    }
  }
  return true;
}

static bool VerifyManifest(xml::Element* el, SourcePathDiagnostics* diag) {
  xml::Attribute* attr = el->FindAttribute({}, "package");
  if (!attr) {
    diag->Error(DiagMessage(el->line_number)
                << "<manifest> tag is missing 'package' attribute");
    return false;
  } else if (ResourceUtils::IsReference(attr->value)) {
    diag->Error(DiagMessage(el->line_number)
                << "attribute 'package' in <manifest> tag must not be a reference");
    return false;
  } else if (!util::IsJavaPackageName(attr->value)) {
    diag->Error(DiagMessage(el->line_number)
                << "attribute 'package' in <manifest> tag is not a valid Java package name: '"
                << attr->value << "'");
    return false;
  }

  attr = el->FindAttribute({}, "split");
  if (attr) {
    if (!util::IsJavaPackageName(attr->value)) {
      diag->Error(DiagMessage(el->line_number) << "attribute 'split' in <manifest> tag is not a "
                                                  "valid split name");
      return false;
    }
  }
  return true;
}

// The coreApp attribute in <manifest> is not a regular AAPT attribute, so type
// checking on it is manual.
static bool FixCoreAppAttribute(xml::Element* el, SourcePathDiagnostics* diag) {
  if (xml::Attribute* attr = el->FindAttribute("", "coreApp")) {
    std::unique_ptr<BinaryPrimitive> result = ResourceUtils::TryParseBool(attr->value);
    if (!result) {
      diag->Error(DiagMessage(el->line_number) << "attribute coreApp must be a boolean");
      return false;
    }
    attr->compiled_value = std::move(result);
  }
  return true;
}

// Checks that <uses-feature> has android:glEsVersion or android:name, not both (or neither).
static bool VerifyUsesFeature(xml::Element* el, SourcePathDiagnostics* diag) {
  bool has_name = false;
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "name")) {
    if (attr->value.empty()) {
      diag->Error(DiagMessage(el->line_number)
                  << "android:name in <uses-feature> must not be empty");
      return false;
    }
    has_name = true;
  }

  bool has_gl_es_version = false;
  if (xml::Attribute* attr = el->FindAttribute(xml::kSchemaAndroid, "glEsVersion")) {
    if (has_name) {
      diag->Error(DiagMessage(el->line_number)
                  << "cannot define both android:name and android:glEsVersion in <uses-feature>");
      return false;
    }
    has_gl_es_version = true;
  }

  if (!has_name && !has_gl_es_version) {
    diag->Error(DiagMessage(el->line_number)
                << "<uses-feature> must have either android:name or android:glEsVersion attribute");
    return false;
  }
  return true;
}

bool ManifestFixer::BuildRules(xml::XmlActionExecutor* executor,
                               IDiagnostics* diag) {
  // First verify some options.
  if (options_.rename_manifest_package) {
    if (!util::IsJavaPackageName(options_.rename_manifest_package.value())) {
      diag->Error(DiagMessage() << "invalid manifest package override '"
                                << options_.rename_manifest_package.value()
                                << "'");
      return false;
    }
  }

  if (options_.rename_instrumentation_target_package) {
    if (!util::IsJavaPackageName(options_.rename_instrumentation_target_package.value())) {
      diag->Error(DiagMessage()
                  << "invalid instrumentation target package override '"
                  << options_.rename_instrumentation_target_package.value()
                  << "'");
      return false;
    }
  }

  // Common <intent-filter> actions.
  xml::XmlNodeAction intent_filter_action;
  intent_filter_action["action"];
  intent_filter_action["category"];
  intent_filter_action["data"];

  // Common <meta-data> actions.
  xml::XmlNodeAction meta_data_action;

  // Common <uses-feature> actions.
  xml::XmlNodeAction uses_feature_action;
  uses_feature_action.Action(VerifyUsesFeature);

  // Common component actions.
  xml::XmlNodeAction component_action;
  component_action.Action(RequiredNameIsJavaClassName);
  component_action["intent-filter"] = intent_filter_action;
  component_action["meta-data"] = meta_data_action;

  // Manifest actions.
  xml::XmlNodeAction& manifest_action = (*executor)["manifest"];
  manifest_action.Action(AutoGenerateIsFeatureSplit);
  manifest_action.Action(VerifyManifest);
  manifest_action.Action(FixCoreAppAttribute);
  manifest_action.Action([&](xml::Element* el) -> bool {
    if (options_.version_name_default) {
      if (el->FindAttribute(xml::kSchemaAndroid, "versionName") == nullptr) {
        el->attributes.push_back(
            xml::Attribute{xml::kSchemaAndroid, "versionName",
                           options_.version_name_default.value()});
      }
    }

    if (options_.version_code_default) {
      if (el->FindAttribute(xml::kSchemaAndroid, "versionCode") == nullptr) {
        el->attributes.push_back(
            xml::Attribute{xml::kSchemaAndroid, "versionCode",
                           options_.version_code_default.value()});
      }
    }
    return true;
  });

  // Meta tags.
  manifest_action["eat-comment"];

  // Uses-sdk actions.
  manifest_action["uses-sdk"].Action([&](xml::Element* el) -> bool {
    if (options_.min_sdk_version_default &&
        el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion") == nullptr) {
      // There was no minSdkVersion defined and we have a default to assign.
      el->attributes.push_back(
          xml::Attribute{xml::kSchemaAndroid, "minSdkVersion",
                         options_.min_sdk_version_default.value()});
    }

    if (options_.target_sdk_version_default &&
        el->FindAttribute(xml::kSchemaAndroid, "targetSdkVersion") == nullptr) {
      // There was no targetSdkVersion defined and we have a default to assign.
      el->attributes.push_back(
          xml::Attribute{xml::kSchemaAndroid, "targetSdkVersion",
                         options_.target_sdk_version_default.value()});
    }
    return true;
  });

  // Instrumentation actions.
  manifest_action["instrumentation"].Action(RequiredNameIsJavaClassName);
  manifest_action["instrumentation"].Action([&](xml::Element* el) -> bool {
    if (!options_.rename_instrumentation_target_package) {
      return true;
    }

    if (xml::Attribute* attr =
            el->FindAttribute(xml::kSchemaAndroid, "targetPackage")) {
      attr->value = options_.rename_instrumentation_target_package.value();
    }
    return true;
  });
  manifest_action["instrumentation"]["meta-data"] = meta_data_action;

  manifest_action["original-package"];
  manifest_action["protected-broadcast"];
  manifest_action["uses-permission"];
  manifest_action["uses-permission-sdk-23"];
  manifest_action["permission"];
  manifest_action["permission-tree"];
  manifest_action["permission-group"];
  manifest_action["uses-configuration"];
  manifest_action["supports-screens"];
  manifest_action["uses-feature"] = uses_feature_action;
  manifest_action["feature-group"]["uses-feature"] = uses_feature_action;
  manifest_action["compatible-screens"];
  manifest_action["compatible-screens"]["screen"];
  manifest_action["supports-gl-texture"];
  manifest_action["meta-data"] = meta_data_action;
  manifest_action["uses-split"].Action(RequiredNameIsJavaPackage);

  // Application actions.
  xml::XmlNodeAction& application_action = manifest_action["application"];
  application_action.Action(OptionalNameIsJavaClassName);

  application_action["uses-library"].Action(RequiredNameIsJavaPackage);
  application_action["library"].Action(RequiredNameIsJavaPackage);

  xml::XmlNodeAction& static_library_action = application_action["static-library"];
  static_library_action.Action(RequiredNameIsJavaPackage);
  static_library_action.Action(RequiredAndroidAttribute("version"));

  xml::XmlNodeAction& uses_static_library_action = application_action["uses-static-library"];
  uses_static_library_action.Action(RequiredNameIsJavaPackage);
  uses_static_library_action.Action(RequiredAndroidAttribute("version"));
  uses_static_library_action.Action(RequiredAndroidAttribute("certDigest"));

  application_action["meta-data"] = meta_data_action;
  application_action["activity"] = component_action;
  application_action["activity-alias"] = component_action;
  application_action["service"] = component_action;
  application_action["receiver"] = component_action;

  // Provider actions.
  application_action["provider"] = component_action;
  application_action["provider"]["grant-uri-permission"];
  application_action["provider"]["path-permission"];

  return true;
}

class FullyQualifiedClassNameVisitor : public xml::Visitor {
 public:
  using xml::Visitor::Visit;

  explicit FullyQualifiedClassNameVisitor(const StringPiece& package) : package_(package) {}

  void Visit(xml::Element* el) override {
    for (xml::Attribute& attr : el->attributes) {
      if (attr.namespace_uri == xml::kSchemaAndroid &&
          class_attributes_.find(attr.name) != class_attributes_.end()) {
        if (Maybe<std::string> new_value = util::GetFullyQualifiedClassName(package_, attr.value)) {
          attr.value = std::move(new_value.value());
        }
      }
    }

    // Super implementation to iterate over the children.
    xml::Visitor::Visit(el);
  }

 private:
  StringPiece package_;
  std::unordered_set<StringPiece> class_attributes_ = {"name"};
};

static bool RenameManifestPackage(const StringPiece& package_override, xml::Element* manifest_el) {
  xml::Attribute* attr = manifest_el->FindAttribute({}, "package");

  // We've already verified that the manifest element is present, with a package
  // name specified.
  CHECK(attr != nullptr);

  std::string original_package = std::move(attr->value);
  attr->value = package_override.to_string();

  FullyQualifiedClassNameVisitor visitor(original_package);
  manifest_el->Accept(&visitor);
  return true;
}

bool ManifestFixer::Consume(IAaptContext* context, xml::XmlResource* doc) {
  xml::Element* root = xml::FindRootElement(doc->root.get());
  if (!root || !root->namespace_uri.empty() || root->name != "manifest") {
    context->GetDiagnostics()->Error(DiagMessage(doc->file.source)
                                     << "root tag must be <manifest>");
    return false;
  }

  if ((options_.min_sdk_version_default || options_.target_sdk_version_default) &&
      root->FindChild({}, "uses-sdk") == nullptr) {
    // Auto insert a <uses-sdk> element. This must be inserted before the
    // <application> tag. The device runtime PackageParser will make SDK version
    // decisions while parsing <application>.
    std::unique_ptr<xml::Element> uses_sdk = util::make_unique<xml::Element>();
    uses_sdk->name = "uses-sdk";
    root->InsertChild(0, std::move(uses_sdk));
  }

  xml::XmlActionExecutor executor;
  if (!BuildRules(&executor, context->GetDiagnostics())) {
    return false;
  }

  if (!executor.Execute(xml::XmlActionExecutorPolicy::kWhitelist, context->GetDiagnostics(), doc)) {
    return false;
  }

  if (options_.rename_manifest_package) {
    // Rename manifest package outside of the XmlActionExecutor.
    // We need to extract the old package name and FullyQualify all class
    // names.
    if (!RenameManifestPackage(options_.rename_manifest_package.value(), root)) {
      return false;
    }
  }
  return true;
}

}  // namespace aapt
