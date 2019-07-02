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
#include "../guardrail/StatsdStats.h"
#include "../stats_log_util.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_DOUBLE;
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
const int FIELD_ID_VALUE_METRICS = 7;
const int FIELD_ID_TIME_BASE = 9;
const int FIELD_ID_BUCKET_SIZE = 10;
const int FIELD_ID_DIMENSION_PATH_IN_WHAT = 11;
const int FIELD_ID_DIMENSION_PATH_IN_CONDITION = 12;
const int FIELD_ID_IS_ACTIVE = 14;
// for ValueMetricDataWrapper
const int FIELD_ID_DATA = 1;
const int FIELD_ID_SKIPPED = 2;
const int FIELD_ID_SKIPPED_START_MILLIS = 3;
const int FIELD_ID_SKIPPED_END_MILLIS = 4;
// for ValueMetricData
const int FIELD_ID_DIMENSION_IN_WHAT = 1;
const int FIELD_ID_DIMENSION_IN_CONDITION = 2;
const int FIELD_ID_BUCKET_INFO = 3;
const int FIELD_ID_DIMENSION_LEAF_IN_WHAT = 4;
const int FIELD_ID_DIMENSION_LEAF_IN_CONDITION = 5;
// for ValueBucketInfo
const int FIELD_ID_VALUE_INDEX = 1;
const int FIELD_ID_VALUE_LONG = 2;
const int FIELD_ID_VALUE_DOUBLE = 3;
const int FIELD_ID_VALUES = 9;
const int FIELD_ID_BUCKET_NUM = 4;
const int FIELD_ID_START_BUCKET_ELAPSED_MILLIS = 5;
const int FIELD_ID_END_BUCKET_ELAPSED_MILLIS = 6;
const int FIELD_ID_CONDITION_TRUE_NS = 10;

const Value ZERO_LONG((int64_t)0);
const Value ZERO_DOUBLE((int64_t)0);

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(
        const ConfigKey& key, const ValueMetric& metric, const int conditionIndex,
        const sp<ConditionWizard>& conditionWizard, const int whatMatcherIndex,
        const sp<EventMatcherWizard>& matcherWizard, const int pullTagId, const int64_t timeBaseNs,
        const int64_t startTimeNs, const sp<StatsPullerManager>& pullerManager)
    : MetricProducer(metric.id(), key, timeBaseNs, conditionIndex, conditionWizard),
      mWhatMatcherIndex(whatMatcherIndex),
      mEventMatcherWizard(matcherWizard),
      mPullerManager(pullerManager),
      mPullTagId(pullTagId),
      mIsPulled(pullTagId != -1),
      mMinBucketSizeNs(metric.min_bucket_size_nanos()),
      mDimensionSoftLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).first
                                  : StatsdStats::kDimensionKeySizeSoftLimit),
      mDimensionHardLimit(StatsdStats::kAtomDimensionKeySizeLimitMap.find(pullTagId) !=
                                          StatsdStats::kAtomDimensionKeySizeLimitMap.end()
                                  ? StatsdStats::kAtomDimensionKeySizeLimitMap.at(pullTagId).second
                                  : StatsdStats::kDimensionKeySizeHardLimit),
      mUseAbsoluteValueOnReset(metric.use_absolute_value_on_reset()),
      mAggregationType(metric.aggregation_type()),
      mUseDiff(metric.has_use_diff() ? metric.use_diff() : (mIsPulled ? true : false)),
      mValueDirection(metric.value_direction()),
      mSkipZeroDiffOutput(metric.skip_zero_diff_output()),
      mUseZeroDefaultBase(metric.use_zero_default_base()),
      mHasGlobalBase(false),
      mCurrentBucketIsInvalid(false),
      mMaxPullDelayNs(metric.max_pull_delay_sec() > 0 ? metric.max_pull_delay_sec() * NS_PER_SEC
                                                      : StatsdStats::kPullMaxDelayNs),
      mSplitBucketForAppUpgrade(metric.split_bucket_for_app_upgrade()),
      // Condition timer will be set in prepareFirstBucketLocked.
      mConditionTimer(false, timeBaseNs) {
    int64_t bucketSizeMills = 0;
    if (metric.has_bucket()) {
        bucketSizeMills = TimeUnitToBucketSizeInMillisGuardrailed(key.GetUid(), metric.bucket());
    } else {
        bucketSizeMills = TimeUnitToBucketSizeInMillis(ONE_HOUR);
    }

    mBucketSizeNs = bucketSizeMills * 1000000;

    translateFieldMatcher(metric.value_field(), &mFieldMatchers);

    if (metric.has_dimensions_in_what()) {
        translateFieldMatcher(metric.dimensions_in_what(), &mDimensionsInWhat);
        mContainANYPositionInDimensionsInWhat = HasPositionANY(metric.dimensions_in_what());
    }

    if (metric.has_dimensions_in_condition()) {
        translateFieldMatcher(metric.dimensions_in_condition(), &mDimensionsInCondition);
    }

    if (metric.links().size() > 0) {
        for (const auto& link : metric.links()) {
            Metric2Condition mc;
            mc.conditionId = link.condition();
            translateFieldMatcher(link.fields_in_what(), &mc.metricFields);
            translateFieldMatcher(link.fields_in_condition(), &mc.conditionFields);
            mMetric2ConditionLinks.push_back(mc);
        }
    }

    mConditionSliced = (metric.links().size() > 0) || (mDimensionsInCondition.size() > 0);
    mSliceByPositionALL = HasPositionALL(metric.dimensions_in_what()) ||
                          HasPositionALL(metric.dimensions_in_condition());

    int64_t numBucketsForward = calcBucketsForwardCount(startTimeNs);
    mCurrentBucketNum += numBucketsForward;

    flushIfNeededLocked(startTimeNs);

    if (mIsPulled) {
        mPullerManager->RegisterReceiver(mPullTagId, this, getCurrentBucketEndTimeNs(),
                                         mBucketSizeNs);
    }

    // Only do this for partial buckets like first bucket. All other buckets should use
    // flushIfNeeded to adjust start and end to bucket boundaries.
    // Adjust start for partial bucket
    mCurrentBucketStartTimeNs = startTimeNs;
    mConditionTimer.newBucketStart(mCurrentBucketStartTimeNs);
    VLOG("value metric %lld created. bucket size %lld start_time: %lld", (long long)metric.id(),
         (long long)mBucketSizeNs, (long long)mTimeBaseNs);
}

