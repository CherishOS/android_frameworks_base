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

#undef LOG_TAG
#define LOG_TAG "Minikin"

#include "Font.h"
#include "SkData.h"
#include "SkFont.h"
#include "SkFontMetrics.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <nativehelper/ScopedUtfChars.h>
#include "Utils.h"
#include "FontUtils.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Paint.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>
#include <ui/FatVector.h>

#include <memory>

namespace android {

struct NativeFontBuilder {
    std::vector<minikin::FontVariation> axes;
};

static inline NativeFontBuilder* toBuilder(jlong ptr) {
    return reinterpret_cast<NativeFontBuilder*>(ptr);
}

static void releaseFont(jlong font) {
    delete reinterpret_cast<FontWrapper*>(font);
}

static void release_global_ref(const void* /*data*/, void* context) {
    JNIEnv* env = GraphicsJNI::getJNIEnv();
    bool needToAttach = (env == nullptr);
    if (needToAttach) {
        env = GraphicsJNI::attachJNIEnv("release_font_data");
        if (env == nullptr) {
            ALOGE("failed to attach to thread to release global ref.");
            return;
        }
    }

    jobject obj = reinterpret_cast<jobject>(context);
    env->DeleteGlobalRef(obj);
}

// Regular JNI
static jlong Font_Builder_initBuilder(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeFontBuilder());
}

// Critical Native
static void Font_Builder_addAxis(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr, jint tag, jfloat value) {
    toBuilder(builderPtr)->axes.emplace_back(static_cast<minikin::AxisTag>(tag), value);
}

// Regular JNI
static jlong Font_Builder_build(JNIEnv* env, jobject clazz, jlong builderPtr, jobject buffer,
        jstring filePath, jint weight, jboolean italic, jint ttcIndex) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    std::unique_ptr<NativeFontBuilder> builder(toBuilder(builderPtr));
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return 0;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return 0;
    }
    ScopedUtfChars fontPath(env, filePath);
    jobject fontRef = MakeGlobalRefOrDie(env, buffer);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    std::shared_ptr<minikin::MinikinFont> minikinFont = fonts::createMinikinFontSkia(
        std::move(data), std::string_view(fontPath.c_str(), fontPath.size()),
        fontPtr, fontSize, ttcIndex, builder->axes);
    if (minikinFont == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Failed to create internal object. maybe invalid font data.");
        return 0;
    }
    std::shared_ptr<minikin::Font> font = minikin::Font::Builder(minikinFont).setWeight(weight)
                    .setSlant(static_cast<minikin::FontStyle::Slant>(italic)).build();
    return reinterpret_cast<jlong>(new FontWrapper(std::move(font)));
}

// Fast Native
static jlong Font_Builder_clone(JNIEnv* env, jobject clazz, jlong fontPtr, jlong builderPtr,
                                jint weight, jboolean italic, jint ttcIndex) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    std::unique_ptr<NativeFontBuilder> builder(toBuilder(builderPtr));

    // Reconstruct SkTypeface with different arguments from existing SkTypeface.
    FatVector<SkFontArguments::VariationPosition::Coordinate, 2> skVariation;
    for (const auto& axis : builder->axes) {
        skVariation.push_back({axis.axisTag, axis.value});
    }
    SkFontArguments args;
    args.setCollectionIndex(ttcIndex);
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});

    sk_sp<SkTypeface> newTypeface = minikinSkia->GetSkTypeface()->makeClone(args);

    std::shared_ptr<minikin::MinikinFont> newMinikinFont = std::make_shared<MinikinFontSkia>(
        std::move(newTypeface),
        minikinSkia->GetFontData(),
        minikinSkia->GetFontSize(),
        minikinSkia->getFilePath(),
        minikinSkia->GetFontIndex(),
        builder->axes);
    std::shared_ptr<minikin::Font> newFont = minikin::Font::Builder(newMinikinFont)
              .setWeight(weight)
              .setSlant(static_cast<minikin::FontStyle::Slant>(italic))
              .build();
    return reinterpret_cast<jlong>(new FontWrapper(std::move(newFont)));
}

// Critical Native
static jlong Font_Builder_getReleaseNativeFont(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(releaseFont);
}

///////////////////////////////////////////////////////////////////////////////

// Fast Native
static jfloat Font_getGlyphBounds(JNIEnv* env, jobject, jlong fontHandle, jint glyphId,
                                  jlong paintHandle, jobject rect) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    SkFont* skFont = &paint->getSkFont();
    // We don't use populateSkFont since it is designed to be used for layout result with addressing
    // auto fake-bolding.
    skFont->setTypeface(minikinSkia->RefSkTypeface());

    uint16_t glyph16 = glyphId;
    SkRect skBounds;
    SkScalar skWidth;
    skFont->getWidthsBounds(&glyph16, 1, &skWidth, &skBounds, nullptr);
    GraphicsJNI::rect_to_jrectf(skBounds, env, rect);
    return SkScalarToFloat(skWidth);
}

// Fast Native
static jfloat Font_getFontMetrics(JNIEnv* env, jobject, jlong fontHandle, jlong paintHandle,
                                  jobject metricsObj) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    SkFont* skFont = &paint->getSkFont();
    // We don't use populateSkFont since it is designed to be used for layout result with addressing
    // auto fake-bolding.
    skFont->setTypeface(minikinSkia->RefSkTypeface());

    SkFontMetrics metrics;
    SkScalar spacing = skFont->getMetrics(&metrics);
    GraphicsJNI::set_metrics(env, metricsObj, metrics);
    return spacing;
}

