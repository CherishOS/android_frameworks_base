/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.shared.pip;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.SurfaceControl;

/**
 * TODO(b/171721389): unify this class with
 * {@link com.android.wm.shell.pip.PipSurfaceTransactionHelper}, for instance, there should be one
 * source of truth on enabling/disabling and the actual value of corner radius.
 */
public class PipSurfaceTransactionHelper {
    private final Matrix mTmpTransform = new Matrix();
    private final float[] mTmpFloat9 = new float[9];
    private final RectF mTmpSourceRectF = new RectF();
    private final RectF mTmpDestinationRectF = new RectF();
    private final Rect mTmpDestinationRect = new Rect();

    public void scale(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds) {
        mTmpSourceRectF.set(sourceBounds);
        mTmpDestinationRectF.set(destinationBounds);
        mTmpTransform.setRectToRect(mTmpSourceRectF, mTmpDestinationRectF, Matrix.ScaleToFit.FILL);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setPosition(leash, mTmpDestinationRectF.left, mTmpDestinationRectF.top);
    }

    public void scale(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds,
            float degree, float positionX, float positionY) {
        mTmpSourceRectF.set(sourceBounds);
        mTmpDestinationRectF.set(destinationBounds);
        mTmpTransform.setRectToRect(mTmpSourceRectF, mTmpDestinationRectF, Matrix.ScaleToFit.FILL);
        mTmpTransform.postRotate(degree, 0, 0);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setPosition(leash, positionX, positionY);
    }

    public void scaleAndCrop(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds, Rect insets) {
        mTmpSourceRectF.set(sourceBounds);
        mTmpDestinationRect.set(sourceBounds);
        mTmpDestinationRect.inset(insets);
        // Scale by the shortest edge and offset such that the top/left of the scaled inset
        // source rect aligns with the top/left of the destination bounds
        final float scale = sourceBounds.width() <= sourceBounds.height()
                ? (float) destinationBounds.width() / sourceBounds.width()
                : (float) destinationBounds.height() / sourceBounds.height();
        final float left = destinationBounds.left - insets.left * scale;
        final float top = destinationBounds.top - insets.top * scale;
        mTmpTransform.setScale(scale, scale);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setWindowCrop(leash, mTmpDestinationRect)
                .setPosition(leash, left, top);
    }

    public void scaleAndRotate(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds, Rect insets,
            float degree, float positionX, float positionY) {
        mTmpSourceRectF.set(sourceBounds);
        mTmpDestinationRect.set(sourceBounds);
        mTmpDestinationRect.inset(insets);
        // Scale by the shortest edge and offset such that the top/left of the scaled inset
        // source rect aligns with the top/left of the destination bounds
        final float scale = sourceBounds.width() <= sourceBounds.height()
                ? (float) destinationBounds.width() / sourceBounds.width()
                : (float) destinationBounds.height() / sourceBounds.height();
        mTmpTransform.setRotate(degree, 0, 0);
        mTmpTransform.postScale(scale, scale);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setWindowCrop(leash, mTmpDestinationRect)
                .setPosition(leash, positionX, positionY);
    }

    public void resetCornerRadius(SurfaceControl.Transaction tx, SurfaceControl leash) {
        tx.setCornerRadius(leash, 0);
    }

    public void crop(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect destinationBounds) {
        tx.setWindowCrop(leash, destinationBounds.width(), destinationBounds.height())
                .setPosition(leash, destinationBounds.left, destinationBounds.top);
    }

    /** @return {@link SurfaceControl.Transaction} instance with vsync-id */
    public static SurfaceControl.Transaction newSurfaceControlTransaction() {
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());
        return tx;
    }
}
