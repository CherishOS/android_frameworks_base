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
using android::os::statsd::Predicate;

#ifdef __ANDROID__

// TODO: ADD MORE TEST CASES.

const ConfigKey kConfigKey(0, "test");

const long timeBaseSec = 1000;

StatsdConfig buildGoodConfig() {
    StatsdConfig config;
    config.set_name("12345");

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    combination->add_matcher("SCREEN_IS_OFF");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    metric->mutable_dimensions()->set_field(2 /*SCREEN_STATE_CHANGE*/);
    metric->mutable_dimensions()->add_child()->set_field(1);

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

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // Circle dependency
    combination->add_matcher("SCREEN_ON_OR_OFF");

    return config;
}

StatsdConfig buildAlertWithUnknownMetric() {
    StatsdConfig config;
    config.set_name("12345");

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    metric->mutable_dimensions()->set_field(2 /*SCREEN_STATE_CHANGE*/);
    metric->mutable_dimensions()->add_child()->set_field(1);

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

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_ON_OR_OFF");

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("SCREEN_IS_ON");
    // undefined matcher
    combination->add_matcher("ABC");

    return config;
}

StatsdConfig buildMissingPredicate() {
    StatsdConfig config;
    config.set_name("12345");

    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("SCREEN_EVENT");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    metric->set_condition("SOME_CONDITION");

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_EVENT");

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2);

    return config;
}

StatsdConfig buildDimensionMetricsWithMultiTags() {
    StatsdConfig config;
    config.set_name("12345");

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("BATTERY_VERY_LOW");
    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("BATTERY_VERY_VERY_LOW");
    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(3);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("BATTERY_LOW");

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher("BATTERY_VERY_LOW");
    combination->add_matcher("BATTERY_VERY_VERY_LOW");

    // Count process state changes, slice by uid, while SCREEN_IS_OFF
    CountMetric* metric = config.add_count_metric();
    metric->set_name("3");
    metric->set_what("BATTERY_LOW");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);
    // This case is interesting. We want to dimension across two atoms.
    metric->mutable_dimensions()->add_child()->set_field(1);

    auto alert = config.add_alert();
    alert->set_name("3");
    alert->set_metric_name("3");
    alert->set_number_of_buckets(10);
    alert->set_refractory_period_secs(100);
    alert->set_trigger_if_sum_gt(100);
    return config;
}

StatsdConfig buildCirclePredicates() {
    StatsdConfig config;
    config.set_name("12345");

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    auto condition = config.add_predicate();
    condition->set_name("SCREEN_IS_ON");
    SimplePredicate* simplePredicate = condition->mutable_simple_predicate();
    simplePredicate->set_start("SCREEN_IS_ON");
    simplePredicate->set_stop("SCREEN_IS_OFF");

    condition = config.add_predicate();
    condition->set_name("SCREEN_IS_EITHER_ON_OFF");

    Predicate_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_predicate("SCREEN_IS_ON");
    combination->add_predicate("SCREEN_IS_EITHER_ON_OFF");

    return config;
}

TEST(MetricsManagerTest, TestGoodConfig) {
    UidMap uidMap;
    StatsdConfig config = buildGoodConfig();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_TRUE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                 allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                 conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
    EXPECT_EQ(1u, allMetricProducers.size());
    EXPECT_EQ(1u, allAnomalyTrackers.size());
}

TEST(MetricsManagerTest, TestDimensionMetricsWithMultiTags) {
    UidMap uidMap;
    StatsdConfig config = buildDimensionMetricsWithMultiTags();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCircleLogMatcherDependency) {
    UidMap uidMap;
    StatsdConfig config = buildCircleMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestMissingMatchers) {
    UidMap uidMap;
    StatsdConfig config = buildMissingMatchers();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestMissingPredicate) {
    UidMap uidMap;
    StatsdConfig config = buildMissingPredicate();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;
    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, TestCirclePredicateDependency) {
    UidMap uidMap;
    StatsdConfig config = buildCirclePredicates();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

TEST(MetricsManagerTest, testAlertWithUnknownMetric) {
    UidMap uidMap;
    StatsdConfig config = buildAlertWithUnknownMetric();
    set<int> allTagIds;
    vector<sp<LogMatchingTracker>> allAtomMatchers;
    vector<sp<ConditionTracker>> allConditionTrackers;
    vector<sp<MetricProducer>> allMetricProducers;
    std::vector<sp<AnomalyTracker>> allAnomalyTrackers;
    unordered_map<int, std::vector<int>> conditionToMetricMap;
    unordered_map<int, std::vector<int>> trackerToMetricMap;
    unordered_map<int, std::vector<int>> trackerToConditionMap;

    EXPECT_FALSE(initStatsdConfig(kConfigKey, config, uidMap, timeBaseSec, allTagIds, allAtomMatchers,
                                  allConditionTrackers, allMetricProducers, allAnomalyTrackers,
                                  conditionToMetricMap, trackerToMetricMap, trackerToConditionMap));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
