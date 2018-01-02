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
#include "src/condition/SimpleConditionTracker.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <vector>
#include <numeric>

using std::map;
using std::unordered_map;
using std::vector;

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const ConfigKey kConfigKey(0, "test");

const int ATTRIBUTION_NODE_FIELD_ID = 1;
const int ATTRIBUTION_UID_FIELD_ID = 1;
const int TAG_ID = 1;

SimplePredicate getWakeLockHeldCondition(bool countNesting, bool defaultFalse,
                                         bool outputSlicedUid, Position position) {
    SimplePredicate simplePredicate;
    simplePredicate.set_start("WAKE_LOCK_ACQUIRE");
    simplePredicate.set_stop("WAKE_LOCK_RELEASE");
    simplePredicate.set_stop_all("RELEASE_ALL");
    if (outputSlicedUid) {
        simplePredicate.mutable_dimensions()->set_field(TAG_ID);
        simplePredicate.mutable_dimensions()->add_child()->set_field(ATTRIBUTION_NODE_FIELD_ID);
        simplePredicate.mutable_dimensions()->mutable_child(0)->set_position(position);
        simplePredicate.mutable_dimensions()->mutable_child(0)->add_child()->set_field(
            ATTRIBUTION_UID_FIELD_ID);
    }

    simplePredicate.set_count_nesting(countNesting);
    simplePredicate.set_initial_value(defaultFalse ? SimplePredicate_InitialValue_FALSE
                                                       : SimplePredicate_InitialValue_UNKNOWN);
    return simplePredicate;
}

void writeAttributionNodesToEvent(LogEvent* event, const std::vector<int> &uids) {
    std::vector<AttributionNode> nodes;
    for (size_t i = 0; i < uids.size(); ++i) {
        AttributionNode node;
        node.set_uid(uids[i]);
        nodes.push_back(node);
    }
    event->write(nodes);  // attribution chain.
}

void makeWakeLockEvent(
        LogEvent* event, const std::vector<int> &uids, const string& wl, int acquire) {
    writeAttributionNodesToEvent(event, uids);
    event->write(wl);
    event->write(acquire);
    event->init();
}

std::map<string, std::vector<HashableDimensionKey>> getWakeLockQueryKey(
    const Position position,
    const std::vector<int> &uids, const string& conditionName) {
    std::map<string, std::vector<HashableDimensionKey>>  outputKeyMap;
    std::vector<int> uid_indexes;
    switch(position) {
        case Position::FIRST:
            uid_indexes.push_back(0);
            break;
        case Position::LAST:
            uid_indexes.push_back(uids.size() - 1);
            break;
        case Position::ANY:
            uid_indexes.resize(uids.size());
            std::iota(uid_indexes.begin(), uid_indexes.end(), 0);
            break;
        default:
            break;
    }

    for (const int idx : uid_indexes) {
        DimensionsValue dimensionsValue;
        dimensionsValue.set_field(TAG_ID);
        dimensionsValue.mutable_value_tuple()->add_dimensions_value()->set_field(ATTRIBUTION_NODE_FIELD_ID);
        dimensionsValue.mutable_value_tuple()->mutable_dimensions_value(0)
            ->mutable_value_tuple()->add_dimensions_value()->set_field(ATTRIBUTION_NODE_FIELD_ID);
        dimensionsValue.mutable_value_tuple()->mutable_dimensions_value(0)
            ->mutable_value_tuple()->mutable_dimensions_value(0)->set_value_int(uids[idx]);
        outputKeyMap[conditionName].push_back(HashableDimensionKey(dimensionsValue));
    }
    return outputKeyMap;
}