ValueMetricProducer::~ValueMetricProducer() {
    VLOG("~ValueMetricProducer() called");
    if (mIsPulled) {
        mPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void ValueMetricProducer::prepareFirstBucketLocked() {
    // Kicks off the puller immediately if condition is true and diff based.
    if (mIsActive && mIsPulled && mCondition == ConditionState::kTrue && mUseDiff) {
        pullAndMatchEventsLocked(mCurrentBucketStartTimeNs, mCondition);
    }
    // Now that activations are processed, start the condition timer if needed.
    mConditionTimer.onConditionChanged(mIsActive && mCondition == ConditionState::kTrue,
                                       mCurrentBucketStartTimeNs);
}

void ValueMetricProducer::onSlicedConditionMayChangeLocked(bool overallCondition,
                                                           const int64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", (long long)mMetricId);
}

void ValueMetricProducer::dropDataLocked(const int64_t dropTimeNs) {
    StatsdStats::getInstance().noteBucketDropped(mMetricId);
    // We are going to flush the data without doing a pull first so we need to invalidte the data.
    bool pullNeeded = mIsPulled && mCondition == ConditionState::kTrue;
    if (pullNeeded) {
        invalidateCurrentBucket();
    }
    flushIfNeededLocked(dropTimeNs);
    clearPastBucketsLocked(dropTimeNs);
}

void ValueMetricProducer::clearPastBucketsLocked(const int64_t dumpTimeNs) {
    mPastBuckets.clear();
    mSkippedBuckets.clear();
}

void ValueMetricProducer::onDumpReportLocked(const int64_t dumpTimeNs,
                                             const bool include_current_partial_bucket,
                                             const bool erase_data,
                                             const DumpLatency dumpLatency,
                                             std::set<string> *str_set,
                                             ProtoOutputStream* protoOutput) {
    VLOG("metric %lld dump report now...", (long long)mMetricId);
    if (include_current_partial_bucket) {
        // For pull metrics, we need to do a pull at bucket boundaries. If we do not do that the
        // current bucket will have incomplete data and the next will have the wrong snapshot to do
        // a diff against. If the condition is false, we are fine since the base data is reset and
        // we are not tracking anything.
        bool pullNeeded = mIsPulled && mCondition == ConditionState::kTrue;
        if (pullNeeded) {
            switch (dumpLatency) {
                case FAST:
                    invalidateCurrentBucket();
                    break;
                case NO_TIME_CONSTRAINTS:
                    pullAndMatchEventsLocked(dumpTimeNs, mCondition);
                    break;
            }
        }
        flushCurrentBucketLocked(dumpTimeNs, dumpTimeNs);
    }
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)mMetricId);
    protoOutput->write(FIELD_TYPE_BOOL | FIELD_ID_IS_ACTIVE, isActiveLocked());

    if (mPastBuckets.empty() && mSkippedBuckets.empty()) {
        return;
    }
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TIME_BASE, (long long)mTimeBaseNs);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_SIZE, (long long)mBucketSizeNs);
    // Fills the dimension path if not slicing by ALL.
    if (!mSliceByPositionALL) {
        if (!mDimensionsInWhat.empty()) {
            uint64_t dimenPathToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_WHAT);
            writeDimensionPathToProto(mDimensionsInWhat, protoOutput);
            protoOutput->end(dimenPathToken);
        }
        if (!mDimensionsInCondition.empty()) {
            uint64_t dimenPathToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_PATH_IN_CONDITION);
            writeDimensionPathToProto(mDimensionsInCondition, protoOutput);
            protoOutput->end(dimenPathToken);
        }
    }

    uint64_t protoToken = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_VALUE_METRICS);

    for (const auto& pair : mSkippedBuckets) {
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_SKIPPED);
        protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_SKIPPED_START_MILLIS,
                           (long long)(NanoToMillis(pair.first)));
        protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_SKIPPED_END_MILLIS,
                           (long long)(NanoToMillis(pair.second)));
        protoOutput->end(wrapperToken);
    }

    for (const auto& pair : mPastBuckets) {
        const MetricDimensionKey& dimensionKey = pair.first;
        VLOG("  dimension key %s", dimensionKey.toString().c_str());
        uint64_t wrapperToken =
                protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension.
        if (mSliceByPositionALL) {
            uint64_t dimensionToken =
                    protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_WHAT);
            writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), str_set, protoOutput);
            protoOutput->end(dimensionToken);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                uint64_t dimensionInConditionToken =
                        protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_DIMENSION_IN_CONDITION);
                writeDimensionToProto(dimensionKey.getDimensionKeyInCondition(), str_set,
                                      protoOutput);
                protoOutput->end(dimensionInConditionToken);
            }
        } else {
            writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInWhat(),
                                           FIELD_ID_DIMENSION_LEAF_IN_WHAT, str_set, protoOutput);
            if (dimensionKey.hasDimensionKeyInCondition()) {
                writeDimensionLeafNodesToProto(dimensionKey.getDimensionKeyInCondition(),
                                               FIELD_ID_DIMENSION_LEAF_IN_CONDITION, str_set,
                                               protoOutput);
            }
        }

        // Then fill bucket_info (ValueBucketInfo).
        for (const auto& bucket : pair.second) {
            uint64_t bucketInfoToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);

            if (bucket.mBucketEndNs - bucket.mBucketStartNs != mBucketSizeNs) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketStartNs));
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_ELAPSED_MILLIS,
                                   (long long)NanoToMillis(bucket.mBucketEndNs));
            } else {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_BUCKET_NUM,
                                   (long long)(getBucketNumFromEndTimeNs(bucket.mBucketEndNs)));
            }
            // only write the condition timer value if the metric has a condition.
            if (mConditionTrackerIndex >= 0) {
                protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_CONDITION_TRUE_NS,
                                   (long long)bucket.mConditionTrueNs);
            }
            for (int i = 0; i < (int)bucket.valueIndex.size(); i ++) {
                int index = bucket.valueIndex[i];
                const Value& value = bucket.values[i];
                uint64_t valueToken = protoOutput->start(
                        FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_VALUES);
                protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_VALUE_INDEX,
                                   index);
                if (value.getType() == LONG) {
                    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_LONG,
                                       (long long)value.long_value);
                    VLOG("\t bucket [%lld - %lld] value %d: %lld", (long long)bucket.mBucketStartNs,
                         (long long)bucket.mBucketEndNs, index, (long long)value.long_value);
                } else if (value.getType() == DOUBLE) {
                    protoOutput->write(FIELD_TYPE_DOUBLE | FIELD_ID_VALUE_DOUBLE,
                                       value.double_value);
                    VLOG("\t bucket [%lld - %lld] value %d: %.2f", (long long)bucket.mBucketStartNs,
                         (long long)bucket.mBucketEndNs, index, value.double_value);
                } else {
                    VLOG("Wrong value type for ValueMetric output: %d", value.getType());
                }
                protoOutput->end(valueToken);
            }
            protoOutput->end(bucketInfoToken);
        }
        protoOutput->end(wrapperToken);
    }
    protoOutput->end(protoToken);

    VLOG("metric %lld dump report now...", (long long)mMetricId);
    if (erase_data) {
        mPastBuckets.clear();
        mSkippedBuckets.clear();
    }
}

