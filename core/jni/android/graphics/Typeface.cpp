/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "jni.h"
#include "core_jni_helpers.h"

#include "FontUtils.h"
#include "GraphicsJNI.h"
#include "ScopedPrimitiveArray.h"
#include "SkTypeface.h"
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>

using namespace android;

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    Typeface* family = reinterpret_cast<Typeface*>(familyHandle);
    Typeface* face = Typeface::createRelative(family, (SkTypeface::Style)style);
    // TODO: the following logic shouldn't be necessary, the above should always succeed.
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = Typeface::createRelative(family, (SkTypeface::Style)(style ^ SkTypeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = Typeface::createRelative(family, (SkTypeface::Style)i);
    }
    return reinterpret_cast<jlong>(face);
}

static jlong Typeface_createFromTypefaceWithExactStyle(JNIEnv* env, jobject, jlong nativeInstance,
        jint weight, jboolean italic) {
    Typeface* baseTypeface = reinterpret_cast<Typeface*>(nativeInstance);
    return reinterpret_cast<jlong>(Typeface::createAbsolute(baseTypeface, weight, italic));
}

static jlong Typeface_createFromTypefaceWithVariation(JNIEnv* env, jobject, jlong familyHandle,
        jobject listOfAxis) {
    std::vector<minikin::FontVariation> variations;
    ListHelper list(env, listOfAxis);
    for (jint i = 0; i < list.size(); i++) {
        jobject axisObject = list.get(i);
        if (axisObject == nullptr) {
            continue;
        }
        AxisHelper axis(env, axisObject);
        variations.push_back(minikin::FontVariation(axis.getTag(), axis.getStyleValue()));
    }
    Typeface* baseTypeface = reinterpret_cast<Typeface*>(familyHandle);
    Typeface* result = Typeface::createFromTypefaceWithVariation(baseTypeface, variations);
    return reinterpret_cast<jlong>(result);
}

static jlong Typeface_createWeightAlias(JNIEnv* env, jobject, jlong familyHandle, jint weight) {
    Typeface* family = reinterpret_cast<Typeface*>(familyHandle);
    Typeface* face = Typeface::createWithDifferentBaseWeight(family, weight);
    return reinterpret_cast<jlong>(face);
}

static void Typeface_unref(JNIEnv* env, jobject obj, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    delete face;
}

static jint Typeface_getStyle(JNIEnv* env, jobject obj, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    return face->fSkiaStyle;
}

static jint Typeface_getWeight(JNIEnv* env, jobject obj, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    return face->fStyle.getWeight() * 100;
}

static jlong Typeface_createFromArray(JNIEnv *env, jobject, jlongArray familyArray,
        int weight, int italic) {
    ScopedLongArrayRO families(env, familyArray);
    std::vector<std::shared_ptr<minikin::FontFamily>> familyVec;
    familyVec.reserve(families.size());
    for (size_t i = 0; i < families.size(); i++) {
        FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(families[i]);
        familyVec.emplace_back(family->family);
    }
    return reinterpret_cast<jlong>(
            Typeface::createFromFamilies(std::move(familyVec), weight, italic));
}

static void Typeface_setDefault(JNIEnv *env, jobject, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    Typeface::setDefault(face);
}

static jobject Typeface_getSupportedAxes(JNIEnv *env, jobject, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    const std::unordered_set<minikin::AxisTag>& tagSet = face->fFontCollection->getSupportedTags();
    const size_t length = tagSet.size();
    if (length == 0) {
        return nullptr;
    }
    std::vector<jint> tagVec(length);
    int index = 0;
    for (const auto& tag : tagSet) {
        tagVec[index++] = tag;
    }
    std::sort(tagVec.begin(), tagVec.end());
    const jintArray result = env->NewIntArray(length);
    env->SetIntArrayRegion(result, 0, length, tagVec.data());
    return result;
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gTypefaceMethods[] = {
    { "nativeCreateFromTypeface", "(JI)J", (void*)Typeface_createFromTypeface },
    { "nativeCreateFromTypefaceWithExactStyle", "(JIZ)J",
            (void*)Typeface_createFromTypefaceWithExactStyle },
    { "nativeCreateFromTypefaceWithVariation", "(JLjava/util/List;)J",
            (void*)Typeface_createFromTypefaceWithVariation },
    { "nativeCreateWeightAlias",  "(JI)J", (void*)Typeface_createWeightAlias },
    { "nativeUnref",              "(J)V",  (void*)Typeface_unref },
    { "nativeGetStyle",           "(J)I",  (void*)Typeface_getStyle },
    { "nativeGetWeight",      "(J)I",  (void*)Typeface_getWeight },
    { "nativeCreateFromArray",    "([JII)J",
                                           (void*)Typeface_createFromArray },
    { "nativeSetDefault",         "(J)V",   (void*)Typeface_setDefault },
    { "nativeGetSupportedAxes",   "(J)[I",  (void*)Typeface_getSupportedAxes },
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/graphics/Typeface", gTypefaceMethods,
                                NELEM(gTypefaceMethods));
}
