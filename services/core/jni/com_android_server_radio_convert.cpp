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

#define LOG_TAG "radio.convert.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_convert.h"

#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace convert {

using hardware::Return;
using hardware::hidl_vec;

using V1_0::Band;
using V1_0::Deemphasis;
using V1_0::Direction;
using V1_0::MetadataType;
using V1_0::Result;
using V1_0::Rds;
using V1_1::ProgramListResult;

static struct {
    struct {
        jfieldID descriptor;
    } BandConfig;
    struct {
        jclass clazz;
        jmethodID cstor;
        jfieldID stereo;
        jfieldID rds;
        jfieldID ta;
        jfieldID af;
        jfieldID ea;
    } FmBandConfig;
    struct {
        jclass clazz;
        jmethodID cstor;
        jfieldID stereo;
    } AmBandConfig;

    struct {
        jfieldID region;
        jfieldID type;
        jfieldID lowerLimit;
        jfieldID upperLimit;
        jfieldID spacing;
    } BandDescriptor;

    struct {
        jclass clazz;
        jmethodID cstor;
    } ProgramInfo;

    struct {
        jclass clazz;
        jmethodID cstor;
        jmethodID putIntFromNative;
        jmethodID putStringFromNative;
        jmethodID putBitmapFromNative;
        jmethodID putClockFromNative;
    } RadioMetadata;

    struct {
        jclass clazz;
        jmethodID cstor;
    } RuntimeException;

    struct {
        jclass clazz;
        jmethodID cstor;
    } ParcelableException;
} gjni;

bool __ThrowIfFailedHidl(JNIEnv *env, const hardware::details::return_status &hidlResult) {
    if (hidlResult.isOk()) return false;

    ThrowParcelableRuntimeException(env, "HIDL call failed: " + hidlResult.description());
    return true;
}

bool __ThrowIfFailed(JNIEnv *env, const Result halResult) {
    switch (halResult) {
        case Result::OK:
            return false;
        case Result::NOT_INITIALIZED:
            ThrowParcelableRuntimeException(env, "Result::NOT_INITIALIZED");
            return true;
        case Result::INVALID_ARGUMENTS:
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Result::INVALID_ARGUMENTS");
            return true;
        case Result::INVALID_STATE:
            jniThrowException(env, "java/lang/IllegalStateException", "Result::INVALID_STATE");
            return true;
        case Result::TIMEOUT:
            ThrowParcelableRuntimeException(env, "Result::TIMEOUT (unexpected here)");
            return true;
        default:
            ThrowParcelableRuntimeException(env, "Unknown failure, result: "
                    + std::to_string(static_cast<int32_t>(halResult)));
            return true;
    }
}

bool __ThrowIfFailed(JNIEnv *env, const ProgramListResult halResult) {
    switch (halResult) {
        case ProgramListResult::NOT_READY:
            jniThrowException(env, "java/lang/IllegalStateException", "Scan is in progress");
            return true;
        case ProgramListResult::NOT_STARTED:
            jniThrowException(env, "java/lang/IllegalStateException", "Scan has not been started");
            return true;
        case ProgramListResult::UNAVAILABLE:
            ThrowParcelableRuntimeException(env,
                    "ProgramListResult::UNAVAILABLE (unexpected here)");
            return true;
        default:
            return __ThrowIfFailed(env, static_cast<Result>(halResult));
    }
}

void ThrowParcelableRuntimeException(JNIEnv *env, const std::string& msg) {
    EnvWrapper wrap(env);

    auto jMsg = wrap(env->NewStringUTF(msg.c_str()));
    auto runtimeExc = wrap(env->NewObject(gjni.RuntimeException.clazz,
            gjni.RuntimeException.cstor, jMsg.get()));
    auto parcelableExc = wrap(env->NewObject(gjni.ParcelableException.clazz,
            gjni.ParcelableException.cstor, runtimeExc.get()));

    auto res = env->Throw(static_cast<jthrowable>(parcelableExc.get()));
    ALOGE_IF(res != JNI_OK, "Couldn't throw parcelable runtime exception");
}

