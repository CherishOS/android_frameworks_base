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

#define LOG_TAG "radio.RadioService.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_RadioService.h"

#include "com_android_server_radio_Tuner.h"
#include "com_android_server_radio_convert.h"

#include <android/hardware/broadcastradio/1.1/IBroadcastRadio.h>
#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace RadioService {

using hardware::Return;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

using V1_0::Class;
using V1_0::Result;

using V1_0::BandConfig;
using V1_0::ProgramInfo;
using V1_0::MetaData;
using V1_0::ITuner;

static Mutex gContextMutex;

static struct {
    struct {
        jclass clazz;
        jmethodID cstor;
    } Tuner;
} gjni;

struct ServiceContext {
    ServiceContext() {}

    sp<V1_0::IBroadcastRadio> mModule;

private:
    DISALLOW_COPY_AND_ASSIGN(ServiceContext);
};


/**
 * Always lock gContextMutex when using native context.
 */
static ServiceContext& getNativeContext(jlong nativeContextHandle) {
    auto nativeContext = reinterpret_cast<ServiceContext*>(nativeContextHandle);
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

static jlong nativeInit(JNIEnv *env, jobject obj) {
    ALOGV("nativeInit()");
    AutoMutex _l(gContextMutex);

    auto nativeContext = new ServiceContext();
    static_assert(sizeof(jlong) >= sizeof(nativeContext), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(nativeContext);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeFinalize()");
    AutoMutex _l(gContextMutex);

    auto ctx = reinterpret_cast<ServiceContext*>(nativeContext);
    delete ctx;
}

static sp<V1_0::IBroadcastRadio> getModule(jlong nativeContext) {
    ALOGV("getModule()");
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (ctx.mModule != nullptr) {
        return ctx.mModule;
    }

    // TODO(b/36863239): what about other HAL implementations?
    auto factory = V1_0::IBroadcastRadioFactory::getService();
    if (factory == nullptr) {
        ALOGE("Can't retrieve radio HAL implementation");
        return nullptr;
    }

    sp<V1_0::IBroadcastRadio> module = nullptr;
    // TODO(b/36863239): not only AM/FM
    factory->connectModule(Class::AM_FM, [&](Result retval,
            const sp<V1_0::IBroadcastRadio>& result) {
        if (retval == Result::OK) {
            module = result;
        }
    });

    ALOGE_IF(module == nullptr, "Couldn't connect module");
    ctx.mModule = module;
    return module;
}

static jobject nativeOpenTuner(JNIEnv *env, jobject obj, long nativeContext, jint moduleId,
        jobject bandConfig, bool withAudio, jobject callback) {
    ALOGV("nativeOpenTuner()");
    EnvWrapper wrap(env);

    if (callback == nullptr) {
        ALOGE("Callback is empty");
        return nullptr;
    }

    // TODO(b/36863239): use moduleId
    auto module = getModule(nativeContext);
    if (module == nullptr) {
        return nullptr;
    }

    HalRevision halRev;
    if (V1_1::IBroadcastRadio::castFrom(module).withDefault(nullptr) != nullptr) {
        ALOGI("Opening tuner with broadcast radio HAL 1.1");
        halRev = HalRevision::V1_1;
    } else {
        ALOGI("Opening tuner with broadcast radio HAL 1.0");
        halRev = HalRevision::V1_0;
    }

    Region region;
    BandConfig bandConfigHal = convert::BandConfigToHal(env, bandConfig, region);

    auto tuner = wrap(env->NewObject(gjni.Tuner.clazz, gjni.Tuner.cstor,
            callback, halRev, region, withAudio));
    if (tuner == nullptr) {
        ALOGE("Unable to create new tuner object.");
        return nullptr;
    }

    auto tunerCb = Tuner::getNativeCallback(env, tuner);
    Result halResult;
    sp<ITuner> halTuner = nullptr;

    auto hidlResult = module->openTuner(bandConfigHal, withAudio, tunerCb,
            [&](Result result, const sp<ITuner>& tuner) {
                halResult = result;
                halTuner = tuner;
            });
    if (!hidlResult.isOk() || halResult != Result::OK || halTuner == nullptr) {
        ALOGE("Couldn't open tuner");
        ALOGE_IF(hidlResult.isOk(), "halResult = %d", halResult);
        ALOGE_IF(!hidlResult.isOk(), "hidlResult = %s", hidlResult.description().c_str());
        return nullptr;
    }

    Tuner::setHalTuner(env, tuner, halTuner);
    ALOGI("Opened tuner %p", halTuner.get());
    return tuner.release();
}

static const JNINativeMethod gRadioServiceMethods[] = {
    { "nativeInit", "()J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeOpenTuner", "(JILandroid/hardware/radio/RadioManager$BandConfig;Z"
            "Landroid/hardware/radio/ITunerCallback;)Lcom/android/server/radio/Tuner;",
            (void*)nativeOpenTuner },
};

} // namespace RadioService
} // namespace radio
} // namespace server

void register_android_server_radio_RadioService(JNIEnv *env) {
    using namespace server::radio::RadioService;

    register_android_server_radio_convert(env);

    auto tunerClass = FindClassOrDie(env, "com/android/server/radio/Tuner");
    gjni.Tuner.clazz = MakeGlobalRefOrDie(env, tunerClass);
    gjni.Tuner.cstor = GetMethodIDOrDie(env, tunerClass, "<init>",
            "(Landroid/hardware/radio/ITunerCallback;IIZ)V");

    auto res = jniRegisterNativeMethods(env, "com/android/server/radio/RadioService",
            gRadioServiceMethods, NELEM(gRadioServiceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
