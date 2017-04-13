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

/**
 * This is the implementation of the Typeface object. Historically, it has
 * just been SkTypeface, but we are migrating to Minikin. For the time
 * being, that choice is hidden under the USE_MINIKIN compile-time flag.
 */

#include "Typeface.h"

#include <pthread.h>
#include <fcntl.h>  // For tests.
#include <sys/stat.h>  // For tests.
#include <sys/mman.h>  // For tests.

#include "MinikinSkia.h"
#include "SkTypeface.h"
#include "SkPaint.h"
#include "SkStream.h"  // Fot tests.

#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include <utils/Log.h>

namespace android {

// Resolve the 1..9 weight based on base weight and bold flag
static void resolveStyle(Typeface* typeface) {
    int weight = typeface->fBaseWeight / 100;
    if (typeface->fSkiaStyle & SkTypeface::kBold) {
        weight += 3;
    }
    if (weight > 9) {
        weight = 9;
    }
    bool italic = (typeface->fSkiaStyle & SkTypeface::kItalic) != 0;
    typeface->fStyle = minikin::FontStyle(weight, italic);
}

Typeface* gDefaultTypeface = NULL;

Typeface* Typeface::resolveDefault(Typeface* src) {
    LOG_ALWAYS_FATAL_IF(gDefaultTypeface == nullptr);
    return src == nullptr ? gDefaultTypeface : src;
}

Typeface* Typeface::createFromTypeface(Typeface* src, SkTypeface::Style style) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fSkiaStyle = style;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        resolveStyle(result);
    }
    return result;
}

Typeface* Typeface::createFromTypefaceWithStyle(Typeface* base, int weight, bool italic) {
    Typeface* resolvedFace = Typeface::resolveDefault(base);
    Typeface* result = new Typeface();
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fBaseWeight = weight;
        result->fStyle = minikin::FontStyle(weight / 100, italic);
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
    }
    return result;
}

Typeface* Typeface::createFromTypefaceWithVariation(Typeface* src,
        const std::vector<minikin::FontVariation>& variations) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface();
    if (result != nullptr) {
        result->fFontCollection =
                resolvedFace->fFontCollection->createCollectionWithVariation(variations);
        if (result->fFontCollection == nullptr) {
            // None of passed axes are supported by this collection.
            // So we will reuse the same collection with incrementing reference count.
            result->fFontCollection = resolvedFace->fFontCollection;
        }
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        resolveStyle(result);
    }
    return result;
}

Typeface* Typeface::createWeightAlias(Typeface* src, int weight) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fBaseWeight = weight;
        resolveStyle(result);
    }
    return result;
}

Typeface* Typeface::createFromFamilies(
        std::vector<std::shared_ptr<minikin::FontFamily>>&& families) {
    Typeface* result = new Typeface;
    result->fFontCollection.reset(new minikin::FontCollection(families));
    if (families.empty()) {
        ALOGW("createFromFamilies creating empty collection");
        result->fSkiaStyle = SkTypeface::kNormal;
    } else {
        const minikin::FontStyle defaultStyle;
        const std::shared_ptr<minikin::FontFamily>& firstFamily = families[0];
        const minikin::MinikinFont* mf = firstFamily->getClosestMatch(defaultStyle).font;
        if (mf != nullptr) {
            SkTypeface* skTypeface = reinterpret_cast<const MinikinFontSkia*>(mf)->GetSkTypeface();
            // TODO: probably better to query more precise style from family, will be important
            // when we open up API to access 100..900 weights
            result->fSkiaStyle = skTypeface->style();
        } else {
            result->fSkiaStyle = SkTypeface::kNormal;
        }
    }
    result->fBaseWeight = 400;
    resolveStyle(result);
    return result;
}

void Typeface::setDefault(Typeface* face) {
    gDefaultTypeface = face;
}

void Typeface::setRobotoTypefaceForTest() {
    const char* kRobotoFont = "/system/fonts/Roboto-Regular.ttf";

    int fd = open(kRobotoFont, O_RDONLY);
    LOG_ALWAYS_FATAL_IF(fd == -1, "Failed to open file %s", kRobotoFont);
    struct stat st = {};
    LOG_ALWAYS_FATAL_IF(fstat(fd, &st) == -1, "Failed to stat file %s", kRobotoFont);
    void* data = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    std::unique_ptr<SkMemoryStream> fontData(new SkMemoryStream(data, st.st_size));
    sk_sp<SkTypeface> typeface = SkTypeface::MakeFromStream(fontData.release());
    LOG_ALWAYS_FATAL_IF(typeface == nullptr, "Failed to make typeface from %s", kRobotoFont);

    std::shared_ptr<minikin::MinikinFont> font = std::make_shared<MinikinFontSkia>(
            std::move(typeface), data, st.st_size, 0, std::vector<minikin::FontVariation>());
    std::shared_ptr<minikin::FontFamily> family = std::make_shared<minikin::FontFamily>(
            std::vector<minikin::Font>({ minikin::Font(std::move(font), minikin::FontStyle()) }));
    std::shared_ptr<minikin::FontCollection> collection =
            std::make_shared<minikin::FontCollection>(std::move(family));

    Typeface* hwTypeface = new Typeface();
    hwTypeface->fFontCollection = collection;
    hwTypeface->fSkiaStyle = SkTypeface::kNormal;
    hwTypeface->fBaseWeight = 400;
    hwTypeface->fStyle = minikin::FontStyle(4 /* weight */, false /* italic */);

    Typeface::setDefault(hwTypeface);
}

}
