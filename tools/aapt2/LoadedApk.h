/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_LOADEDAPK_H
#define AAPT_LOADEDAPK_H

#include "androidfw/StringPiece.h"

#include "ResourceTable.h"
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "io/ZipArchive.h"
#include "unflatten/BinaryResourceParser.h"

namespace aapt {

/** Info about an APK loaded in memory. */
class LoadedApk {
 public:
  LoadedApk(
      const Source& source,
      std::unique_ptr<io::IFileCollection> apk,
      std::unique_ptr<ResourceTable> table)
      : source_(source), apk_(std::move(apk)), table_(std::move(table)) {}

  io::IFileCollection* GetFileCollection() { return apk_.get(); }

  ResourceTable* GetResourceTable() { return table_.get(); }

  const Source& GetSource() { return source_; }

  /**
   * Writes the APK on disk at the given path, while also removing the resource
   * files that are not referenced in the resource table.
   */
  bool WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                      IArchiveWriter* writer);

  static std::unique_ptr<LoadedApk> LoadApkFromPath(IAaptContext* context,
                                                    const android::StringPiece& path);

 private:
  Source source_;
  std::unique_ptr<io::IFileCollection> apk_;
  std::unique_ptr<ResourceTable> table_;

  DISALLOW_COPY_AND_ASSIGN(LoadedApk);
};

}  // namespace aapt

#endif /* AAPT_LOADEDAPK_H */
