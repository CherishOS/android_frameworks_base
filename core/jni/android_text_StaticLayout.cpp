/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "StaticLayout"

#include "ScopedIcuLocale.h"
#include "unicode/locid.h"
#include "unicode/brkiter.h"
#include "utils/misc.h"
#include "utils/Log.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include <cstdint>
#include <vector>
#include <list>
#include <algorithm>

#include "SkPaint.h"
#include "SkTypeface.h"
#include <hwui/MinikinSkia.h>
#include <hwui/MinikinUtils.h>
#include <hwui/Paint.h>
#include <minikin/FontCollection.h>
#include <minikin/LineBreaker.h>
#include <minikin/MinikinFont.h>

namespace android {

struct JLineBreaksID {
    jfieldID breaks;
    jfieldID widths;
    jfieldID ascents;
    jfieldID descents;
    jfieldID flags;
};

static jclass gLineBreaks_class;
static JLineBreaksID gLineBreaks_fieldID;

class JNILineBreakerLineWidth : public minikin::LineBreaker::LineWidthDelegate {
    public:
        JNILineBreakerLineWidth(float firstWidth, int32_t firstLineCount, float restWidth,
                std::vector<float>&& indents, std::vector<float>&& leftPaddings,
                std::vector<float>&& rightPaddings, int32_t indentsAndPaddingsOffset)
            : mFirstWidth(firstWidth), mFirstLineCount(firstLineCount), mRestWidth(restWidth),
              mIndents(std::move(indents)), mLeftPaddings(std::move(leftPaddings)),
              mRightPaddings(std::move(rightPaddings)), mOffset(indentsAndPaddingsOffset) {}

        float getLineWidth(size_t lineNo) override {
            const float width = ((ssize_t)lineNo < (ssize_t)mFirstLineCount)
                    ? mFirstWidth : mRestWidth;
            return width - get(mIndents, lineNo);
        }

        float getLeftPadding(size_t lineNo) override {
            return get(mLeftPaddings, lineNo);
        }

        float getRightPadding(size_t lineNo) override {
            return get(mRightPaddings, lineNo);
        }

    private:
        float get(const std::vector<float>& vec, size_t lineNo) {
            if (vec.empty()) {
                return 0;
            }
            const size_t index = lineNo + mOffset;
            if (index < vec.size()) {
                return vec[index];
            } else {
                return vec.back();
            }
        }

        const float mFirstWidth;
        const int32_t mFirstLineCount;
        const float mRestWidth;
        const std::vector<float> mIndents;
        const std::vector<float> mLeftPaddings;
        const std::vector<float> mRightPaddings;
        const int32_t mOffset;
};

static inline std::vector<float> jintArrayToFloatVector(JNIEnv* env, jintArray javaArray) {
    if (javaArray == nullptr) {
         return std::vector<float>();
    } else {
        ScopedIntArrayRO intArr(env, javaArray);
        return std::vector<float>(intArr.get(), intArr.get() + intArr.size());
    }
}

// set text and set a number of parameters for creating a layout (width, tabstops, strategy,
// hyphenFrequency)
static void nSetupParagraph(JNIEnv* env, jclass, jlong nativePtr, jcharArray text, jint length,
        jfloat firstWidth, jint firstWidthLineLimit, jfloat restWidth,
        jintArray variableTabStops, jint defaultTabStop, jint strategy, jint hyphenFrequency,
        jboolean isJustified, jintArray indents, jintArray leftPaddings, jintArray rightPaddings,
        jint indentsAndPaddingsOffset) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    b->resize(length);
    env->GetCharArrayRegion(text, 0, length, b->buffer());
    b->setText();
    if (variableTabStops == nullptr) {
        b->setTabStops(nullptr, 0, defaultTabStop);
    } else {
        ScopedIntArrayRO stops(env, variableTabStops);
        b->setTabStops(stops.get(), stops.size(), defaultTabStop);
    }
    b->setStrategy(static_cast<minikin::BreakStrategy>(strategy));
    b->setHyphenationFrequency(static_cast<minikin::HyphenationFrequency>(hyphenFrequency));
    b->setJustified(isJustified);

    // TODO: copy indents and paddings only once when LineBreaker is started to be used.
    b->setLineWidthDelegate(std::make_unique<JNILineBreakerLineWidth>(
            firstWidth, firstWidthLineLimit, restWidth, jintArrayToFloatVector(env, indents),
            jintArrayToFloatVector(env, leftPaddings), jintArrayToFloatVector(env, rightPaddings),
            indentsAndPaddingsOffset));
}

static void recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jfloatArray recycleAscents,
                        jfloatArray recycleDescents, jintArray recycleFlags,
                        jint recycleLength, size_t nBreaks, const jint* breaks,
                        const jfloat* widths, const jfloat* ascents, const jfloat* descents,
                        const jint* flags) {
    if ((size_t)recycleLength < nBreaks) {
        // have to reallocate buffers
        recycleBreaks = env->NewIntArray(nBreaks);
        recycleWidths = env->NewFloatArray(nBreaks);
        recycleAscents = env->NewFloatArray(nBreaks);
        recycleDescents = env->NewFloatArray(nBreaks);
        recycleFlags = env->NewIntArray(nBreaks);

        env->SetObjectField(recycle, gLineBreaks_fieldID.breaks, recycleBreaks);
        env->SetObjectField(recycle, gLineBreaks_fieldID.widths, recycleWidths);
        env->SetObjectField(recycle, gLineBreaks_fieldID.ascents, recycleAscents);
        env->SetObjectField(recycle, gLineBreaks_fieldID.descents, recycleDescents);
        env->SetObjectField(recycle, gLineBreaks_fieldID.flags, recycleFlags);
    }
    // copy data
    env->SetIntArrayRegion(recycleBreaks, 0, nBreaks, breaks);
    env->SetFloatArrayRegion(recycleWidths, 0, nBreaks, widths);
    env->SetFloatArrayRegion(recycleAscents, 0, nBreaks, ascents);
    env->SetFloatArrayRegion(recycleDescents, 0, nBreaks, descents);
    env->SetIntArrayRegion(recycleFlags, 0, nBreaks, flags);
}