static Rds RdsForRegion(bool rds, Region region) {
    if (!rds) return Rds::NONE;

    switch(region) {
        case Region::ITU_1:
        case Region::OIRT:
        case Region::JAPAN:
        case Region::KOREA:
            return Rds::WORLD;
        case Region::ITU_2:
            return Rds::US;
        default:
            ALOGE("Unexpected region: %d", region);
            return Rds::NONE;
    }
}

static Deemphasis DeemphasisForRegion(Region region) {
    switch(region) {
        case Region::KOREA:
        case Region::ITU_2:
            return Deemphasis::D75;
        case Region::ITU_1:
        case Region::OIRT:
        case Region::JAPAN:
            return Deemphasis::D50;
        default:
            ALOGE("Unexpected region: %d", region);
            return Deemphasis::D50;
    }
}

JavaRef<jobject> BandConfigFromHal(JNIEnv *env, const V1_0::BandConfig &config, Region region) {
    ALOGV("BandConfigFromHal()");
    EnvWrapper wrap(env);

    jint spacing = config.spacings.size() > 0 ? config.spacings[0] : 0;
    ALOGW_IF(config.spacings.size() == 0, "No channel spacing specified");

    switch (config.type) {
        case Band::FM:
        case Band::FM_HD: {
            auto& fm = config.ext.fm;
            return wrap(env->NewObject(gjni.FmBandConfig.clazz, gjni.FmBandConfig.cstor,
                    region, config.type, config.lowerLimit, config.upperLimit, spacing,
                    fm.stereo, fm.rds != Rds::NONE, fm.ta, fm.af, fm.ea));
        }
        case Band::AM:
        case Band::AM_HD: {
            auto& am = config.ext.am;
            return wrap(env->NewObject(gjni.AmBandConfig.clazz, gjni.AmBandConfig.cstor,
                    region, config.type, config.lowerLimit, config.upperLimit, spacing,
                    am.stereo));
        }
        default:
            ALOGE("Unsupported band type: %d", config.type);
            return nullptr;
    }
}

V1_0::BandConfig BandConfigToHal(JNIEnv *env, jobject jConfig, Region &region) {
    ALOGV("BandConfigToHal()");
    auto jDescriptor = env->GetObjectField(jConfig, gjni.BandConfig.descriptor);
    if (jDescriptor == nullptr) {
        ALOGE("Descriptor is missing");
        return {};
    }

    region = static_cast<Region>(env->GetIntField(jDescriptor, gjni.BandDescriptor.region));

    V1_0::BandConfig config = {};
    config.type = static_cast<Band>(env->GetIntField(jDescriptor, gjni.BandDescriptor.type));
    config.antennaConnected = false;  // just don't set it
    config.lowerLimit = env->GetIntField(jDescriptor, gjni.BandDescriptor.lowerLimit);
    config.upperLimit = env->GetIntField(jDescriptor, gjni.BandDescriptor.upperLimit);
    config.spacings = hidl_vec<uint32_t>({
        static_cast<uint32_t>(env->GetIntField(jDescriptor, gjni.BandDescriptor.spacing))
    });

    if (env->IsInstanceOf(jConfig, gjni.FmBandConfig.clazz)) {
        auto& fm = config.ext.fm;
        fm.deemphasis = DeemphasisForRegion(region);
        fm.stereo = env->GetBooleanField(jConfig, gjni.FmBandConfig.stereo);
        fm.rds = RdsForRegion(env->GetBooleanField(jConfig, gjni.FmBandConfig.rds), region);
        fm.ta = env->GetBooleanField(jConfig, gjni.FmBandConfig.ta);
        fm.af = env->GetBooleanField(jConfig, gjni.FmBandConfig.af);
        fm.ea = env->GetBooleanField(jConfig, gjni.FmBandConfig.ea);
    } else if (env->IsInstanceOf(jConfig, gjni.AmBandConfig.clazz)) {
        auto& am = config.ext.am;
        am.stereo = env->GetBooleanField(jConfig, gjni.AmBandConfig.stereo);
    } else {
        ALOGE("Unexpected band config type");
        return {};
    }

    return config;
}

Direction DirectionToHal(bool directionDown) {
    return directionDown ? Direction::DOWN : Direction::UP;
}

