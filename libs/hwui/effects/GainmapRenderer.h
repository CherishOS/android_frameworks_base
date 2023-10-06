/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

#include <SkCanvas.h>
#include <SkGainmapInfo.h>
#include <SkImage.h>
#include <SkPaint.h>

#include "hwui/Bitmap.h"

namespace android::uirenderer {

float getTargetHdrSdrRatio(const SkColorSpace* destColorspace);

void DrawGainmapBitmap(SkCanvas* c, const sk_sp<const SkImage>& image, const SkRect& src,
                       const SkRect& dst, const SkSamplingOptions& sampling, const SkPaint* paint,
                       SkCanvas::SrcRectConstraint constraint,
                       const sk_sp<const SkImage>& gainmapImage, const SkGainmapInfo& gainmapInfo);

sk_sp<SkShader> MakeGainmapShader(const sk_sp<const SkImage>& image,
                                  const sk_sp<const SkImage>& gainmapImage,
                                  const SkGainmapInfo& gainmapInfo, SkTileMode tileModeX,
                                  SkTileMode tileModeY, const SkSamplingOptions& sampling);

}  // namespace android::uirenderer
