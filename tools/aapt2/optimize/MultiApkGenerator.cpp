/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "MultiApkGenerator.h"

#include <algorithm>
#include <string>

#include "androidfw/StringPiece.h"

#include "LoadedApk.h"
#include "configuration/ConfigurationParser.h"
#include "filter/AbiFilter.h"
#include "filter/Filter.h"
#include "flatten/Archive.h"
#include "process/IResourceTableConsumer.h"
#include "split/TableSplitter.h"
#include "util/Files.h"

namespace aapt {

using ::aapt::configuration::Artifact;
using ::aapt::configuration::PostProcessingConfiguration;
using ::android::StringPiece;

MultiApkGenerator::MultiApkGenerator(LoadedApk* apk, IAaptContext* context)
    : apk_(apk), context_(context) {
}

bool MultiApkGenerator::FromBaseApk(const std::string& out_dir,
                                    const PostProcessingConfiguration& config,
                                    const TableFlattenerOptions& table_flattener_options) {
  // TODO(safarmer): Handle APK version codes for the generated APKs.
  IDiagnostics* diag = context_->GetDiagnostics();

  const std::string& apk_name = file::GetFilename(apk_->GetSource().path).to_string();
  const StringPiece ext = file::GetExtension(apk_name);
  const std::string base_name = apk_name.substr(0, apk_name.rfind(ext.to_string()));

  // For now, just write out the stripped APK since ABI splitting doesn't modify anything else.
  for (const Artifact& artifact : config.artifacts) {
    FilterChain filters;
    TableSplitterOptions splits;
    AxisConfigFilter axis_filter;

    if (!artifact.name && !config.artifact_format) {
      diag->Error(
          DiagMessage() << "Artifact does not have a name and no global name template defined");
      return false;
    }

    Maybe<std::string> artifact_name =
        (artifact.name) ? artifact.Name(apk_name, diag)
                        : artifact.ToArtifactName(config.artifact_format.value(), apk_name, diag);

    if (!artifact_name) {
      diag->Error(DiagMessage() << "Could not determine split APK artifact name");
      return false;
    }

    if (artifact.abi_group) {
      const std::string& group_name = artifact.abi_group.value();

      auto group = config.abi_groups.find(group_name);
      // TODO: Remove validation when configuration parser ensures referential integrity.
      if (group == config.abi_groups.end()) {
        diag->Error(DiagMessage() << "could not find referenced ABI group '" << group_name << "'");
        return false;
      }
      filters.AddFilter(AbiFilter::FromAbiList(group->second));
    }

    if (artifact.screen_density_group) {
      const std::string& group_name = artifact.screen_density_group.value();

      auto group = config.screen_density_groups.find(group_name);
      // TODO: Remove validation when configuration parser ensures referential integrity.
      if (group == config.screen_density_groups.end()) {
        diag->Error(DiagMessage() << "could not find referenced group '" << group_name << "'");
        return false;
      }

      const std::vector<ConfigDescription>& densities = group->second;
      std::for_each(densities.begin(), densities.end(), [&](const ConfigDescription& c) {
        splits.preferred_densities.push_back(c.density);
      });
    }

    if (artifact.locale_group) {
      const std::string& group_name = artifact.locale_group.value();
      auto group = config.locale_groups.find(group_name);
      // TODO: Remove validation when configuration parser ensures referential integrity.
      if (group == config.locale_groups.end()) {
        diag->Error(DiagMessage() << "could not find referenced group '" << group_name << "'");
        return false;
      }

      const std::vector<ConfigDescription>& locales = group->second;
      std::for_each(locales.begin(), locales.end(),
                    [&](const ConfigDescription& c) { axis_filter.AddConfig(c); });
      splits.config_filter = &axis_filter;
    }

    std::unique_ptr<ResourceTable> table = apk_->GetResourceTable()->Clone();


    TableSplitter splitter{{}, splits};
    splitter.SplitTable(table.get());

    std::string out = out_dir;
    if (!file::mkdirs(out)) {
      context_->GetDiagnostics()->Warn(DiagMessage() << "could not create out dir: " << out);
    }
    file::AppendPath(&out, artifact_name.value());

    std::unique_ptr<IArchiveWriter> writer = CreateZipFileArchiveWriter(diag, out);

    if (context_->IsVerbose()) {
      diag->Note(DiagMessage() << "Writing output: " << out);
    }

    if (!apk_->WriteToArchive(context_, table.get(), table_flattener_options, &filters,
                              writer.get())) {
      return false;
    }
  }

  return true;
}

}  // namespace aapt