void ValueMetricProducer::invalidateCurrentBucketWithoutResetBase() {
    if (!mCurrentBucketIsInvalid) {
        // Only report once per invalid bucket.
        StatsdStats::getInstance().noteInvalidatedBucket(mMetricId);
    }
    mCurrentBucketIsInvalid = true;
}

void ValueMetricProducer::invalidateCurrentBucket() {
    invalidateCurrentBucketWithoutResetBase();
    resetBase();
}

void ValueMetricProducer::resetBase() {
    for (auto& slice : mCurrentSlicedBucket) {
        for (auto& interval : slice.second) {
            interval.hasBase = false;
        }
    }
    mHasGlobalBase = false;
}

// Handle active state change. Active state change is treated like a condition change:
// - drop bucket if active state change event arrives too late
// - if condition is true, pull data on active state changes
// - ConditionTimer tracks changes based on AND of condition and active state.
void ValueMetricProducer::onActiveStateChangedLocked(const int64_t& eventTimeNs) {
    bool isEventTooLate  = eventTimeNs < mCurrentBucketStartTimeNs;
    if (ConditionState::kTrue == mCondition && isEventTooLate) {
        // Drop bucket because event arrived too late, ie. we are missing data for this bucket.
        invalidateCurrentBucket();
    }

    // Call parent method once we've verified the validity of current bucket.
    MetricProducer::onActiveStateChangedLocked(eventTimeNs);

    if (ConditionState::kTrue != mCondition) {
        return;
    }

    // Pull on active state changes.
    if (!isEventTooLate) {
        if (mIsPulled) {
            pullAndMatchEventsLocked(eventTimeNs, mCondition);
        }
        // When active state changes from true to false, clear diff base but don't
        // reset other counters as we may accumulate more value in the bucket.
        if (mUseDiff && !mIsActive) {
            resetBase();
        }
    }

    flushIfNeededLocked(eventTimeNs);

    // Let condition timer know of new active state.
    mConditionTimer.onConditionChanged(mIsActive, eventTimeNs);
}

