/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "GraphicsJNI.h"
#include "ImageDecoder.h"
#include "Utils.h"
#include "core_jni_helpers.h"

#include <SkAndroidCodec.h>
#include <SkAnimatedImage.h>
#include <SkColorFilter.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <hwui/AnimatedImageDrawable.h>
#include <hwui/Canvas.h>

using namespace android;

static jmethodID gAnimatedImageDrawable_postOnAnimationEndMethodID;

// Note: jpostProcess holds a handle to the ImageDecoder.
static jlong AnimatedImageDrawable_nCreate(JNIEnv* env, jobject /*clazz*/,
                                           jlong nativeImageDecoder, jobject jpostProcess,
                                           jint width, jint height, jobject jsubset) {
    if (nativeImageDecoder == 0) {
        doThrowIOE(env, "Cannot create AnimatedImageDrawable from null!");
        return 0;
    }

    auto* imageDecoder = reinterpret_cast<ImageDecoder*>(nativeImageDecoder);
    auto info = imageDecoder->mCodec->getInfo();
    const SkISize scaledSize = SkISize::Make(width, height);
    SkIRect subset;
    if (jsubset) {
        GraphicsJNI::jrect_to_irect(env, jsubset, &subset);
    } else {
        subset = SkIRect::MakeWH(width, height);
    }

    sk_sp<SkPicture> picture;
    if (jpostProcess) {
        SkRect bounds = SkRect::MakeWH(subset.width(), subset.height());

        SkPictureRecorder recorder;
        SkCanvas* skcanvas = recorder.beginRecording(bounds);
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(skcanvas));
        postProcessAndRelease(env, jpostProcess, std::move(canvas));
        if (env->ExceptionCheck()) {
            return 0;
        }
        picture = recorder.finishRecordingAsPicture();
    }


    sk_sp<SkAnimatedImage> animatedImg = SkAnimatedImage::Make(std::move(imageDecoder->mCodec),
                                                               scaledSize, subset,
                                                               std::move(picture));
    if (!animatedImg) {
        doThrowIOE(env, "Failed to create drawable");
        return 0;
    }

    sk_sp<AnimatedImageDrawable> drawable(new AnimatedImageDrawable(animatedImg));
    return reinterpret_cast<jlong>(drawable.release());
}

static void AnimatedImageDrawable_destruct(AnimatedImageDrawable* drawable) {
    SkSafeUnref(drawable);
}

static jlong AnimatedImageDrawable_nGetNativeFinalizer(JNIEnv* /*env*/, jobject /*clazz*/) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&AnimatedImageDrawable_destruct));
}

// Java's FINISHED relies on this being -1
static_assert(SkAnimatedImage::kFinished == -1);

static jlong AnimatedImageDrawable_nDraw(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                         jlong canvasPtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    auto* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    return (jlong) canvas->drawAnimatedImage(drawable);
}

static void AnimatedImageDrawable_nSetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                            jint alpha) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setStagingAlpha(alpha);
}

static jlong AnimatedImageDrawable_nGetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->getStagingAlpha();
}

static void AnimatedImageDrawable_nSetColorFilter(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                                  jlong nativeFilter) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    auto* filter = reinterpret_cast<SkColorFilter*>(nativeFilter);
    drawable->setStagingColorFilter(sk_ref_sp(filter));
}

static jboolean AnimatedImageDrawable_nIsRunning(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->isRunning();
}

static jboolean AnimatedImageDrawable_nStart(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->start();
}

static void AnimatedImageDrawable_nStop(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->stop();
}

// Java's LOOP_INFINITE relies on this being the same.
static_assert(SkCodec::kRepetitionCountInfinite == -1);

static void AnimatedImageDrawable_nSetLoopCount(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                                jint loopCount) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setRepetitionCount(loopCount);
}

class JniAnimationEndListener : public OnAnimationEndListener {
public:
    JniAnimationEndListener(JNIEnv* env, jobject javaObject) {
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mJvm) != JNI_OK);
        mJavaObject = env->NewGlobalRef(javaObject);
    }

    ~JniAnimationEndListener() override {
        auto* env = get_env_or_die(mJvm);
        env->DeleteGlobalRef(mJavaObject);
    }

    void onAnimationEnd() override {
        auto* env = get_env_or_die(mJvm);
        env->CallVoidMethod(mJavaObject, gAnimatedImageDrawable_postOnAnimationEndMethodID);
    }

private:
    JavaVM* mJvm;
    jobject mJavaObject;
};

static void AnimatedImageDrawable_nSetOnAnimationEndListener(JNIEnv* env, jobject /*clazz*/,
                                                             jlong nativePtr, jobject jdrawable) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setOnAnimationEndListener(std::unique_ptr<OnAnimationEndListener>(
                new JniAnimationEndListener(env, jdrawable)));
}

static long AnimatedImageDrawable_nNativeByteSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    // FIXME: Report the size of the internal SkBitmap etc.
    return sizeof(drawable);
}

static void AnimatedImageDrawable_nMarkInvisible(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->markInvisible();
}

static const JNINativeMethod gAnimatedImageDrawableMethods[] = {
    { "nCreate",             "(JLandroid/graphics/ImageDecoder;IILandroid/graphics/Rect;)J", (void*) AnimatedImageDrawable_nCreate },
    { "nGetNativeFinalizer", "()J",                                                          (void*) AnimatedImageDrawable_nGetNativeFinalizer },
    { "nDraw",               "(JJ)J",                                                        (void*) AnimatedImageDrawable_nDraw },
    { "nSetAlpha",           "(JI)V",                                                        (void*) AnimatedImageDrawable_nSetAlpha },
    { "nGetAlpha",           "(J)I",                                                         (void*) AnimatedImageDrawable_nGetAlpha },
    { "nSetColorFilter",     "(JJ)V",                                                        (void*) AnimatedImageDrawable_nSetColorFilter },
    { "nIsRunning",          "(J)Z",                                                         (void*) AnimatedImageDrawable_nIsRunning },
    { "nStart",              "(J)Z",                                                         (void*) AnimatedImageDrawable_nStart },
    { "nStop",               "(J)V",                                                         (void*) AnimatedImageDrawable_nStop },
    { "nSetLoopCount",       "(JI)V",                                                        (void*) AnimatedImageDrawable_nSetLoopCount },
    { "nSetOnAnimationEndListener", "(JLandroid/graphics/drawable/AnimatedImageDrawable;)V", (void*) AnimatedImageDrawable_nSetOnAnimationEndListener },
    { "nNativeByteSize",     "(J)J",                                                         (void*) AnimatedImageDrawable_nNativeByteSize },
    { "nMarkInvisible",      "(J)V",                                                         (void*) AnimatedImageDrawable_nMarkInvisible },
};

int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv* env) {
    jclass animatedImageDrawable_class = FindClassOrDie(env, "android/graphics/drawable/AnimatedImageDrawable");
    gAnimatedImageDrawable_postOnAnimationEndMethodID = GetMethodIDOrDie(env, animatedImageDrawable_class, "postOnAnimationEnd", "()V");

    return android::RegisterMethodsOrDie(env, "android/graphics/drawable/AnimatedImageDrawable",
            gAnimatedImageDrawableMethods, NELEM(gAnimatedImageDrawableMethods));
}

