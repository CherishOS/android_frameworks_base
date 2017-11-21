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

#ifndef ORING_DURATION_TRACKER_H
#define ORING_DURATION_TRACKER_H

#include "DurationTracker.h"

#include <set>
namespace android {
namespace os {
namespace statsd {

// Tracks the "Or'd" duration -- if 2 durations are overlapping, they won't be double counted.
class OringDurationTracker : public DurationTracker {
public:
    OringDurationTracker(const HashableDimensionKey& eventKey, sp<ConditionWizard> wizard,
                         int conditionIndex, bool nesting, uint64_t currentBucketStartNs,
                         uint64_t bucketSizeNs,
                         const std::vector<sp<AnomalyTracker>>& anomalyTrackers,
                         std::vector<DurationBucket>& bucket);
    void noteStart(const HashableDimensionKey& key, bool condition, const uint64_t eventTime,
                   const ConditionKey& conditionKey) override;
    void noteStop(const HashableDimensionKey& key, const uint64_t eventTime,
                  const bool stopAll) override;
    void noteStopAll(const uint64_t eventTime) override;
    void onSlicedConditionMayChange(const uint64_t timestamp) override;
    void onConditionChanged(bool condition, const uint64_t timestamp) override;
    bool flushIfNeeded(uint64_t timestampNs) override;

    int64_t predictAnomalyTimestampNs(const AnomalyTracker& anomalyTracker,
                                      const uint64_t currentTimestamp) const override;

private:
    // We don't need to keep track of individual durations. The information that's needed is:
    // 1) which keys are started. We record the first start time.
    // 2) which keys are paused (started but condition was false)
    // 3) whenever a key stops, we remove it from the started set. And if the set becomes empty,
    //    it means everything has stopped, we then record the end time.
    std::map<HashableDimensionKey, int> mStarted;
    std::map<HashableDimensionKey, int> mPaused;
    int64_t mLastStartTime;
    std::map<HashableDimensionKey, ConditionKey> mConditionKeyMap;

    FRIEND_TEST(OringDurationTrackerTest, TestDurationOverlap);
    FRIEND_TEST(OringDurationTrackerTest, TestCrossBucketBoundary);
    FRIEND_TEST(OringDurationTrackerTest, TestDurationConditionChange);
    FRIEND_TEST(OringDurationTrackerTest, TestPredictAnomalyTimestamp);
    FRIEND_TEST(OringDurationTrackerTest, TestAnomalyDetection);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // ORING_DURATION_TRACKER_H
