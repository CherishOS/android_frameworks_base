/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <DeviceInfo.h>

#include "Properties.h"

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <ui/GraphicTypes.h>

#include <mutex>
#include <thread>

#include <log/log.h>

namespace android {
namespace uirenderer {

static constexpr android::DisplayInfo sDummyDisplay{
        1080,   // w
        1920,   // h
        320.0,  // xdpi
        320.0,  // ydpi
        60.0,   // fps
        2.0,    // density
        0,      // orientation
        false,  // secure?
        0,      // appVsyncOffset
        0,      // presentationDeadline
        1080,   // viewportW
        1920,   // viewportH
};

DeviceInfo* DeviceInfo::get() {
        static DeviceInfo sDeviceInfo;
        return &sDeviceInfo;
}

static DisplayInfo QueryDisplayInfo() {
    if (Properties::isolatedProcess) {
        return sDummyDisplay;
    }

    const sp<IBinder> token = SurfaceComposerClient::getInternalDisplayToken();
    LOG_ALWAYS_FATAL_IF(token == nullptr,
                        "Failed to get display info because internal display is disconnected");

    DisplayInfo displayInfo;
    status_t status = SurfaceComposerClient::getDisplayInfo(token, &displayInfo);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get display info, error %d", status);
    return displayInfo;
}

static float QueryMaxRefreshRate() {
    if (Properties::isolatedProcess) {
        return sDummyDisplay.fps;
    }

    const sp<IBinder> token = SurfaceComposerClient::getInternalDisplayToken();
    LOG_ALWAYS_FATAL_IF(token == nullptr,
                        "Failed to get display info because internal display is disconnected");

    Vector<DisplayInfo> configs;
    configs.reserve(10);
    status_t status = SurfaceComposerClient::getDisplayConfigs(token, &configs);
    LOG_ALWAYS_FATAL_IF(status, "Failed to getDisplayConfigs, error %d", status);
    LOG_ALWAYS_FATAL_IF(configs.size() == 0, "getDisplayConfigs returned 0 configs?");
    float max = 0.0f;
    for (auto& info : configs) {
        max = std::max(max, info.fps);
    }
    return max;
}

static void queryWideColorGamutPreference(sk_sp<SkColorSpace>* colorSpace, SkColorType* colorType) {
    if (Properties::isolatedProcess) {
        *colorSpace = SkColorSpace::MakeSRGB();
        *colorType = SkColorType::kN32_SkColorType;
        return;
    }
    ui::Dataspace defaultDataspace, wcgDataspace;
    ui::PixelFormat defaultPixelFormat, wcgPixelFormat;
    status_t status =
        SurfaceComposerClient::getCompositionPreference(&defaultDataspace, &defaultPixelFormat,
                                                        &wcgDataspace, &wcgPixelFormat);
    LOG_ALWAYS_FATAL_IF(status, "Failed to get composition preference, error %d", status);
    switch (wcgDataspace) {
        case ui::Dataspace::DISPLAY_P3:
            *colorSpace = SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kDCIP3);
            break;
        case ui::Dataspace::V0_SCRGB:
            *colorSpace = SkColorSpace::MakeSRGB();
            break;
        case ui::Dataspace::V0_SRGB:
            // when sRGB is returned, it means wide color gamut is not supported.
            *colorSpace = SkColorSpace::MakeSRGB();
            break;
        default:
            LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
    }
    switch (wcgPixelFormat) {
        case ui::PixelFormat::RGBA_8888:
            *colorType = SkColorType::kN32_SkColorType;
            break;
        case ui::PixelFormat::RGBA_FP16:
            *colorType = SkColorType::kRGBA_F16_SkColorType;
            break;
        default:
            LOG_ALWAYS_FATAL("Unreachable: unsupported pixel format.");
    }
}

DeviceInfo::DeviceInfo() : mMaxRefreshRate(QueryMaxRefreshRate()) {
#if HWUI_NULL_GPU
        mMaxTextureSize = NULL_GPU_MAX_TEXTURE_SIZE;
#else
        mMaxTextureSize = -1;
#endif
    mDisplayInfo = QueryDisplayInfo();
    queryWideColorGamutPreference(&mWideColorSpace, &mWideColorType);
}

int DeviceInfo::maxTextureSize() const {
    LOG_ALWAYS_FATAL_IF(mMaxTextureSize < 0, "MaxTextureSize has not been initialized yet.");
    return mMaxTextureSize;
}

void DeviceInfo::setMaxTextureSize(int maxTextureSize) {
    DeviceInfo::get()->mMaxTextureSize = maxTextureSize;
}

void DeviceInfo::onDisplayConfigChanged() {
    mDisplayInfo = QueryDisplayInfo();
}

} /* namespace uirenderer */
} /* namespace android */
