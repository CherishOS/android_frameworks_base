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

#include "src/metrics/duration_helper/MaxDurationTracker.h"
#include "metrics_test_helper.h"
#include "src/condition/ConditionWizard.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <set>
#include <unordered_map>
#include <vector>

using namespace android::os::statsd;
using namespace testing;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, "test");

const HashableDimensionKey eventKey = getMockedDimensionKey(0, "1");
const HashableDimensionKey conditionKey = getMockedDimensionKey(4, "1");
const HashableDimensionKey key1 = getMockedDimensionKey(1, "1");
const HashableDimensionKey key2 = getMockedDimensionKey(1, "2");

TEST(MaxDurationTrackerTest, TestSimpleMaxDuration) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;
    ConditionKey conditionKey1;
    conditionKey1["condition"] = conditionKey;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {});

    tracker.noteStart(key1, true, bucketStartTimeNs, conditionKey1);
    // Event starts again. This would not change anything as it already starts.
    tracker.noteStart(key1, true, bucketStartTimeNs + 3, conditionKey1);
    // Stopped.
    tracker.noteStop(key1, bucketStartTimeNs + 10, false);

    // Another event starts in this bucket.
    tracker.noteStart(key2, true, bucketStartTimeNs + 20, conditionKey1);
    tracker.noteStop(key2, bucketStartTimeNs + 40, false /*stop all*/);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(20ULL, buckets[eventKey][0].mDuration);
}

TEST(MaxDurationTrackerTest, TestStopAll) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;
    ConditionKey conditionKey1;
    conditionKey1["condition"] = conditionKey;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {});

    tracker.noteStart(key1, true, bucketStartTimeNs + 1, conditionKey1);

    // Another event starts in this bucket.
    tracker.noteStart(key2, true, bucketStartTimeNs + 20, conditionKey1);
    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 40, &buckets);
    tracker.noteStopAll(bucketStartTimeNs + bucketSizeNs + 40);
    EXPECT_TRUE(tracker.mInfos.empty());

    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);

    tracker.flushIfNeeded(bucketStartTimeNs + 3 * bucketSizeNs + 40, &buckets);
    EXPECT_EQ(2u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);
    EXPECT_EQ(40ULL, buckets[eventKey][1].mDuration);
}

TEST(MaxDurationTrackerTest, TestCrossBucketBoundary) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;
    ConditionKey conditionKey1;
    conditionKey1["condition"] = conditionKey;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, -1, false, bucketStartTimeNs,
                               bucketSizeNs, {});

    // The event starts.
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, bucketStartTimeNs + 1, conditionKey1);

    // Starts again. Does not change anything.
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, bucketStartTimeNs + bucketSizeNs + 1,
                      conditionKey1);

    // The event stops at early 4th bucket.
    tracker.flushIfNeeded(bucketStartTimeNs + (3 * bucketSizeNs) + 20, &buckets);
    tracker.noteStop(DEFAULT_DIMENSION_KEY, bucketStartTimeNs + (3 * bucketSizeNs) + 20,
                     false /*stop all*/);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(3u, buckets[eventKey].size());
    EXPECT_EQ((unsigned long long)(bucketSizeNs - 1), buckets[eventKey][0].mDuration);
    EXPECT_EQ((unsigned long long)bucketSizeNs, buckets[eventKey][1].mDuration);
    EXPECT_EQ((unsigned long long)bucketSizeNs, buckets[eventKey][2].mDuration);
}