void ValueMetricProducer::onConditionChangedLocked(const bool condition,
                                                   const int64_t eventTimeNs) {
    ConditionState newCondition = condition ? ConditionState::kTrue : ConditionState::kFalse;
    bool isEventTooLate  = eventTimeNs < mCurrentBucketStartTimeNs;

    if (mIsActive) {
        if (isEventTooLate) {
            VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
                 (long long)mCurrentBucketStartTimeNs);
            StatsdStats::getInstance().noteConditionChangeInNextBucket(mMetricId);
            invalidateCurrentBucket();
        } else {
            if (mCondition == ConditionState::kUnknown) {
                // If the condition was unknown, we mark the bucket as invalid since the bucket will
                // contain partial data. For instance, the condition change might happen close to
                // the end of the bucket and we might miss lots of data.
                //
                // We still want to pull to set the base.
                invalidateCurrentBucket();
            }

            // Pull on condition changes.
            bool conditionChanged =
                    (mCondition == ConditionState::kTrue && newCondition == ConditionState::kFalse)
                    || (mCondition == ConditionState::kFalse &&
                            newCondition == ConditionState::kTrue);
            // We do not need to pull when we go from unknown to false.
            //
            // We also pull if the condition was already true in order to be able to flush the
            // bucket at the end if needed.
            //
            // onConditionChangedLocked might happen on bucket boundaries if this is called before
            // #onDataPulled.
            if (mIsPulled && (conditionChanged || condition)) {
                pullAndMatchEventsLocked(eventTimeNs, newCondition);
            }

            // When condition change from true to false, clear diff base but don't
            // reset other counters as we may accumulate more value in the bucket.
            if (mUseDiff && mCondition == ConditionState::kTrue
                    && newCondition == ConditionState::kFalse) {
                resetBase();
            }
        }
    }

    mCondition = isEventTooLate ? initialCondition(mConditionTrackerIndex) : newCondition;

    if (mIsActive) {
        flushIfNeededLocked(eventTimeNs);
        mConditionTimer.onConditionChanged(mCondition, eventTimeNs);
    }
}

void ValueMetricProducer::pullAndMatchEventsLocked(const int64_t timestampNs,
        ConditionState condition) {
    vector<std::shared_ptr<LogEvent>> allData;
    if (!mPullerManager->Pull(mPullTagId, &allData)) {
        ALOGE("Stats puller failed for tag: %d at %lld", mPullTagId, (long long)timestampNs);
        invalidateCurrentBucket();
        return;
    }

    accumulateEvents(allData, timestampNs, timestampNs, condition);
}

int64_t ValueMetricProducer::calcPreviousBucketEndTime(const int64_t currentTimeNs) {
    return mTimeBaseNs + ((currentTimeNs - mTimeBaseNs) / mBucketSizeNs) * mBucketSizeNs;
}

