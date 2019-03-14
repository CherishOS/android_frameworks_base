/*
 * Copyright 2019 The Android Open Source Project
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

#include "StatsPuller.h"

namespace android {
namespace os {
namespace statsd {

/**
 * Pull GpuStats from GpuService.
 */
class GpuStatsPuller : public StatsPuller {
public:
    explicit GpuStatsPuller(const int tagId);
    bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) override;
};

// convert a int64_t vector into a byte string for proto message like:
// message RepeatedInt64Wrapper {
//   repeated int64 value = 1;
// }
std::string int64VectorToProtoByteString(const std::vector<int64_t>& value);

}  // namespace statsd
}  // namespace os
}  // namespace android
