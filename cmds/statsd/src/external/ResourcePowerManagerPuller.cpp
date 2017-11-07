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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include <android/hardware/power/1.0/IPower.h>
#include <android/hardware/power/1.1/IPower.h>
#include <fcntl.h>
#include <hardware/power.h>
#include <hardware_legacy/power.h>
#include <inttypes.h>
#include <semaphore.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include "external/ResourcePowerManagerPuller.h"
#include "external/StatsPuller.h"

#include "logd/LogEvent.h"

using android::hardware::hidl_vec;
using android::hardware::power::V1_0::IPower;
using android::hardware::power::V1_0::PowerStatePlatformSleepState;
using android::hardware::power::V1_0::PowerStateVoter;
using android::hardware::power::V1_0::Status;
using android::hardware::power::V1_1::PowerStateSubsystem;
using android::hardware::power::V1_1::PowerStateSubsystemSleepState;
using android::hardware::Return;
using android::hardware::Void;

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

sp<android::hardware::power::V1_0::IPower> gPowerHalV1_0 = nullptr;
sp<android::hardware::power::V1_1::IPower> gPowerHalV1_1 = nullptr;
std::mutex gPowerHalMutex;
bool gPowerHalExists = true;

static const int power_state_platform_sleep_state_tag = 1011;
static const int power_state_voter_tag = 1012;
static const int power_state_subsystem_state_tag = 1013;

bool getPowerHal() {
    if (gPowerHalExists && gPowerHalV1_0 == nullptr) {
        gPowerHalV1_0 = android::hardware::power::V1_0::IPower::getService();
        if (gPowerHalV1_0 != nullptr) {
            gPowerHalV1_1 = android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);
            ALOGI("Loaded power HAL service");
        } else {
            ALOGW("Couldn't load power HAL service");
            gPowerHalExists = false;
        }
    }
    return gPowerHalV1_0 != nullptr;
}

bool ResourcePowerManagerPuller::Pull(const int tagId, vector<shared_ptr<LogEvent>>* data) {
    std::lock_guard<std::mutex> lock(gPowerHalMutex);

    if (!getPowerHal()) {
        ALOGE("Power Hal not loaded");
        return false;
    }

    uint64_t timestamp = time(nullptr) * NS_PER_SEC;

    data->clear();
    Return<void> ret = gPowerHalV1_0->getPlatformLowPowerStats(
            [&data, timestamp](hidl_vec<PowerStatePlatformSleepState> states, Status status) {

                if (status != Status::SUCCESS) return;

                for (size_t i = 0; i < states.size(); i++) {
                    const PowerStatePlatformSleepState& state = states[i];

                    auto statePtr =
                            make_shared<LogEvent>(power_state_platform_sleep_state_tag, timestamp);
                    auto elemList = statePtr->GetAndroidLogEventList();
                    *elemList << state.name;
                    *elemList << state.residencyInMsecSinceBoot;
                    *elemList << state.totalTransitions;
                    *elemList << state.supportedOnlyInSuspend;
                    statePtr->init();
                    data->push_back(statePtr);
                    VLOG("powerstate: %s, %lld, %lld, %d", state.name.c_str(),
                         (long long)state.residencyInMsecSinceBoot,
                         (long long)state.totalTransitions, state.supportedOnlyInSuspend ? 1 : 0);
                    for (auto voter : state.voters) {
                        auto voterPtr = make_shared<LogEvent>(power_state_voter_tag, timestamp);
                        auto elemList = voterPtr->GetAndroidLogEventList();
                        *elemList << state.name;
                        *elemList << voter.name;
                        *elemList << voter.totalTimeInMsecVotedForSinceBoot;
                        *elemList << voter.totalNumberOfTimesVotedSinceBoot;
                        data->push_back(voterPtr);
                        VLOG("powerstatevoter: %s, %s, %lld, %lld", state.name.c_str(),
                             voter.name.c_str(), (long long)voter.totalTimeInMsecVotedForSinceBoot,
                             (long long)voter.totalNumberOfTimesVotedSinceBoot);
                    }
                }
            });
    if (!ret.isOk()) {
        ALOGE("getLowPowerStats() failed: power HAL service not available");
        gPowerHalV1_0 = nullptr;
        return false;
    }

    // Trying to cast to IPower 1.1, this will succeed only for devices supporting 1.1
    sp<android::hardware::power::V1_1::IPower> gPowerHal_1_1 =
            android::hardware::power::V1_1::IPower::castFrom(gPowerHalV1_0);
    if (gPowerHal_1_1 != nullptr) {
        ret = gPowerHal_1_1->getSubsystemLowPowerStats(
                [&data, timestamp](hidl_vec<PowerStateSubsystem> subsystems, Status status) {

                    if (status != Status::SUCCESS) return;

                    if (subsystems.size() > 0) {
                        for (size_t i = 0; i < subsystems.size(); i++) {
                            const PowerStateSubsystem& subsystem = subsystems[i];
                            for (size_t j = 0; j < subsystem.states.size(); j++) {
                                const PowerStateSubsystemSleepState& state = subsystem.states[j];
                                auto subsystemStatePtr = make_shared<LogEvent>(
                                        power_state_subsystem_state_tag, timestamp);
                                auto elemList = subsystemStatePtr->GetAndroidLogEventList();
                                *elemList << subsystem.name;
                                *elemList << state.name;
                                *elemList << state.residencyInMsecSinceBoot;
                                *elemList << state.totalTransitions;
                                *elemList << state.lastEntryTimestampMs;
                                *elemList << state.supportedOnlyInSuspend;
                                subsystemStatePtr->init();
                                data->push_back(subsystemStatePtr);
                                VLOG("subsystemstate: %s, %s, %lld, %lld, %lld",
                                     subsystem.name.c_str(), state.name.c_str(),
                                     (long long)state.residencyInMsecSinceBoot,
                                     (long long)state.totalTransitions,
                                     (long long)state.lastEntryTimestampMs);
                            }
                        }
                    }
                });
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
