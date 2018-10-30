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

#include <algorithm>
#include <iomanip>
#include <iostream>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "android-base/macros.h"

#include "idmap2/CommandLineOptions.h"

namespace android {
namespace idmap2 {

std::unique_ptr<std::vector<std::string>> CommandLineOptions::ConvertArgvToVector(
    int argc, const char** argv) {
  return std::unique_ptr<std::vector<std::string>>(
      new std::vector<std::string>(argv + 1, argv + argc));
}

CommandLineOptions& CommandLineOptions::OptionalFlag(const std::string& name,
                                                     const std::string& description, bool* value) {
  assert(value != nullptr);
  auto func = [value](const std::string& arg ATTRIBUTE_UNUSED) -> void { *value = true; };
  options_.push_back(Option{name, description, func, Option::COUNT_OPTIONAL, false});
  return *this;
}

CommandLineOptions& CommandLineOptions::MandatoryOption(const std::string& name,
                                                        const std::string& description,
                                                        std::string* value) {
  assert(value != nullptr);
  auto func = [value](const std::string& arg) -> void { *value = arg; };
  options_.push_back(Option{name, description, func, Option::COUNT_EXACTLY_ONCE, true});
  return *this;
}

CommandLineOptions& CommandLineOptions::MandatoryOption(const std::string& name,
                                                        const std::string& description,
                                                        std::vector<std::string>* value) {
  assert(value != nullptr);
  auto func = [value](const std::string& arg) -> void { value->push_back(arg); };
  options_.push_back(Option{name, description, func, Option::COUNT_ONCE_OR_MORE, true});
  return *this;
}

CommandLineOptions& CommandLineOptions::OptionalOption(const std::string& name,
                                                       const std::string& description,
                                                       std::string* value) {
  assert(value != nullptr);
  auto func = [value](const std::string& arg) -> void { *value = arg; };
  options_.push_back(Option{name, description, func, Option::COUNT_OPTIONAL, true});
  return *this;
}

bool CommandLineOptions::Parse(const std::vector<std::string>& argv, std::ostream& outError) const {
  const auto pivot = std::partition(options_.begin(), options_.end(), [](const Option& opt) {
    return opt.count != Option::COUNT_OPTIONAL;
  });
  std::set<std::string> mandatory_opts;
  std::transform(options_.begin(), pivot, std::inserter(mandatory_opts, mandatory_opts.end()),
                 [](const Option& opt) -> std::string { return opt.name; });

  const size_t argv_size = argv.size();
  for (size_t i = 0; i < argv_size; i++) {
    const std::string arg = argv[i];
    if ("--help" == arg || "-h" == arg) {
      Usage(outError);
      return false;
    }
    bool match = false;
    for (const Option& opt : options_) {
      if (opt.name == arg) {
        match = true;

        if (opt.argument) {
          i++;
          if (i >= argv_size) {
            outError << "error: " << opt.name << ": missing argument" << std::endl;
            Usage(outError);
            return false;
          }
        }
        opt.action(argv[i]);
        mandatory_opts.erase(opt.name);
        break;
      }
    }
    if (!match) {
      outError << "error: " << arg << ": unknown option" << std::endl;
      Usage(outError);
      return false;
    }
  }

  if (!mandatory_opts.empty()) {
    for (auto iter = mandatory_opts.cbegin(); iter != mandatory_opts.cend(); ++iter) {
      outError << "error: " << *iter << ": missing mandatory option" << std::endl;
    }
    Usage(outError);
    return false;
  }
  return true;
}

void CommandLineOptions::Usage(std::ostream& out) const {
  size_t maxLength = 0;
  out << "usage: " << name_;
  for (const Option& opt : options_) {
    const bool mandatory = opt.count != Option::COUNT_OPTIONAL;
    out << " ";
    if (!mandatory) {
      out << "[";
    }
    if (opt.argument) {
      out << opt.name << " arg";
      maxLength = std::max(maxLength, opt.name.size() + 4);
    } else {
      out << opt.name;
      maxLength = std::max(maxLength, opt.name.size());
    }
    if (!mandatory) {
      out << "]";
    }
    if (opt.count == Option::COUNT_ONCE_OR_MORE) {
      out << " [" << opt.name << " arg [..]]";
    }
  }
  out << std::endl << std::endl;
  for (const Option& opt : options_) {
    out << std::left << std::setw(maxLength);
    if (opt.argument) {
      out << (opt.name + " arg");
    } else {
      out << opt.name;
    }
    out << "    " << opt.description;
    if (opt.count == Option::COUNT_ONCE_OR_MORE) {
      out << " (can be provided multiple times)";
    }
    out << std::endl;
  }
}

}  // namespace idmap2
}  // namespace android
