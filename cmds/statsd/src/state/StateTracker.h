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

#include <atoms_info.h>
#include <utils/RefBase.h>
#include "HashableDimensionKey.h"
#include "logd/LogEvent.h"

#include "state/StateListener.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StateTracker : public virtual RefBase {
public:
    StateTracker(const int32_t atomId, const android::util::StateAtomFieldOptions& stateAtomInfo);

    virtual ~StateTracker(){};

    // Updates state map and notifies all listeners if a state change occurs.
    // Checks if a state change has occurred by getting the state value from
    // the log event and comparing the old and new states.
    void onLogEvent(const LogEvent& event);

    // Adds new listeners to set of StateListeners. If a listener is already
    // registered, it is ignored.
    void registerListener(wp<StateListener> listener);

    void unregisterListener(wp<StateListener> listener);

    // The output is a FieldValue object that has mStateField as the field and
    // the original state value (found using the given query key) as the value.
    //
    // If the key isn't mapped to a state or the key size doesn't match the
    // number of primary fields, the output value is set to kStateUnknown.
    bool getStateValue(const HashableDimensionKey& queryKey, FieldValue* output) const;

    inline int getListenersCount() const {
        return mListeners.size();
    }

    const static int kStateUnknown = -1;

private:
    struct StateValueInfo {
        int32_t state;  // state value
        int count;      // nested count (only used for binary states)
    };

    const int32_t mAtomId;  // id of the state atom being tracked

    Matcher mStateField;  // matches the atom's exclusive state field

    std::vector<Matcher> mPrimaryFields;  // matches the atom's primary fields

    int32_t mDefaultState = kStateUnknown;

    int32_t mResetState = kStateUnknown;

    const bool mNested;

    // Maps primary key to state value info
    std::unordered_map<HashableDimensionKey, StateValueInfo> mStateMap;

    // Set of all StateListeners (objects listening for state changes)
    std::set<wp<StateListener>> mListeners;

    // Reset all state values in map to default state.
    void handleReset(const int64_t eventTimeNs);

    // Reset only the state value mapped to the given primary key to default state.
    // Partial resets are used when we only need to update the state of one primary
    // key instead of clearing/reseting every key in the map.
    void handlePartialReset(const int64_t eventTimeNs, const HashableDimensionKey& primaryKey);

    // Update the StateMap based on the received state value.
    // Store the old and new states.
    void updateState(const HashableDimensionKey& primaryKey, const int32_t eventState,
                     int32_t* oldState, int32_t* newState);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
