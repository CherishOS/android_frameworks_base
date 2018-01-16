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

#include <android/util/ProtoOutputStream.h>
#include "field_util.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

// Helper function to write DimensionsValue proto to ProtoOutputStream.
void writeDimensionsValueProtoToStream(const DimensionsValue& fieldValue,
                                       util::ProtoOutputStream* protoOutput);

// Helper function to write Field proto to ProtoOutputStream.
void writeFieldProtoToStream(const Field& field, util::ProtoOutputStream* protoOutput);

// Helper function to construct the field value tree and write to ProtoOutputStream
void writeFieldValueTreeToStream(const FieldValueMap& fieldValueMap,
                                 util::ProtoOutputStream* protoOutput);

// Convert the TimeUnit enum to the bucket size in millis.
int64_t TimeUnitToBucketSizeInMillis(TimeUnit unit);

// Helper function to write PulledAtomStats to ProtoOutputStream
void writePullerStatsToStream(const std::pair<int, StatsdStats::PulledAtomStats>& pair,
                              util::ProtoOutputStream* protoOutput);

template<class T>
bool parseProtoOutputStream(util::ProtoOutputStream& protoOutput, T* message) {
    std::string pbBytes;
    auto iter = protoOutput.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
         pbBytes.append(reinterpret_cast<const char*>(iter.readBuffer()), toRead);
        iter.rp()->move(toRead);
    }
    return message->ParseFromArray(pbBytes.c_str(), pbBytes.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android