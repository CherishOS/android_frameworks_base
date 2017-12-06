/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "SkiaOpenGLPipeline.h"

#include "hwui/Bitmap.h"
#include "DeferredLayerUpdater.h"
#include "GlLayer.h"
#include "LayerDrawable.h"
#include "renderthread/EglManager.h"
#include "renderthread/Frame.h"
#include "renderstate/RenderState.h"
#include "SkiaPipeline.h"
#include "SkiaProfileRenderer.h"
#include "utils/TraceUtils.h"

#include <GrBackendSurface.h>

#include <cutils/properties.h>
#include <strings.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaOpenGLPipeline::SkiaOpenGLPipeline(RenderThread& thread)
        : SkiaPipeline(thread)
        , mEglManager(thread.eglManager()) {
}

MakeCurrentResult SkiaOpenGLPipeline::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    EGLint error = 0;
    if (!mEglManager.makeCurrent(mEglSurface, &error)) {
        return MakeCurrentResult::AlreadyCurrent;
    }
    return error ? MakeCurrentResult::Failed : MakeCurrentResult::Succeeded;
}

Frame SkiaOpenGLPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
                "drawRenderNode called on a context with no surface!");
    return mEglManager.beginFrame(mEglSurface);
}

bool SkiaOpenGLPipeline::draw(const Frame& frame, const SkRect& screenDirty,
        const SkRect& dirty,
        const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque, bool wideColorGamut,
        const BakedOpRenderer::LightInfo& lightInfo,
        const std::vector<sp<RenderNode>>& renderNodes,
        FrameInfoVisualizer* profiler) {

    mEglManager.damageFrame(frame, dirty);

    // setup surface for fbo0
    GrGLFramebufferInfo fboInfo;
    fboInfo.fFBOID = 0;

    GrBackendRenderTarget backendRT(frame.width(), frame.height(), 0, STENCIL_BUFFER_SIZE,
            kRGBA_8888_GrPixelConfig, fboInfo);

    SkSurfaceProps props(0, kUnknown_SkPixelGeometry);

    SkASSERT(mRenderThread.getGrContext() != nullptr);
    sk_sp<SkSurface> surface(SkSurface::MakeFromBackendRenderTarget(
            mRenderThread.getGrContext(), backendRT, kBottomLeft_GrSurfaceOrigin, nullptr, &props));

    SkiaPipeline::updateLighting(lightGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, wideColorGamut,
            contentDrawBounds, surface);
    layerUpdateQueue->clear();

    // Draw visual debugging features
    if (CC_UNLIKELY(Properties::showDirtyRegions
            || ProfileType::None != Properties::getProfileType())) {
        SkCanvas* profileCanvas = surface->getCanvas();
        SkiaProfileRenderer profileRenderer(profileCanvas);
        profiler->draw(profileRenderer);
        profileCanvas->flush();
    }

    // Log memory statistics
    if (CC_UNLIKELY(Properties::debugLevel != kDebugDisabled)) {
        dumpResourceCacheUsage();
    }

    return true;
}

bool SkiaOpenGLPipeline::swapBuffers(const Frame& frame, bool drew,
        const SkRect& screenDirty, FrameInfo* currentFrameInfo, bool* requireSwap) {

    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    *requireSwap = drew || mEglManager.damageRequiresSwap();

    if (*requireSwap && (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty)))) {
        return false;
    }

    return *requireSwap;
}

bool SkiaOpenGLPipeline::copyLayerInto(DeferredLayerUpdater* deferredLayer, SkBitmap* bitmap) {
    if (!mRenderThread.getGrContext()) {
        return false;
    }

    // acquire most recent buffer for drawing
    deferredLayer->updateTexImage();
    deferredLayer->apply();

    SkCanvas canvas(*bitmap);
    Layer* layer = deferredLayer->backingLayer();
    return LayerDrawable::DrawLayer(mRenderThread.getGrContext(), &canvas, layer);
}

static Layer* createLayer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight,
        SkColorFilter* colorFilter, int alpha, SkBlendMode mode, bool blend) {
    GlLayer* layer = new GlLayer(renderState, layerWidth, layerHeight, colorFilter, alpha,
            mode, blend);
    layer->generateTexture();
    return layer;
}

