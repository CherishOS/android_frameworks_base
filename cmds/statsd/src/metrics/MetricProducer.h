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

#ifndef METRIC_PRODUCER_H
#define METRIC_PRODUCER_H

#include <log/logprint.h>
#include <utils/RefBase.h>
#include "../matchers/matcher_util.h"
#include "PackageInfoListener.h"

namespace android {
namespace os {
namespace statsd {

// A MetricProducer is responsible for compute one single metrics, creating stats log report, and
// writing the report to dropbox. MetricProducers should respond to package changes as required in
// PackageInfoListener, but if none of the metrics are slicing by package name, then the update can
// be a no-op.
class MetricProducer : public virtual PackageInfoListener {
public:
    virtual ~MetricProducer(){};

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    virtual void onMatchedLogEvent(const LogEventWrapper& event) = 0;

    virtual void onConditionChanged(const bool condition) = 0;

    // This is called when the metric collecting is done, e.g., when there is a new configuration
    // coming. MetricProducer should do the clean up, and dump existing data to dropbox.
    virtual void finish() = 0;

    virtual void onDumpReport() = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // METRIC_PRODUCER_H
