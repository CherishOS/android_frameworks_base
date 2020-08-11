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
#include <private/android_filesystem_config.h>
#include <stdio.h>

#include <set>
#include <unordered_map>
#include <vector>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "metrics/metrics_test_helper.h"
#include "src/condition/ConditionTracker.h"
#include "src/matchers/LogMatchingTracker.h"
#include "src/metrics/CountMetricProducer.h"
#include "src/metrics/GaugeMetricProducer.h"
#include "src/metrics/MetricProducer.h"
#include "src/metrics/ValueMetricProducer.h"
#include "src/metrics/parsing_utils/metrics_manager_util.h"
#include "src/state/StateManager.h"
#include "statsd_test_util.h"

using namespace testing;
using android::sp;
using android::os::statsd::Predicate;
using std::map;
using std::set;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

namespace {
const ConfigKey kConfigKey(0, 12345);

const long timeBaseSec = 1000;

StatsdConfig buildGoodConfig() {
    StatsdConfig config;
    config.set_id(12345);

    AtomMatcher* eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_ON"));

    SimpleAtomMatcher* simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_IS_OFF"));

    simpleAtomMatcher = eventMatcher->mutable_simple_atom_matcher();
    simpleAtomMatcher->set_atom_id(2 /*SCREEN_STATE_CHANGE*/);
    simpleAtomMatcher->add_field_value_matcher()->set_field(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleAtomMatcher->mutable_field_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);

    eventMatcher = config.add_atom_matcher();
    eventMatcher->set_id(StringToId("SCREEN_ON_OR_OFF"));

    AtomMatcher_Combination* combination = eventMatcher->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_matcher(StringToId("SCREEN_IS_ON"));
    combination->add_matcher(StringToId("SCREEN_IS_OFF"));

    CountMetric* metric = config.add_count_metric();
    metric->set_id(3);
    metric->set_what(StringToId("SCREEN_IS_ON"));
    metric->set_bucket(ONE_MINUTE);
    metric->mutable_dimensions_in_what()->set_field(2 /*SCREEN_STATE_CHANGE*/);
    metric->mutable_dimensions_in_what()->add_child()->set_field(1);
    return config;
}

bool isSubset(const set<int32_t>& set1, const set<int32_t>& set2) {
    return std::includes(set2.begin(), set2.end(), set1.begin(), set1.end());
}
}  // anonymous namespace

