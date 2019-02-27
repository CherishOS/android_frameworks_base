/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "Result.h"

#ifndef IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
#define IDMAP2_INCLUDE_IDMAP2_POLICIES_H_

namespace android::idmap2 {

using PolicyFlags = ResTable_overlayable_policy_header::PolicyFlags;
using PolicyBitmask = uint32_t;

// Parses a the string representation of a set of policies into a bitmask. The format of the string
// is the same as for the <policy> element.
Result<PolicyBitmask> PoliciesToBitmask(const std::vector<std::string>& policies);

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
