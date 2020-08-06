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

package com.android.systemui.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.view.SurfaceControl;
import android.view.animation.AccelerateInterpolator;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicReference;


@MediumTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationAnimationControllerTest extends SysuiTestCase {

    private static final float DEFAULT_SCALE = 3.0f;
    private static final float DEFAULT_CENTER_X = 400.0f;
    private static final float DEFAULT_CENTER_Y = 500.0f;
    private static final long ANIMATION_DURATION_MS = 100;

    private AtomicReference<Float> mCurrentScale = new AtomicReference<>((float) 0);
    private AtomicReference<Float> mCurrentCenterX = new AtomicReference<>((float) 0);
    private AtomicReference<Float> mCurrentCenterY = new AtomicReference<>((float) 0);
    private ArgumentCaptor<Float> mScaleCaptor = ArgumentCaptor.forClass(Float.class);
    private ArgumentCaptor<Float> mCenterXCaptor = ArgumentCaptor.forClass(Float.class);
    private ArgumentCaptor<Float> mCenterYCaptor = ArgumentCaptor.forClass(Float.class);

    @Mock
    Handler mHandler;
    @Mock
    SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    WindowMagnifierCallback mWindowMagnifierCallback;

    private SpyWindowMagnificationController mController;
    private WindowMagnificationController mSpyController;
    private WindowMagnificationAnimationController mWindowMagnificationAnimationController;
    private Instrumentation mInstrumentation;
    private long mWaitingAnimationPeriod;
    private long mWaitIntermediateAnimationPeriod;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mWaitingAnimationPeriod = ANIMATION_DURATION_MS + 50;
        mWaitIntermediateAnimationPeriod = ANIMATION_DURATION_MS / 2;
        mController = new SpyWindowMagnificationController(mContext, mHandler,
                mSfVsyncFrameProvider, null, new SurfaceControl.Transaction(),
                mWindowMagnifierCallback);
        mSpyController = mController.getSpyController();
        mWindowMagnificationAnimationController = new WindowMagnificationAnimationController(
                mContext, mController, newValueAnimator());
    }

    @Test
    public void enableWindowMagnification_disabled_expectedStartAndEndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        verifyStartValue(mScaleCaptor, 1.0f);
        verifyStartValue(mCenterXCaptor, DEFAULT_CENTER_X);
        verifyStartValue(mCenterYCaptor, DEFAULT_CENTER_Y);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void enableWindowMagnification_enabling_expectedStartAndEndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(() -> {
            Mockito.reset(mSpyController);
            mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                    targetCenterX, targetCenterY);
            mCurrentScale.set(mController.getScale());
            mCurrentCenterX.set(mController.getCenterX());
            mCurrentCenterY.set(mController.getCenterY());
        });

        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        verifyStartValue(mScaleCaptor, mCurrentScale.get());
        verifyStartValue(mCenterXCaptor, mCurrentCenterX.get());
        verifyStartValue(mCenterYCaptor, mCurrentCenterY.get());
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnification_disabling_expectedStartAndEndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod);
        final float targetScale = DEFAULT_SCALE + 1.0f;
        final float targetCenterX = DEFAULT_CENTER_X + 100;
        final float targetCenterY = DEFAULT_CENTER_Y + 100;

        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(targetScale,
                            targetCenterX, targetCenterY);
                    mCurrentScale.set(mController.getScale());
                    mCurrentCenterX.set(mController.getCenterX());
                    mCurrentCenterY.set(mController.getCenterY());
                });
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(
                mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        //Animating in reverse, so we only check if the start values are greater than current.
        assertTrue(mScaleCaptor.getAllValues().get(0) > mCurrentScale.get());
        assertEquals(targetScale, mScaleCaptor.getValue(), 0f);
        assertTrue(mCenterXCaptor.getAllValues().get(0) > mCurrentCenterX.get());
        assertEquals(targetCenterX, mCenterXCaptor.getValue(), 0f);
        assertTrue(mCenterYCaptor.getAllValues().get(0) > mCurrentCenterY.get());
        assertEquals(targetCenterY, mCenterYCaptor.getValue(), 0f);
        verifyFinalSpec(targetScale, targetCenterX, targetCenterY);
    }

    @Test
    public void enableWindowMagnificationWithSameScale_doNothing() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        verify(mSpyController, never()).enableWindowMagnification(anyFloat(), anyFloat(),
                anyFloat());
    }

    @Test
    public void setScale_enabled_expectedScale() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationAnimationController.setScale(DEFAULT_SCALE + 1));

        verify(mSpyController).setScale(DEFAULT_SCALE + 1);
        verifyFinalSpec(DEFAULT_SCALE + 1, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
    }

    @Test
    public void deleteWindowMagnification_enabled_expectedStartAndEndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        verify(mSpyController).deleteWindowMagnification();
        verifyStartValue(mScaleCaptor, DEFAULT_SCALE);
        verifyStartValue(mCenterXCaptor, Float.NaN);
        verifyStartValue(mCenterYCaptor, Float.NaN);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
    }

    @Test
    public void deleteWindowMagnification_disabled_doNothing() {
        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        Mockito.verifyNoMoreInteractions(mSpyController);
    }

    @Test
    public void deleteWindowMagnification_enabling_checkStartAndEndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod);

        //It just reverse the animation, so we don't need to wait the whole duration.
        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.deleteWindowMagnification();
                    mCurrentScale.set(mController.getScale());
                    mCurrentCenterX.set(mController.getCenterX());
                    mCurrentCenterY.set(mController.getCenterY());
                });
        SystemClock.sleep(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        verify(mSpyController).deleteWindowMagnification();

        //The animation is in verse, so we only check the start values should no be greater than
        // the current one.
        assertTrue(mScaleCaptor.getAllValues().get(0) <= mCurrentScale.get());
        assertEquals(1.0f, mScaleCaptor.getValue(), 0f);
        verifyStartValue(mCenterXCaptor, Float.NaN);
        verifyStartValue(mCenterYCaptor, Float.NaN);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
    }

    @Test
    public void deleteWindowMagnification_disabling_checkStartAndValues() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);
        deleteWindowMagnificationAndWaitAnimating(mWaitIntermediateAnimationPeriod);

        deleteWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        verify(mSpyController, atLeast(2)).enableWindowMagnification(mScaleCaptor.capture(),
                mCenterXCaptor.capture(), mCenterYCaptor.capture());
        verify(mSpyController).deleteWindowMagnification();
        assertEquals(1.0f, mScaleCaptor.getValue(), 0f);
        verifyFinalSpec(Float.NaN, Float.NaN, Float.NaN);
    }

    @Test
    public void moveWindowMagnifier_enabled() {
        enableWindowMagnificationAndWaitAnimating(mWaitingAnimationPeriod);

        mInstrumentation.runOnMainSync(
                () -> mWindowMagnificationAnimationController.moveWindowMagnifier(100f, 200f));

        verify(mSpyController).moveWindowMagnifier(100f, 200f);
        verifyFinalSpec(DEFAULT_SCALE, DEFAULT_CENTER_X + 100f, DEFAULT_CENTER_Y + 100f);
    }

    @Test
    public void onConfigurationChanged_passThrough() {
        mWindowMagnificationAnimationController.onConfigurationChanged(100);

        verify(mSpyController).onConfigurationChanged(100);
    }
    private void verifyFinalSpec(float expectedScale, float expectedCenterX,
            float expectedCenterY) {
        assertEquals(expectedScale, mController.getScale(), 0f);
        assertEquals(expectedCenterX, mController.getCenterX(), 0f);
        assertEquals(expectedCenterY, mController.getCenterY(), 0f);
    }

    private void enableWindowMagnificationAndWaitAnimating(long duration) {
        mInstrumentation.runOnMainSync(
                () -> {
                    Mockito.reset(mSpyController);
                    mWindowMagnificationAnimationController.enableWindowMagnification(DEFAULT_SCALE,
                            DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
                });
        SystemClock.sleep(duration);
    }

    private void deleteWindowMagnificationAndWaitAnimating(long duration) {
        mInstrumentation.runOnMainSync(
                () -> {
                    resetMockObjects();
                    mWindowMagnificationAnimationController.deleteWindowMagnification();
                });
        SystemClock.sleep(duration);
    }

    private void verifyStartValue(ArgumentCaptor<Float> captor, float startValue) {
        assertEquals(startValue, captor.getAllValues().get(0), 0f);
    }

    private void resetMockObjects() {
        Mockito.reset(mSpyController);
    }

    /**
     * It observes the methods in {@link WindowMagnificationController} since we couldn't spy it
     * directly.
     */
    private static class SpyWindowMagnificationController extends WindowMagnificationController {
        private WindowMagnificationController mSpyController;

        SpyWindowMagnificationController(Context context, Handler handler,
                SfVsyncFrameCallbackProvider sfVsyncFrameProvider,
                MirrorWindowControl mirrorWindowControl, SurfaceControl.Transaction transaction,
                WindowMagnifierCallback callback) {
            super(context, handler, sfVsyncFrameProvider, mirrorWindowControl, transaction,
                    callback);
            mSpyController = Mockito.mock(WindowMagnificationController.class);
        }

        WindowMagnificationController getSpyController() {
            return mSpyController;
        }

        @Override
        void enableWindowMagnification(float scale, float centerX, float centerY) {
            super.enableWindowMagnification(scale, centerX, centerY);
            mSpyController.enableWindowMagnification(scale, centerX, centerY);
        }

        @Override
        void deleteWindowMagnification() {
            super.deleteWindowMagnification();
            mSpyController.deleteWindowMagnification();
        }

        @Override
        void moveWindowMagnifier(float offsetX, float offsetY) {
            super.moveWindowMagnifier(offsetX, offsetX);
            mSpyController.moveWindowMagnifier(offsetX, offsetY);
        }

        @Override
        void setScale(float scale) {
            super.setScale(scale);
            mSpyController.setScale(scale);
        }

        @Override
        void onConfigurationChanged(int configDiff) {
            super.onConfigurationChanged(configDiff);
            mSpyController.onConfigurationChanged(configDiff);
        }

    }

    private static ValueAnimator newValueAnimator() {
        final ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(ANIMATION_DURATION_MS);
        valueAnimator.setInterpolator(new AccelerateInterpolator(2.5f));
        valueAnimator.setFloatValues(0.0f, 1.0f);
        return valueAnimator;
    }
}