// Critical Native
static jlong Font_getFontInfo(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());

    uint64_t result = font->style().weight();
    result |= font->style().slant() == minikin::FontStyle::Slant::ITALIC ? 0x10000 : 0x00000;
    result |= ((static_cast<uint64_t>(minikinSkia->GetFontIndex())) << 32);
    result |= ((static_cast<uint64_t>(minikinSkia->GetAxes().size())) << 48);
    return result;
}

// Critical Native
static jlong Font_getAxisInfo(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle, jint index) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());
    const minikin::FontVariation& var = minikinSkia->GetAxes().at(index);
    uint32_t floatBinary = *reinterpret_cast<const uint32_t*>(&var.value);
    return (static_cast<uint64_t>(var.axisTag) << 32) | static_cast<uint64_t>(floatBinary);
}

// FastNative
static jstring Font_getFontPath(JNIEnv* env, jobject, jlong fontHandle) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());
    const std::string& filePath = minikinSkia->getFilePath();
    if (filePath.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(filePath.c_str());
}

// Critical Native
static jlong Font_getNativeFontPtr(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    return reinterpret_cast<jlong>(font->font.get());
}

// Critical Native
static jlong Font_GetBufferAddress(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    const void* bufferPtr = font->font->typeface()->GetFontData();
    return reinterpret_cast<jlong>(bufferPtr);
}

///////////////////////////////////////////////////////////////////////////////

struct FontBufferWrapper {
    FontBufferWrapper(const std::shared_ptr<minikin::MinikinFont>& font) : minikinFont(font) {}
    // MinikinFont holds a shared pointer of SkTypeface which has reference to font data.
    std::shared_ptr<minikin::MinikinFont> minikinFont;
};

static void unrefBuffer(jlong nativePtr) {
    FontBufferWrapper* wrapper = reinterpret_cast<FontBufferWrapper*>(nativePtr);
    delete wrapper;
}

// Critical Native
static jlong FontBufferHelper_refFontBuffer(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    return reinterpret_cast<jlong>(new FontBufferWrapper(font->typeface()));
}

// Fast Native
static jobject FontBufferHelper_wrapByteBuffer(JNIEnv* env, jobject, jlong nativePtr) {
    FontBufferWrapper* wrapper = reinterpret_cast<FontBufferWrapper*>(nativePtr);
    return env->NewDirectByteBuffer(
        const_cast<void*>(wrapper->minikinFont->GetFontData()),
        wrapper->minikinFont->GetFontSize());
}

// Critical Native
static jlong FontBufferHelper_getReleaseFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(unrefBuffer);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontBuilderMethods[] = {
    { "nInitBuilder", "()J", (void*) Font_Builder_initBuilder },
    { "nAddAxis", "(JIF)V", (void*) Font_Builder_addAxis },
    { "nBuild", "(JLjava/nio/ByteBuffer;Ljava/lang/String;IZI)J", (void*) Font_Builder_build },
    { "nClone", "(JJIZI)J", (void*) Font_Builder_clone },
    { "nGetReleaseNativeFont", "()J", (void*) Font_Builder_getReleaseNativeFont },
};

static const JNINativeMethod gFontMethods[] = {
    { "nGetGlyphBounds", "(JIJLandroid/graphics/RectF;)F", (void*) Font_getGlyphBounds },
    { "nGetFontMetrics", "(JJLandroid/graphics/Paint$FontMetrics;)F", (void*) Font_getFontMetrics },
    { "nGetFontInfo", "(J)J", (void*) Font_getFontInfo },
    { "nGetAxisInfo", "(JI)J", (void*) Font_getAxisInfo },
    { "nGetFontPath", "(J)Ljava/lang/String;", (void*) Font_getFontPath },
    { "nGetNativeFontPtr", "(J)J", (void*) Font_getNativeFontPtr },
    { "nGetFontBufferAddress", "(J)J", (void*) Font_GetBufferAddress },
};

static const JNINativeMethod gFontBufferHelperMethods[] = {
    { "nRefFontBuffer", "(J)J", (void*) FontBufferHelper_refFontBuffer },
    { "nWrapByteBuffer", "(J)Ljava/nio/ByteBuffer;", (void*) FontBufferHelper_wrapByteBuffer },
    { "nGetReleaseFunc", "()J", (void*) FontBufferHelper_getReleaseFunc },
};

int register_android_graphics_fonts_Font(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/Font$Builder", gFontBuilderMethods,
            NELEM(gFontBuilderMethods)) +
            RegisterMethodsOrDie(env, "android/graphics/fonts/Font", gFontMethods,
            NELEM(gFontMethods)) +
            RegisterMethodsOrDie(env, "android/graphics/fonts/NativeFontBufferHelper",
            gFontBufferHelperMethods, NELEM(gFontBufferHelperMethods));
}

namespace fonts {

std::shared_ptr<minikin::MinikinFont> createMinikinFontSkia(
        sk_sp<SkData>&& data, std::string_view fontPath, const void *fontPtr, size_t fontSize,
        int ttcIndex, const std::vector<minikin::FontVariation>& axes) {
    FatVector<SkFontArguments::VariationPosition::Coordinate, 2> skVariation;
    for (const auto& axis : axes) {
        skVariation.push_back({axis.axisTag, axis.value});
    }

    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontArguments args;
    args.setCollectionIndex(ttcIndex);
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->makeFromStream(std::move(fontData), args));
    if (face == nullptr) {
        return nullptr;
    }
    return std::make_shared<MinikinFontSkia>(std::move(face), fontPtr, fontSize,
                                             fontPath, ttcIndex, axes);
}

}  // namespace fonts

}  // namespace android
