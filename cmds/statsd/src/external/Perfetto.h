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

#pragma once

using android::os::StatsLogEventWrapper;

namespace android {
namespace os {
namespace statsd {

class PerfettoDetails;  // Declared in statsd_config.pb.h

// Starts the collection of a Perfetto trace with the given |config|.
// The trace is uploaded to Dropbox by the perfetto cmdline util once done.
// This method returns immediately after passing the config and does NOT wait
// for the full duration of the trace.
bool CollectPerfettoTraceAndUploadToDropbox(const PerfettoDetails& config);

}  // namespace statsd
}  // namespace os
}  // namespace android
