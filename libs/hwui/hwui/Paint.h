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

#ifndef ANDROID_GRAPHICS_PAINT_H_
#define ANDROID_GRAPHICS_PAINT_H_

#include <cutils/compiler.h>

#include <SkPaint.h>
#include <string>

#include <minikin/FontFamily.h>

namespace android {

class ANDROID_API Paint : public SkPaint {
public:
    // Default values for underlined and strikethrough text,
    // as defined by Skia in SkTextFormatParams.h.
    constexpr static float kStdStrikeThru_Offset   = (-6.0f / 21.0f);
    constexpr static float kStdUnderline_Offset    = (1.0f / 9.0f);
    constexpr static float kStdUnderline_Thickness = (1.0f / 18.0f);

    constexpr static float kStdUnderline_Top =
            kStdUnderline_Offset - 0.5f * kStdUnderline_Thickness;

    constexpr static float kStdStrikeThru_Thickness = kStdUnderline_Thickness;
    constexpr static float kStdStrikeThru_Top =
            kStdStrikeThru_Offset - 0.5f * kStdStrikeThru_Thickness;

    Paint();
    Paint(const Paint& paint);
    Paint(const SkPaint& paint);  // NOLINT(implicit)
    ~Paint();

    Paint& operator=(const Paint& other);

    friend bool operator==(const Paint& a, const Paint& b);
    friend bool operator!=(const Paint& a, const Paint& b) {
        return !(a == b);
    }

    void setLetterSpacing(float letterSpacing) {
        mLetterSpacing = letterSpacing;
    }

    float getLetterSpacing() const {
        return mLetterSpacing;
    }

    void setWordSpacing(float wordSpacing) {
        mWordSpacing = wordSpacing;
    }

    float getWordSpacing() const {
        return mWordSpacing;
    }

    void setFontFeatureSettings(const std::string& fontFeatureSettings) {
        mFontFeatureSettings = fontFeatureSettings;
    }

    std::string getFontFeatureSettings() const {
        return mFontFeatureSettings;
    }

    void setMinikinLangListId(uint32_t minikinLangListId) {
        mMinikinLangListId = minikinLangListId;
    }

    uint32_t getMinikinLangListId() const {
        return mMinikinLangListId;
    }

    void setFontVariant(minikin::FontVariant variant) {
        mFontVariant = variant;
    }

    minikin::FontVariant getFontVariant() const {
        return mFontVariant;
    }

    void setHyphenEdit(uint32_t hyphen) {
        mHyphenEdit = hyphen;
    }

    uint32_t getHyphenEdit() const {
        return mHyphenEdit;
    }

private:
    float mLetterSpacing = 0;
    float mWordSpacing = 0;
    std::string mFontFeatureSettings;
    uint32_t mMinikinLangListId;
    minikin::FontVariant mFontVariant;
    uint32_t mHyphenEdit = 0;
};

}  // namespace android

#endif // ANDROID_GRAPHICS_PAINT_H_