static jint nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
                               jobject recycle, jintArray recycleBreaks,
                               jfloatArray recycleWidths, jfloatArray recycleAscents,
                               jfloatArray recycleDescents, jintArray recycleFlags,
                               jint recycleLength, jfloatArray charWidths) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);

    size_t nBreaks = b->computeBreaks();

    recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleAscents, recycleDescents,
            recycleFlags, recycleLength, nBreaks, b->getBreaks(), b->getWidths(), b->getAscents(),
            b->getDescents(), b->getFlags());

    env->SetFloatArrayRegion(charWidths, 0, b->size(), b->charWidths());

    b->finish();

    return static_cast<jint>(nBreaks);
}

static jlong nNewBuilder(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new minikin::LineBreaker);
}

static void nFreeBuilder(JNIEnv*, jclass, jlong nativePtr) {
    delete reinterpret_cast<minikin::LineBreaker*>(nativePtr);
}

static void nFinishBuilder(JNIEnv*, jclass, jlong nativePtr) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    b->finish();
}

class ScopedNullableUtfString {
public:
    ScopedNullableUtfString(JNIEnv* env, jstring s) : mEnv(env), mStr(s) {
        if (s == nullptr) {
            mUtf8Chars = nullptr;
        } else {
            mUtf8Chars = mEnv->GetStringUTFChars(s, nullptr);
        }
    }

    ~ScopedNullableUtfString() {
        if (mUtf8Chars != nullptr) {
            mEnv->ReleaseStringUTFChars(mStr, mUtf8Chars);
        }
    }

    const char* get() const {
        return mUtf8Chars;
    }

private:
    JNIEnv* mEnv;
    jstring mStr;
    const char* mUtf8Chars;
};

static std::vector<minikin::Hyphenator*> makeHyphenators(JNIEnv* env, jlongArray hyphenators) {
    std::vector<minikin::Hyphenator*> out;
    if (hyphenators == nullptr) {
        return out;
    }
    ScopedLongArrayRO longArray(env, hyphenators);
    size_t size = longArray.size();
    out.reserve(size);
    for (size_t i = 0; i < size; i++) {
        out.push_back(reinterpret_cast<minikin::Hyphenator*>(longArray[i]));
    }
    return out;
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
static void nAddStyleRun(JNIEnv* env, jclass, jlong nativePtr, jlong nativePaint, jint start,
        jint end, jboolean isRtl, jstring langTags, jlongArray hyphenators) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    const Typeface* typeface = paint->getAndroidTypeface();
    minikin::MinikinPaint minikinPaint;
    const Typeface* resolvedTypeface = Typeface::resolveDefault(typeface);
    minikin::FontStyle style = MinikinUtils::prepareMinikinPaint(&minikinPaint, paint,
            typeface);

    ScopedNullableUtfString langTagsString(env, langTags);
    b->addStyleRun(&minikinPaint, resolvedTypeface->fFontCollection, style, start,
            end, isRtl, langTagsString.get(), makeHyphenators(env, hyphenators));
}

static void nAddReplacementRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloat width, jstring langTags, jlongArray hyphenators) {
    minikin::LineBreaker* b = reinterpret_cast<minikin::LineBreaker*>(nativePtr);
    ScopedNullableUtfString langTagsString(env, langTags);
    b->addReplacement(start, end, width, langTagsString.get(), makeHyphenators(env, hyphenators));
}

static const JNINativeMethod gMethods[] = {
    // TODO performance: many of these are candidates for fast jni, awaiting guidance
    {"nNewBuilder", "()J", (void*) nNewBuilder},
    {"nFreeBuilder", "(J)V", (void*) nFreeBuilder},
    {"nFinishBuilder", "(J)V", (void*) nFinishBuilder},
    {"nSetupParagraph", "(J[CIFIF[IIIIZ[I[I[II)V", (void*) nSetupParagraph},
    {"nAddStyleRun", "(JJIIZLjava/lang/String;[J)V", (void*) nAddStyleRun},
    {"nAddReplacementRun", "(JIIFLjava/lang/String;[J)V", (void*) nAddReplacementRun},
    {"nComputeLineBreaks", "(JLandroid/text/StaticLayout$LineBreaks;[I[F[F[F[II[F)I",
        (void*) nComputeLineBreaks}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    gLineBreaks_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/text/StaticLayout$LineBreaks"));

    gLineBreaks_fieldID.breaks = GetFieldIDOrDie(env, gLineBreaks_class, "breaks", "[I");
    gLineBreaks_fieldID.widths = GetFieldIDOrDie(env, gLineBreaks_class, "widths", "[F");
    gLineBreaks_fieldID.ascents = GetFieldIDOrDie(env, gLineBreaks_class, "ascents", "[F");
    gLineBreaks_fieldID.descents = GetFieldIDOrDie(env, gLineBreaks_class, "descents", "[F");
    gLineBreaks_fieldID.flags = GetFieldIDOrDie(env, gLineBreaks_class, "flags", "[I");

    return RegisterMethodsOrDie(env, "android/text/StaticLayout", gMethods, NELEM(gMethods));
}

}