// By design, statsd pulls data at bucket boundaries using AlarmManager. These pulls are likely
// to be delayed. Other events like condition changes or app upgrade which are not based on
// AlarmManager might have arrived earlier and close the bucket.
void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData,
                                       bool pullSuccess, int64_t originalPullTimeNs) {
    std::lock_guard<std::mutex> lock(mMutex);
        if (mCondition == ConditionState::kTrue) {
            // If the pull failed, we won't be able to compute a diff.
            if (!pullSuccess) {
                invalidateCurrentBucket();
            } else {
                bool isEventLate = originalPullTimeNs < getCurrentBucketEndTimeNs();
                if (isEventLate) {
                    // If the event is late, we are in the middle of a bucket. Just
                    // process the data without trying to snap the data to the nearest bucket.
                    accumulateEvents(allData, originalPullTimeNs, originalPullTimeNs, mCondition);
                } else {
                    // For scheduled pulled data, the effective event time is snap to the nearest
                    // bucket end. In the case of waking up from a deep sleep state, we will
                    // attribute to the previous bucket end. If the sleep was long but not very
                    // long, we will be in the immediate next bucket. Previous bucket may get a
                    // larger number as we pull at a later time than real bucket end.
                    //
                    // If the sleep was very long, we skip more than one bucket before sleep. In
                    // this case, if the diff base will be cleared and this new data will serve as
                    // new diff base.
                    int64_t bucketEndTime = calcPreviousBucketEndTime(originalPullTimeNs) - 1;
                    StatsdStats::getInstance().noteBucketBoundaryDelayNs(
                            mMetricId, originalPullTimeNs - bucketEndTime);
                    accumulateEvents(allData, originalPullTimeNs, bucketEndTime, mCondition);
                }
            }
        }

    // We can probably flush the bucket. Since we used bucketEndTime when calling
    // #onMatchedLogEventInternalLocked, the current bucket will not have been flushed.
    flushIfNeededLocked(originalPullTimeNs);
}

void ValueMetricProducer::accumulateEvents(const std::vector<std::shared_ptr<LogEvent>>& allData,
                                           int64_t originalPullTimeNs, int64_t eventElapsedTimeNs,
                                           ConditionState condition) {
    bool isEventLate = eventElapsedTimeNs < mCurrentBucketStartTimeNs;
    if (isEventLate) {
        VLOG("Skip bucket end pull due to late arrival: %lld vs %lld",
             (long long)eventElapsedTimeNs, (long long)mCurrentBucketStartTimeNs);
        StatsdStats::getInstance().noteLateLogEventSkipped(mMetricId);
        invalidateCurrentBucket();
        return;
    }

    const int64_t pullDelayNs = getElapsedRealtimeNs() - originalPullTimeNs;
    StatsdStats::getInstance().notePullDelay(mPullTagId, pullDelayNs);
    if (pullDelayNs > mMaxPullDelayNs) {
        ALOGE("Pull finish too late for atom %d, longer than %lld", mPullTagId,
              (long long)mMaxPullDelayNs);
        StatsdStats::getInstance().notePullExceedMaxDelay(mPullTagId);
        // We are missing one pull from the bucket which means we will not have a complete view of
        // what's going on.
        invalidateCurrentBucket();
        return;
    }

    if (allData.size() == 0) {
        VLOG("Data pulled is empty");
        StatsdStats::getInstance().noteEmptyData(mPullTagId);
    }

    mMatchedMetricDimensionKeys.clear();
    for (const auto& data : allData) {
        LogEvent localCopy = data->makeCopy();
        if (mEventMatcherWizard->matchLogEvent(localCopy, mWhatMatcherIndex) ==
            MatchingState::kMatched) {
            localCopy.setElapsedTimestampNs(eventElapsedTimeNs);
            onMatchedLogEventLocked(mWhatMatcherIndex, localCopy);
        }
    }
    // If the new pulled data does not contains some keys we track in our intervals, we need to
    // reset the base.
    for (auto& slice : mCurrentSlicedBucket) {
        bool presentInPulledData = mMatchedMetricDimensionKeys.find(slice.first)
                != mMatchedMetricDimensionKeys.end();
        if (!presentInPulledData) {
            for (auto& interval : slice.second) {
                interval.hasBase = false;
            }
        }
    }
    mMatchedMetricDimensionKeys.clear();
    mHasGlobalBase = true;

    // If we reach the guardrail, we might have dropped some data which means the bucket is
    // incomplete.
    //
    // The base also needs to be reset. If we do not have the full data, we might
    // incorrectly compute the diff when mUseZeroDefaultBase is true since an existing key
    // might be missing from mCurrentSlicedBucket.
    if (hasReachedGuardRailLimit()) {
        invalidateCurrentBucket();
        mCurrentSlicedBucket.clear();
    }
}

void ValueMetricProducer::dumpStatesLocked(FILE* out, bool verbose) const {
    if (mCurrentSlicedBucket.size() == 0) {
        return;
    }

    fprintf(out, "ValueMetric %lld dimension size %lu\n", (long long)mMetricId,
            (unsigned long)mCurrentSlicedBucket.size());
    if (verbose) {
        for (const auto& it : mCurrentSlicedBucket) {
          for (const auto& interval : it.second) {
            fprintf(out, "\t(what)%s\t(condition)%s  (value)%s\n",
                    it.first.getDimensionKeyInWhat().toString().c_str(),
                    it.first.getDimensionKeyInCondition().toString().c_str(),
                    interval.value.toString().c_str());
          }
        }
    }
}

bool ValueMetricProducer::hasReachedGuardRailLimit() const {
    return mCurrentSlicedBucket.size() >= mDimensionHardLimit;
}

