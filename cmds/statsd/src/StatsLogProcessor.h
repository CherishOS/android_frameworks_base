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

#include <gtest/gtest_prod.h>
#include "config/ConfigListener.h"
#include "logd/LogReader.h"
#include "metrics/MetricsManager.h"
#include "packages/UidMap.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StatsLogProcessor : public ConfigListener {
public:
    StatsLogProcessor(const sp<UidMap>& uidMap, const sp<AnomalyMonitor>& anomalyMonitor,
                      const long timeBaseSec,
                      const std::function<void(const ConfigKey&)>& sendBroadcast);
    virtual ~StatsLogProcessor();

    void OnLogEvent(LogEvent* event);

    void OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

    size_t GetMetricsSize(const ConfigKey& key) const;

    void onDumpReport(const ConfigKey& key, vector<uint8_t>* outData);
    void onDumpReport(const ConfigKey& key, const uint64_t& dumpTimeStampNs,
                      ConfigMetricsReportList* report);

    /* Tells MetricsManager that the alarms in anomalySet have fired. Modifies anomalySet. */
    void onAnomalyAlarmFired(
            const uint64_t timestampNs,
            unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>> anomalySet);

    /* Flushes data to disk. Data on memory will be gone after written to disk. */
    void WriteDataToDisk();

    inline sp<UidMap> getUidMap() {
        return mUidMap;
    }

private:
    mutable mutex mMetricsMutex;

    std::unordered_map<ConfigKey, sp<MetricsManager>> mMetricsManagers;

    std::unordered_map<ConfigKey, long> mLastBroadcastTimes;

    // Tracks when we last checked the bytes consumed for each config key.
    std::unordered_map<ConfigKey, long> mLastByteSizeTimes;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.

    sp<AnomalyMonitor> mAnomalyMonitor;

    /* Check if we should send a broadcast if approaching memory limits and if we're over, we
     * actually delete the data. */
    void flushIfNecessaryLocked(uint64_t timestampNs, const ConfigKey& key,
                                MetricsManager& metricsManager);

    // Maps the isolated uid in the log event to host uid if the log event contains uid fields.
    void mapIsolatedUidToHostUidIfNecessaryLocked(LogEvent* event) const;

    // Handler over the isolated uid change event.
    void onIsolatedUidChangedEventLocked(const LogEvent& event);

    // Function used to send a broadcast so that receiver for the config key can call getData
    // to retrieve the stored data.
    std::function<void(const ConfigKey& key)> mSendBroadcast;

    const long mTimeBaseSec;

    FRIEND_TEST(StatsLogProcessorTest, TestRateLimitByteSize);
    FRIEND_TEST(StatsLogProcessorTest, TestRateLimitBroadcast);
    FRIEND_TEST(StatsLogProcessorTest, TestDropWhenByteSizeTooLarge);
    FRIEND_TEST(StatsLogProcessorTest, TestDropWhenByteSizeTooLarge);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensions);
    FRIEND_TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks);
    FRIEND_TEST(AttributionE2eTest, TestAttributionMatchAndSlice);

};

}  // namespace statsd
}  // namespace os
}  // namespace android
