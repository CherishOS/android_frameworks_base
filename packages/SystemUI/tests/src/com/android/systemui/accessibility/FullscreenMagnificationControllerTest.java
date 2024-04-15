/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.os.Build.HW_TIMEOUT_MULTIPLIER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.window.InputTransferToken;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class FullscreenMagnificationControllerTest extends SysuiTestCase {
    private static final long ANIMATION_DURATION_MS = 100L;
    private static final long WAIT_TIMEOUT_S = 5L * HW_TIMEOUT_MULTIPLIER;
    private static final long ANIMATION_TIMEOUT_MS =
            5L * ANIMATION_DURATION_MS * HW_TIMEOUT_MULTIPLIER;
    private FullscreenMagnificationController mFullscreenMagnificationController;
    private SurfaceControlViewHost mSurfaceControlViewHost;
    private ValueAnimator mShowHideBorderAnimator;
    private SurfaceControl.Transaction mTransaction;

    @Before
    public void setUp() {
        getInstrumentation().runOnMainSync(() -> mSurfaceControlViewHost =
                spy(new SurfaceControlViewHost(mContext, mContext.getDisplay(),
                        new InputTransferToken(), "FullscreenMagnification")));
        Supplier<SurfaceControlViewHost> scvhSupplier = () -> mSurfaceControlViewHost;

        mTransaction = new SurfaceControl.Transaction();
        mShowHideBorderAnimator = spy(newNullTargetObjectAnimator());
        mFullscreenMagnificationController = new FullscreenMagnificationController(
                mContext,
                mContext.getMainExecutor(),
                mContext.getSystemService(AccessibilityManager.class),
                mContext.getSystemService(WindowManager.class),
                scvhSupplier,
                mTransaction,
                mShowHideBorderAnimator);
    }

    @After
    public void tearDown() {
        getInstrumentation().runOnMainSync(
                () -> mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(false));
    }

    @Test
    public void enableFullscreenMagnification_visibleBorder() throws InterruptedException {
        CountDownLatch transactionCommittedLatch = new CountDownLatch(1);
        CountDownLatch animationEndLatch = new CountDownLatch(1);
        mTransaction.addTransactionCommittedListener(
                Runnable::run, transactionCommittedLatch::countDown);
        mShowHideBorderAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animationEndLatch.countDown();
            }
        });
        getInstrumentation().runOnMainSync(() ->
                //Enable fullscreen magnification
                mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(true));
        assertTrue("Failed to wait for transaction committed",
                transactionCommittedLatch.await(WAIT_TIMEOUT_S, TimeUnit.SECONDS));
        assertTrue("Failed to wait for animation to be finished",
                animationEndLatch.await(ANIMATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mShowHideBorderAnimator).start();
        assertThat(mSurfaceControlViewHost.getView().isVisibleToUser()).isTrue();
    }

    @Test
    public void disableFullscreenMagnification_reverseAnimationAndReleaseScvh()
            throws InterruptedException {
        CountDownLatch transactionCommittedLatch = new CountDownLatch(1);
        CountDownLatch enableAnimationEndLatch = new CountDownLatch(1);
        CountDownLatch disableAnimationEndLatch = new CountDownLatch(1);
        mTransaction.addTransactionCommittedListener(
                Runnable::run, transactionCommittedLatch::countDown);
        mShowHideBorderAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation, boolean isReverse) {
                if (isReverse) {
                    disableAnimationEndLatch.countDown();
                } else {
                    enableAnimationEndLatch.countDown();
                }
            }
        });
        getInstrumentation().runOnMainSync(() ->
                //Enable fullscreen magnification
                mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(true));
        assertTrue("Failed to wait for transaction committed",
                transactionCommittedLatch.await(WAIT_TIMEOUT_S, TimeUnit.SECONDS));
        assertTrue("Failed to wait for enabling animation to be finished",
                enableAnimationEndLatch.await(
                        ANIMATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mShowHideBorderAnimator).start();

        getInstrumentation().runOnMainSync(() ->
                // Disable fullscreen magnification
                mFullscreenMagnificationController
                        .onFullscreenMagnificationActivationChanged(false));

        assertTrue("Failed to wait for disabling animation to be finished",
                disableAnimationEndLatch.await(
                        ANIMATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mShowHideBorderAnimator).reverse();
        verify(mSurfaceControlViewHost).release();
    }

    @Test
    public void onFullscreenMagnificationActivationChangeTrue_deactivating_reverseAnimator()
            throws InterruptedException {
        // Simulate the hiding border animation is running
        when(mShowHideBorderAnimator.isRunning()).thenReturn(true);
        CountDownLatch transactionCommittedLatch = new CountDownLatch(1);
        CountDownLatch animationEndLatch = new CountDownLatch(1);
        mTransaction.addTransactionCommittedListener(
                Runnable::run, transactionCommittedLatch::countDown);
        mShowHideBorderAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animationEndLatch.countDown();
            }
        });

        getInstrumentation().runOnMainSync(
                () -> mFullscreenMagnificationController
                            .onFullscreenMagnificationActivationChanged(true));

        assertTrue("Failed to wait for transaction committed",
                transactionCommittedLatch.await(WAIT_TIMEOUT_S, TimeUnit.SECONDS));
        assertTrue("Failed to wait for animation to be finished",
                animationEndLatch.await(ANIMATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mShowHideBorderAnimator).reverse();
    }

    private ValueAnimator newNullTargetObjectAnimator() {
        final ValueAnimator animator =
                ObjectAnimator.ofFloat(/* target= */ null, View.ALPHA, 0f, 1f);
        Interpolator interpolator = new DecelerateInterpolator(2.5f);
        animator.setInterpolator(interpolator);
        animator.setDuration(ANIMATION_DURATION_MS);
        return animator;
    }
}