bool ValueMetricProducer::hitGuardRailLocked(const MetricDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket.find(newKey) != mCurrentSlicedBucket.end()) {
        return false;
    }
    if (mCurrentSlicedBucket.size() > mDimensionSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetricId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (hasReachedGuardRailLimit()) {
            ALOGE("ValueMetric %lld dropping data for dimension key %s", (long long)mMetricId,
                  newKey.toString().c_str());
            StatsdStats::getInstance().noteHardDimensionLimitReached(mMetricId);
            return true;
        }
    }

    return false;
}

bool ValueMetricProducer::hitFullBucketGuardRailLocked(const MetricDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentFullBucket.find(newKey) != mCurrentFullBucket.end()) {
        return false;
    }
    if (mCurrentFullBucket.size() > mDimensionSoftLimit - 1) {
        size_t newTupleCount = mCurrentFullBucket.size() + 1;
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > mDimensionHardLimit) {
            ALOGE("ValueMetric %lld dropping data for full bucket dimension key %s",
                  (long long)mMetricId,
                  newKey.toString().c_str());
            return true;
        }
    }

    return false;
}

bool getDoubleOrLong(const LogEvent& event, const Matcher& matcher, Value& ret) {
    for (const FieldValue& value : event.getValues()) {
        if (value.mField.matches(matcher)) {
            switch (value.mValue.type) {
                case INT:
                    ret.setLong(value.mValue.int_value);
                    break;
                case LONG:
                    ret.setLong(value.mValue.long_value);
                    break;
                case FLOAT:
                    ret.setDouble(value.mValue.float_value);
                    break;
                case DOUBLE:
                    ret.setDouble(value.mValue.double_value);
                    break;
                default:
                    break;
            }
            return true;
        }
    }
    return false;
}

void ValueMetricProducer::onMatchedLogEventInternalLocked(const size_t matcherIndex,
                                                          const MetricDimensionKey& eventKey,
                                                          const ConditionKey& conditionKey,
                                                          bool condition, const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }
    mMatchedMetricDimensionKeys.insert(eventKey);

    if (!mIsPulled) {
        // We cannot flush without doing a pull first.
        flushIfNeededLocked(eventTimeNs);
    }

    // We should not accumulate the data for pushed metrics when the condition is false.
    bool shouldSkipForPushMetric = !mIsPulled && !condition;
    // For pulled metrics, there are two cases:
    // - to compute diffs, we need to process all the state changes
    // - for non-diffs metrics, we should ignore the data if the condition wasn't true. If we have a
    // state change from
    //     + True -> True: we should process the data, it might be a bucket boundary
    //     + True -> False: we als need to process the data.
    bool shouldSkipForPulledMetric = mIsPulled && !mUseDiff
            && mCondition != ConditionState::kTrue;
    if (shouldSkipForPushMetric || shouldSkipForPulledMetric) {
        VLOG("ValueMetric skip event because condition is false");
        return;
    }

    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    vector<Interval>& multiIntervals = mCurrentSlicedBucket[eventKey];
    if (multiIntervals.size() < mFieldMatchers.size()) {
        VLOG("Resizing number of intervals to %d", (int)mFieldMatchers.size());
        multiIntervals.resize(mFieldMatchers.size());
    }

    // We only use anomaly detection under certain cases.
    // N.B.: The anomaly detection cases were modified in order to fix an issue with value metrics
    // containing multiple values. We tried to retain all previous behaviour, but we are unsure the
    // previous behaviour was correct. At the time of the fix, anomaly detection had no owner.
    // Whoever next works on it should look into the cases where it is triggered in this function.
    // Discussion here: http://ag/6124370.
    bool useAnomalyDetection = true;

    for (int i = 0; i < (int)mFieldMatchers.size(); i++) {
        const Matcher& matcher = mFieldMatchers[i];
        Interval& interval = multiIntervals[i];
        interval.valueIndex = i;
        Value value;
        if (!getDoubleOrLong(event, matcher, value)) {
            VLOG("Failed to get value %d from event %s", i, event.ToString().c_str());
            StatsdStats::getInstance().noteBadValueType(mMetricId);
            return;
        }
        interval.seenNewData = true;

        if (mUseDiff) {
            if (!interval.hasBase) {
                if (mHasGlobalBase && mUseZeroDefaultBase) {
                    // The bucket has global base. This key does not.
                    // Optionally use zero as base.
                    interval.base = (value.type == LONG ? ZERO_LONG : ZERO_DOUBLE);
                    interval.hasBase = true;
                } else {
                    // no base. just update base and return.
                    interval.base = value;
                    interval.hasBase = true;
                    // If we're missing a base, do not use anomaly detection on incomplete data
                    useAnomalyDetection = false;
                    // Continue (instead of return) here in order to set interval.base and
                    // interval.hasBase for other intervals
                    continue;
                }
            }
            Value diff;
            switch (mValueDirection) {
                case ValueMetric::INCREASING:
                    if (value >= interval.base) {
                        diff = value - interval.base;
                    } else if (mUseAbsoluteValueOnReset) {
                        diff = value;
                    } else {
                        VLOG("Unexpected decreasing value");
                        StatsdStats::getInstance().notePullDataError(mPullTagId);
                        interval.base = value;
                        // If we've got bad data, do not use anomaly detection
                        useAnomalyDetection = false;
                        continue;
                    }
                    break;
                case ValueMetric::DECREASING:
                    if (interval.base >= value) {
                        diff = interval.base - value;
                    } else if (mUseAbsoluteValueOnReset) {
                        diff = value;
                    } else {
                        VLOG("Unexpected increasing value");
                        StatsdStats::getInstance().notePullDataError(mPullTagId);
                        interval.base = value;
                        // If we've got bad data, do not use anomaly detection
                        useAnomalyDetection = false;
                        continue;
                    }
                    break;
                case ValueMetric::ANY:
                    diff = value - interval.base;
                    break;
                default:
                    break;
            }
            interval.base = value;
            value = diff;
        }

        if (interval.hasValue) {
            switch (mAggregationType) {
                case ValueMetric::SUM:
                    // for AVG, we add up and take average when flushing the bucket
                case ValueMetric::AVG:
                    interval.value += value;
                    break;
                case ValueMetric::MIN:
                    interval.value = std::min(value, interval.value);
                    break;
                case ValueMetric::MAX:
                    interval.value = std::max(value, interval.value);
                    break;
                default:
                    break;
            }
        } else {
            interval.value = value;
            interval.hasValue = true;
        }
        interval.sampleSize += 1;
    }

    // Only trigger the tracker if all intervals are correct
    if (useAnomalyDetection) {
        // TODO: propgate proper values down stream when anomaly support doubles
        long wholeBucketVal = multiIntervals[0].value.long_value;
        auto prev = mCurrentFullBucket.find(eventKey);
        if (prev != mCurrentFullBucket.end()) {
            wholeBucketVal += prev->second;
        }
        for (auto& tracker : mAnomalyTrackers) {
            tracker->detectAndDeclareAnomaly(eventTimeNs, mCurrentBucketNum, mMetricId, eventKey,
                                             wholeBucketVal);
        }
    }
}

