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
#include "metrics_test_helper.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>

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

const ConfigKey kConfigKey(0, "test");
const int tagId = 1;
const string metricName = "test_metric";
const int64_t bucketStartTimeNs = 10000000000;
const int64_t bucketSizeNs = 60 * 1000 * 1000 * 1000LL;
const int64_t bucket2StartTimeNs = bucketStartTimeNs + bucketSizeNs;
const int64_t bucket3StartTimeNs = bucketStartTimeNs + 2 * bucketSizeNs;
const int64_t bucket4StartTimeNs = bucketStartTimeNs + 3 * bucketSizeNs;

/*
 * Tests pulled atoms with no conditions
 */
TEST(ValueMetricProducerTest, TestNonDimensionalEvents) {
    ValueMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    // TODO: pending refactor of StatsPullerManager
    // For now we still need this so that it doesn't do real pulling.
    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillOnce(Return());

    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      tagId, bucketStartTimeNs, pullerManager);

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(tagId);
    event->write(11);
    event->init();
    allData.push_back(event);

    valueProducer.onDataPulled(allData);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // startUpdated:true tainted:0 sum:0 start:11
    EXPECT_EQ(true, curInterval.startUpdated);
    EXPECT_EQ(0, curInterval.tainted);
    EXPECT_EQ(0, curInterval.sum);
    EXPECT_EQ(11, curInterval.start);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(0, valueProducer.mPastBuckets.begin()->second.back().mValue);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket3StartTimeNs + 1);
    event->write(tagId);
    event->write(23);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // tartUpdated:false tainted:0 sum:12
    EXPECT_EQ(true, curInterval.startUpdated);
    EXPECT_EQ(0, curInterval.tainted);
    EXPECT_EQ(0, curInterval.sum);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(2UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(12, valueProducer.mPastBuckets.begin()->second.back().mValue);

    allData.clear();
    event = make_shared<LogEvent>(tagId, bucket4StartTimeNs + 1);
    event->write(tagId);
    event->write(36);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // startUpdated:false tainted:0 sum:12
    EXPECT_EQ(true, curInterval.startUpdated);
    EXPECT_EQ(0, curInterval.tainted);
    EXPECT_EQ(0, curInterval.sum);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(3UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(13, valueProducer.mPastBuckets.begin()->second.back().mValue);
}

/*
 * Test pulled event with non sliced condition.
 */
TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition) {
    ValueMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);
    metric.set_condition("SCREEN_ON");

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();
    EXPECT_CALL(*pullerManager, RegisterReceiver(tagId, _, _)).WillOnce(Return());
    EXPECT_CALL(*pullerManager, UnRegisterReceiver(tagId, _)).WillRepeatedly(Return());

    EXPECT_CALL(*pullerManager, Pull(tagId, _))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
                event->write(tagId);
                event->write(100);
                event->init();
                data->push_back(event);
                return true;
            }))
            .WillOnce(Invoke([](int tagId, vector<std::shared_ptr<LogEvent>>* data) {
                data->clear();
                shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 10);
                event->write(tagId);
                event->write(120);
                event->init();
                data->push_back(event);
                return true;
            }));

    ValueMetricProducer valueProducer(kConfigKey, metric, 1, wizard, tagId, bucketStartTimeNs,
                                      pullerManager);

    valueProducer.onConditionChanged(true, bucketStartTimeNs + 8);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // startUpdated:false tainted:0 sum:0 start:100
    EXPECT_EQ(100, curInterval.start);
    EXPECT_EQ(true, curInterval.startUpdated);
    EXPECT_EQ(0, curInterval.tainted);
    EXPECT_EQ(0, curInterval.sum);
    EXPECT_EQ(0UL, valueProducer.mPastBuckets.size());

    vector<shared_ptr<LogEvent>> allData;
    allData.clear();
    shared_ptr<LogEvent> event = make_shared<LogEvent>(tagId, bucket2StartTimeNs + 1);
    event->write(1);
    event->write(110);
    event->init();
    allData.push_back(event);
    valueProducer.onDataPulled(allData);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // startUpdated:false tainted:0 sum:0 start:110
    EXPECT_EQ(110, curInterval.start);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(10, valueProducer.mPastBuckets.begin()->second.back().mValue);

    valueProducer.onConditionChanged(false, bucket2StartTimeNs + 1);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    // startUpdated:false tainted:0 sum:0 start:110
    EXPECT_EQ(10, curInterval.sum);
    EXPECT_EQ(false, curInterval.startUpdated);
}

TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition) {
    ValueMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    shared_ptr<MockStatsPullerManager> pullerManager =
            make_shared<StrictMock<MockStatsPullerManager>>();

    ValueMetricProducer valueProducer(kConfigKey, metric, -1, wizard, -1, bucketStartTimeNs,
                                      pullerManager);

    shared_ptr<LogEvent> event1 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 10);
    event1->write(1);
    event1->write(10);
    event1->init();
    shared_ptr<LogEvent> event2 = make_shared<LogEvent>(tagId, bucketStartTimeNs + 20);
    event2->write(1);
    event2->write(20);
    event2->init();
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    ValueMetricProducer::Interval curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    EXPECT_EQ(10, curInterval.sum);

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);

    // has one slice
    EXPECT_EQ(1UL, valueProducer.mCurrentSlicedBucket.size());
    curInterval = valueProducer.mCurrentSlicedBucket.begin()->second;
    EXPECT_EQ(30, curInterval.sum);

    valueProducer.flushIfNeededLocked(bucket3StartTimeNs);
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.size());
    EXPECT_EQ(1UL, valueProducer.mPastBuckets.begin()->second.size());
    EXPECT_EQ(30, valueProducer.mPastBuckets.begin()->second.back().mValue);
}

TEST(ValueMetricProducerTest, TestAnomalyDetection) {
    Alert alert;
    alert.set_name("alert");
    alert.set_metric_name(metricName);
    alert.set_trigger_if_sum_gt(130);
    alert.set_number_of_buckets(2);
    alert.set_refractory_period_secs(3);

    ValueMetric metric;
    metric.set_name(metricName);
    metric.mutable_bucket()->set_bucket_size_millis(bucketSizeNs / 1000000);
    metric.set_value_field(2);

    sp<MockConditionWizard> wizard = new NaggyMock<MockConditionWizard>();
    ValueMetricProducer valueProducer(kConfigKey, metric, -1 /*-1 meaning no condition*/, wizard,
                                      -1 /*not pulled*/, bucketStartTimeNs);
    sp<AnomalyTracker> anomalyTracker = valueProducer.addAnomalyTracker(alert);


    shared_ptr<LogEvent> event1
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 1 * NS_PER_SEC);
    event1->write(161);
    event1->write(10); // value of interest
    event1->init();
    shared_ptr<LogEvent> event2
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 + NS_PER_SEC);
    event2->write(162);
    event2->write(20); // value of interest
    event2->init();
    shared_ptr<LogEvent> event3
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 2 * bucketSizeNs + 1 * NS_PER_SEC);
    event3->write(163);
    event3->write(130); // value of interest
    event3->init();
    shared_ptr<LogEvent> event4
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 1 * NS_PER_SEC);
    event4->write(35);
    event4->write(1); // value of interest
    event4->init();
    shared_ptr<LogEvent> event5
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 2 * NS_PER_SEC);
    event5->write(45);
    event5->write(150); // value of interest
    event5->init();
    shared_ptr<LogEvent> event6
            = make_shared<LogEvent>(tagId, bucketStartTimeNs + 3 * bucketSizeNs + 10 * NS_PER_SEC);
    event6->write(25);
    event6->write(160); // value of interest
    event6->init();

    // Two events in bucket #0.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event1);
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event2);
    EXPECT_EQ(anomalyTracker->getLastAnomalyTimestampNs(), -1LL); // Value sum == 30 <= 130.

    // One event in bucket #2. No alarm as bucket #0 is trashed out.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event3);
    EXPECT_EQ(anomalyTracker->getLastAnomalyTimestampNs(), -1LL); // Value sum == 130 <= 130.

    // Three events in bucket #3.
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event4);
    // Anomaly at event 4 since Value sum == 131 > 130!
    EXPECT_EQ(anomalyTracker->getLastAnomalyTimestampNs(), (long long)event4->GetTimestampNs());
    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event5);
    // Event 5 is within 3 sec refractory period. Thus last alarm timestamp is still event4.
    EXPECT_EQ(anomalyTracker->getLastAnomalyTimestampNs(), (long long)event4->GetTimestampNs());

    valueProducer.onMatchedLogEvent(1 /*log matcher index*/, *event6);
    // Anomaly at event 6 since Value sum == 160 > 130 and after refractory period.
    EXPECT_EQ(anomalyTracker->getLastAnomalyTimestampNs(), (long long)event6->GetTimestampNs());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
