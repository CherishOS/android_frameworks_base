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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "ValueMetricProducer.h"
#include "guardrail/StatsdStats.h"
#include "stats_log_util.h"

#include <cutils/log.h>
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
using std::list;
using std::make_pair;
using std::make_shared;
using std::map;
using std::shared_ptr;
using std::unique_ptr;
using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_ID = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_VALUE_METRICS = 7;
// for ValueMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for ValueMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for ValueBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_VALUE = 3;

static const uint64_t kDefaultBucketSizeMillis = 60 * 60 * 1000L;

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const uint64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(metric.id(), key, startTimeNs, conditionIndex, wizard),
      mValueField(metric.value_field()),
      mStatsPullerManager(statsPullerManager),
      mPullTagId(pullTagId) {
    // TODO: valuemetric for pushed events may need unlimited bucket length
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = kDefaultBucketSizeMillis * 1000 * 1000;
    }

    mDimensions = metric.dimensions();

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    if (!metric.has_condition() && mPullTagId != -1) {
        VLOG("Setting up periodic pulling for %d", mPullTagId);
        mStatsPullerManager->RegisterReceiver(mPullTagId, this,
                                              metric.bucket().bucket_size_millis());
    }
    VLOG("value metric %lld created. bucket size %lld start_time: %lld",
        (long long)metric.id(), (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

// for testing
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const uint64_t startTimeNs)
    : ValueMetricProducer(key, metric, conditionIndex, wizard, pullTagId, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

ValueMetricProducer::~ValueMetricProducer() {
    VLOG("~ValueMetricProducer() called");
    if (mPullTagId != -1) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void ValueMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
}

void ValueMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs, StatsLogReport* report) {
    flushIfNeededLocked(dumpTimeNs);
    report->set_metric_id(mMetricId);
    report->set_start_report_nanos(mStartTimeNs);
    auto value_metrics = report->mutable_value_metrics();
    for (const auto& pair : mPastBuckets) {
        ValueMetricData* metricData = value_metrics->add_data();
        *metricData->mutable_dimension() = pair.first.getDimensionsValue();
        for (const auto& bucket : pair.second) {
            ValueBucketInfo* bucketInfo = metricData->add_bucket_info();
            bucketInfo->set_start_bucket_nanos(bucket.mBucketStartNs);
            bucketInfo->set_end_bucket_nanos(bucket.mBucketEndNs);
            bucketInfo->set_value(bucket.mValue);
        }
    }
}

void ValueMetricProducer::onDumpReportLocked(const uint64_t dumpTimeNs,
                                             ProtoOutputStream* protoOutput) {
    VLOG("metric %lld dump report now...", (long long)mMetricId);
    flushIfNeededLocked(dumpTimeNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, (long long)mStartTimeNs);
    long long protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_VALUE_METRICS);

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        VLOG("  dimension key %s", hashableKey.c_str());
        long long wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        long long dimensionToken = protoOutput->start(
                FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DIMENSION);
        writeDimensionsValueProtoToStream(hashableKey.getDimensionsValue(), protoOutput);
        protoOutput->end(dimensionToken);

        // Then fill bucket_info (ValueBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                               (long long)bucket.mBucketStartNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                               (long long)bucket.mBucketEndNs);
            protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE, (long long)bucket.mValue);
            protoOutput->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] count: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mValue);
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS, (long long)dumpTimeNs);

    VLOG("metric %lld dump report now...", (long long)mMetricId);
    mPastBuckets.clear();
    mStartTimeNs = mCurrentBucketStartTimeNs;
    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void ValueMetricProducer::onConditionChangedLocked(const bool condition, const uint64_t eventTime) {
    mCondition = condition;

    if (eventTime < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTime,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    flushIfNeededLocked(eventTime);

    if (mPullTagId != -1) {
        if (mCondition == true) {
            mStatsPullerManager->RegisterReceiver(mPullTagId, this, mBucketSizeNs / 1000 / 1000);
        } else if (mCondition == false) {
            mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
        }

        vector<shared_ptr<LogEvent>> allData;
        if (mStatsPullerManager->Pull(mPullTagId, &allData)) {
            if (allData.size() == 0) {
                return;
            }
            for (const auto& data : allData) {
                onMatchedLogEventLocked(0, *data);
            }
        }
        return;
    }
}

void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mCondition == true || mConditionTrackerIndex < 0) {
        if (allData.size() == 0) {
            return;
        }
        // For scheduled pulled data, the effective event time is snap to the nearest
        // bucket boundary to make bucket finalize.
        uint64_t realEventTime = allData.at(0)->GetTimestampNs();
        uint64_t eventTime = mStartTimeNs + ((realEventTime - mStartTimeNs)/mBucketSizeNs) * mBucketSizeNs;

        mCondition = false;
        for (const auto& data : allData) {
            data->setTimestampNs(eventTime-1);
            onMatchedLogEventLocked(0, *data);
        }

        mCondition = true;
        for (const auto& data : allData) {
            data->setTimestampNs(eventTime);
            onMatchedLogEventLocked(0, *data);
        }
    }
}

bool ValueMetricProducer::hitGuardRailLocked(const HashableDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket.find(newKey) != mCurrentSlicedBucket.end()) {
        return false;
    }
    if (mCurrentSlicedBucket.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("ValueMetric %lld dropping data for dimension key %s",
                (long long)mMetricId, newKey.c_str());
            return true;
        }
    }

    return false;
}

void ValueMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const ConditionKey& conditionKey, bool condition,
        const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    flushIfNeededLocked(eventTimeNs);

    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    Interval& interval = mCurrentSlicedBucket[eventKey];

    long value = get_value(event);

    if (mPullTagId != -1) { // for pulled events
        if (mCondition == true) {
            interval.start = value;
            interval.startUpdated = true;
        } else {
            if (interval.startUpdated) {
                interval.sum += (value - interval.start);
                interval.startUpdated = false;
            } else {
                VLOG("No start for matching end %ld", value);
                interval.tainted += 1;
            }
        }
    } else {    // for pushed events
        interval.sum += value;
    }

    for (auto& tracker : mAnomalyTrackers) {
        tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, eventKey, interval.sum);
    }
}

long ValueMetricProducer::get_value(const LogEvent& event) {
    status_t err = NO_ERROR;
    long val = event.GetLong(mValueField, &err);
    if (err == NO_ERROR) {
        return val;
    } else {
        VLOG("Can't find value in message. %s", event.ToString().c_str());
        return 0;
    }
}

void ValueMetricProducer::flushIfNeededLocked(const uint64_t& eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }
    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    ValueBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketNum = mCurrentBucketNum;

    int tainted = 0;
    for (const auto& slice : mCurrentSlicedBucket) {
        tainted += slice.second.tainted;
        info.mValue = slice.second.sum;
        // it will auto create new vector of ValuebucketInfo if the key is not found.
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);

        for (auto& tracker : mAnomalyTrackers) {
            if (tracker != nullptr) {
                tracker->addPastBucket(slice.first, info.mValue, info.mBucketNum);
            }
        }
    }
    VLOG("%d tainted pairs in the bucket", tainted);

    // Reset counters
    mCurrentSlicedBucket.clear();

    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;

    if (numBucketsForward > 1) {
        VLOG("Skipping forward %lld buckets", (long long)numBucketsForward);
    }
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

size_t ValueMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