// For pulled metrics, we always need to make sure we do a pull before flushing the bucket
// if mCondition is true!
void ValueMetricProducer::flushIfNeededLocked(const int64_t& eventTimeNs) {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();
    if (eventTimeNs < currentBucketEndTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(currentBucketEndTimeNs));
        return;
    }
    int64_t numBucketsForward = calcBucketsForwardCount(eventTimeNs);
    int64_t nextBucketStartTimeNs = currentBucketEndTimeNs + (numBucketsForward - 1) * mBucketSizeNs;
    flushCurrentBucketLocked(eventTimeNs, nextBucketStartTimeNs);
}

int64_t ValueMetricProducer::calcBucketsForwardCount(const int64_t& eventTimeNs) const {
    int64_t currentBucketEndTimeNs = getCurrentBucketEndTimeNs();
    if (eventTimeNs < currentBucketEndTimeNs) {
        return 0;
    }
    return 1 + (eventTimeNs - currentBucketEndTimeNs) / mBucketSizeNs;
}

void ValueMetricProducer::flushCurrentBucketLocked(const int64_t& eventTimeNs,
                                                   const int64_t& nextBucketStartTimeNs) {
    if (mCondition == ConditionState::kUnknown) {
        StatsdStats::getInstance().noteBucketUnknownCondition(mMetricId);
    }

    int64_t numBucketsForward = calcBucketsForwardCount(eventTimeNs);
    if (numBucketsForward > 1) {
        VLOG("Skipping forward %lld buckets", (long long)numBucketsForward);
        StatsdStats::getInstance().noteSkippedForwardBuckets(mMetricId);
        // Something went wrong. Maybe the device was sleeping for a long time. It is better
        // to mark the current bucket as invalid. The last pull might have been successful through.
        invalidateCurrentBucketWithoutResetBase();
    }

    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    int64_t fullBucketEndTimeNs = getCurrentBucketEndTimeNs();
    int64_t bucketEndTime = eventTimeNs < fullBucketEndTimeNs ? eventTimeNs : fullBucketEndTimeNs;
    // Close the current bucket.
    int64_t conditionTrueDuration = mConditionTimer.newBucketStart(bucketEndTime);
    bool isBucketLargeEnough = bucketEndTime - mCurrentBucketStartTimeNs >= mMinBucketSizeNs;
    if (isBucketLargeEnough && !mCurrentBucketIsInvalid) {
        // The current bucket is large enough to keep.
        for (const auto& slice : mCurrentSlicedBucket) {
            ValueBucket bucket = buildPartialBucket(bucketEndTime, slice.second);
            bucket.mConditionTrueNs = conditionTrueDuration;
            // it will auto create new vector of ValuebucketInfo if the key is not found.
            if (bucket.valueIndex.size() > 0) {
                auto& bucketList = mPastBuckets[slice.first];
                bucketList.push_back(bucket);
            }
        }
    } else {
        mSkippedBuckets.emplace_back(mCurrentBucketStartTimeNs, bucketEndTime);
    }

    appendToFullBucket(eventTimeNs, fullBucketEndTimeNs);
    initCurrentSlicedBucket(nextBucketStartTimeNs);
    // Update the condition timer again, in case we skipped buckets.
    mConditionTimer.newBucketStart(nextBucketStartTimeNs);
    mCurrentBucketNum += numBucketsForward;
}

