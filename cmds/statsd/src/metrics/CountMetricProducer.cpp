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

#include "CountMetricProducer.h"
#include "guardrail/StatsdStats.h"
#include "stats_util.h"

#include <limits.h>
#include <stdlib.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::map;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_NAME = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_COUNT_METRICS = 5;
// for CountMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for CountMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for KeyValuePair
const int FIELD_ID_KEY = 1;
const int FIELD_ID_VALUE_STR = 2;
const int FIELD_ID_VALUE_INT = 3;
const int FIELD_ID_VALUE_BOOL = 4;
const int FIELD_ID_VALUE_FLOAT = 5;
// for CountBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_COUNT = 3;

CountMetricProducer::CountMetricProducer(const ConfigKey& key, const CountMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard,
                                         const uint64_t startTimeNs)
    : MetricProducer(metric.name(), key, startTimeNs, conditionIndex, wizard) {
    // TODO: evaluate initial conditions. and set mConditionMet.
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    VLOG("metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

CountMetricProducer::~CountMetricProducer() {
    VLOG("~CountMetricProducer() called");
}

void CountMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mName.c_str());
}

void CountMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    flushIfNeededLocked(dumpTimeNs);

    protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mName);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_COUNT_METRICS);

    VLOG("metric %s dump report now...", mName.c_str());

    for (const auto& counter : mPastBuckets) {
        const HashableDimensionKey& hashableKey = counter.first;
        VLOG("  dimension key %s", hashableKey.c_str());
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        long long wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension (KeyValuePairs).
        for (const auto& kv : it->second) {
            long long dimensionToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DIMENSION);
            protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_KEY, kv.key());
            if (kv.has_value_str()) {
                protoOutput->write(FIELD_TYPE_STRING | FIELD_ID_VALUE_STR, kv.value_str());
            } else if (kv.has_value_int()) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_INT, kv.value_int());
            } else if (kv.has_value_bool()) {
                protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_VALUE_BOOL, kv.value_bool());
            } else if (kv.has_value_float()) {
                protoOutput->write(FIELD_TYPE_FLOAT | FIELD_ID_VALUE_FLOAT, kv.value_float());
            }
            protoOutput->end(dimensionToken);
        }

        // Then fill bucket_info (CountBucketInfo).
        for (const auto& bucket : counter.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                               (long long)bucket.mBucketEndNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_COUNT, (long long)bucket.mCount);
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] count: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mCount);
        }
        protoOutput->end(wrapperToken);
    }

    protoOutput->end(protoToken);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, (long long)dumpTimeNs);

    mPastBuckets.clear();
    mStartTimeNs = mCurrentBucketStartTimeNs;

    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void CountMetricProducer::onConditionChangedLocked(const bool conditionMet,
                                                   const uint64_t eventTime) {
    VLOG("Metric %s onConditionChanged", mName.c_str());
    mCondition = conditionMet;
}

bool CountMetricProducer::hitGuardRailLocked(const HashableDimensionKey& newKey) {
    if (mCurrentSlicedCounter->find(newKey) != mCurrentSlicedCounter->end()) {
        return false;
    }
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedCounter->size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedCounter->size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mName, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("CountMetric %s dropping data for dimension key %s", mName.c_str(),
                  newKey.c_str());
            return true;
        }
    }

    return false;
}

void CountMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();

    flushIfNeededLocked(eventTimeNs);

    if (condition == false) {
        return;
    }

    auto it = mCurrentSlicedCounter->find(eventKey);

    if (it == mCurrentSlicedCounter->end()) {
        // ===========GuardRail==============
        if (hitGuardRailLocked(eventKey)) {
            return;
        }

        // create a counter for the new key
        (*mCurrentSlicedCounter)[eventKey] = 1;
    } else {
        // increment the existing value
        auto& count = it->second;
        count++;
    }

    for (auto& tracker : mAnomalyTrackers) {
        tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey,
                                         mCurrentSlicedCounter->find(eventKey)->second);
    }

    VLOG("metric %s %s->%lld", mName.c_str(), eventKey.c_str(),
         (long long)(*mCurrentSlicedCounter)[eventKey]);
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the new bucket.
void CountMetricProducer::flushIfNeededLocked(const uint64_t& eventTimeNs) {
    if (eventTimeNs < mCurrentBucketStartTimeNs + mBucketSizeNs) {
        return;
    }

    CountBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketNum = mCurrentBucketNum;
    for (const auto& counter : *mCurrentSlicedCounter) {
        info.mCount = counter.second;
        auto& bucketList = mPastBuckets[counter.first];
        bucketList.push_back(info);
        VLOG("metric %s, dump key value: %s -> %lld", mName.c_str(), counter.first.c_str(),
             (long long)counter.second);
    }

    for (auto& tracker : mAnomalyTrackers) {
        tracker->addPastBucket(mCurrentSlicedCounter, mCurrentBucketNum);
    }

    // Reset counters (do not clear, since the old one is still referenced in mAnomalyTrackers).
    mCurrentSlicedCounter = std::make_shared<DimToValMap>();
    uint64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;
    VLOG("metric %s: new bucket start time: %lld", mName.c_str(),
         (long long)mCurrentBucketStartTimeNs);
}

// Rough estimate of CountMetricProducer buffer stored. This number will be
// greater than actual data size as it contains each dimension of
// CountMetricData is  duplicated.
size_t CountMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
