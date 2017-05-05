/**
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

#define LOG_TAG "radio.TunerCallback.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_Tuner_TunerCallback.h"

#include "com_android_server_radio_convert.h"
#include "com_android_server_radio_Tuner.h"

#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace Tuner {

using hardware::Return;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

using V1_0::BandConfig;
using V1_0::MetaData;
using V1_0::Result;
using V1_1::ITunerCallback;
using V1_1::ProgramListResult;

static JavaVM *gvm = nullptr;

static jclass gITunerCallbackClass;
static struct {
    jmethodID onError;
    jmethodID onConfigurationChanged;
    jmethodID onProgramInfoChanged;
} gITunerCallbackMethods;

// from frameworks/base/core/java/android/hardware/radio/RadioTuner.java
enum class TunerError : jint {
    HARDWARE_FAILURE = 0,
    SERVER_DIED = 1,
    CANCELLED = 2,
    SCAN_TIMEOUT = 3,
    CONFIG = 4,
};

TunerCallback::TunerCallback(JNIEnv *env, jobject tuner, jobject clientCallback, HalRevision halRev)
        : mCallbackThread(gvm), mHalRev(halRev) {
    ALOGV("TunerCallback()");
    mTuner = env->NewGlobalRef(tuner);
    mClientCallback = env->NewGlobalRef(clientCallback);
}

TunerCallback::~TunerCallback() {
    ALOGV("~TunerCallback()");

    // stop callback thread before dereferencing client callback
    mCallbackThread.stop();

    JNIEnv *env = nullptr;
    gvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4);
    if (env != nullptr) {
        env->DeleteGlobalRef(mTuner);
        env->DeleteGlobalRef(mClientCallback);
    }
}

void TunerCallback::detach() {
    // stop callback thread to ignore further calls
    mCallbackThread.stop();
}

Return<void> TunerCallback::hardwareFailure() {
    ALOGE("Not implemented: hardwareFailure");
    return Return<void>();
}

Return<void> TunerCallback::configChange(Result result, const BandConfig& config) {
    ALOGV("configChange(%d)", result);

    mCallbackThread.enqueue([result, config, this](JNIEnv *env) {
        if (result == Result::OK) {
            auto region = getRegion(env, mTuner);
            auto jConfig = convert::BandConfigFromHal(env, config, region);
            if (jConfig == nullptr) return;
            env->CallVoidMethod(mClientCallback, gITunerCallbackMethods.onConfigurationChanged,
                    jConfig.get());
        } else {
            env->CallVoidMethod(mClientCallback, gITunerCallbackMethods.onError,
                    TunerError::CONFIG);
        }
    });

    return Return<void>();
}

Return<void> TunerCallback::tuneComplete(Result result, const V1_0::ProgramInfo& info) {
    ALOGV("tuneComplete(%d)", result);

    if (mHalRev > HalRevision::V1_0) {
        ALOGD("1.0 callback was ignored");
        return Return<void>();
    }

    V1_1::ProgramInfo info_1_1 {
        .base = info,
    };
    return tuneComplete_1_1(result, info_1_1);
}

Return<void> TunerCallback::tuneComplete_1_1(Result result, const V1_1::ProgramInfo& info) {
    ALOGV("tuneComplete_1_1(%d)", result);

    mCallbackThread.enqueue([result, info, this](JNIEnv *env) {
        if (result == Result::OK) {
            auto jInfo = convert::ProgramInfoFromHal(env, info);
            if (jInfo == nullptr) return;
            env->CallVoidMethod(mClientCallback, gITunerCallbackMethods.onProgramInfoChanged,
                    jInfo.get());
        } else {
            TunerError cause = TunerError::CANCELLED;
            if (result == Result::TIMEOUT) cause = TunerError::SCAN_TIMEOUT;
            env->CallVoidMethod(mClientCallback, gITunerCallbackMethods.onError, cause);
        }
    });

    return Return<void>();
}

Return<void> TunerCallback::afSwitch(const V1_0::ProgramInfo& info) {
    ALOGE("Not implemented: afSwitch");
    return Return<void>();
}

Return<void> TunerCallback::afSwitch_1_1(const V1_1::ProgramInfo& info) {
    ALOGE("Not implemented: afSwitch_1_1");
    return Return<void>();
}

Return<void> TunerCallback::antennaStateChange(bool connected) {
    ALOGE("Not implemented: antennaStateChange");
    return Return<void>();
}

Return<void> TunerCallback::trafficAnnouncement(bool active) {
    ALOGE("Not implemented: trafficAnnouncement");
    return Return<void>();
}

Return<void> TunerCallback::emergencyAnnouncement(bool active) {
    ALOGE("Not implemented: emergencyAnnouncement");
    return Return<void>();
}

Return<void> TunerCallback::newMetadata(uint32_t channel, uint32_t subChannel,
        const hidl_vec<MetaData>& metadata) {
    ALOGE("Not implemented: newMetadata");
    return Return<void>();
}

Return<void> TunerCallback::backgroundScanAvailable(bool isAvailable) {
    ALOGE("Not implemented: backgroundScanAvailable");
    return Return<void>();
}

Return<void> TunerCallback::backgroundScanComplete(ProgramListResult result) {
    ALOGE("Not implemented: backgroundScanComplete");
    return Return<void>();
}

Return<void> TunerCallback::programListChanged() {
    ALOGE("Not implemented: programListChanged");
    return Return<void>();
}

} // namespace Tuner
} // namespace radio
} // namespace server

void register_android_server_radio_Tuner_TunerCallback(JavaVM *vm, JNIEnv *env) {
    using namespace server::radio::Tuner;

    gvm = vm;

    auto iTunerCallbackClass = FindClassOrDie(env, "android/hardware/radio/ITunerCallback");
    gITunerCallbackClass = MakeGlobalRefOrDie(env, iTunerCallbackClass);
    gITunerCallbackMethods.onError = GetMethodIDOrDie(env, gITunerCallbackClass, "onError", "(I)V");
    gITunerCallbackMethods.onConfigurationChanged = GetMethodIDOrDie(env, gITunerCallbackClass,
            "onConfigurationChanged", "(Landroid/hardware/radio/RadioManager$BandConfig;)V");
    gITunerCallbackMethods.onProgramInfoChanged = GetMethodIDOrDie(env, gITunerCallbackClass,
            "onProgramInfoChanged", "(Landroid/hardware/radio/RadioManager$ProgramInfo;)V");
}

} // namespace android
