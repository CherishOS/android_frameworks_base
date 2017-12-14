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

#pragma once

#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

#define DEFAULT_DIMENSION_KEY ""

typedef std::string HashableDimensionKey;

typedef std::map<std::string, HashableDimensionKey> ConditionKey;

typedef std::unordered_map<HashableDimensionKey, int64_t> DimToValMap;

std::string getHashableKey(std::vector<KeyValuePair> key);

}  // namespace statsd
}  // namespace os
}  // namespace android