static JavaRef<jobject> MetadataFromHal(JNIEnv *env, const hidl_vec<V1_0::MetaData> metadata) {
    ALOGV("MetadataFromHal()");
    EnvWrapper wrap(env);

    if (metadata.size() == 0) return nullptr;

    auto jMetadata = wrap(env->NewObject(gjni.RadioMetadata.clazz, gjni.RadioMetadata.cstor));

    for (auto& item : metadata) {
        jint key = static_cast<jint>(item.key);
        jint status = 0;
        switch (item.type) {
            case MetadataType::INT:
                ALOGV("metadata INT %d", key);
                status = env->CallIntMethod(jMetadata.get(), gjni.RadioMetadata.putIntFromNative,
                        key, item.intValue);
                break;
            case MetadataType::TEXT: {
                ALOGV("metadata TEXT %d", key);
                auto value = wrap(env->NewStringUTF(item.stringValue.c_str()));
                status = env->CallIntMethod(jMetadata.get(), gjni.RadioMetadata.putStringFromNative,
                        key, value.get());
                break;
            }
            case MetadataType::RAW: {
                ALOGV("metadata RAW %d", key);
                auto len = item.rawValue.size();
                if (len == 0) break;
                auto value = wrap(env->NewByteArray(len));
                if (value == nullptr) {
                    ALOGE("Failed to allocate byte array of len %zu", len);
                    break;
                }
                env->SetByteArrayRegion(value.get(), 0, len,
                        reinterpret_cast<const jbyte*>(item.rawValue.data()));
                status = env->CallIntMethod(jMetadata.get(), gjni.RadioMetadata.putBitmapFromNative,
                        key, value.get());
                break;
            }
            case MetadataType::CLOCK:
                ALOGV("metadata CLOCK %d", key);
                status = env->CallIntMethod(jMetadata.get(), gjni.RadioMetadata.putClockFromNative,
                        key, item.clockValue.utcSecondsSinceEpoch,
                        item.clockValue.timezoneOffsetInMinutes);
                break;
            default:
                ALOGW("invalid metadata type %d", item.type);
        }
        ALOGE_IF(status != 0, "Failed inserting metadata %d (of type %d)", key, item.type);
    }

    return jMetadata;
}

static JavaRef<jobject> ProgramInfoFromHal(JNIEnv *env, const V1_0::ProgramInfo &info10,
        const V1_1::ProgramInfo *info11) {
    ALOGV("ProgramInfoFromHal()");
    EnvWrapper wrap(env);

    auto jMetadata = MetadataFromHal(env, info10.metadata);
    auto jVendorExtension = info11 ?
            wrap(env->NewStringUTF(info11->vendorExension.c_str())) : nullptr;

    return wrap(env->NewObject(gjni.ProgramInfo.clazz, gjni.ProgramInfo.cstor, info10.channel,
            info10.subChannel, info10.tuned, info10.stereo, info10.digital, info10.signalStrength,
            jMetadata.get(), info11 ? info11->flags : 0, jVendorExtension.get()));
}

JavaRef<jobject> ProgramInfoFromHal(JNIEnv *env, const V1_0::ProgramInfo &info) {
    return ProgramInfoFromHal(env, info, nullptr);
}

JavaRef<jobject> ProgramInfoFromHal(JNIEnv *env, const V1_1::ProgramInfo &info) {
    return ProgramInfoFromHal(env, info.base, &info);
}

} // namespace convert
} // namespace radio
} // namespace server

