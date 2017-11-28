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

#include <gtest/gtest.h>

#include "src/condition/ConditionTracker.h"
#include "src/matchers/LogMatchingTracker.h"
#include "src/metrics/CountMetricProducer.h"
#include "src/metrics/GaugeMetricProducer.h"
#include "src/metrics/MetricProducer.h"
#include "src/metrics/ValueMetricProducer.h"
#include "src/metrics/metrics_manager_util.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>
#include <set>
#include <unordered_map>
#include <vector>

using namespace android::os::statsd;
using android::sp;
using std::set;
using std::unordered_map;
using std::vector;
using android::os::statsd::Condition;

#ifdef __ANDROID__

// TODO: ADD MORE TEST CASES.

const ConfigKey kConfigKey(0, "test");

StatsdConfig buildGoodConfig() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    combination->add_matcher("SCREEN_IS_OFF");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    KeyMatcher* keyMatcher = metric->add_dimension();
    keyMatcher->set_key(1);

    auto alert = config.add_alert();
    alert->set_name("3");
    alert->set_metric_name("3");
    alert->set_number_of_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildCircleMatchers() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // Circle dependency
    combination->add_matcher("SCREEN_ON_OR_OFF");

    return config;
}

StatsdConfig buildAlertWithUnknownMetric() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    KeyMatcher* keyMatcher = metric->add_dimension();
    keyMatcher->set_key(1);

    auto alert = config.add_alert();
    alert->set_name("3");
    alert->set_metric_name("2");
    alert->set_number_of_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildMissingMatchers() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // undefined matcher
    combination->add_matcher("ABC");

    return config;
}

StatsdConfig buildMissingCondition() {
    StatsdConfig config;
    config.set_name("12345");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_EVENT");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    metric->set_condition("SOME_CONDITION");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_EVENT");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2);

    return config;
}

StatsdConfig buildDimensionMetricsWithMultiTags() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("BATTERY_VERY_LOW");
    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("BATTERY_VERY_VERY_LOW");
    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(3);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("BATTERY_LOW");

    LogEntryMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("BATTERY_VERY_LOW");
    combination->add_matcher("BATTERY_VERY_VERY_LOW");

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("BATTERY_LOW");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    KeyMatcher* keyMatcher = metric->add_dimension();
    keyMatcher->set_key(1);

    auto alert = config.add_alert();
    alert->set_name("3");
    alert->set_metric_name("3");
    alert->set_number_of_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildCircleConditions() {
    StatsdConfig config;
    config.set_name("12345");

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->set_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    auto condition = config.add_condition();
    condition->set_name("SCREEN_IS_ON");
    SimpleCondition* simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_ON");
    simpleCondition->set_stop("SCREEN_IS_OFF");

    condition = config.add_condition();
    condition->set_name("SCREEN_IS_EITHER_ON_OFF");

    Condition_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_EITHER_ON_OFF");

    return config;
}

TEST(MetricsManagerTest, TestGoodConfig) {
    StatsdConfig config = buildGoodConfig();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_TRUE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                 allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                 conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
    EXPECT_EQ(1u, allMetricProducers.size());
    EXPECT_EQ(1u, allAnomalyTrackers.size());
}

TEST(MetricsManagerTest, TestDimensionMetricsWithMultiTags) {
    StatsdConfig config = buildDimensionMetricsWithMultiTags();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCircleLogMatcherDependency) {
    StatsdConfig config = buildCircleMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestMissingMatchers) {
    StatsdConfig config = buildMissingMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestMissingCondition) {
    StatsdConfig config = buildMissingCondition();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCircleConditionDependency) {
    StatsdConfig config = buildCircleConditions();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, testAlertWithUnknownMetric) {
    StatsdConfig config = buildAlertWithUnknownMetric();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allLogEntryMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, allTagIds, allLogEntryMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