TEST(SimpleConditionTrackerTest, TestNonSlicedCondition) {
    SimplePredicate simplePredicate;
    simplePredicate.set_start("SCREEN_TURNED_ON");
    simplePredicate.set_stop("SCREEN_TURNED_OFF");
    simplePredicate.set_count_nesting(false);
    simplePredicate.set_initial_value(SimplePredicate_InitialValue_UNKNOWN);

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["SCREEN_TURNED_ON"] = 0;
    trackerNameIndexMap["SCREEN_TURNED_OFF"] = 1;

    SimpleConditionTracker conditionTracker(kConfigKey, "SCREEN_IS_ON", 0 /*tracker index*/,
                                            simplePredicate, trackerNameIndexMap);

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);

    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);

    vector<sp<ConditionTracker>> allPredicates;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // not matched start or stop. condition doesn't change
    EXPECT_EQ(ConditionState::kUnknown, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // prepare a case for match start.
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // now condition should change to true.
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // match nothing.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // the case for match stop.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);

    // condition changes to false.
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // match stop again.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // condition should still be false. not changed.
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);
}

TEST(SimpleConditionTrackerTest, TestNonSlicedConditionNestCounting) {
    SimplePredicate simplePredicate;
    simplePredicate.set_start("SCREEN_TURNED_ON");
    simplePredicate.set_stop("SCREEN_TURNED_OFF");
    simplePredicate.set_count_nesting(true);

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["SCREEN_TURNED_ON"] = 0;
    trackerNameIndexMap["SCREEN_TURNED_OFF"] = 1;

    SimpleConditionTracker conditionTracker(kConfigKey, "SCREEN_IS_ON",
                                            0 /*condition tracker index*/, simplePredicate,
                                            trackerNameIndexMap);

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);

    // one matched start
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allPredicates;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);

    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);

    // prepare for another matched start.
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);

    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // ONE MATCHED STOP
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // result should still be true
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);
    EXPECT_FALSE(changedCache[0]);

    // ANOTHER MATCHED STOP
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // result should still be true
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    EXPECT_TRUE(changedCache[0]);
}

TEST(SimpleConditionTrackerTest, TestSlicedCondition) {
    for (Position position :
            { Position::ANY, Position::FIRST, Position::LAST}) {
        SimplePredicate simplePredicate = getWakeLockHeldCondition(
                true /*nesting*/, true /*default to false*/, true /*output slice by uid*/,
                position);
        string conditionName = "WL_HELD_BY_UID2";

        unordered_map<string, int> trackerNameIndexMap;
        trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
        trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
        trackerNameIndexMap["RELEASE_ALL"] = 2;

        SimpleConditionTracker conditionTracker(kConfigKey, conditionName,
                                                0 /*condition tracker index*/, simplePredicate,
                                                trackerNameIndexMap);
        std::vector<int> uids = {111, 222, 333};

        LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event, uids, "wl1", 1);

        // one matched start
        vector<MatchingState> matcherState;
        matcherState.push_back(MatchingState::kMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        vector<sp<ConditionTracker>> allPredicates;
        vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
        vector<bool> changedCache(1, false);

        conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                           changedCache);

        if (position == Position::FIRST ||
            position == Position::LAST) {
            EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
        } else {
            EXPECT_EQ(uids.size(), conditionTracker.mSlicedConditionState.size());
        }
        EXPECT_TRUE(changedCache[0]);

        // Now test query
        const auto queryKey = getWakeLockQueryKey(position, uids, conditionName);
        conditionCache[0] = ConditionState::kNotEvaluated;

        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

        // another wake lock acquired by this uid
        LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event2, uids, "wl2", 1);
        matcherState.clear();
        matcherState.push_back(MatchingState::kMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        conditionCache[0] = ConditionState::kNotEvaluated;
        changedCache[0] = false;
        conditionTracker.evaluateCondition(event2, matcherState, allPredicates, conditionCache,
                                           changedCache);
        EXPECT_FALSE(changedCache[0]);
        if (position == Position::FIRST ||
            position == Position::LAST) {
            EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
        } else {
            EXPECT_EQ(uids.size(), conditionTracker.mSlicedConditionState.size());
        }

        // wake lock 1 release
        LogEvent event3(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event3, uids, "wl1", 0);  // now release it.
        matcherState.clear();
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kMatched);
        conditionCache[0] = ConditionState::kNotEvaluated;
        changedCache[0] = false;
        conditionTracker.evaluateCondition(event3, matcherState, allPredicates, conditionCache,
                                           changedCache);
        // nothing changes, because wake lock 2 is still held for this uid
        EXPECT_FALSE(changedCache[0]);
        if (position == Position::FIRST ||
            position == Position::LAST) {
            EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
        } else {
            EXPECT_EQ(uids.size(), conditionTracker.mSlicedConditionState.size());
        }

        LogEvent event4(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event4, uids, "wl2", 0);  // now release it.
        matcherState.clear();
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kMatched);
        conditionCache[0] = ConditionState::kNotEvaluated;
        changedCache[0] = false;
        conditionTracker.evaluateCondition(event4, matcherState, allPredicates, conditionCache,
                                           changedCache);
        EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());
        EXPECT_TRUE(changedCache[0]);

        // query again
        conditionCache[0] = ConditionState::kNotEvaluated;
        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);

    }

}

