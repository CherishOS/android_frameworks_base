// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/metrics/ValueMetricProducer.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <math.h>
#include <stdio.h>

#include <vector>

#include "metrics_test_helper.h"
#include "src/matchers/SimpleLogMatchingTracker.h"
#include "src/metrics/MetricProducer.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

using namespace testing;
using android::sp;
using std::make_shared;
using std::set;
using std::shared_ptr;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, 12345);
const int tagId = 1;
const int64_t metricId = 123;
const int64_t atomMatcherId = 678;
const int logEventMatcherIndex = 0;
const int64_t bucketStartTimeNs = 10000000000;
const int64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(ONE_MINUTE) * 1000000LL;
const int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
const int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
const int64_t bucket4StartTimeNs = bucketStartTimeNs + 3 * bucketSizeNs;
const int64_t bucket5StartTimeNs = bucketStartTimeNs + 4 * bucketSizeNs;
const int64_t bucket6StartTimeNs = bucketStartTimeNs + 5 * bucketSizeNs;
double epsilon = 0.001;

static void assertPastBucketValuesSingleKey(
        const std::unordered_map<MetricDimensionKey, std::vector<ValueBucket>>& mPastBuckets,
        const std::initializer_list<int>& expectedValuesList,
        const std::initializer_list<int64_t>& expectedDurationNsList) {
    std::vector<int> expectedValues(expectedValuesList);
    std::vector<int64_t> expectedDurationNs(expectedDurationNsList);
    ASSERT_EQ(expectedValues.size(), expectedDurationNs.size());
    if (expectedValues.size() == 0) {
        ASSERT_EQ(0, mPastBuckets.size());
        return;
    }

    ASSERT_EQ(1, mPastBuckets.size());
    ASSERT_EQ(expectedValues.size(), mPastBuckets.begin()->second.size());

    auto buckets = mPastBuckets.begin()->second;
    for (int i = 0; i < expectedValues.size(); i++) {
        EXPECT_EQ(expectedValues[i], buckets[i].values[0].long_value)
                << "Values differ at index " << i;
        EXPECT_EQ(expectedDurationNs[i], buckets[i].mConditionTrueNs)
                << "Condition duration value differ at index " << i;
    }
}

class ValueMetricProducerTestHelper {
public:
    static sp<ValueMetricProducer> createValueProducerNoConditions(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard, logEventMatcherIndex,
                eventMatcherWizard, tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithCondition(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, 1, wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                bucketStartTimeNs, bucketStartTimeNs, pullerManager);
        valueProducer->mCondition = ConditionState::kFalse;
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithNoInitialCondition(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, 1, wizard, logEventMatcherIndex, eventMatcherWizard, tagId,
                bucketStartTimeNs, bucketStartTimeNs, pullerManager);
        return valueProducer;
    }

    static sp<ValueMetricProducer> createValueProducerWithState(
            sp<MockStatsPullerManager>& pullerManager, ValueMetric& metric,
            vector<int32_t> slicedStateAtoms,
            unordered_map<int, unordered_map<int, int64_t>> stateGroupMap) {
        UidMap uidMap;
        SimpleAtomMatcher atomMatcher;
        atomMatcher.set_atom_id(tagId);
        sp<EventMatcherWizard> eventMatcherWizard =
                new EventMatcherWizard({new SimpleLogMatchingTracker(
                        atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
        sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
        EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
        EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
        sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
                kConfigKey, metric, -1 /* no condition */, wizard, logEventMatcherIndex,
                eventMatcherWizard, tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager, {},
                {}, slicedStateAtoms, stateGroupMap);
        return valueProducer;
    }

    static ValueMetric createMetric() {
        ValueMetric metric;
        metric.set_id(metricId);
        metric.set_bucket(ONE_MINUTE);
        metric.mutable_value_field()->set_field(tagId);
        metric.mutable_value_field()->add_child()->set_field(2);
        metric.set_max_pull_delay_sec(INT_MAX);
        return metric;
    }

    static ValueMetric createMetricWithCondition() {
        ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
        metric.set_condition(StringToId("SCREEN_ON"));
        return metric;
    }

    static ValueMetric createMetricWithState(string state) {
        ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
        metric.add_slice_by_state(StringToId(state));
        return metric;
    }
};

/*
 * Tests that the first bucket works correctly
 */
TEST(ValueMetricProducerTest, TestCalcPreviousBucketEndTime) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    int64_t startTimeBase = 11;
    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    // statsd started long ago.
    // The metric starts in the middle of the bucket
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1, startTimeBase,
                                      22, pullerManager);

    EXPECT_EQ(startTimeBase, valueProducer.calcPreviousBucketEndTime(60 * NS_PER_SEC + 10));
    EXPECT_EQ(startTimeBase, valueProducer.calcPreviousBucketEndTime(60 * NS_PER_SEC + 10));
    EXPECT_EQ(60 * NS_PER_SEC + startTimeBase,
              valueProducer.calcPreviousBucketEndTime(2 * 60 * NS_PER_SEC));
    EXPECT_EQ(2 * 60 * NS_PER_SEC + startTimeBase,
              valueProducer.calcPreviousBucketEndTime(3 * 60 * NS_PER_SEC));
}

/*
 * Tests that the first bucket works correctly
 */
TEST(ValueMetricProducerTest, TestFirstBucket) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    // statsd started long ago.
    // The metric starts in the middle of the bucket
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      logEventMatcherIndex, eventMatcherWizard, -1, 5,
                                      600 * NS_PER_SEC + NS_PER_SEC / 2, pullerManager);

    EXPECT_EQ(600500000000, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(10, valueProducer.mCurrentBucketNum);
    EXPECT_EQ(660000000005, valueProducer.getCurrentBucketEndTimeNs());
}

/*
 * Tests pulled atoms with no conditions
 */
TEST(ValueMetricProducerTest, TestPulledEventsNoCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 23));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(23, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(12, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(12, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(13, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(3UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(12, valueProducer->mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[1].mConditionTrueNs);
    EXPECT_EQ(13, valueProducer->mPastBuckets.begin()->second[2].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[2].mConditionTrueNs);
}

TEST(ValueMetricProducerTest, TestPartialBucketCreated) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            // Initialize bucket.
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 1));
                return true;
            }))
            // Partial bucket.
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 10, 5));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    // First bucket ends.
    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 10, 2));
    valueProducer->onDataPulled(allData, /** success */ true, bucket2StartTimeNs);

    // Partial buckets created in 2nd bucket.
    valueProducer->notifyAppUpgrade(bucket2StartTimeNs + 2, "com.foo", 10000, 1);

    // One full bucket and one partial bucket.
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    vector<ValueBucket> buckets = valueProducer->mPastBuckets.begin()->second;
    EXPECT_EQ(2UL, buckets.size());
    // Full bucket (2 - 1)
    EXPECT_EQ(1, buckets[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, buckets[0].mConditionTrueNs);
    // Full bucket (5 - 3)
    EXPECT_EQ(3, buckets[1].values[0].long_value);
    // partial bucket [bucket2StartTimeNs, bucket2StartTimeNs + 2]
    EXPECT_EQ(2, buckets[1].mConditionTrueNs);
}

/*
 * Tests pulled atoms with filtering
 */
TEST(ValueMetricProducerTest, TestPulledEventsWithFiltering) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    auto keyValue = atomMatcher.add_field_value_matcher();
    keyValue->set_field(1);
    keyValue->set_eq_int(3);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateTwoValueLogEvent(tagId, bucketStartTimeNs, 3, 3));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer = new ValueMetricProducer(
            kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard, logEventMatcherIndex,
            eventMatcherWizard, tagId, bucketStartTimeNs, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 3, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket3StartTimeNs + 1, 4, 23));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // No new data seen, so data has been cleared.
    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(8, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket4StartTimeNs + 1, 3, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    // the base was reset
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(8, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);
}

/*
 * Tests pulled atoms with no conditions and take absolute value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeAbsoluteValueOnReset) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_use_absolute_value_on_reset(true);

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(10, valueProducer->mPastBuckets.begin()->second.back().values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second.back().mConditionTrueNs);

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer->mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
    EXPECT_EQ(26, valueProducer->mPastBuckets.begin()->second[1].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[1].mConditionTrueNs);
}

/*
 * Tests pulled atoms with no conditions and take zero value after reset
 */
TEST(ValueMetricProducerTest, TestPulledEventsTakeZeroOnReset) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(false));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 11));

    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];

    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(11, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 10));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(10, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket4StartTimeNs + 1, 36));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(36, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(26, curInterval.value.long_value);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
    EXPECT_EQ(26, valueProducer->mPastBuckets.begin()->second[0].values[0].long_value);
    EXPECT_EQ(bucketSizeNs, valueProducer->mPastBuckets.begin()->second[0].mConditionTrueNs);
}

/*
 * Test pulled event with non sliced condition.
 */
TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 8, 100));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 130));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 180));
                return true;
            }));

    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval =
            valueProducer->mCurrentSlicedBucket.begin()->second[0];
    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    // startUpdated:false sum:0 start:100
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(100, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateTwoValueLogEvent(tagId, bucket2StartTimeNs + 1, 1, 110));
    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs - 8});

    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curBaseInfo.hasBase);
    EXPECT_EQ(110, curBaseInfo.base.long_value);
    EXPECT_EQ(false, curInterval.hasValue);
    EXPECT_EQ(10, curInterval.value.long_value);

    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs - 8});

    // has one slice
    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
    EXPECT_EQ(true, curInterval.hasValue);
    EXPECT_EQ(20, curInterval.value.long_value);
    EXPECT_EQ(false, curBaseInfo.hasBase);

    valueProducer->onConditionChanged(true, bucket3StartTimeNs + 1);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10, 20}, {bucketSizeNs - 8, 1});
}

TEST(ValueMetricProducerTest, TestPushedEventsWithUpgrade) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, -1, bucketStartTimeNs, bucketStartTimeNs,
                                      pullerManager);

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event1, tagId, bucketStartTimeNs + 10, 1, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event1);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucketStartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event2, tagId, bucketStartTimeNs + 59 * NS_PER_SEC, 1, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event2);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);

    // Next value should create a new bucket.
    LogEvent event3(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event3, tagId, bucketStartTimeNs + 65 * NS_PER_SEC, 1, 10);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, event3);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs + bucketSizeNs, valueProducer.mCurrentBucketStartTimeNs);
}

TEST(ValueMetricProducerTest, TestPulledValueWithUpgrade) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Return(true))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 149, 120));
                return true;
            }));
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 100));

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket2StartTimeNs + 150, valueProducer.mCurrentBucketStartTimeNs);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {20}, {150});

    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket3StartTimeNs + 1, 150));
    valueProducer.onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
    EXPECT_EQ(2UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket3StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
    EXPECT_EQ(20L,
              valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].values[0].long_value);
    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {20, 30},
                                    {150, bucketSizeNs - 150});
}

TEST(ValueMetricProducerTest, TestPulledWithAppUpgradeDisabled) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
    metric.set_split_bucket_for_app_upgrade(false);

    UidMap uidMap;
    SimpleAtomMatcher atomMatcher;
    atomMatcher.set_atom_id(tagId);
    sp<EventMatcherWizard> eventMatcherWizard =
            new EventMatcherWizard({new SimpleLogMatchingTracker(
                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));
    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
                                      eventMatcherWizard, tagId, bucketStartTimeNs,
                                      bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    allData.push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs + 1, 100));

    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());

    valueProducer.notifyAppUpgrade(bucket2StartTimeNs + 150, "ANY.APP", 1, 1);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucket2StartTimeNs, valueProducer.mCurrentBucketStartTimeNs);
}

TEST(ValueMetricProducerTest, TestPulledValueWithUpgradeWhileConditionFalse) {
    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();

    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucketStartTimeNs + 1, 100));
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                data->push_back(CreateRepeatedValueLogEvent(tagId, bucket2StartTimeNs - 100, 120));
                return true;
            }));
    sp<ValueMetricProducer> valueProducer =
            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager, metric);

    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);

    valueProducer->onConditionChanged(false, bucket2StartTimeNs - 100);
    EXPECT_FALSE(valueProducer->mCondition);

    valueProducer->notifyAppUpgrade(bucket2StartTimeNs - 50, "ANY.APP", 1, 1);
    // Expect one full buckets already done and starting a partial bucket.
    EXPECT_EQ(bucket2StartTimeNs - 50, valueProducer->mCurrentBucketStartTimeNs);
    EXPECT_EQ(1UL, valueProducer->mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY].size());
    EXPECT_EQ(bucketStartTimeNs,
              valueProducer->mPastBuckets[DEFAULT_METRIC_DIMENSION_KEY][0].mBucketStartNs);
    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20},
                                    {(bucket2StartTimeNs - 100) - (bucketStartTimeNs + 1)});
    EXPECT_FALSE(valueProducer->mCondition);
}

// TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(20);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//    valueProducer.mCurrentSlicedBucket.begin()->second[0]; ValueMetricProducer::BaseInfo
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(10,
//    curInterval.value.long_value); EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(30, curInterval.value.long_value);
//
//    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
//    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {30}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestPushedEventsWithCondition) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//    valueProducer.mCondition = ConditionState::kFalse;
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has 1 slice
//    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
//
//    valueProducer.onConditionChangedLocked(true, bucketStartTimeNs + 15);
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(20);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(20, curInterval.value.long_value);
//
//    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 30);
//    event3->write(1);
//    event3->write(30);
//    event3->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(50, curInterval.value.long_value);
//
//    valueProducer.onConditionChangedLocked(false, bucketStartTimeNs + 35);
//    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 40);
//    event4->write(1);
//    event4->write(40);
//    event4->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(50, curInterval.value.long_value);
//
//    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
//    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {50}, {20});
//}
//
// TEST(ValueMetricProducerTest, TestAnomalyDetection) {
//    sp<AlarmMonitor> alarmMonitor;
//    Alert alert;
//    alert.set_id(101);
//    alert.set_metric_id(metricId);
//    alert.set_trigger_if_sum_gt(130);
//    alert.set_num_buckets(2);
//    const int32_t refPeriodSec = 3;
//    alert.set_refractory_period_secs(refPeriodSec);
//
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
//                                      logEventMatcherIndex, eventMatcherWizard, -1 /*not pulled*/,
//                                      bucketStartTimeNs, bucketStartTimeNs, pullerManager);
//
//    sp<AnomalyTracker> anomalyTracker = valueProducer.addAnomalyTracker(alert, alarmMonitor);
//
//
//    shared_ptr<LogEvent> event1
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1 * NS_PER_SEC);
//    event1->write(161);
//    event1->write(10); // value of interest
//    event1->init();
//    shared_ptr<LogEvent> event2
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 + NS_PER_SEC);
//    event2->write(162);
//    event2->write(20); // value of interest
//    event2->init();
//    shared_ptr<LogEvent> event3
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 1 * NS_PER_SEC);
//    event3->write(163);
//    event3->write(130); // value of interest
//    event3->init();
//    shared_ptr<LogEvent> event4
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 1 * NS_PER_SEC);
//    event4->write(35);
//    event4->write(1); // value of interest
//    event4->init();
//    shared_ptr<LogEvent> event5
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 2 * NS_PER_SEC);
//    event5->write(45);
//    event5->write(150); // value of interest
//    event5->init();
//    shared_ptr<LogEvent> event6
//            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 10 *
//            NS_PER_SEC);
//    event6->write(25);
//    event6->write(160); // value of interest
//    event6->init();
//
//    // Two events in bucket #0.
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//    // Value sum == 30 <= 130.
//    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);
//
//    // One event in bucket #2. No alarm as bucket #0 is trashed out.
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
//    // Value sum == 130 <= 130.
//    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY), 0U);
//
//    // Three events in bucket #3.
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
//    // Anomaly at event 4 since Value sum == 131 > 130!
//    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
//            std::ceil(1.0 * event4->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event5);
//    // Event 5 is within 3 sec refractory period. Thus last alarm timestamp is still event4.
//    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
//            std::ceil(1.0 * event4->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event6);
//    // Anomaly at event 6 since Value sum == 160 > 130 and after refractory period.
//    EXPECT_EQ(anomalyTracker->getRefractoryPeriodEndsSec(DEFAULT_METRIC_DIMENSION_KEY),
//            std::ceil(1.0 * event6->GetElapsedTimestampNs() / NS_PER_SEC + refPeriodSec));
//}
//
//// Test value metric no condition, the pull on bucket boundary come in time and too late
// TEST(ValueMetricProducerTest, TestBucketBoundaryNoCondition) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _)).WillOnce(Return(true));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    vector<shared_ptr<LogEvent>> allData;
//    // pull 1
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event->write(tagId);
//    event->write(11);
//    event->init();
//    allData.push_back(event);
//
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0];
//
//    // startUpdated:true sum:0 start:11
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(11, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//
//    // pull 2 at correct time
//    allData.clear();
//    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
//    event->write(tagId);
//    event->write(23);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    // tartUpdated:false sum:12
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(23, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {12}, {bucketSizeNs});
//
//    // pull 3 come late.
//    // The previous bucket gets closed with error. (Has start value 23, no ending)
//    // Another bucket gets closed with error. (No start, but ending with 36)
//    // The new bucket is back to normal.
//    allData.clear();
//    event = make_shared<LogEvent>(tagId, bucket6StartTimeNs + 1);
//    event->write(tagId);
//    event->write(36);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket6StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    // startUpdated:false sum:12
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(36, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {12}, {bucketSizeNs});
//}
//
///*
// * Test pulled event with non sliced condition. The pull on boundary come late because the alarm
// * was delivered late.
// */
// TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // condition becomes true
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // condition becomes false
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//                event->write(tagId);
//                event->write(120);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(100, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//
//    // pull on bucket boundary come late, condition change happens before it
//    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8});
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//
//    // Now the alarm is delivered.
//    // since the condition turned to off before this pull finish, it has no effect
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 110));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8});
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//}
//
///*
// * Test pulled event with non sliced condition. The pull on boundary come late, after the
// condition
// * change to false, and then true again. This is due to alarm delivered late.
// */
// TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition2) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // condition becomes true
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // condition becomes false
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//                event->write(tagId);
//                event->write(120);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // condition becomes true again
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs +
//                25); event->write(tagId); event->write(130); event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0];
//    // startUpdated:false sum:0 start:100
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(100, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//
//    // pull on bucket boundary come late, condition change happens before it
//    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 1);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8});
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    // condition changed to true again, before the pull alarm is delivered
//    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 25);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8});
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(130, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    // Now the alarm is delivered, but it is considered late, the data will be used
//    // for the new bucket since it was just pulled.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 50, 140));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 50);
//
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(140, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(10, curInterval.value.long_value);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {bucketSizeNs - 8});
//
//    allData.clear();
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket3StartTimeNs, 160));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20, 30},
//                                    {bucketSizeNs - 8, bucketSizeNs - 24});
//}
//
// TEST(ValueMetricProducerTest, TestPushedAggregateMin) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_aggregation_type(ValueMetric::MIN);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(20);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(10, curInterval.value.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(10, curInterval.value.long_value);
//
//    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
//    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {10}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestPushedAggregateMax) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_aggregation_type(ValueMetric::MAX);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(20);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(10, curInterval.value.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(20, curInterval.value.long_value);
//
//    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
//    /* EXPECT_EQ(1UL, valueProducer.mPastBuckets.size()); */
//    /* EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size()); */
//    /* EXPECT_EQ(20, valueProducer.mPastBuckets.begin()->second.back().values[0].long_value); */
//}
//
// TEST(ValueMetricProducerTest, TestPushedAggregateAvg) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_aggregation_type(ValueMetric::AVG);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(15);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval;
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(10, curInterval.value.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(1, curInterval.sampleSize);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(25, curInterval.value.long_value);
//    EXPECT_EQ(2, curInterval.sampleSize);
//
//    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
//
//    EXPECT_TRUE(std::abs(valueProducer.mPastBuckets.begin()->second.back().values[0].double_value
//    -
//                         12.5) < epsilon);
//}
//
// TEST(ValueMetricProducerTest, TestPushedAggregateSum) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_aggregation_type(ValueMetric::SUM);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//    event2->write(1);
//    event2->write(15);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(10, curInterval.value.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(25, curInterval.value.long_value);
//
//    valueProducer.flushIfNeededLocked(bucket2StartTimeNs);
//    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {25}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestSkipZeroDiffOutput) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_aggregation_type(ValueMetric::MIN);
//    metric.set_use_diff(true);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
//    event2->write(1);
//    event2->write(15);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(10, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(5, curInterval.value.long_value);
//
//    // no change in data.
//    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
//    event3->write(1);
//    event3->write(15);
//    event3->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(15, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 15);
//    event4->write(1);
//    event4->write(15);
//    event4->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(15, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
//    assertPastBucketValuesSingleKey(valueProducer.mPastBuckets, {5}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestSkipZeroDiffOutputMultiValue) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_value_field()->add_child()->set_field(3);
//    metric.set_aggregation_type(ValueMetric::MIN);
//    metric.set_use_diff(true);
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, -1, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//    event1->write(1);
//    event1->write(10);
//    event1->write(20);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
//    event2->write(1);
//    event2->write(15);
//    event2->write(22);
//    event2->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(10, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(20, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(5, curInterval.value.long_value);
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(2, curInterval.value.long_value);
//
//    // no change in first value field
//    shared_ptr<LogEvent> event3 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
//    event3->write(1);
//    event3->write(15);
//    event3->write(25);
//    event3->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(15, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(25, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    shared_ptr<LogEvent> event4 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 15);
//    event4->write(1);
//    event4->write(15);
//    event4->write(29);
//    event4->init();
//    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
//    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(15, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second[1];
//    curBaseInfo = valueProducer.mCurrentBaseInfo.begin()->second[1];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(29, curBaseInfo.base.long_value);
//    EXPECT_EQ(true, curInterval.hasValue);
//
//    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
//
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
//    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
//    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second[0].values.size());
//    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second[1].values.size());
//
//    EXPECT_EQ(bucketSizeNs, valueProducer.mPastBuckets.begin()->second[0].mConditionTrueNs);
//    EXPECT_EQ(5, valueProducer.mPastBuckets.begin()->second[0].values[0].long_value);
//    EXPECT_EQ(0, valueProducer.mPastBuckets.begin()->second[0].valueIndex[0]);
//    EXPECT_EQ(2, valueProducer.mPastBuckets.begin()->second[0].values[1].long_value);
//    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[0].valueIndex[1]);
//
//    EXPECT_EQ(bucketSizeNs, valueProducer.mPastBuckets.begin()->second[1].mConditionTrueNs);
//    EXPECT_EQ(3, valueProducer.mPastBuckets.begin()->second[1].values[0].long_value);
//    EXPECT_EQ(1, valueProducer.mPastBuckets.begin()->second[1].valueIndex[0]);
//}
//
///*
// * Tests zero default base.
// */
// TEST(ValueMetricProducerTest, TestUseZeroDefaultBase) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//    metric.set_use_zero_default_base(true);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(1);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    auto iter = valueProducer->mCurrentSlicedBucket.begin();
//    auto& interval1 = iter->second[0];
//    auto iterBase = valueProducer->mCurrentBaseInfo.begin();
//    auto& baseInfo1 = iterBase->second[0];
//    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(3, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    vector<shared_ptr<LogEvent>> allData;
//
//    allData.clear();
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event1->write(2);
//    event1->write(4);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(11);
//    event2->init();
//    allData.push_back(event1);
//    allData.push_back(event2);
//
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(11, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(8, interval1.value.long_value);
//
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    for (; it != valueProducer->mCurrentSlicedBucket.end(); it++) {
//        if (it != iter) {
//            break;
//        }
//    }
//    auto itBase = valueProducer->mCurrentBaseInfo.begin();
//    for (; itBase != valueProducer->mCurrentBaseInfo.end(); it++) {
//        if (itBase != iterBase) {
//            break;
//        }
//    }
//    EXPECT_TRUE(it != iter);
//    EXPECT_TRUE(itBase != iterBase);
//    auto& interval2 = it->second[0];
//    auto& baseInfo2 = itBase->second[0];
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(4, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_EQ(4, interval2.value.long_value);
//
//    EXPECT_EQ(2UL, valueProducer->mPastBuckets.size());
//    auto iterator = valueProducer->mPastBuckets.begin();
//    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
//    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
//    iterator++;
//    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
//    EXPECT_EQ(4, iterator->second[0].values[0].long_value);
//}
//
///*
// * Tests using zero default base with failed pull.
// */
// TEST(ValueMetricProducerTest, TestUseZeroDefaultBaseWithPullFailures) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//    metric.set_use_zero_default_base(true);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(1);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    auto& interval1 = it->second[0];
//    auto& baseInfo1 =
//            valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat())->second[0];
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(3, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    vector<shared_ptr<LogEvent>> allData;
//
//    allData.clear();
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event1->write(2);
//    event1->write(4);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(11);
//    event2->init();
//    allData.push_back(event1);
//    allData.push_back(event2);
//
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(11, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(8, interval1.value.long_value);
//
//    auto it2 = valueProducer->mCurrentSlicedBucket.begin();
//    for (; it2 != valueProducer->mCurrentSlicedBucket.end(); it2++) {
//        if (it2 != it) {
//            break;
//        }
//    }
//    // auto itBase = valueProducer->mCurrentBaseInfo.begin();
//    // for (; itBase != valueProducer->mCurrentBaseInfo.end(); it++) {
//    //     if (itBase != iterBase) {
//    //         break;
//    //     }
//    // }
//    EXPECT_TRUE(it2 != it);
//    // EXPECT_TRUE(itBase != iterBase);
//    auto& interval2 = it2->second[0];
//    auto& baseInfo2 =
//            valueProducer->mCurrentBaseInfo.find(it2->first.getDimensionKeyInWhat())->second[0];
//    EXPECT_EQ(2, it2->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(4, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_EQ(4, interval2.value.long_value);
//    EXPECT_EQ(2UL, valueProducer->mPastBuckets.size());
//
//    // next pull somehow did not happen, skip to end of bucket 3
//    allData.clear();
//    event1 = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
//    event1->write(2);
//    event1->write(5);
//    event1->init();
//    allData.push_back(event1);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
//
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(5, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//    EXPECT_EQ(2UL, valueProducer->mPastBuckets.size());
//
//    allData.clear();
//    event1 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
//    event1->write(2);
//    event1->write(13);
//    event1->init();
//    allData.push_back(event1);
//    event2 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
//    event2->write(1);
//    event2->write(5);
//    event2->init();
//    allData.push_back(event2);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);
//
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    it2 = std::next(valueProducer->mCurrentSlicedBucket.begin());
//    interval1 = it->second[0];
//    interval2 = it2->second[0];
//    baseInfo1 =
//    valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat())->second[0]; baseInfo2
//    = valueProducer->mCurrentBaseInfo.find(it2->first.getDimensionKeyInWhat())->second[0];
//
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(5, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(5, interval1.value.long_value);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(13, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_EQ(8, interval2.value.long_value);
//
//    EXPECT_EQ(2UL, valueProducer->mPastBuckets.size());
//}
//
///*
// * Tests trim unused dimension key if no new data is seen in an entire bucket.
// */
// TEST(ValueMetricProducerTest, TestTrimUnusedDimensionKey) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(1);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    auto iter = valueProducer->mCurrentSlicedBucket.begin();
//    auto& interval1 = iter->second[0];
//    auto iterBase = valueProducer->mCurrentBaseInfo.begin();
//    auto& baseInfo1 = iterBase->second[0];
//    EXPECT_EQ(1, iter->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(3, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    vector<shared_ptr<LogEvent>> allData;
//
//    allData.clear();
//    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event1->write(2);
//    event1->write(4);
//    event1->init();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(11);
//    event2->init();
//    allData.push_back(event1);
//    allData.push_back(event2);
//
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(true, baseInfo1.hasBase);
//    EXPECT_EQ(11, baseInfo1.base.long_value);
//    EXPECT_EQ(false, interval1.hasValue);
//    EXPECT_EQ(8, interval1.value.long_value);
//    EXPECT_FALSE(interval1.seenNewData);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {8}, {bucketSizeNs});
//
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    for (; it != valueProducer->mCurrentSlicedBucket.end(); it++) {
//        if (it != iter) {
//            break;
//        }
//    }
//    auto itBase = valueProducer->mCurrentBaseInfo.begin();
//    for (; itBase != valueProducer->mCurrentBaseInfo.end(); it++) {
//        if (itBase != iterBase) {
//            break;
//        }
//    }
//    EXPECT_TRUE(it != iter);
//    EXPECT_TRUE(itBase != iterBase);
//    auto& interval2 = it->second[0];
//    auto& baseInfo2 = itBase->second[0];
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(4, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_FALSE(interval2.seenNewData);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {8}, {bucketSizeNs});
//
//    // next pull somehow did not happen, skip to end of bucket 3
//    allData.clear();
//    event1 = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
//    event1->write(2);
//    event1->write(5);
//    event1->init();
//    allData.push_back(event1);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket4StartTimeNs);
//    // Only one interval left. One was trimmed.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    interval2 = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    baseInfo2 = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(5, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_FALSE(interval2.seenNewData);
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {8}, {bucketSizeNs});
//
//    allData.clear();
//    event1 = make_shared<LogEvent>(tagId, bucket5StartTimeNs + 1);
//    event1->write(2);
//    event1->write(14);
//    event1->init();
//    allData.push_back(event1);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket5StartTimeNs);
//
//    interval2 = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    baseInfo2 = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, baseInfo2.hasBase);
//    EXPECT_EQ(14, baseInfo2.base.long_value);
//    EXPECT_EQ(false, interval2.hasValue);
//    EXPECT_FALSE(interval2.seenNewData);
//    ASSERT_EQ(2UL, valueProducer->mPastBuckets.size());
//    auto iterator = valueProducer->mPastBuckets.begin();
//    EXPECT_EQ(9, iterator->second[0].values[0].long_value);
//    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
//    iterator++;
//    EXPECT_EQ(8, iterator->second[0].values[0].long_value);
//    EXPECT_EQ(bucketSizeNs, iterator->second[0].mConditionTrueNs);
//}
//
// TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange_EndOfBucket) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    // Used by onConditionChanged.
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo& curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(100, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    vector<shared_ptr<LogEvent>> allData;
//    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
//}
//
// TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            .WillOnce(Return(false));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo& curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(100, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 20);
//
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
//}
//
// TEST(ValueMetricProducerTest, TestResetBaseOnPullFailBeforeConditionChange) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(50);
//                event->init();
//                data->push_back(event);
//                return false;
//            }))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Don't directly set mCondition; the real code never does that. Go through regular code path
//    // to avoid unexpected behaviors.
//    // valueProducer->mCondition = ConditionState::kTrue;
//    valueProducer->onConditionChanged(true, bucketStartTimeNs);
//
//    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 1);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
//}
//
// TEST(ValueMetricProducerTest, TestResetBaseOnPullDelayExceeded) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_condition(StringToId("SCREEN_ON"));
//    metric.set_max_pull_delay_sec(0);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//                event->write(tagId);
//                event->write(120);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    // Max delay is set to 0 so pull will exceed max delay.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);
//    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//}
//
// TEST(ValueMetricProducerTest, TestResetBaseOnPullTooLate) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
//    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, tagId, bucket2StartTimeNs,
//                                      bucket2StartTimeNs, pullerManager);
//    valueProducer.mCondition = ConditionState::kFalse;
//
//    // Event should be skipped since it is from previous bucket.
//    // Pull should not be called.
//    valueProducer.onConditionChanged(true, bucketStartTimeNs);
//    EXPECT_EQ(0UL, valueProducer.mCurrentSlicedBucket.size());
//}
//
// TEST(ValueMetricProducerTest, TestBaseSetOnConditionChange) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//                event->write(tagId);
//                event->write(100);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->mCondition = ConditionState::kFalse;
//    valueProducer->mHasGlobalBase = false;
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 1);
//    valueProducer->mHasGlobalBase = true;
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(100, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//}
//
///*
// * Tests that a bucket is marked invalid when a condition change pull fails.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenOneConditionFailed) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Return(false))
//            // Second onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(130);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->mCondition = ConditionState::kTrue;
//
//    // Bucket start.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//    event->write(1);
//    event->write(110);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);
//
//    // This will fail and should invalidate the whole bucket since we do not have all the data
//    // needed to compute the metric value when the screen was on.
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);
//
//    // Bucket end.
//    allData.clear();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(140);
//    event2->init();
//    allData.push_back(event2);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);
//
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    // Contains base from last pull which was successful.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(140, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 10, false /* include partial bucket */, true,
//                                FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
//}
//
///*
// * Tests that a bucket is marked invalid when the guardrail is hit.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenGuardRailHit) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//    metric.set_condition(StringToId("SCREEN_ON"));
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                for (int i = 0; i < 2000; i++) {
//                    shared_ptr<LogEvent> event =
//                            make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//                    event->write(i);
//                    event->write(i);
//                    event->init();
//                    data->push_back(event);
//                }
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 2);
//    EXPECT_EQ(true, valueProducer->mCurrentBucketIsInvalid);
//    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(0UL, valueProducer->mSkippedBuckets.size());
//
//    // Bucket 2 start.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event->write(1);
//    event->write(10);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // First bucket added to mSkippedBuckets after flush.
//    EXPECT_EQ(1UL, valueProducer->mSkippedBuckets.size());
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
//                                true, FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::DIMENSION_GUARDRAIL_REACHED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
//}
//
///*
// * Tests that a bucket is marked invalid when the bucket's initial pull fails.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenInitialPullFailed) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(120);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Second onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(130);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->mCondition = ConditionState::kTrue;
//
//    // Bucket start.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//    event->write(1);
//    event->write(110);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ false, bucketStartTimeNs);
//
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);
//
//    // Bucket end.
//    allData.clear();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(140);
//    event2->init();
//    allData.push_back(event2);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);
//
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    // Contains base from last pull which was successful.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(140, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
//                                true, FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 2), dropEvent.drop_time_millis());
//}
//
///*
// * Tests that a bucket is marked invalid when the bucket's final pull fails
// * (i.e. failed pull on bucket boundary).
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenLastPullFailed) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(120);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Second onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 8);
//                event->write(tagId);
//                event->write(130);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->mCondition = ConditionState::kTrue;
//
//    // Bucket start.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//    event->write(1);
//    event->write(110);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs);
//
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 2);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 3);
//
//    // Bucket end.
//    allData.clear();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event2->write(1);
//    event2->write(140);
//    event2->init();
//    allData.push_back(event2);
//    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);
//
//    valueProducer->flushIfNeededLocked(bucket2StartTimeNs + 1);
//
//    EXPECT_EQ(0UL, valueProducer->mPastBuckets.size());
//    // Last pull failed so base has been reset.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 10000, false /* include recent buckets */,
//                                true, FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs), dropEvent.drop_time_millis());
//}
//
// TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onDataPulled) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Start bucket.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    // Bucket 2 start.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event->write(tagId);
//    event->write(110);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
//
//    // Bucket 3 empty.
//    allData.clear();
//    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
//    event2->init();
//    allData.push_back(event2);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket3StartTimeNs);
//    // Data has been trimmed.
//    EXPECT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
//}
//
// TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onConditionChanged) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    // Empty pull.
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 10);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(false, valueProducer->mHasGlobalBase);
//}
//
// TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onBucketBoundary) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(1);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(2);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(5);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 11);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 12);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval& curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    // End of bucket
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    // Data is empty, base should be reset.
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(5, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//
//    EXPECT_EQ(1UL, valueProducer->mPastBuckets.size());
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {1}, {bucketSizeNs - 12 + 1});
//}
//
// TEST(ValueMetricProducerTest, TestPartialResetOnBucketBoundaries) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//    metric.set_condition(StringToId("SCREEN_ON"));
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First onConditionChanged
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(1);
//                event->write(1);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//
//    // End of bucket
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event->write(2);
//    event->write(2);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // Key 1 should be reset since in not present in the most pull.
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    auto iterator = valueProducer->mCurrentSlicedBucket.begin();
//    auto baseInfoIter = valueProducer->mCurrentBaseInfo.begin();
//    EXPECT_EQ(true, baseInfoIter->second[0].hasBase);
//    EXPECT_EQ(2, baseInfoIter->second[0].base.long_value);
//    EXPECT_EQ(false, iterator->second[0].hasValue);
//    iterator++;
//    baseInfoIter++;
//    EXPECT_EQ(false, baseInfoIter->second[0].hasBase);
//    EXPECT_EQ(1, baseInfoIter->second[0].base.long_value);
//    EXPECT_EQ(false, iterator->second[0].hasValue);
//
//    EXPECT_EQ(true, valueProducer->mHasGlobalBase);
//}
//
// TEST(ValueMetricProducerTest, TestFullBucketResetWhenLastBucketInvalid) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initialization.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 1));
//                return true;
//            }))
//            // notifyAppUpgrade.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(
//                        bucketStartTimeNs + bucketSizeNs / 2, 10));
//                return true;
//            }));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//    ASSERT_EQ(0UL, valueProducer->mCurrentFullBucket.size());
//
//    valueProducer->notifyAppUpgrade(bucketStartTimeNs + bucketSizeNs / 2, "com.foo", 10000, 1);
//    ASSERT_EQ(1UL, valueProducer->mCurrentFullBucket.size());
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket3StartTimeNs + 1, 4));
//    valueProducer->onDataPulled(allData, /** fails */ false, bucket3StartTimeNs + 1);
//    ASSERT_EQ(0UL, valueProducer->mCurrentFullBucket.size());
//}
//
// TEST(ValueMetricProducerTest, TestBucketBoundariesOnConditionChange) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Second onConditionChanged.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 10, 5));
//                return true;
//            }))
//            // Third onConditionChanged.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucket3StartTimeNs + 10, 7));
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kUnknown;
//
//    valueProducer->onConditionChanged(false, bucketStartTimeNs);
//    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//
//    // End of first bucket
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 1, 4));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 1);
//    ASSERT_EQ(0UL, valueProducer->mCurrentSlicedBucket.size());
//
//    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);
//    ASSERT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    auto curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    auto curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curBaseInfo.hasBase);
//    EXPECT_EQ(5, curBaseInfo.base.long_value);
//    EXPECT_EQ(false, curInterval.hasValue);
//
//    valueProducer->onConditionChanged(false, bucket3StartTimeNs + 10);
//
//    // Bucket should have been completed.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {2}, {bucketSizeNs - 10});
//}
//
// TEST(ValueMetricProducerTest, TestLateOnDataPulledWithoutDiff) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 30, 10));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + 30);
//
//    allData.clear();
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs, 20));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // Bucket should have been completed.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {30}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestLateOnDataPulledWithDiff) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initialization.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 1));
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 30, 10));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucketStartTimeNs + 30);
//
//    allData.clear();
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs, 20));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // Bucket should have been completed.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {19}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestBucketBoundariesOnAppUpgrade) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initialization.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 1));
//                return true;
//            }))
//            // notifyAppUpgrade.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 2, 10));
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    valueProducer->notifyAppUpgrade(bucket2StartTimeNs + 2, "com.foo", 10000, 1);
//
//    // Bucket should have been completed.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {9}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestDataIsNotUpdatedWhenNoConditionChanged) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First on condition changed.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 1));
//                return true;
//            }))
//            // Second on condition changed.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 3));
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 10);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 10);
//
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    auto curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    auto curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(2, curInterval.value.long_value);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 1, 10));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 1);
//
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {2}, {2});
//}
//
//// TODO: b/145705635 fix or delete this test
// TEST(ValueMetricProducerTest, TestBucketInvalidIfGlobalBaseIsNotSet) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First condition change.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs, 1));
//                return true;
//            }))
//            // 2nd condition change.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs,
//                1)); return true;
//            }))
//            // 3rd condition change.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs,
//                1)); return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 3, 10));
//    valueProducer->onDataPulled(allData, /** succeed */ false, bucketStartTimeNs + 3);
//
//    allData.clear();
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs, 20));
//    valueProducer->onDataPulled(allData, /** succeed */ false, bucket2StartTimeNs);
//
//    valueProducer->onConditionChanged(false, bucket2StartTimeNs + 8);
//    valueProducer->onConditionChanged(true, bucket2StartTimeNs + 10);
//
//    allData.clear();
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket3StartTimeNs, 30));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // There was not global base available so all buckets are invalid.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {});
//}
//
// TEST(ValueMetricProducerTest, TestPullNeededFastDump) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
//    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
//
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initial pull.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(1);
//                event->write(1);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, tagId, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer.onDumpReport(bucketStartTimeNs + 10, true /* include recent buckets */, true,
//                               FAST, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    // Bucket is invalid since we did not pull when dump report was called.
//    EXPECT_EQ(0, report.value_metrics().data_size());
//}
//
// TEST(ValueMetricProducerTest, TestFastDumpWithoutCurrentBucket) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
//    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
//
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initial pull.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(1);
//                event->write(1);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, tagId, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.clear();
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
//    event->write(tagId);
//    event->write(2);
//    event->write(2);
//    event->init();
//    allData.push_back(event);
//    valueProducer.onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer.onDumpReport(bucket4StartTimeNs, false /* include recent buckets */, true, FAST,
//                               &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    // Previous bucket is part of the report.
//    EXPECT_EQ(1, report.value_metrics().data_size());
//    EXPECT_EQ(0, report.value_metrics().data(0).bucket_info(0).bucket_num());
//}
//
// TEST(ValueMetricProducerTest, TestPullNeededNoTimeConstraints) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//
//    UidMap uidMap;
//    SimpleAtomMatcher atomMatcher;
//    atomMatcher.set_atom_id(tagId);
//    sp<EventMatcherWizard> eventMatcherWizard =
//            new EventMatcherWizard({new SimpleLogMatchingTracker(
//                    atomMatcherId, logEventMatcherIndex, atomMatcher, uidMap)});
//    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _, _)).WillOnce(Return());
//    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());
//
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Initial pull.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(tagId);
//                event->write(1);
//                event->write(1);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write(tagId);
//                event->write(3);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, logEventMatcherIndex,
//                                      eventMatcherWizard, tagId, bucketStartTimeNs,
//                                      bucketStartTimeNs, pullerManager);
//
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer.onDumpReport(bucketStartTimeNs + 10, true /* include recent buckets */, true,
//                               NO_TIME_CONSTRAINTS, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_EQ(1, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().data(0).bucket_info_size());
//    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
//}
//
// TEST(ValueMetricProducerTest, TestPulledData_noDiff_withoutCondition) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetric();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerNoConditions(pullerManager, metric);
//
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 10));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs + 30);
//
//    // Bucket should have been completed.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {10}, {bucketSizeNs});
//}
//
// TEST(ValueMetricProducerTest, TestPulledData_noDiff_withMultipleConditionChanges) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // condition becomes true
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 30, 10));
//                return true;
//            }))
//            // condition becomes false
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 50, 20));
//                return true;
//            }));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 50);
//    // has one slice
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    ValueMetricProducer::Interval curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(true, curInterval.hasValue);
//    EXPECT_EQ(20, curInterval.value.long_value);
//
//    // Now the alarm is delivered. Condition is off though.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 110));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {20}, {50 - 8});
//    curInterval = valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    curBaseInfo = valueProducer->mCurrentBaseInfo.begin()->second[0];
//    EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//}
//
// TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryTrue) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // condition becomes true
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 30, 10));
//                return true;
//            }));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//
//    // Now the alarm is delivered. Condition is off though.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 30));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {30}, {bucketSizeNs - 8});
//    ValueMetricProducer::Interval curInterval =
//            valueProducer->mCurrentSlicedBucket.begin()->second[0];
//    ValueMetricProducer::BaseInfo curBaseInfo =
//    valueProducer->mCurrentBaseInfo.begin()->second[0]; EXPECT_EQ(false, curBaseInfo.hasBase);
//    EXPECT_EQ(false, curInterval.hasValue);
//}
//
// TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryFalse) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    // Now the alarm is delivered. Condition is off though.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 30));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // Condition was always false.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {});
//}
//
// TEST(ValueMetricProducerTest, TestPulledData_noDiff_withFailure) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    metric.set_use_diff(false);
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // condition becomes true
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                data->push_back(
//                        ValueMetricProducerTestHelper::createEvent(bucketStartTimeNs + 30, 10));
//                return true;
//            }))
//            .WillOnce(Return(false));
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//    valueProducer->mCondition = ConditionState::kFalse;
//
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 8);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 50);
//
//    // Now the alarm is delivered. Condition is off though.
//    vector<shared_ptr<LogEvent>> allData;
//    allData.push_back(ValueMetricProducerTestHelper::createEvent(bucket2StartTimeNs + 30, 30));
//    valueProducer->onDataPulled(allData, /** succeed */ true, bucket2StartTimeNs);
//
//    // No buckets, we had a failure.
//    assertPastBucketValuesSingleKey(valueProducer->mPastBuckets, {}, {});
//}
//
///*
// * Test that DUMP_REPORT_REQUESTED dump reason is logged.
// *
// * For the bucket to be marked invalid during a dump report requested,
// * three things must be true:
// * - we want to include the current partial bucket
// * - we need a pull (metric is pulled and condition is true)
// * - the dump latency must be FAST
// */
//
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenDumpReportRequested) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 20);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucketStartTimeNs + 40, true /* include recent buckets */, true,
//                                FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 40),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::DUMP_REPORT_REQUESTED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 40), dropEvent.drop_time_millis());
//}
//
///*
// * Test that EVENT_IN_WRONG_BUCKET dump reason is logged for a late condition
// * change event (i.e. the condition change occurs in the wrong bucket).
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenConditionEventWrongBucket) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
//
//    // Bucket boundary pull.
//    vector<shared_ptr<LogEvent>> allData;
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs);
//    event->write("field1");
//    event->write(15);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);
//
//    // Late condition change event.
//    valueProducer->onConditionChanged(false, bucket2StartTimeNs - 100);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 100, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(1, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs + 100),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::EVENT_IN_WRONG_BUCKET, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs - 100), dropEvent.drop_time_millis());
//}
//
///*
// * Test that EVENT_IN_WRONG_BUCKET dump reason is logged for a late accumulate
// * event (i.e. the accumulate events call occurs in the wrong bucket).
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenAccumulateEventWrongBucket) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs +
//                100); event->write("field1"); event->write(15); event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
//
//    // Bucket boundary pull.
//    vector<shared_ptr<LogEvent>> allData;
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs);
//    event->write("field1");
//    event->write(15);
//    event->init();
//    allData.push_back(event);
//    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);
//
//    allData.clear();
//    event = make_shared<LogEvent>(tagId, bucket2StartTimeNs - 100);
//    event->write("field1");
//    event->write(20);
//    event->init();
//    allData.push_back(event);
//
//    // Late accumulateEvents event.
//    valueProducer->accumulateEvents(allData, bucket2StartTimeNs - 100, bucket2StartTimeNs - 100);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 100, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(1, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs + 100),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::EVENT_IN_WRONG_BUCKET, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs - 100), dropEvent.drop_time_millis());
//}
//
///*
// * Test that CONDITION_UNKNOWN dump reason is logged due to an unknown condition
// * when a metric is initialized.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenConditionUnknown) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs +
//                100); event->write("field1"); event->write(15); event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithNoInitialCondition(pullerManager,
//                                                                                     metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000;
//    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
//}
//
///*
// * Test that PULL_FAILED dump reason is logged due to a pull failure in
// * #pullAndMatchEventsLocked.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenPullFailed) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested, pull fails.
//            .WillOnce(Return(false));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    int64_t dumpReportTimeNs = bucketStartTimeNs + 10000;
//    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
//}
//
///*
// * Test that MULTIPLE_BUCKETS_SKIPPED dump reason is logged when a log event
// * skips over more than one bucket.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenMultipleBucketsSkipped) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event =
//                        make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1000);
//                event->write("field1");
//                event->write(15);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//
//    // Condition change event that skips forward by three buckets.
//    valueProducer->onConditionChanged(false, bucket4StartTimeNs + 10);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket4StartTimeNs + 1000, true /* include recent buckets */,
//    true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(bucket2StartTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::MULTIPLE_BUCKETS_SKIPPED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucket4StartTimeNs + 10), dropEvent.drop_time_millis());
//}
//
///*
// * Test that BUCKET_TOO_SMALL dump reason is logged when a flushed bucket size
// * is smaller than the "min_bucket_size_nanos" specified in the metric config.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestBucketDropWhenBucketTooSmall) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//    metric.set_min_bucket_size_nanos(10000000000);  // 10 seconds
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event =
//                        make_shared<LogEvent>(tagId, bucketStartTimeNs + 9000000);
//                event->write("field1");
//                event->write(15);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithCondition(pullerManager,
//            metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    int64_t dumpReportTimeNs = bucketStartTimeNs + 9000000;
//    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(1, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::BUCKET_TOO_SMALL, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
//}
//
///*
// * Test multiple bucket drop events in the same bucket.
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestMultipleBucketDropEvents) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // Condition change to true.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write("field1");
//                event->write(10);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithNoInitialCondition(pullerManager,
//                                                                                     metric);
//
//    // Condition change event.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    int64_t dumpReportTimeNs = bucketStartTimeNs + 1000;
//    valueProducer->onDumpReport(dumpReportTimeNs, true /* include recent buckets */, true,
//                                FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(2, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 10), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(1);
//    EXPECT_EQ(BucketDropReason::DUMP_REPORT_REQUESTED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs), dropEvent.drop_time_millis());
//}
//
///*
// * Test that the number of logged bucket drop events is capped at the maximum.
// * The maximum is currently 10 and is set in MetricProducer::maxDropEventsReached().
// */
// TEST(ValueMetricProducerTest_BucketDrop, TestMaxBucketDropEvents) {
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithCondition();
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // First condition change event.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                for (int i = 0; i < 2000; i++) {
//                    shared_ptr<LogEvent> event =
//                            make_shared<LogEvent>(tagId, bucketStartTimeNs + 1);
//                    event->write(i);
//                    event->write(i);
//                    event->init();
//                    data->push_back(event);
//                }
//                return true;
//            }))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Return(false))
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs +
//                220); event->write("field1"); event->write(10); event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithNoInitialCondition(pullerManager,
//                                                                                     metric);
//
//    // First condition change event causes guardrail to be reached.
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 10);
//
//    // 2-10 condition change events result in failed pulls.
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 30);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 50);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 70);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 90);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 100);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 150);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 170);
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 190);
//    valueProducer->onConditionChanged(false, bucketStartTimeNs + 200);
//
//    // Condition change event 11
//    valueProducer->onConditionChanged(true, bucketStartTimeNs + 220);
//
//    // Check dump report.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    int64_t dumpReportTimeNs = bucketStartTimeNs + 1000;
//    // Because we already have 10 dump events in the current bucket,
//    // this case should not be added to the list of dump events.
//    valueProducer->onDumpReport(bucketStartTimeNs + 1000, true /* include recent buckets */, true,
//                                FAST /* dumpLatency */, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(0, report.value_metrics().data_size());
//    EXPECT_EQ(1, report.value_metrics().skipped_size());
//
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs),
//              report.value_metrics().skipped(0).start_bucket_elapsed_millis());
//    EXPECT_EQ(NanoToMillis(dumpReportTimeNs),
//              report.value_metrics().skipped(0).end_bucket_elapsed_millis());
//    EXPECT_EQ(10, report.value_metrics().skipped(0).drop_event_size());
//
//    auto dropEvent = report.value_metrics().skipped(0).drop_event(0);
//    EXPECT_EQ(BucketDropReason::CONDITION_UNKNOWN, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 10), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(1);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 30), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(2);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 50), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(3);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 70), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(4);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 90), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(5);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 100), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(6);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 150), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(7);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 170), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(8);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 190), dropEvent.drop_time_millis());
//
//    dropEvent = report.value_metrics().skipped(0).drop_event(9);
//    EXPECT_EQ(BucketDropReason::PULL_FAILED, dropEvent.drop_reason());
//    EXPECT_EQ(NanoToMillis(bucketStartTimeNs + 200), dropEvent.drop_time_millis());
//}
//
///*
// * Test metric with a simple sliced state
// * - Increasing values
// * - Using diff
// * - Second field is value field
// */
// TEST(ValueMetricProducerTest, TestSlicedState) {
//    // Set up ValueMetricProducer.
//    ValueMetric metric = ValueMetricProducerTestHelper::createMetricWithState("SCREEN_STATE");
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // ValueMetricProducer initialized.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write("field1");
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to ON.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 5);
//                event->write("field1");
//                event->write(5);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to OFF.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write("field1");
//                event->write(9);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to ON.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
//                event->write("field1");
//                event->write(21);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(30);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithState(
//                    pullerManager, metric, {android::util::SCREEN_STATE_CHANGED}, {});
//
//    // Set up StateManager and check that StateTrackers are initialized.
//    StateManager::getInstance().clear();
//    StateManager::getInstance().registerListener(SCREEN_STATE_ATOM_ID, valueProducer);
//    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
//    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));
//
//    // Bucket status after metric initialized.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(3, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after screen state change kStateUnknown->ON.
//    auto screenEvent = CreateScreenStateChangedEvent(
//            android::view::DisplayStateEnum::DISPLAY_STATE_ON, bucketStartTimeNs + 5);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(5, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Bucket status after screen state change ON->OFF.
//    screenEvent =
//    CreateScreenStateChangedEvent(android::view::DisplayStateEnum::DISPLAY_STATE_OFF,
//                                                bucketStartTimeNs + 10);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(9, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, ON}
//    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
//              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(4, it->second[0].value.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    it++;
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Bucket status after screen state change OFF->ON.
//    screenEvent = CreateScreenStateChangedEvent(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
//                                                bucketStartTimeNs + 15);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(3UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(21, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, OFF}
//    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF,
//              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(12, it->second[0].value.long_value);
//    // Value for dimension, state key {{}, ON}
//    it++;
//    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
//              it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(4, it->second[0].value.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    it++;
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Start dump report and check output.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucketStartTimeNs + 50, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(3, report.value_metrics().data_size());
//
//    auto data = report.value_metrics().data(0);
//    EXPECT_EQ(1, data.bucket_info_size());
//    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
//
//    data = report.value_metrics().data(1);
//    EXPECT_EQ(1, report.value_metrics().data(1).bucket_info_size());
//    EXPECT_EQ(13, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_value());
//    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON, data.slice_by_state(0).value());
//
//    data = report.value_metrics().data(2);
//    EXPECT_EQ(1, report.value_metrics().data(2).bucket_info_size());
//    EXPECT_EQ(12, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_value());
//    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_OFF, data.slice_by_state(0).value());
//}
//
///*
// * Test metric with sliced state with map
// * - Increasing values
// * - Using diff
// * - Second field is value field
// */
// TEST(ValueMetricProducerTest, TestSlicedStateWithMap) {
//    // Set up ValueMetricProducer.
//    ValueMetric metric =
//    ValueMetricProducerTestHelper::createMetricWithState("SCREEN_STATE_ONOFF");
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // ValueMetricProducer initialized.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write("field1");
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to ON.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 5);
//                event->write("field1");
//                event->write(5);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to VR.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
//                event->write("field1");
//                event->write(9);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Screen state change to OFF.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 15);
//                event->write("field1");
//                event->write(21);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report requested.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 50);
//                event->write("field1");
//                event->write(30);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    const StateMap& stateMap = CreateScreenStateOnOffMap();
//    const StateMap_StateGroup screenOnGroup = stateMap.group(0);
//    const StateMap_StateGroup screenOffGroup = stateMap.group(1);
//
//    unordered_map<int, unordered_map<int, int64_t>> stateGroupMap;
//    for (auto group : stateMap.group()) {
//        for (auto value : group.value()) {
//            stateGroupMap[SCREEN_STATE_ATOM_ID][value] = group.group_id();
//        }
//    }
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithState(
//                    pullerManager, metric, {android::util::SCREEN_STATE_CHANGED}, stateGroupMap);
//
//    // Set up StateManager and check that StateTrackers are initialized.
//    StateManager::getInstance().clear();
//    StateManager::getInstance().registerListener(SCREEN_STATE_ATOM_ID, valueProducer);
//    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
//    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(SCREEN_STATE_ATOM_ID));
//
//    // Bucket status after metric initialized.
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(3, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, {}}
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after screen state change kStateUnknown->ON.
//    auto screenEvent = CreateScreenStateChangedEvent(
//            android::view::DisplayStateEnum::DISPLAY_STATE_ON, bucketStartTimeNs + 5);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(1UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(5, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Bucket status after screen state change ON->VR (also ON).
//    screenEvent = CreateScreenStateChangedEvent(android::view::DisplayStateEnum::DISPLAY_STATE_VR,
//                                                bucketStartTimeNs + 10);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(9, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, ON GROUP}
//    EXPECT_EQ(screenOnGroup.group_id(),
//              it->first.getStateValuesKey().getValues()[0].mValue.long_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(4, it->second[0].value.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    it++;
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Bucket status after screen state change VR->OFF.
//    screenEvent =
//    CreateScreenStateChangedEvent(android::view::DisplayStateEnum::DISPLAY_STATE_OFF,
//                                                bucketStartTimeNs + 15);
//    StateManager::getInstance().onLogEvent(*screenEvent);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(21, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{}, ON GROUP}
//    EXPECT_EQ(screenOnGroup.group_id(),
//              it->first.getStateValuesKey().getValues()[0].mValue.long_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(16, it->second[0].value.long_value);
//    // Value for dimension, state key {{}, kStateUnknown}
//    it++;
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Start dump report and check output.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucketStartTimeNs + 50, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(3, report.value_metrics().data_size());
//
//    auto data = report.value_metrics().data(0);
//    EXPECT_EQ(1, data.bucket_info_size());
//    EXPECT_EQ(2, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
//
//    data = report.value_metrics().data(1);
//    EXPECT_EQ(1, report.value_metrics().data(1).bucket_info_size());
//    EXPECT_EQ(16, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
//    EXPECT_EQ(screenOnGroup.group_id(), data.slice_by_state(0).group_id());
//
//    data = report.value_metrics().data(2);
//    EXPECT_EQ(1, report.value_metrics().data(2).bucket_info_size());
//    EXPECT_EQ(9, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(SCREEN_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_group_id());
//    EXPECT_EQ(screenOffGroup.group_id(), data.slice_by_state(0).group_id());
//}
//
///*
// * Test metric that slices by state with a primary field and has dimensions
// * - Increasing values
// * - Using diff
// * - Second field is value field
// */
// TEST(ValueMetricProducerTest, TestSlicedStateWithPrimaryField_WithDimensions) {
//    // Set up ValueMetricProducer.
//    ValueMetric metric =
//    ValueMetricProducerTestHelper::createMetricWithState("UID_PROCESS_STATE");
//    metric.mutable_dimensions_in_what()->set_field(tagId);
//    metric.mutable_dimensions_in_what()->add_child()->set_field(1);
//
//    MetricStateLink* stateLink = metric.add_state_link();
//    stateLink->set_state_atom_id(UID_PROCESS_STATE_ATOM_ID);
//    auto fieldsInWhat = stateLink->mutable_fields_in_what();
//    *fieldsInWhat = CreateDimensions(tagId, {1 /* uid */});
//    auto fieldsInState = stateLink->mutable_fields_in_state();
//    *fieldsInState = CreateDimensions(UID_PROCESS_STATE_ATOM_ID, {1 /* uid */});
//
//    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
//    EXPECT_CALL(*pullerManager, Pull(tagId, _))
//            // ValueMetricProducer initialized.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(2 /* uid */);
//                event->write(7);
//                event->init();
//                data->push_back(event);
//
//                event = make_shared<LogEvent>(tagId, bucketStartTimeNs);
//                event->write(1 /* uid */);
//                event->write(3);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Uid 1 process state change from kStateUnknown -> Foreground
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//                event->write(1 /* uid */);
//                event->write(6);
//                event->init();
//                data->push_back(event);
//
//                // This event should be skipped.
//                event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
//                event->write(2 /* uid */);
//                event->write(8);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Uid 2 process state change from kStateUnknown -> Background
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 40);
//                event->write(2 /* uid */);
//                event->write(9);
//                event->init();
//                data->push_back(event);
//
//                // This event should be skipped.
//                event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 40);
//                event->write(1 /* uid */);
//                event->write(12);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Uid 1 process state change from Foreground -> Background
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs +
//                20); event->write(1 /* uid */); event->write(13); event->init();
//                data->push_back(event);
//
//                // This event should be skipped.
//                event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 20);
//                event->write(2 /* uid */);
//                event->write(11);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Uid 1 process state change from Background -> Foreground
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs +
//                40); event->write(1 /* uid */); event->write(17); event->init();
//                data->push_back(event);
//
//                // This event should be skipped.
//                event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 40);
//                event->write(2 /* uid */);
//                event->write(15);
//                event->init();
//                data->push_back(event);
//                return true;
//            }))
//            // Dump report pull.
//            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
//                data->clear();
//                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs +
//                50); event->write(2 /* uid */); event->write(20); event->init();
//                data->push_back(event);
//
//                event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 50);
//                event->write(1 /* uid */);
//                event->write(21);
//                event->init();
//                data->push_back(event);
//                return true;
//            }));
//
//    sp<ValueMetricProducer> valueProducer =
//            ValueMetricProducerTestHelper::createValueProducerWithState(
//                    pullerManager, metric, {UID_PROCESS_STATE_ATOM_ID}, {});
//
//    // Set up StateManager and check that StateTrackers are initialized.
//    StateManager::getInstance().clear();
//    StateManager::getInstance().registerListener(UID_PROCESS_STATE_ATOM_ID, valueProducer);
//    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
//    EXPECT_EQ(1, StateManager::getInstance().getListenersCount(UID_PROCESS_STATE_ATOM_ID));
//
//    // Bucket status after metric initialized.
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {uid 1}.
//    auto it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    auto itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(3, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{uid 1}, kStateUnknown}
//    // TODO(tsaichristine): test equality of state values key
//    // EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//    // Base for dimension key {uid 2}
//    it++;
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(7, itBase->second[0].base.long_value);
//    // Value for dimension, state key {{uid 2}, kStateUnknown}
//    // EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after uid 1 process state change kStateUnknown -> Foreground.
//    auto uidProcessEvent = CreateUidProcessStateChangedEvent(
//            1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, bucketStartTimeNs +
//            20);
//    StateManager::getInstance().onLogEvent(*uidProcessEvent);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {uid 1}.
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(6, itBase->second[0].base.long_value);
//    // Value for key {uid 1, kStateUnknown}.
//    // EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(3, it->second[0].value.long_value);
//
//    // Base for dimension key {uid 2}
//    it++;
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(7, itBase->second[0].base.long_value);
//    // Value for key {uid 2, kStateUnknown}
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after uid 2 process state change kStateUnknown -> Background.
//    uidProcessEvent = CreateUidProcessStateChangedEvent(
//            2 /* uid */, android::app::PROCESS_STATE_IMPORTANT_BACKGROUND, bucketStartTimeNs +
//            40);
//    StateManager::getInstance().onLogEvent(*uidProcessEvent);
//    EXPECT_EQ(2UL, valueProducer->mCurrentSlicedBucket.size());
//    // Base for dimension key {uid 1}.
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(6, itBase->second[0].base.long_value);
//    // Value for key {uid 1, kStateUnknown}.
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(3, it->second[0].value.long_value);
//
//    // Base for dimension key {uid 2}
//    it++;
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(9, itBase->second[0].base.long_value);
//    // Value for key {uid 2, kStateUnknown}
//    // EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(2, it->second[0].value.long_value);
//
//    // Pull at end of first bucket.
//    vector<shared_ptr<LogEvent>> allData;
//    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs);
//    event->write(1 /* uid */);
//    event->write(10);
//    event->init();
//    allData.push_back(event);
//
//    event = make_shared<LogEvent>(tagId, bucket2StartTimeNs);
//    event->write(2 /* uid */);
//    event->write(15);
//    event->init();
//    allData.push_back(event);
//
//    valueProducer->onDataPulled(allData, /** succeeds */ true, bucket2StartTimeNs + 1);
//
//    // Buckets flushed after end of first bucket.
//    // None of the buckets should have a value.
//    EXPECT_EQ(4UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(4UL, valueProducer->mPastBuckets.size());
//    EXPECT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
//    // Base for dimension key {uid 2}.
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(15, itBase->second[0].base.long_value);
//    // Value for key {uid 2, BACKGROUND}.
//    EXPECT_EQ(1, it->first.getStateValuesKey().getValues().size());
//    EXPECT_EQ(1006, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Base for dimension key {uid 1}
//    it++;
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(10, itBase->second[0].base.long_value);
//    // Value for key {uid 1, kStateUnknown}
//    EXPECT_EQ(0, it->first.getStateValuesKey().getValues().size());
//    // EXPECT_EQ(-1, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Value for key {uid 1, FOREGROUND}
//    it++;
//    EXPECT_EQ(1, it->first.getStateValuesKey().getValues().size());
//    EXPECT_EQ(1005, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Value for key {uid 2, kStateUnknown}
//    it++;
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after uid 1 process state change from Foreground -> Background.
//    uidProcessEvent = CreateUidProcessStateChangedEvent(
//            1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_BACKGROUND, bucket2StartTimeNs +
//            20);
//    StateManager::getInstance().onLogEvent(*uidProcessEvent);
//
//    EXPECT_EQ(4UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(4UL, valueProducer->mPastBuckets.size());
//    EXPECT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
//    // Base for dimension key {uid 2}.
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(15, itBase->second[0].base.long_value);
//    // Value for key {uid 2, BACKGROUND}.
//    EXPECT_EQ(false, it->second[0].hasValue);
//    // Base for dimension key {uid 1}
//    it++;
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(13, itBase->second[0].base.long_value);
//    // Value for key {uid 1, kStateUnknown}
//    EXPECT_EQ(false, it->second[0].hasValue);
//    // Value for key {uid 1, FOREGROUND}
//    it++;
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    EXPECT_EQ(1005, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(3, it->second[0].value.long_value);
//    // Value for key {uid 2, kStateUnknown}
//    it++;
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Bucket status after uid 1 process state change Background->Foreground.
//    uidProcessEvent = CreateUidProcessStateChangedEvent(
//            1 /* uid */, android::app::PROCESS_STATE_IMPORTANT_FOREGROUND, bucket2StartTimeNs +
//            40);
//    StateManager::getInstance().onLogEvent(*uidProcessEvent);
//
//    EXPECT_EQ(5UL, valueProducer->mCurrentSlicedBucket.size());
//    EXPECT_EQ(2UL, valueProducer->mCurrentBaseInfo.size());
//    // Base for dimension key {uid 2}
//    it = valueProducer->mCurrentSlicedBucket.begin();
//    EXPECT_EQ(2, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(true, itBase->second[0].hasBase);
//    EXPECT_EQ(15, itBase->second[0].base.long_value);
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    it++;
//    EXPECT_EQ(false, it->second[0].hasValue);
//
//    // Base for dimension key {uid 1}
//    it++;
//    EXPECT_EQ(1, it->first.getDimensionKeyInWhat().getValues()[0].mValue.int_value);
//    itBase = valueProducer->mCurrentBaseInfo.find(it->first.getDimensionKeyInWhat());
//    EXPECT_EQ(17, itBase->second[0].base.long_value);
//    // Value for key {uid 1, BACKGROUND}
//    EXPECT_EQ(1006, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(4, it->second[0].value.long_value);
//    // Value for key {uid 1, FOREGROUND}
//    it++;
//    EXPECT_EQ(1005, it->first.getStateValuesKey().getValues()[0].mValue.int_value);
//    EXPECT_EQ(true, it->second[0].hasValue);
//    EXPECT_EQ(3, it->second[0].value.long_value);
//
//    // Start dump report and check output.
//    ProtoOutputStream output;
//    std::set<string> strSet;
//    valueProducer->onDumpReport(bucket2StartTimeNs + 50, true /* include recent buckets */, true,
//                                NO_TIME_CONSTRAINTS, &strSet, &output);
//
//    StatsLogReport report = outputStreamToProto(&output);
//    EXPECT_TRUE(report.has_value_metrics());
//    EXPECT_EQ(5, report.value_metrics().data_size());
//
//    auto data = report.value_metrics().data(0);
//    EXPECT_EQ(1, data.bucket_info_size());
//    EXPECT_EQ(4, report.value_metrics().data(0).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_value());
//    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND,
//              data.slice_by_state(0).value());
//
//    data = report.value_metrics().data(1);
//    EXPECT_EQ(1, report.value_metrics().data(1).bucket_info_size());
//    EXPECT_EQ(2, report.value_metrics().data(1).bucket_info(0).values(0).value_long());
//
//    data = report.value_metrics().data(2);
//    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_value());
//    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND,
//              data.slice_by_state(0).value());
//    EXPECT_EQ(2, report.value_metrics().data(2).bucket_info_size());
//    EXPECT_EQ(4, report.value_metrics().data(2).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(7, report.value_metrics().data(2).bucket_info(1).values(0).value_long());
//
//    data = report.value_metrics().data(3);
//    EXPECT_EQ(1, report.value_metrics().data(3).bucket_info_size());
//    EXPECT_EQ(3, report.value_metrics().data(3).bucket_info(0).values(0).value_long());
//
//    data = report.value_metrics().data(4);
//    EXPECT_EQ(UID_PROCESS_STATE_ATOM_ID, data.slice_by_state(0).atom_id());
//    EXPECT_TRUE(data.slice_by_state(0).has_value());
//    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND,
//              data.slice_by_state(0).value());
//    EXPECT_EQ(2, report.value_metrics().data(4).bucket_info_size());
//    EXPECT_EQ(6, report.value_metrics().data(4).bucket_info(0).values(0).value_long());
//    EXPECT_EQ(5, report.value_metrics().data(4).bucket_info(1).values(0).value_long());
//}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
