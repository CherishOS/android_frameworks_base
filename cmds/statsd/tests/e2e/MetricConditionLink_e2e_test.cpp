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

#include "src/StatsLogProcessor.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

StatsdConfig CreateStatsdConfig() {
    StatsdConfig config;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    *config.add_atom_matcher() = CreateSyncStartAtomMatcher();
    *config.add_atom_matcher() = CreateSyncEndAtomMatcher();

    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    auto appCrashMatcher = CreateProcessCrashAtomMatcher();
    *config.add_atom_matcher() = appCrashMatcher;

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();

    auto isSyncingPredicate = CreateIsSyncingPredicate();
    *isSyncingPredicate.mutable_simple_predicate()->mutable_dimensions() =
        CreateDimensions(
            android::util::SYNC_STATE_CHANGED, {1 /* uid field */});

    auto isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
        CreateDimensions(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /* uid field */ });

    *config.add_predicate() = screenIsOffPredicate;
    *config.add_predicate() = isSyncingPredicate;
    *config.add_predicate() = isInBackgroundPredicate;

    auto combinationPredicate = config.add_predicate();
    combinationPredicate->set_id(StringToId("combinationPredicate"));
    combinationPredicate->mutable_combination()->set_operation(LogicalOperation::AND);
    addPredicateToPredicateCombination(screenIsOffPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isSyncingPredicate, combinationPredicate);
    addPredicateToPredicateCombination(isInBackgroundPredicate, combinationPredicate);

    auto countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("AppCrashes"));
    countMetric->set_what(appCrashMatcher.id());
    countMetric->set_condition(combinationPredicate->id());
    // The metric is dimensioning by uid only.
    *countMetric->mutable_dimensions() =
        CreateDimensions(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED, {1});
    countMetric->mutable_bucket()->set_bucket_size_millis(30 * 1000LL);

    // Links between crash atom and condition of app is in syncing.
    auto links = countMetric->add_links();
    links->set_condition(isSyncingPredicate.id());
    auto dimensionWhat = links->mutable_dimensions_in_what();
    dimensionWhat->set_field(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    auto dimensionCondition = links->mutable_dimensions_in_condition();
    dimensionCondition->set_field(android::util::SYNC_STATE_CHANGED);
    dimensionCondition->add_child()->set_field(1);  // uid field.

    // Links between crash atom and condition of app is in background.
    links = countMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    dimensionWhat = links->mutable_dimensions_in_what();
    dimensionWhat->set_field(android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    dimensionWhat->add_child()->set_field(1);  // uid field.
    dimensionCondition = links->mutable_dimensions_in_condition();
    dimensionCondition->set_field(android::util::ACTIVITY_FOREGROUND_STATE_CHANGED);
    dimensionCondition->add_child()->set_field(1);  // uid field.
    return config;
}

TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks) {
    auto config = CreateStatsdConfig();
    uint64_t bucketStartTimeNs = 10000000000;
    uint64_t bucketSizeNs = config.count_metric(0).bucket().bucket_size_millis() * 1000 * 1000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs / NS_PER_SEC, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    int appUid = 123;
    auto crashEvent1 = CreateAppCrashEvent(appUid, bucketStartTimeNs + 1);
    auto crashEvent2 = CreateAppCrashEvent(appUid, bucketStartTimeNs + 201);
    auto crashEvent3= CreateAppCrashEvent(appUid, bucketStartTimeNs + 2 * bucketSizeNs - 101);

    auto crashEvent4 = CreateAppCrashEvent(appUid, bucketStartTimeNs + 51);
    auto crashEvent5 = CreateAppCrashEvent(appUid, bucketStartTimeNs + bucketSizeNs + 299);
    auto crashEvent6 = CreateAppCrashEvent(appUid, bucketStartTimeNs + bucketSizeNs + 2001);

    auto crashEvent7 = CreateAppCrashEvent(appUid, bucketStartTimeNs + 16);
    auto crashEvent8 = CreateAppCrashEvent(appUid, bucketStartTimeNs + bucketSizeNs + 249);

    auto crashEvent9 = CreateAppCrashEvent(appUid, bucketStartTimeNs + bucketSizeNs + 351);
    auto crashEvent10 = CreateAppCrashEvent(appUid, bucketStartTimeNs + 2 * bucketSizeNs - 2);

    auto screenTurnedOnEvent =
        CreateScreenStateChangedEvent(ScreenStateChanged::STATE_ON, bucketStartTimeNs + 2);
    auto screenTurnedOffEvent =
        CreateScreenStateChangedEvent(ScreenStateChanged::STATE_OFF, bucketStartTimeNs + 200);
    auto screenTurnedOnEvent2 =
        CreateScreenStateChangedEvent(ScreenStateChanged::STATE_ON,
                                      bucketStartTimeNs + 2 * bucketSizeNs - 100);

    auto syncOnEvent1 =
        CreateSyncStartEvent(appUid, "ReadEmail", bucketStartTimeNs + 50);
    auto syncOffEvent1 =
        CreateSyncEndEvent(appUid, "ReadEmail", bucketStartTimeNs + bucketSizeNs + 300);
    auto syncOnEvent2 =
        CreateSyncStartEvent(appUid, "ReadDoc", bucketStartTimeNs + bucketSizeNs + 2000);

    auto moveToBackgroundEvent1 =
        CreateMoveToBackgroundEvent(appUid, bucketStartTimeNs + 15);
    auto moveToForegroundEvent1 =
        CreateMoveToForegroundEvent(appUid, bucketStartTimeNs + bucketSizeNs + 250);

    auto moveToBackgroundEvent2 =
        CreateMoveToBackgroundEvent(appUid, bucketStartTimeNs + bucketSizeNs + 350);
    auto moveToForegroundEvent2 =
        CreateMoveToForegroundEvent(appUid, bucketStartTimeNs + 2 * bucketSizeNs - 1);

    /*
                    bucket #1                               bucket #2


       |      |   |  |                      |   |          |        |   |   |     (crashEvents)
    |-------------------------------------|-----------------------------------|---------

             |                                           |                        (MoveToBkground)

                                             |                               |    (MoveToForeground)

                |                                                 |                (SyncIsOn)
                                                  |                                (SyncIsOff)
          |                                                               |        (ScreenIsOn)
                   |                                                               (ScreenIsOff)
    */
    std::vector<std::unique_ptr<LogEvent>> events;
    events.push_back(std::move(crashEvent1));
    events.push_back(std::move(crashEvent2));
    events.push_back(std::move(crashEvent3));
    events.push_back(std::move(crashEvent4));
    events.push_back(std::move(crashEvent5));
    events.push_back(std::move(crashEvent6));
    events.push_back(std::move(crashEvent7));
    events.push_back(std::move(crashEvent8));
    events.push_back(std::move(crashEvent9));
    events.push_back(std::move(crashEvent10));
    events.push_back(std::move(screenTurnedOnEvent));
    events.push_back(std::move(screenTurnedOffEvent));
    events.push_back(std::move(screenTurnedOnEvent2));
    events.push_back(std::move(syncOnEvent1));
    events.push_back(std::move(syncOffEvent1));
    events.push_back(std::move(syncOnEvent2));
    events.push_back(std::move(moveToBackgroundEvent1));
    events.push_back(std::move(moveToForegroundEvent1));
    events.push_back(std::move(moveToBackgroundEvent2));
    events.push_back(std::move(moveToForegroundEvent2));

    sortLogEventsByTimestamp(&events);

    for (const auto& event : events) {
        processor->OnLogEvent(*event);
    }
    ConfigMetricsReportList reports;
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs - 1, &reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(0).count(), 1);
    auto data = reports.reports(0).metrics(0).count_metrics().data(0);
    // Validate dimension value.
    EXPECT_EQ(data.dimension().field(),
              android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value_size(), 1);
    // Uid field.
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value(0).value_int(), appUid);

    reports.Clear();
    processor->onDumpReport(cfgKey, bucketStartTimeNs + 2 * bucketSizeNs + 1, &reports);
    EXPECT_EQ(reports.reports_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info_size(), 2);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(0).count(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).count_metrics().data(0).bucket_info(1).count(), 3);
    data = reports.reports(0).metrics(0).count_metrics().data(0);
    // Validate dimension value.
    EXPECT_EQ(data.dimension().field(),
              android::util::PROCESS_LIFE_CYCLE_STATE_CHANGED);
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value_size(), 1);
    // Uid field.
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(data.dimension().value_tuple().dimensions_value(0).value_int(), appUid);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android