DeferredLayerUpdater* SkiaOpenGLPipeline::createTextureLayer() {
    mEglManager.initialize();
    return new DeferredLayerUpdater(mRenderThread.renderState(), createLayer, Layer::Api::OpenGL);
}

void SkiaOpenGLPipeline::onStop() {
    if (mEglManager.isCurrent(mEglSurface)) {
        mEglManager.makeCurrent(EGL_NO_SURFACE);
    }
}

bool SkiaOpenGLPipeline::setSurface(Surface* surface, SwapBehavior swapBehavior,
        ColorMode colorMode) {

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        const bool wideColorGamut = colorMode == ColorMode::WideColorGamut;
        mEglSurface = mEglManager.createSurface(surface, wideColorGamut);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (swapBehavior != SwapBehavior::kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        return true;
    }

    return false;
}

bool SkiaOpenGLPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mEglSurface != EGL_NO_SURFACE);
}

bool SkiaOpenGLPipeline::isContextReady() {
    return CC_LIKELY(mEglManager.hasEglContext());
}

void SkiaOpenGLPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        mode = DrawGlInfo::kModeProcess;
    }

    (*functor)(mode, nullptr);

    // If there's no context we don't need to reset as there's no gl state to save/restore
    if (mode != DrawGlInfo::kModeProcessNoContext) {
        thread.getGrContext()->resetContext();
    }
}

#define FENCE_TIMEOUT 2000000000

class AutoEglFence {
public:
    AutoEglFence(EGLDisplay display)
            : mDisplay(display) {
        fence = eglCreateSyncKHR(mDisplay, EGL_SYNC_FENCE_KHR, NULL);
    }

    ~AutoEglFence() {
        if (fence != EGL_NO_SYNC_KHR) {
            eglDestroySyncKHR(mDisplay, fence);
        }
    }

    EGLSyncKHR fence = EGL_NO_SYNC_KHR;
private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
};

class AutoEglImage {
public:
    AutoEglImage(EGLDisplay display, EGLClientBuffer clientBuffer)
            : mDisplay(display) {
        EGLint imageAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        image = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttrs);
    }

    ~AutoEglImage() {
        if (image != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mDisplay, image);
        }
    }

    EGLImageKHR image = EGL_NO_IMAGE_KHR;
private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
};

class AutoSkiaGlTexture {
public:
    AutoSkiaGlTexture() {
        glGenTextures(1, &mTexture);
        glBindTexture(GL_TEXTURE_2D, mTexture);
    }

    ~AutoSkiaGlTexture() {
        glDeleteTextures(1, &mTexture);
    }

private:
    GLuint mTexture = 0;
};

