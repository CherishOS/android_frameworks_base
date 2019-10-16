/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include <gtest/gtest_prod.h>
#include <inttypes.h>
#include <utils/RefBase.h>

#include "HashableDimensionKey.h"
#include "state/StateListener.h"
#include "state/StateTracker.h"

namespace android {
namespace os {
namespace statsd {

class StateManager : public virtual RefBase {
public:
    StateManager(){};

    ~StateManager(){};

    // Returns a pointer to the single, shared StateManager object.
    static StateManager& getInstance();

    // Notifies the correct StateTracker of an event.
    void onLogEvent(const LogEvent& event);

    // Returns true if atomId is being tracked and is associated with a state
    // atom. StateManager notifies the correct StateTracker to register listener.
    // If the correct StateTracker does not exist, a new StateTracker is created.
    bool registerListener(int atomId, wp<StateListener> listener);

    // Notifies the correct StateTracker to unregister a listener
    // and removes the tracker if it no longer has any listeners.
    void unregisterListener(int atomId, wp<StateListener> listener);

    // Queries the correct StateTracker for the original/un-mapped state value
    // that is mapped to the given query key.
    // If the StateTracker doesn't exist, returns StateTracker::kStateUnknown.
    int getStateValue(int atomId, const HashableDimensionKey& queryKey);

    inline int getStateTrackersCount() {
        std::lock_guard<std::mutex> lock(mMutex);
        return mStateTrackers.size();
    }

    inline int getListenersCount(int atomId) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mStateTrackers.find(atomId) != mStateTrackers.end()) {
            return mStateTrackers[atomId]->getListenersCount();
        }
        return -1;
    }

private:
  mutable std::mutex mMutex;

  // Maps state atom ids to StateTrackers
  std::unordered_map<int, sp<StateTracker>> mStateTrackers;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