void register_android_server_radio_convert(JNIEnv *env) {
    using namespace server::radio::convert;

    auto bandConfigClass = FindClassOrDie(env, "android/hardware/radio/RadioManager$BandConfig");
    gjni.BandConfig.descriptor = GetFieldIDOrDie(env, bandConfigClass,
            "mDescriptor", "Landroid/hardware/radio/RadioManager$BandDescriptor;");

    auto fmBandConfigClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$FmBandConfig");
    gjni.FmBandConfig.clazz = MakeGlobalRefOrDie(env, fmBandConfigClass);
    gjni.FmBandConfig.cstor = GetMethodIDOrDie(env, fmBandConfigClass,
            "<init>", "(IIIIIZZZZZ)V");
    gjni.FmBandConfig.stereo = GetFieldIDOrDie(env, fmBandConfigClass, "mStereo", "Z");
    gjni.FmBandConfig.rds = GetFieldIDOrDie(env, fmBandConfigClass, "mRds", "Z");
    gjni.FmBandConfig.ta = GetFieldIDOrDie(env, fmBandConfigClass, "mTa", "Z");
    gjni.FmBandConfig.af = GetFieldIDOrDie(env, fmBandConfigClass, "mAf", "Z");
    gjni.FmBandConfig.ea = GetFieldIDOrDie(env, fmBandConfigClass, "mEa", "Z");

    auto amBandConfigClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$AmBandConfig");
    gjni.AmBandConfig.clazz = MakeGlobalRefOrDie(env, amBandConfigClass);
    gjni.AmBandConfig.cstor = GetMethodIDOrDie(env, amBandConfigClass, "<init>", "(IIIIIZ)V");
    gjni.AmBandConfig.stereo = GetFieldIDOrDie(env, amBandConfigClass, "mStereo", "Z");

    auto bandDescriptorClass = FindClassOrDie(env,
            "android/hardware/radio/RadioManager$BandDescriptor");
    gjni.BandDescriptor.region = GetFieldIDOrDie(env, bandDescriptorClass, "mRegion", "I");
    gjni.BandDescriptor.type = GetFieldIDOrDie(env, bandDescriptorClass, "mType", "I");
    gjni.BandDescriptor.lowerLimit = GetFieldIDOrDie(env, bandDescriptorClass, "mLowerLimit", "I");
    gjni.BandDescriptor.upperLimit = GetFieldIDOrDie(env, bandDescriptorClass, "mUpperLimit", "I");
    gjni.BandDescriptor.spacing = GetFieldIDOrDie(env, bandDescriptorClass, "mSpacing", "I");

    auto programInfoClass = FindClassOrDie(env, "android/hardware/radio/RadioManager$ProgramInfo");
    gjni.ProgramInfo.clazz = MakeGlobalRefOrDie(env, programInfoClass);
    gjni.ProgramInfo.cstor = GetMethodIDOrDie(env, programInfoClass, "<init>",
            "(IIZZZILandroid/hardware/radio/RadioMetadata;ILjava/lang/String;)V");

    auto radioMetadataClass = FindClassOrDie(env, "android/hardware/radio/RadioMetadata");
    gjni.RadioMetadata.clazz = MakeGlobalRefOrDie(env, radioMetadataClass);
    gjni.RadioMetadata.cstor = GetMethodIDOrDie(env, radioMetadataClass, "<init>", "()V");
    gjni.RadioMetadata.putIntFromNative = GetMethodIDOrDie(env, radioMetadataClass,
            "putIntFromNative", "(II)I");
    gjni.RadioMetadata.putStringFromNative = GetMethodIDOrDie(env, radioMetadataClass,
            "putStringFromNative", "(ILjava/lang/String;)I");
    gjni.RadioMetadata.putBitmapFromNative = GetMethodIDOrDie(env, radioMetadataClass,
            "putBitmapFromNative", "(I[B)I");
    gjni.RadioMetadata.putClockFromNative = GetMethodIDOrDie(env, radioMetadataClass,
            "putClockFromNative", "(IJI)I");

    auto runtimeExcClass = FindClassOrDie(env, "java/lang/RuntimeException");
    gjni.RuntimeException.clazz = MakeGlobalRefOrDie(env, runtimeExcClass);
    gjni.RuntimeException.cstor = GetMethodIDOrDie(env, runtimeExcClass, "<init>",
            "(Ljava/lang/String;)V");

    auto parcelableExcClass = FindClassOrDie(env, "android/os/ParcelableException");
    gjni.ParcelableException.clazz = MakeGlobalRefOrDie(env, parcelableExcClass);
    gjni.ParcelableException.cstor = GetMethodIDOrDie(env, parcelableExcClass, "<init>",
            "(Ljava/lang/Throwable;)V");
}

} // namespace android
