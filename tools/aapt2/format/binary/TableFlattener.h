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

#ifndef AAPT_FORMAT_BINARY_TABLEFLATTENER_H
#define AAPT_FORMAT_BINARY_TABLEFLATTENER_H

#include "Resource.h"
#include "ResourceTable.h"
#include "android-base/macros.h"
#include "androidfw/BigBuffer.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

// The percentage of used entries for a type for which using a sparse encoding is
// preferred.
constexpr const size_t kSparseEncodingThreshold = 60;

enum class SparseEntriesMode {
  // Disables sparse encoding for entries.
  Disabled,
  // Enables sparse encoding for all entries for APKs with O+ minSdk. For APKs with minSdk less
  // than O only applies sparse encoding for resource configuration available on O+.
  Enabled,
  // Enables sparse encoding for all entries regardless of minSdk.
  Forced,
};

struct TableFlattenerOptions {
  // When enabled, types for configurations with a sparse set of entries are encoded
  // as a sparse map of entry ID and offset to actual data.
  SparseEntriesMode sparse_entries = SparseEntriesMode::Disabled;

  // When true, the key string pool in the final ResTable
  // is collapsed to a single entry. All resource entries
  // have name indices that point to this single value
  bool collapse_key_stringpool = false;

  // Set of resources to avoid collapsing to a single entry in key stringpool.
  std::set<ResourceName> name_collapse_exemptions;

  // Map from original resource paths to shortened resource paths.
  std::map<std::string, std::string> shortened_path_map;

  // When enabled, only unique pairs of entry and value are stored in type chunks.
  //
  // By default, all such pairs are unique because a reference to resource name in the string pool
  // is a part of the pair. But when resource names are collapsed (using 'collapse_key_stringpool'
  // flag or manually) the same data might be duplicated multiple times in the same type chunk.
  //
  // For example: an application has 3 boolean resources with collapsed names and 3 'true' values
  // are defined for these resources in 'default' configuration. All pairs of entry and value for
  // these resources will have the same binary representation and stored only once in type chunk
  // instead of three times when this flag is disabled.
  //
  // This applies only to simple entries (entry->flags & ResTable_entry::FLAG_COMPLEX == 0).
  bool deduplicate_entry_values = false;
};

class TableFlattener : public IResourceTableConsumer {
 public:
  explicit TableFlattener(const TableFlattenerOptions& options, android::BigBuffer* buffer)
      : options_(options), buffer_(buffer) {
  }

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(TableFlattener);

  TableFlattenerOptions options_;
  android::BigBuffer* buffer_;
};

}  // namespace aapt

#endif /* AAPT_FORMAT_BINARY_TABLEFLATTENER_H */
