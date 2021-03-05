/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "StretchEffect.h"
#include <SkImageFilter.h>
#include <SkRefCnt.h>
#include <SkRuntimeEffect.h>
#include <SkString.h>
#include <SkSurface.h>
#include <include/effects/SkImageFilters.h>

#include <memory>

namespace android::uirenderer {

static const SkString stretchShader = SkString(R"(
    uniform shader uContentTexture;

    // multiplier to apply to scale effect
    uniform float uMaxStretchIntensity;

    // Maximum percentage to stretch beyond bounds  of target
    uniform float uStretchAffectedDist;

    // Distance stretched as a function of the normalized overscroll times
    // scale intensity
    uniform float uDistanceStretchedX;
    uniform float uDistanceStretchedY;
    uniform float uDistDiffX;

    // Difference between the peak stretch amount and overscroll amount normalized
    uniform float uDistDiffY;

    // Horizontal offset represented as a ratio of pixels divided by the target width
    uniform float uScrollX;
    // Vertical offset represented as a ratio of pixels divided by the target height
    uniform float uScrollY;

    // Normalized overscroll amount in the horizontal direction
    uniform float uOverscrollX;

    // Normalized overscroll amount in the vertical direction
    uniform float uOverscrollY;
    uniform float viewportWidth; // target height in pixels
    uniform float viewportHeight; // target width in pixels

    void computeOverscrollStart(
        out float outPos,
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float distanceStretched
    ) {
        float offsetPos = uStretchAffectedDist - inPos;
        float posBasedVariation = smoothstep(0., uStretchAffectedDist, offsetPos);
        float stretchIntensity = overscroll * posBasedVariation;
        outPos = distanceStretched - (offsetPos / (1. + stretchIntensity));
    }

    void computeOverscrollEnd(
        out float outPos,
        float inPos,
        float overscroll,
        float reverseStretchDist,
        float uStretchAffectedDist,
        float distanceStretched
    ) {
        float offsetPos = inPos - reverseStretchDist;
        float posBasedVariation = (smoothstep(0., uStretchAffectedDist, offsetPos));
        float stretchIntensity = (-overscroll) * posBasedVariation;
        outPos = 1 - (distanceStretched - (offsetPos / (1. + stretchIntensity)));
    }

    void computeOverscroll(
        out float outPos,
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float distanceStretched,
        float distanceDiff
    ) {
        if (overscroll > 0) {
            if (inPos <= uStretchAffectedDist) {
                computeOverscrollStart(
                  outPos,
                  inPos,
                  overscroll,
                  uStretchAffectedDist,
                  distanceStretched
                );
            } else if (inPos >= distanceStretched) {
                outPos = distanceDiff + inPos;
            }
        }
        if (overscroll < 0) {
            float stretchAffectedDist = 1. - uStretchAffectedDist;
            if (inPos >= stretchAffectedDist) {
                computeOverscrollEnd(
                  outPos,
                  inPos,
                  overscroll,
                  stretchAffectedDist,
                  uStretchAffectedDist,
                  distanceStretched
                );
            } else if (inPos < stretchAffectedDist) {
                outPos = -distanceDiff + inPos;
            }
        }
    }

    vec4 main(vec2 coord) {
        // Normalize SKSL pixel coordinate into a unit vector
        float inU = coord.x / viewportWidth;
        float inV = coord.y / viewportHeight;
        float outU;
        float outV;
        float stretchIntensity;
        // Add the normalized scroll position within scrolling list
        inU += uScrollX;
        inV += uScrollY;
        outU = inU;
        outV = inV;
        computeOverscroll(
            outU,
            inU,
            uOverscrollX,
            uStretchAffectedDist,
            uDistanceStretchedX,
            uDistDiffX
        );
        computeOverscroll(
            outV,
            inV,
            uOverscrollY,
            uStretchAffectedDist,
            uDistanceStretchedY,
            uDistDiffY
        );
        coord.x = outU * viewportWidth;
        coord.y = outV * viewportHeight;
        return sample(uContentTexture, coord);
    })");

static const float ZERO = 0.f;

sk_sp<SkImageFilter> StretchEffect::getImageFilter(const sk_sp<SkImage>& snapshotImage) const {
    if (isEmpty()) {
        return nullptr;
    }

    if (mStretchFilter != nullptr) {
        return mStretchFilter;
    }

    float distanceNotStretchedX = maxStretchAmount / stretchArea.width();
    float distanceNotStretchedY = maxStretchAmount / stretchArea.height();
    float normOverScrollDistX = mStretchDirection.x();
    float normOverScrollDistY = mStretchDirection.y();
    float distanceStretchedX = maxStretchAmount / (1 + abs(normOverScrollDistX));
    float distanceStretchedY = maxStretchAmount / (1 + abs(normOverScrollDistY));
    float diffX = distanceStretchedX - distanceNotStretchedX;
    float diffY = distanceStretchedY - distanceNotStretchedY;
    float viewportWidth = stretchArea.width();
    float viewportHeight = stretchArea.height();

    if (mBuilder == nullptr) {
        mBuilder = std::make_unique<SkRuntimeShaderBuilder>(getStretchEffect());
    }

    mBuilder->child("uContentTexture") = snapshotImage->makeShader(
            SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kLinear));
    mBuilder->uniform("uStretchAffectedDist").set(&maxStretchAmount, 1);
    mBuilder->uniform("uDistanceStretchedX").set(&distanceStretchedX, 1);
    mBuilder->uniform("uDistanceStretchedY").set(&distanceStretchedY, 1);
    mBuilder->uniform("uDistDiffX").set(&diffX, 1);
    mBuilder->uniform("uDistDiffY").set(&diffY, 1);
    mBuilder->uniform("uOverscrollX").set(&normOverScrollDistX, 1);
    mBuilder->uniform("uOverscrollY").set(&normOverScrollDistY, 1);
    mBuilder->uniform("uScrollX").set(&ZERO, 1);
    mBuilder->uniform("uScrollY").set(&ZERO, 1);
    mBuilder->uniform("viewportWidth").set(&viewportWidth, 1);
    mBuilder->uniform("viewportHeight").set(&viewportHeight, 1);

    mStretchFilter = SkImageFilters::Shader(mBuilder->makeShader(nullptr, false),
                                            SkRect{0, 0, viewportWidth, viewportHeight});

    return mStretchFilter;
}

sk_sp<SkRuntimeEffect> StretchEffect::getStretchEffect() {
    const static SkRuntimeEffect::Result instance = SkRuntimeEffect::Make(stretchShader);
    return instance.effect;
}

} // namespace android::uirenderer