TEST(SimpleConditionTrackerTest, TestSlicedWithNoOutputDim) {
    SimplePredicate simplePredicate = getWakeLockHeldCondition(
            true /*nesting*/, true /*default to false*/, false /*slice output by uid*/,
            Position::ANY /* position */);
    string conditionName = "WL_HELD";

    unordered_map<string, int> trackerNameIndexMap;
    trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
    trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
    trackerNameIndexMap["RELEASE_ALL"] = 2;

    SimpleConditionTracker conditionTracker(kConfigKey, conditionName,
                                            0 /*condition tracker index*/, simplePredicate,
                                            trackerNameIndexMap);

    std::vector<int> uid_list1 = {111, 1111, 11111};
    string uid1_wl1 = "wl1_1";
    std::vector<int> uid_list2 = {222, 2222, 22222};
    string uid2_wl1 = "wl2_1";

    LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event, uid_list1, uid1_wl1, 1);

    // one matched start for uid1
    vector<MatchingState> matcherState;
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    vector<sp<ConditionTracker>> allPredicates;
    vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
    vector<bool> changedCache(1, false);

    conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                       changedCache);

    EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // Now test query
    ConditionKey queryKey;
    conditionCache[0] = ConditionState::kNotEvaluated;

    conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
    EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

    // another wake lock acquired by this uid
    LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event2, uid_list2, uid2_wl1, 1);
    matcherState.clear();
    matcherState.push_back(MatchingState::kMatched);
    matcherState.push_back(MatchingState::kNotMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event2, matcherState, allPredicates, conditionCache,
                                       changedCache);
    EXPECT_FALSE(changedCache[0]);

    // uid1 wake lock 1 release
    LogEvent event3(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event3, uid_list1, uid1_wl1, 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event3, matcherState, allPredicates, conditionCache,
                                       changedCache);
    // nothing changes, because uid2 is still holding wl.
    EXPECT_FALSE(changedCache[0]);

    LogEvent event4(1 /*tagId*/, 0 /*timestamp*/);
    makeWakeLockEvent(&event4, uid_list2, uid2_wl1, 0);  // now release it.
    matcherState.clear();
    matcherState.push_back(MatchingState::kNotMatched);
    matcherState.push_back(MatchingState::kMatched);
    conditionCache[0] = ConditionState::kNotEvaluated;
    changedCache[0] = false;
    conditionTracker.evaluateCondition(event4, matcherState, allPredicates, conditionCache,
                                       changedCache);
    EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());
    EXPECT_TRUE(changedCache[0]);

    // query again
    conditionCache[0] = ConditionState::kNotEvaluated;
    conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
    EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
}