TEST(MaxDurationTrackerTest, TestCrossBucketBoundary_nested) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;
    ConditionKey conditionKey1;
    conditionKey1["condition"] = conditionKey;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;

    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, -1, true, bucketStartTimeNs,
                               bucketSizeNs, {});

    // 2 starts
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, bucketStartTimeNs + 1, conditionKey1);
    tracker.noteStart(DEFAULT_DIMENSION_KEY, true, bucketStartTimeNs + 10, conditionKey1);
    // one stop
    tracker.noteStop(DEFAULT_DIMENSION_KEY, bucketStartTimeNs + 20, false /*stop all*/);

    tracker.flushIfNeeded(bucketStartTimeNs + (2 * bucketSizeNs) + 1, &buckets);

    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(2u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[eventKey][1].mDuration);

    // real stop now.
    tracker.noteStop(DEFAULT_DIMENSION_KEY, bucketStartTimeNs + (2 * bucketSizeNs) + 5, false);
    tracker.flushIfNeeded(bucketStartTimeNs + (3 * bucketSizeNs) + 1, &buckets);

    EXPECT_EQ(3u, buckets[eventKey].size());
    EXPECT_EQ(bucketSizeNs - 1, buckets[eventKey][0].mDuration);
    EXPECT_EQ(bucketSizeNs, buckets[eventKey][1].mDuration);
    EXPECT_EQ(5ULL, buckets[eventKey][2].mDuration);
}

TEST(MaxDurationTrackerTest, TestMaxDurationWithCondition) {
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();

    ConditionKey conditionKey1;
    HashableDimensionKey eventKey = getMockedDimensionKey(2, "maps");
    conditionKey1["APP_BACKGROUND"] = conditionKey;

    EXPECT_CALL(*wizard, query(_, conditionKey1))  // #4
            .WillOnce(Return(ConditionState::kFalse));

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;

    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t eventStartTimeNs = bucketStartTimeNs + 1;
    uint64_t bucketSizeNs = 30 * 1000 * 1000 * 1000LL;
    int64_t durationTimeNs = 2 * 1000;

    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, 1, false, bucketStartTimeNs,
                               bucketSizeNs, {});
    EXPECT_TRUE(tracker.mAnomalyTrackers.empty());

    tracker.noteStart(key1, true, eventStartTimeNs, conditionKey1);

    tracker.onSlicedConditionMayChange(eventStartTimeNs + 5);

    tracker.noteStop(key1, eventStartTimeNs + durationTimeNs, false);

    tracker.flushIfNeeded(bucketStartTimeNs + bucketSizeNs + 1, &buckets);
    EXPECT_TRUE(buckets.find(eventKey) != buckets.end());
    EXPECT_EQ(1u, buckets[eventKey].size());
    EXPECT_EQ(5ULL, buckets[eventKey][0].mDuration);
}

TEST(MaxDurationTrackerTest, TestAnomalyDetection) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name("metric");
    alert.set_trigger_if_sum_gt(32 * NS_PER_SEC);
    alert.set_number_of_buckets(2);
    alert.set_refractory_period_secs(1);

    unordered_map<HashableDimensionKey, vector<DurationBucket>> buckets;
    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    ConditionKey conditionKey1;
    conditionKey1["APP_BACKGROUND"] = conditionKey;
    uint64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    uint64_t eventStartTimeNs = bucketStartTimeNs + NS_PER_SEC + 1;
    uint64_t bucketSizeNs = 30 * NS_PER_SEC;

    sp<DurationAnomalyTracker> anomalyTracker = new DurationAnomalyTracker(alert, kConfigKey);
    MaxDurationTracker tracker(kConfigKey, "metric", eventKey, wizard, -1, true, bucketStartTimeNs,
                               bucketSizeNs, {anomalyTracker});

    tracker.noteStart(key1, true, eventStartTimeNs, conditionKey1);
    tracker.noteStop(key1, eventStartTimeNs + 10, false);
    EXPECT_EQ(anomalyTracker->mLastAnomalyTimestampNs, -1);
    EXPECT_EQ(10LL, tracker.mDuration);

    tracker.noteStart(key2, true, eventStartTimeNs + 20, conditionKey1);
    tracker.flushIfNeeded(eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC, &buckets);
    tracker.noteStop(key2, eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC, false);
    EXPECT_EQ((long long)(4 * NS_PER_SEC + 1LL), tracker.mDuration);
    EXPECT_EQ(anomalyTracker->mLastAnomalyTimestampNs,
              (long long)(eventStartTimeNs + 2 * bucketSizeNs + 3 * NS_PER_SEC));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