TEST(MetricsManagerTest, TestLogSources) {
    string app1 = "app1";
    set<int32_t> app1Uids = {1111, 11111};
    string app2 = "app2";
    set<int32_t> app2Uids = {2222};
    string app3 = "app3";
    set<int32_t> app3Uids = {3333, 1111};

    map<string, set<int32_t>> pkgToUids;
    pkgToUids[app1] = app1Uids;
    pkgToUids[app2] = app2Uids;
    pkgToUids[app3] = app3Uids;

    int32_t atom1 = 10;
    int32_t atom2 = 20;
    int32_t atom3 = 30;
    sp<MockUidMap> uidMap = new StrictMock<MockUidMap>();
    EXPECT_CALL(*uidMap, getAppUid(_))
            .Times(4)
            .WillRepeatedly(Invoke([&pkgToUids](const string& pkg) {
                const auto& it = pkgToUids.find(pkg);
                if (it != pkgToUids.end()) {
                    return it->second;
                }
                return set<int32_t>();
            }));
    sp<MockStatsPullerManager> pullerManager = new StrictMock<MockStatsPullerManager>();
    EXPECT_CALL(*pullerManager, RegisterPullUidProvider(kConfigKey, _)).Times(1);
    EXPECT_CALL(*pullerManager, UnregisterPullUidProvider(kConfigKey, _)).Times(1);

    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;

    StatsdConfig config;
    config.add_allowed_log_source("AID_SYSTEM");
    config.add_allowed_log_source(app1);
    config.add_default_pull_packages("AID_SYSTEM");
    config.add_default_pull_packages("AID_ROOT");

    const set<int32_t> defaultPullUids = {AID_SYSTEM, AID_ROOT};

    PullAtomPackages* pullAtomPackages = config.add_pull_atom_packages();
    pullAtomPackages->set_atom_id(atom1);
    pullAtomPackages->add_packages(app1);
    pullAtomPackages->add_packages(app3);

    pullAtomPackages = config.add_pull_atom_packages();
    pullAtomPackages->set_atom_id(atom2);
    pullAtomPackages->add_packages(app2);
    pullAtomPackages->add_packages("AID_STATSD");

    MetricsManager metricsManager(kConfigKey, config, timeBaseSec, timeBaseSec, uidMap,
                                  pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor);

    EXPECT_TRUE(metricsManager.isConfigValid());

    ASSERT_EQ(metricsManager.mAllowedUid.size(), 1);
    EXPECT_EQ(metricsManager.mAllowedUid[0], AID_SYSTEM);

    ASSERT_EQ(metricsManager.mAllowedPkg.size(), 1);
    EXPECT_EQ(metricsManager.mAllowedPkg[0], app1);

    ASSERT_EQ(metricsManager.mAllowedLogSources.size(), 3);
    EXPECT_TRUE(isSubset({AID_SYSTEM}, metricsManager.mAllowedLogSources));
    EXPECT_TRUE(isSubset(app1Uids, metricsManager.mAllowedLogSources));

    ASSERT_EQ(metricsManager.mDefaultPullUids.size(), 2);
    EXPECT_TRUE(isSubset(defaultPullUids, metricsManager.mDefaultPullUids));
    ;

    vector<int32_t> atom1Uids = metricsManager.getPullAtomUids(atom1);
    ASSERT_EQ(atom1Uids.size(), 5);
    set<int32_t> expectedAtom1Uids;
    expectedAtom1Uids.insert(defaultPullUids.begin(), defaultPullUids.end());
    expectedAtom1Uids.insert(app1Uids.begin(), app1Uids.end());
    expectedAtom1Uids.insert(app3Uids.begin(), app3Uids.end());
    EXPECT_TRUE(isSubset(expectedAtom1Uids, set<int32_t>(atom1Uids.begin(), atom1Uids.end())));

    vector<int32_t> atom2Uids = metricsManager.getPullAtomUids(atom2);
    ASSERT_EQ(atom2Uids.size(), 4);
    set<int32_t> expectedAtom2Uids;
    expectedAtom1Uids.insert(defaultPullUids.begin(), defaultPullUids.end());
    expectedAtom1Uids.insert(app2Uids.begin(), app2Uids.end());
    expectedAtom1Uids.insert(AID_STATSD);
    EXPECT_TRUE(isSubset(expectedAtom2Uids, set<int32_t>(atom2Uids.begin(), atom2Uids.end())));

    vector<int32_t> atom3Uids = metricsManager.getPullAtomUids(atom3);
    ASSERT_EQ(atom3Uids.size(), 2);
    EXPECT_TRUE(isSubset(defaultPullUids, set<int32_t>(atom3Uids.begin(), atom3Uids.end())));
}

TEST(MetricsManagerTest, TestCheckLogCredentialsWhitelistedAtom) {
    sp<UidMap> uidMap;
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;

    StatsdConfig config;
    config.add_whitelisted_atom_ids(3);
    config.add_whitelisted_atom_ids(4);

    MetricsManager metricsManager(kConfigKey, config, timeBaseSec, timeBaseSec, uidMap,
                                  pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor);

    LogEvent event(0 /* uid */, 0 /* pid */);
    CreateNoValuesLogEvent(&event, 10 /* atom id */, 0 /* timestamp */);
    EXPECT_FALSE(metricsManager.checkLogCredentials(event));

    CreateNoValuesLogEvent(&event, 3 /* atom id */, 0 /* timestamp */);
    EXPECT_TRUE(metricsManager.checkLogCredentials(event));

    CreateNoValuesLogEvent(&event, 4 /* atom id */, 0 /* timestamp */);
    EXPECT_TRUE(metricsManager.checkLogCredentials(event));
}

TEST(MetricsManagerTest, TestWhitelistedAtomStateTracker) {
    sp<UidMap> uidMap;
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;

    StatsdConfig config = buildGoodConfig();
    config.add_allowed_log_source("AID_SYSTEM");
    config.add_whitelisted_atom_ids(3);
    config.add_whitelisted_atom_ids(4);

    State state;
    state.set_id(1);
    state.set_atom_id(3);

    *config.add_state() = state;

    config.mutable_count_metric(0)->add_slice_by_state(state.id());

    StateManager::getInstance().clear();

    MetricsManager metricsManager(kConfigKey, config, timeBaseSec, timeBaseSec, uidMap,
                                  pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor);

    EXPECT_EQ(0, StateManager::getInstance().getStateTrackersCount());
    EXPECT_FALSE(metricsManager.isConfigValid());
}

}  // namespace statsd
}  // namespace os
}  // namespace android

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
