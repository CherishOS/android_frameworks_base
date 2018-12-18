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

#pragma once

#include "FunctorDrawable.h"

#include <SkImageInfo.h>
#include <ui/GraphicBuffer.h>
#include <utils/RefBase.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * This draw handler will be returned by VkFunctorDrawable's onSnapGpuDrawHandler. It allows us to
 * issue Vulkan commands while the command buffer is being flushed.
 */
class VkFunctorDrawHandler : public FunctorDrawable::GpuDrawHandler {
public:
    explicit VkFunctorDrawHandler(Functor* functor);
    ~VkFunctorDrawHandler() override;

    void draw(const GrBackendDrawableInfo& info) override;

private:
    typedef GpuDrawHandler INHERITED;

    Functor* mFunctor;
};

/**
 * This drawable wraps a Vulkan functor enabling it to be recorded into a list of Skia drawing
 * commands.
 */
class VkFunctorDrawable : public FunctorDrawable {
public:
    using FunctorDrawable::FunctorDrawable;

    ~VkFunctorDrawable() override;

protected:
    // SkDrawable functions:
    void onDraw(SkCanvas* canvas) override;
    std::unique_ptr<FunctorDrawable::GpuDrawHandler> onSnapGpuDrawHandler(
            GrBackendApi backendApi, const SkMatrix& matrix) override;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
