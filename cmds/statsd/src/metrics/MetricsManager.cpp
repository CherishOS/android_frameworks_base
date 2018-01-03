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
#define DEBUG true  // STOPSHIP if true
#include "Log.h"
#include "MetricsManager.h"
#include "statslog.h"

#include "CountMetricProducer.h"
#include "condition/CombinationConditionTracker.h"
#include "condition/SimpleConditionTracker.h"
#include "guardrail/StatsdStats.h"
#include "matchers/CombinationLogMatchingTracker.h"
#include "matchers/SimpleLogMatchingTracker.h"
#include "metrics_manager_util.h"
#include "stats_util.h"

#include <log/logprint.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::ProtoOutputStream;

using std::make_unique;
using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

const int FIELD_ID_METRICS = 1;

MetricsManager::MetricsManager(const ConfigKey& key, const StatsdConfig& config,
                               const long timeBaseSec, sp<UidMap> uidMap)
    : mConfigKey(key), mUidMap(uidMap) {
    mConfigValid =
            initStatsdConfig(key, config, *uidMap, timeBaseSec, mTagIds, mAllAtomMatchers, mAllConditionTrackers,
                             mAllMetricProducers, mAllAnomalyTrackers, mConditionToMetricMap,
                             mTrackerToMetricMap, mTrackerToConditionMap);

    if (!config.has_log_source()) {
        // TODO(b/70794411): uncomment the following line and remove the hard coded log source
        // after all configs have the log source added.
        // mConfigValid = false;
        // ALOGE("Log source white list is empty! This config won't get any data.");

        mAllowedUid.push_back(1000);
        mAllowedUid.push_back(0);
        mAllowedLogSources.insert(mAllowedUid.begin(), mAllowedUid.end());
    } else {
        mAllowedUid.insert(mAllowedUid.begin(), config.log_source().uid().begin(),
                           config.log_source().uid().end());
        mAllowedPkg.insert(mAllowedPkg.begin(), config.log_source().package().begin(),
                           config.log_source().package().end());

        if (mAllowedUid.size() + mAllowedPkg.size() > StatsdStats::kMaxLogSourceCount) {
            ALOGE("Too many log sources. This is likely to be an error in the config.");
            mConfigValid = false;
        } else {
            initLogSourceWhiteList();
        }
    }

    // Guardrail. Reject the config if it's too big.
    if (mAllMetricProducers.size() > StatsdStats::kMaxMetricCountPerConfig ||
        mAllConditionTrackers.size() > StatsdStats::kMaxConditionCountPerConfig ||
        mAllAtomMatchers.size() > StatsdStats::kMaxMatcherCountPerConfig) {
        ALOGE("This config is too big! Reject!");
        mConfigValid = false;
    }

    // TODO: add alert size.
    // no matter whether this config is valid, log it in the stats.
    StatsdStats::getInstance().noteConfigReceived(key, mAllMetricProducers.size(),
                                                  mAllConditionTrackers.size(),
                                                  mAllAtomMatchers.size(), 0, mConfigValid);
}

MetricsManager::~MetricsManager() {
    VLOG("~MetricsManager()");
}

void MetricsManager::initLogSourceWhiteList() {
    std::lock_guard<std::mutex> lock(mAllowedLogSourcesMutex);
    mAllowedLogSources.clear();
    mAllowedLogSources.insert(mAllowedUid.begin(), mAllowedUid.end());

    for (const auto& pkg : mAllowedPkg) {
        auto uids = mUidMap->getAppUid(pkg);
        mAllowedLogSources.insert(uids.begin(), uids.end());
    }
    if (DEBUG) {
        for (const auto& uid : mAllowedLogSources) {
            VLOG("Allowed uid %d", uid);
        }
    }
}

bool MetricsManager::isConfigValid() const {
    return mConfigValid;
}

void MetricsManager::notifyAppUpgrade(const string& apk, const int uid, const int64_t version) {
    // check if we care this package
    if (std::find(mAllowedPkg.begin(), mAllowedPkg.end(), apk) == mAllowedPkg.end()) {
        return;
    }
    // We will re-initialize the whole list because we don't want to keep the multi mapping of
    // UID<->pkg inside MetricsManager to reduce the memory usage.
    initLogSourceWhiteList();
}

void MetricsManager::notifyAppRemoved(const string& apk, const int uid) {
    // check if we care this package
    if (std::find(mAllowedPkg.begin(), mAllowedPkg.end(), apk) == mAllowedPkg.end()) {
        return;
    }
    // We will re-initialize the whole list because we don't want to keep the multi mapping of
    // UID<->pkg inside MetricsManager to reduce the memory usage.
    initLogSourceWhiteList();
}

void MetricsManager::onUidMapReceived() {
    if (mAllowedPkg.size() == 0) {
        return;
    }
    initLogSourceWhiteList();
}

void MetricsManager::onDumpReport(const uint64_t& dumpTimeStampNs, ConfigMetricsReport* report) {
    for (auto& producer : mAllMetricProducers) {
        producer->onDumpReport(dumpTimeStampNs, report->add_metrics());
    }
}

