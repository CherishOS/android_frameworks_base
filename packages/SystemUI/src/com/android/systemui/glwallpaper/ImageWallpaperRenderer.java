/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.glwallpaper;

import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glUniform1f;
import static android.opengl.GLES20.glViewport;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;
import android.util.MathUtils;

import com.android.systemui.ImageWallpaper;
import com.android.systemui.ImageWallpaper.ImageGLView;
import com.android.systemui.R;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A GL renderer for image wallpaper.
 */
public class ImageWallpaperRenderer implements GLSurfaceView.Renderer,
        ImageWallpaper.WallpaperStatusListener, ImageRevealHelper.RevealStateListener {
    private static final String TAG = ImageWallpaperRenderer.class.getSimpleName();
    private static final float SCALE_VIEWPORT_MIN = 0.98f;
    private static final float SCALE_VIEWPORT_MAX = 1f;

    private final WallpaperManager mWallpaperManager;
    private final ImageGLProgram mProgram;
    private final ImageGLWallpaper mWallpaper;
    private final ImageProcessHelper mImageProcessHelper;
    private final ImageRevealHelper mImageRevealHelper;
    private final ImageGLView mGLView;
    private float mXOffset = 0f;
    private float mYOffset = 0f;
    private int mWidth = 0;
    private int mHeight = 0;

    private Bitmap mBitmap;
    private int mBitmapWidth = 0;
    private int mBitmapHeight = 0;

    public ImageWallpaperRenderer(Context context, ImageGLView glView) {
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
        if (mWallpaperManager == null) {
            Log.w(TAG, "WallpaperManager not available");
        }

        mProgram = new ImageGLProgram(context);
        mWallpaper = new ImageGLWallpaper(mProgram);
        mImageProcessHelper = new ImageProcessHelper();
        mImageRevealHelper = new ImageRevealHelper(this);
        mGLView = glView;

        if (mWallpaperManager != null) {
            mBitmap = mWallpaperManager.getBitmap();
            mBitmapWidth = mBitmap.getWidth();
            mBitmapHeight = mBitmap.getHeight();
            // Compute threshold of the image, this is an async work.
            mImageProcessHelper.start(mBitmap);
            mWallpaperManager.forgetLoadedWallpaper();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        glClearColor(0f, 0f, 0f, 1.0f);
        mProgram.useGLProgram(
                R.raw.image_wallpaper_vertex_shader, R.raw.image_wallpaper_fragment_shader);
        mWallpaper.setup(mBitmap);
        mBitmap = null;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        glViewport(0, 0, width, height);
        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height
                    + ", xOffset=" + mXOffset + ", yOffset=" + mYOffset);
        }
        mWidth = width;
        mHeight = height;
        mWallpaper.adjustTextureCoordinates(
                mBitmapWidth, mBitmapHeight, width, height, mXOffset, mYOffset);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        float threshold = mImageProcessHelper.getThreshold();
        float reveal = mImageRevealHelper.getReveal();

        glClear(GL_COLOR_BUFFER_BIT);

        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_AOD2OPACITY), 1);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_PER85), threshold);
        glUniform1f(mWallpaper.getHandle(ImageGLWallpaper.U_REVEAL), reveal);

        scaleViewport(reveal);
        mWallpaper.useTexture();
        mWallpaper.draw();
    }

    private void scaleViewport(float reveal) {
        // Interpolation between SCALE_VIEWPORT_MAX and SCALE_VIEWPORT_MIN by reveal.
        float vpScaled = MathUtils.lerp(SCALE_VIEWPORT_MAX, SCALE_VIEWPORT_MIN, reveal);
        // Calculate the offset amount from the lower left corner.
        float offset = (SCALE_VIEWPORT_MAX - vpScaled) / 2;
        // Change the viewport.
        glViewport((int) (mWidth * offset), (int) (mHeight * offset),
                (int) (mWidth * vpScaled), (int) (mHeight * vpScaled));
    }

    @Override
    public void onAmbientModeChanged(boolean inAmbientMode, long duration) {
        mImageRevealHelper.updateAwake(!inAmbientMode, duration);
        requestRender();
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset, Rect frame) {
        if (frame == null || (xOffset == mXOffset && yOffset == mYOffset)) {
            return;
        }

        int width = frame.width();
        int height = frame.height();
        mXOffset = xOffset;
        mYOffset = yOffset;

        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, "onOffsetsChanged: width=" + width + ", height=" + height
                    + ", xOffset=" + mXOffset + ", yOffset=" + mYOffset);
        }
        mWallpaper.adjustTextureCoordinates(
                mBitmapWidth, mBitmapHeight, width, height, mXOffset, mYOffset);
        requestRender();
    }

    @Override
    public void onRevealStateChanged() {
        requestRender();
    }

    private void requestRender() {
        if (mGLView != null) {
            mGLView.render();
        }
    }
}