TEST(SimpleConditionTrackerTest, TestStopAll) {
    for (Position position :
            {Position::ANY, Position::FIRST, Position::LAST}) {
        SimplePredicate simplePredicate = getWakeLockHeldCondition(
                true /*nesting*/, true /*default to false*/, true /*output slice by uid*/,
                position);
        string conditionName = "WL_HELD_BY_UID3";

        unordered_map<string, int> trackerNameIndexMap;
        trackerNameIndexMap["WAKE_LOCK_ACQUIRE"] = 0;
        trackerNameIndexMap["WAKE_LOCK_RELEASE"] = 1;
        trackerNameIndexMap["RELEASE_ALL"] = 2;

        SimpleConditionTracker conditionTracker(kConfigKey, conditionName,
                                                0 /*condition tracker index*/, simplePredicate,
                                                trackerNameIndexMap);

        std::vector<int> uid_list1 = {111, 1111, 11111};
        std::vector<int> uid_list2 = {222, 2222, 22222};

        LogEvent event(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event, uid_list1, "wl1", 1);

        // one matched start
        vector<MatchingState> matcherState;
        matcherState.push_back(MatchingState::kMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        vector<sp<ConditionTracker>> allPredicates;
        vector<ConditionState> conditionCache(1, ConditionState::kNotEvaluated);
        vector<bool> changedCache(1, false);

        conditionTracker.evaluateCondition(event, matcherState, allPredicates, conditionCache,
                                           changedCache);
        if (position == Position::FIRST ||
            position == Position::LAST) {
            EXPECT_EQ(1UL, conditionTracker.mSlicedConditionState.size());
        } else {
            EXPECT_EQ(uid_list1.size(), conditionTracker.mSlicedConditionState.size());
        }
        EXPECT_TRUE(changedCache[0]);

        // Now test query
        const auto queryKey = getWakeLockQueryKey(position, uid_list1, conditionName);
        conditionCache[0] = ConditionState::kNotEvaluated;

        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);

        // another wake lock acquired by uid2
        LogEvent event2(1 /*tagId*/, 0 /*timestamp*/);
        makeWakeLockEvent(&event2, uid_list2, "wl2", 1);
        matcherState.clear();
        matcherState.push_back(MatchingState::kMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        conditionCache[0] = ConditionState::kNotEvaluated;
        changedCache[0] = false;
        conditionTracker.evaluateCondition(event2, matcherState, allPredicates, conditionCache,
                                           changedCache);
        if (position == Position::FIRST ||
            position == Position::LAST) {
            EXPECT_EQ(2UL, conditionTracker.mSlicedConditionState.size());
        } else {
            EXPECT_EQ(uid_list1.size() + uid_list2.size(),
                      conditionTracker.mSlicedConditionState.size());
        }
        EXPECT_TRUE(changedCache[0]);

        // TEST QUERY
        const auto queryKey2 = getWakeLockQueryKey(position, uid_list2, conditionName);
        conditionCache[0] = ConditionState::kNotEvaluated;

        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kTrue, conditionCache[0]);


        // stop all event
        LogEvent event3(2 /*tagId*/, 0 /*timestamp*/);
        matcherState.clear();
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kNotMatched);
        matcherState.push_back(MatchingState::kMatched);

        conditionCache[0] = ConditionState::kNotEvaluated;
        changedCache[0] = false;
        conditionTracker.evaluateCondition(event3, matcherState, allPredicates, conditionCache,
                                           changedCache);
        EXPECT_TRUE(changedCache[0]);
        EXPECT_EQ(0UL, conditionTracker.mSlicedConditionState.size());

        // TEST QUERY
        const auto queryKey3 = getWakeLockQueryKey(position, uid_list1, conditionName);
        conditionCache[0] = ConditionState::kNotEvaluated;

        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);

        // TEST QUERY
        const auto queryKey4 = getWakeLockQueryKey(position, uid_list2, conditionName);
        conditionCache[0] = ConditionState::kNotEvaluated;

        conditionTracker.isConditionMet(queryKey, allPredicates, conditionCache);
        EXPECT_EQ(ConditionState::kFalse, conditionCache[0]);
    }

}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