ValueBucket ValueMetricProducer::buildPartialBucket(int64_t bucketEndTime,
                                                    const std::vector<Interval>& intervals) {
    ValueBucket bucket;
    bucket.mBucketStartNs = mCurrentBucketStartTimeNs;
    bucket.mBucketEndNs = bucketEndTime;
    for (const auto& interval : intervals) {
        if (interval.hasValue) {
            // skip the output if the diff is zero
            if (mSkipZeroDiffOutput && mUseDiff && interval.value.isZero()) {
                continue;
            }
            bucket.valueIndex.push_back(interval.valueIndex);
            if (mAggregationType != ValueMetric::AVG) {
                bucket.values.push_back(interval.value);
            } else {
                double sum = interval.value.type == LONG ? (double)interval.value.long_value
                                                         : interval.value.double_value;
                bucket.values.push_back(Value((double)sum / interval.sampleSize));
            }
        }
    }
    return bucket;
}

void ValueMetricProducer::initCurrentSlicedBucket(int64_t nextBucketStartTimeNs) {
    StatsdStats::getInstance().noteBucketCount(mMetricId);
    // Cleanup data structure to aggregate values.
    for (auto it = mCurrentSlicedBucket.begin(); it != mCurrentSlicedBucket.end();) {
        bool obsolete = true;
        for (auto& interval : it->second) {
            interval.hasValue = false;
            interval.sampleSize = 0;
            if (interval.seenNewData) {
                obsolete = false;
            }
            interval.seenNewData = false;
        }

        if (obsolete) {
            it = mCurrentSlicedBucket.erase(it);
        } else {
            it++;
        }
    }

    mCurrentBucketIsInvalid = false;
    // If we do not have a global base when the condition is true,
    // we will have incomplete bucket for the next bucket.
    if (mUseDiff && !mHasGlobalBase && mCondition) {
        mCurrentBucketIsInvalid = false;
    }
    mCurrentBucketStartTimeNs = nextBucketStartTimeNs;
    VLOG("metric %lld: new bucket start time: %lld", (long long)mMetricId,
         (long long)mCurrentBucketStartTimeNs);
}

void ValueMetricProducer::appendToFullBucket(int64_t eventTimeNs, int64_t fullBucketEndTimeNs) {
    bool isFullBucketReached = eventTimeNs > fullBucketEndTimeNs;
    if (mCurrentBucketIsInvalid) {
        if (isFullBucketReached) {
            // If the bucket is invalid, we ignore the full bucket since it contains invalid data.
            mCurrentFullBucket.clear();
        }
        // Current bucket is invalid, we do not add it to the full bucket.
        return;
    }

    if (isFullBucketReached) {  // If full bucket, send to anomaly tracker.
        // Accumulate partial buckets with current value and then send to anomaly tracker.
        if (mCurrentFullBucket.size() > 0) {
            for (const auto& slice : mCurrentSlicedBucket) {
                if (hitFullBucketGuardRailLocked(slice.first)) {
                    continue;
                }
                // TODO: fix this when anomaly can accept double values
                auto& interval = slice.second[0];
                if (interval.hasValue) {
                    mCurrentFullBucket[slice.first] += interval.value.long_value;
                }
            }
            for (const auto& slice : mCurrentFullBucket) {
                for (auto& tracker : mAnomalyTrackers) {
                    if (tracker != nullptr) {
                        tracker->addPastBucket(slice.first, slice.second, mCurrentBucketNum);
                    }
                }
            }
            mCurrentFullBucket.clear();
        } else {
            // Skip aggregating the partial buckets since there's no previous partial bucket.
            for (const auto& slice : mCurrentSlicedBucket) {
                for (auto& tracker : mAnomalyTrackers) {
                    if (tracker != nullptr) {
                        // TODO: fix this when anomaly can accept double values
                        auto& interval = slice.second[0];
                        if (interval.hasValue) {
                            tracker->addPastBucket(slice.first, interval.value.long_value,
                                                   mCurrentBucketNum);
                        }
                    }
                }
            }
        }
    } else {
        // Accumulate partial bucket.
        for (const auto& slice : mCurrentSlicedBucket) {
            // TODO: fix this when anomaly can accept double values
            auto& interval = slice.second[0];
            if (interval.hasValue) {
                mCurrentFullBucket[slice.first] += interval.value.long_value;
            }
        }
    }
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
