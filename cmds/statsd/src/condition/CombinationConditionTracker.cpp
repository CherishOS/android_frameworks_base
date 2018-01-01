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
#include "CombinationConditionTracker.h"

#include <log/logprint.h>

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

CombinationConditionTracker::CombinationConditionTracker(const string& name, const int index)
    : ConditionTracker(name, index) {
    VLOG("creating CombinationConditionTracker %s", mName.c_str());
}

CombinationConditionTracker::~CombinationConditionTracker() {
    VLOG("~CombinationConditionTracker() %s", mName.c_str());
}

bool CombinationConditionTracker::init(const vector<Predicate>& allConditionConfig,
                                       const vector<sp<ConditionTracker>>& allConditionTrackers,
                                       const unordered_map<string, int>& conditionNameIndexMap,
                                       vector<bool>& stack) {
    VLOG("Combination predicate init() %s", mName.c_str());
    if (mInitialized) {
        return true;
    }

    // mark this node as visited in the recursion stack.
    stack[mIndex] = true;

    Predicate_Combination combinationCondition = allConditionConfig[mIndex].combination();

    if (!combinationCondition.has_operation()) {
        return false;
    }
    mLogicalOperation = combinationCondition.operation();

    if (mLogicalOperation == LogicalOperation::NOT && combinationCondition.predicate_size() != 1) {
        return false;
    }

    for (string child : combinationCondition.predicate()) {
        auto it = conditionNameIndexMap.find(child);

        if (it == conditionNameIndexMap.end()) {
            ALOGW("Predicate %s not found in the config", child.c_str());
            return false;
        }

        int childIndex = it->second;
        const auto& childTracker = allConditionTrackers[childIndex];
        // if the child is a visited node in the recursion -> circle detected.
        if (stack[childIndex]) {
            ALOGW("Circle detected!!!");
            return false;
        }

        bool initChildSucceeded = childTracker->init(allConditionConfig, allConditionTrackers,
                                                     conditionNameIndexMap, stack);

        if (!initChildSucceeded) {
            ALOGW("Child initialization failed %s ", child.c_str());
            return false;
        } else {
            ALOGW("Child initialization success %s ", child.c_str());
        }

        mChildren.push_back(childIndex);

        mTrackerIndex.insert(childTracker->getLogTrackerIndex().begin(),
                             childTracker->getLogTrackerIndex().end());
    }

    // unmark this node in the recursion stack.
    stack[mIndex] = false;

    mInitialized = true;

    return true;
}

void CombinationConditionTracker::isConditionMet(
        const ConditionKey& conditionParameters,
        const vector<sp<ConditionTracker>>& allConditions,
        vector<ConditionState>& conditionCache) const {
    for (const int childIndex : mChildren) {
        if (conditionCache[childIndex] == ConditionState::kNotEvaluated) {
            allConditions[childIndex]->isConditionMet(conditionParameters, allConditions,
                                                      conditionCache);
        }
    }
    conditionCache[mIndex] =
            evaluateCombinationCondition(mChildren, mLogicalOperation, conditionCache);
}

void CombinationConditionTracker::evaluateCondition(
        const LogEvent& event, const std::vector<MatchingState>& eventMatcherValues,
        const std::vector<sp<ConditionTracker>>& mAllConditions,
        std::vector<ConditionState>& nonSlicedConditionCache,
        std::vector<bool>& conditionChangedCache) {
    // value is up to date.
    if (nonSlicedConditionCache[mIndex] != ConditionState::kNotEvaluated) {
        return;
    }

    for (const int childIndex : mChildren) {
        if (nonSlicedConditionCache[childIndex] == ConditionState::kNotEvaluated) {
            const sp<ConditionTracker>& child = mAllConditions[childIndex];
            child->evaluateCondition(event, eventMatcherValues, mAllConditions,
                                     nonSlicedConditionCache, conditionChangedCache);
        }
    }

    if (!mSliced) {
        ConditionState newCondition =
                evaluateCombinationCondition(mChildren, mLogicalOperation, nonSlicedConditionCache);

        bool nonSlicedChanged = (mNonSlicedConditionState != newCondition);
        mNonSlicedConditionState = newCondition;

        nonSlicedConditionCache[mIndex] = mNonSlicedConditionState;

        conditionChangedCache[mIndex] = nonSlicedChanged;
    } else {
        for (const int childIndex : mChildren) {
            // If any of the sliced condition in children condition changes, the combination
            // condition may be changed too.
            if (conditionChangedCache[childIndex]) {
                conditionChangedCache[mIndex] = true;
                break;
            }
        }
        nonSlicedConditionCache[mIndex] = ConditionState::kUnknown;
        ALOGD("CombinationPredicate %s sliced may changed? %d", mName.c_str(),
              conditionChangedCache[mIndex] == true);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
