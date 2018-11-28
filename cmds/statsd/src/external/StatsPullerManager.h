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

#include <android/os/IStatsCompanionService.h>
#include <binder/IServiceManager.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <list>
#include <string>
#include <unordered_map>
#include <vector>
#include "PullDataReceiver.h"
#include "StatsPuller.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

typedef struct {
    // The field numbers of the fields that need to be summed when merging
    // isolated uid with host uid.
    std::vector<int> additiveFields;
    // The field numbers of the fields that can't be merged when merging
    // data belong to isolated uid and host uid.
    std::vector<int> nonAdditiveFields;
    // How long should the puller wait before doing an actual pull again. Default
    // 1 sec. Set this to 0 if this is handled elsewhere.
    int64_t coolDownNs = 1 * NS_PER_SEC;
    // The actual puller
    sp<StatsPuller> puller;
} PullAtomInfo;

class StatsPullerManager : public virtual RefBase {
public:
    StatsPullerManager();

    virtual ~StatsPullerManager() {
    }

    // Registers a receiver for tagId. It will be pulled on the nextPullTimeNs
    // and then every intervalNs thereafter.
    virtual void RegisterReceiver(int tagId, wp<PullDataReceiver> receiver, int64_t nextPullTimeNs,
                                  int64_t intervalNs);

    // Stop listening on a tagId.
    virtual void UnRegisterReceiver(int tagId, wp<PullDataReceiver> receiver);

    // Verify if we know how to pull for this matcher
    bool PullerForMatcherExists(int tagId) const;

    void OnAlarmFired(const int64_t timeNs);

    // Use respective puller to pull the data. The returned data will have
    // elapsedTimeNs set as timeNs and will have wallClockTimeNs set as current
    // wall clock time.
    virtual bool Pull(const int tagId, const int64_t timeNs,
                      vector<std::shared_ptr<LogEvent>>* data);

    // Clear pull data cache immediately.
    int ForceClearPullerCache();

    // Clear pull data cache if it is beyond respective cool down time.
    int ClearPullerCacheIfNecessary(int64_t timestampNs);

    void SetStatsCompanionService(sp<IStatsCompanionService> statsCompanionService);

    const static std::map<int, PullAtomInfo> kAllPullAtomInfo;

private:
    sp<IStatsCompanionService> mStatsCompanionService = nullptr;

    typedef struct {
        int64_t nextPullTimeNs;
        int64_t intervalNs;
        wp<PullDataReceiver> receiver;
    } ReceiverInfo;

    // mapping from simple matcher tagId to receivers
    std::map<int, std::list<ReceiverInfo>> mReceivers;

    // locks for data receiver and StatsCompanionService changes
    Mutex mLock;

    void updateAlarmLocked();

    int64_t mNextPullTimeNs;

    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvents);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvent_LateAlarm);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents_LateAlarm);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