void MetricsManager::onDumpReport(ProtoOutputStream* protoOutput) {
    VLOG("=========================Metric Reports Start==========================");
    uint64_t dumpTimeStampNs = time(nullptr) * NS_PER_SEC;
    // one StatsLogReport per MetricProduer
    for (auto& metric : mAllMetricProducers) {
        long long token =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_METRICS);
        metric->onDumpReport(dumpTimeStampNs, protoOutput);
        protoOutput->end(token);
    }
    VLOG("=========================Metric Reports End==========================");
}

// Consume the stats log if it's interesting to this metric.
void MetricsManager::onLogEvent(const LogEvent& event) {
    if (!mConfigValid) {
        return;
    }

    if (event.GetTagId() != android::util::APP_HOOK) {
        std::lock_guard<std::mutex> lock(mAllowedLogSourcesMutex);
        if (mAllowedLogSources.find(event.GetUid()) == mAllowedLogSources.end()) {
            VLOG("log source %d not on the whitelist", event.GetUid());
            return;
        }
    } else { // Check that app hook fields are valid.
        // TODO: Find a way to make these checks easier to maintain if the app hooks get changed.

        // Label is 2nd from last field and must be from [0, 15].
        status_t err = NO_ERROR;
        long label = event.GetLong(event.size()-1, &err);
        if (err != NO_ERROR || label < 0 || label > 15) {
            VLOG("App hook does not have valid label %ld", label);
            return;
        }
        // The state must be from 0,3. This part of code must be manually updated.
        long apphookState = event.GetLong(event.size(), &err);
        if (err != NO_ERROR || apphookState < 0 || apphookState > 3) {
            VLOG("App hook does not have valid state %ld", apphookState);
            return;
        }
    }

    int tagId = event.GetTagId();
    uint64_t eventTime = event.GetTimestampNs();
    if (mTagIds.find(tagId) == mTagIds.end()) {
        // not interesting...
        return;
    }

    vector<MatchingState> matcherCache(mAllAtomMatchers.size(), MatchingState::kNotComputed);

    for (auto& matcher : mAllAtomMatchers) {
        matcher->onLogEvent(event, mAllAtomMatchers, matcherCache);
    }

    // A bitmap to see which ConditionTracker needs to be re-evaluated.
    vector<bool> conditionToBeEvaluated(mAllConditionTrackers.size(), false);

    for (const auto& pair : mTrackerToConditionMap) {
        if (matcherCache[pair.first] == MatchingState::kMatched) {
            const auto& conditionList = pair.second;
            for (const int conditionIndex : conditionList) {
                conditionToBeEvaluated[conditionIndex] = true;
            }
        }
    }

    vector<ConditionState> conditionCache(mAllConditionTrackers.size(),
                                          ConditionState::kNotEvaluated);
    // A bitmap to track if a condition has changed value.
    vector<bool> changedCache(mAllConditionTrackers.size(), false);
    for (size_t i = 0; i < mAllConditionTrackers.size(); i++) {
        if (conditionToBeEvaluated[i] == false) {
            continue;
        }
        sp<ConditionTracker>& condition = mAllConditionTrackers[i];
        condition->evaluateCondition(event, matcherCache, mAllConditionTrackers, conditionCache,
                                     changedCache);
    }

    for (size_t i = 0; i < mAllConditionTrackers.size(); i++) {
        if (changedCache[i] == false) {
            continue;
        }
        auto pair = mConditionToMetricMap.find(i);
        if (pair != mConditionToMetricMap.end()) {
            auto& metricList = pair->second;
            for (auto metricIndex : metricList) {
                // metric cares about non sliced condition, and it's changed.
                // Push the new condition to it directly.
                if (!mAllMetricProducers[metricIndex]->isConditionSliced()) {
                    mAllMetricProducers[metricIndex]->onConditionChanged(conditionCache[i],
                                                                         eventTime);
                    // metric cares about sliced conditions, and it may have changed. Send
                    // notification, and the metric can query the sliced conditions that are
                    // interesting to it.
                } else {
                    mAllMetricProducers[metricIndex]->onSlicedConditionMayChange(eventTime);
                }
            }
        }
    }

    // For matched AtomMatchers, tell relevant metrics that a matched event has come.
    for (size_t i = 0; i < mAllAtomMatchers.size(); i++) {
        if (matcherCache[i] == MatchingState::kMatched) {
            StatsdStats::getInstance().noteMatcherMatched(mConfigKey,
                                                          mAllAtomMatchers[i]->getName());
            auto pair = mTrackerToMetricMap.find(i);
            if (pair != mTrackerToMetricMap.end()) {
                auto& metricList = pair->second;
                for (const int metricIndex : metricList) {
                    // pushed metrics are never scheduled pulls
                    mAllMetricProducers[metricIndex]->onMatchedLogEvent(i, event);
                }
            }
        }
    }
}

void MetricsManager::onAnomalyAlarmFired(const uint64_t timestampNs,
                         unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>>& anomalySet) {
    for (const auto& itr : mAllAnomalyTrackers) {
        itr->informAlarmsFired(timestampNs, anomalySet);
    }
}

void MetricsManager::setAnomalyMonitor(const sp<AnomalyMonitor>& anomalyMonitor) {
    for (auto& itr : mAllAnomalyTrackers) {
        itr->setAnomalyMonitor(anomalyMonitor);
    }
}

// Returns the total byte size of all metrics managed by a single config source.
size_t MetricsManager::byteSize() {
    size_t totalSize = 0;
    for (auto metricProducer : mAllMetricProducers) {
        totalSize += metricProducer->byteSize();
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