sk_sp<Bitmap> SkiaOpenGLPipeline::allocateHardwareBitmap(renderthread::RenderThread& renderThread,
        SkBitmap& skBitmap) {
    renderThread.eglManager().initialize();

    sk_sp<GrContext> grContext = sk_ref_sp(renderThread.getGrContext());
    const SkImageInfo& info = skBitmap.info();
    PixelFormat pixelFormat;
    GLint format, type;
    bool isSupported = false;

    //TODO: add support for linear blending (when ANDROID_ENABLE_LINEAR_BLENDING is defined)
    switch (info.colorType()) {
    case kRGBA_8888_SkColorType:
        isSupported = true;
    // ARGB_4444 is upconverted to RGBA_8888
    case kARGB_4444_SkColorType:
        pixelFormat = PIXEL_FORMAT_RGBA_8888;
        format = GL_RGBA;
        type = GL_UNSIGNED_BYTE;
        break;
    case kRGBA_F16_SkColorType:
        isSupported = grContext->caps()->isConfigTexturable(kRGBA_half_GrPixelConfig);
        if (isSupported) {
            type = GL_HALF_FLOAT;
            pixelFormat = PIXEL_FORMAT_RGBA_FP16;
        } else {
            type = GL_UNSIGNED_BYTE;
            pixelFormat = PIXEL_FORMAT_RGBA_8888;
        }
        format = GL_RGBA;
        break;
    case kRGB_565_SkColorType:
        isSupported = true;
        pixelFormat = PIXEL_FORMAT_RGB_565;
        format = GL_RGB;
        type = GL_UNSIGNED_SHORT_5_6_5;
        break;
    case kGray_8_SkColorType:
        isSupported = true;
        pixelFormat = PIXEL_FORMAT_RGBA_8888;
        format = GL_LUMINANCE;
        type = GL_UNSIGNED_BYTE;
        break;
    default:
        ALOGW("unable to create hardware bitmap of colortype: %d", info.colorType());
        return nullptr;
    }

    auto colorSpace = info.colorSpace();
    bool convertToSRGB = false;
    if (colorSpace && (!colorSpace->isSRGB())) {
        isSupported = false;
        convertToSRGB = true;
    }

    SkBitmap bitmap;
    if (isSupported) {
        bitmap = skBitmap;
    } else {
        bitmap.allocPixels(SkImageInfo::MakeN32(info.width(), info.height(), info.alphaType(),
                nullptr));
        bitmap.eraseColor(0);
        if (info.colorType() == kRGBA_F16_SkColorType || convertToSRGB) {
            // Drawing RGBA_F16 onto ARGB_8888 is not supported
            skBitmap.readPixels(bitmap.info().makeColorSpace(SkColorSpace::MakeSRGB()),
                    bitmap.getPixels(), bitmap.rowBytes(), 0, 0);
        } else {
            SkCanvas canvas(bitmap);
            canvas.drawBitmap(skBitmap, 0.0f, 0.0f, nullptr);
        }
    }

    sp<GraphicBuffer> buffer = new GraphicBuffer(info.width(), info.height(), pixelFormat,
            GraphicBuffer::USAGE_HW_TEXTURE |
            GraphicBuffer::USAGE_SW_WRITE_NEVER |
            GraphicBuffer::USAGE_SW_READ_NEVER,
            std::string("Bitmap::allocateSkiaHardwareBitmap pid [") + std::to_string(getpid()) + "]");

    status_t error = buffer->initCheck();
    if (error < 0) {
        ALOGW("createGraphicBuffer() failed in GraphicBuffer.create()");
        return nullptr;
    }

    //upload the bitmap into a texture
    EGLDisplay display = eglGetCurrentDisplay();
    LOG_ALWAYS_FATAL_IF(display == EGL_NO_DISPLAY,
                "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                uirenderer::renderthread::EglManager::eglErrorString());
    // We use an EGLImage to access the content of the GraphicBuffer
    // The EGL image is later bound to a 2D texture
    EGLClientBuffer clientBuffer = (EGLClientBuffer) buffer->getNativeBuffer();
    AutoEglImage autoImage(display, clientBuffer);
    if (autoImage.image == EGL_NO_IMAGE_KHR) {
        ALOGW("Could not create EGL image, err =%s",
                uirenderer::renderthread::EglManager::eglErrorString());
        return nullptr;
    }
    AutoSkiaGlTexture glTexture;
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, autoImage.image);
    GL_CHECKPOINT(MODERATE);

    // glTexSubImage2D is synchronous in sense that it memcpy() from pointer that we provide.
    // But asynchronous in sense that driver may upload texture onto hardware buffer when we first
    // use it in drawing
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, info.width(), info.height(), format, type,
            bitmap.getPixels());
    GL_CHECKPOINT(MODERATE);

    // The fence is used to wait for the texture upload to finish
    // properly. We cannot rely on glFlush() and glFinish() as
    // some drivers completely ignore these API calls
    AutoEglFence autoFence(display);
    if (autoFence.fence == EGL_NO_SYNC_KHR) {
        LOG_ALWAYS_FATAL("Could not create sync fence %#x", eglGetError());
        return nullptr;
    }
    // The flag EGL_SYNC_FLUSH_COMMANDS_BIT_KHR will trigger a
    // pipeline flush (similar to what a glFlush() would do.)
    EGLint waitStatus = eglClientWaitSyncKHR(display, autoFence.fence,
            EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, FENCE_TIMEOUT);
    if (waitStatus != EGL_CONDITION_SATISFIED_KHR) {
        LOG_ALWAYS_FATAL("Failed to wait for the fence %#x", eglGetError());
        return nullptr;
    }

    grContext->resetContext(kTextureBinding_GrGLBackendState);

    return sk_sp<Bitmap>(new Bitmap(buffer.get(), bitmap.info()));
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
