/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.util.TimeUtils.NANOS_PER_MS;
import static android.view.Choreographer.CALLBACK_TRAVERSAL;
import static android.view.Choreographer.getSfInstance;

import android.animation.AnimationHandler;
import android.animation.AnimationHandler.AnimationFrameCallbackProvider;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.server.AnimationThread;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;

/**
 * Class to run animations without holding the window manager lock.
 */
class SurfaceAnimationRunner {

    private final Object mLock = new Object();

    /**
     * Lock for cancelling animations. Must be acquired on it's own, or after acquiring
     * {@link #mLock}
     */
    private final Object mCancelLock = new Object();

    @VisibleForTesting
    Choreographer mChoreographer;

    private final Runnable mApplyTransactionRunnable = this::applyTransaction;
    private final AnimationHandler mAnimationHandler;
    private final Transaction mFrameTransaction;
    private final AnimatorFactory mAnimatorFactory;
    private boolean mApplyScheduled;

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mPendingAnimations = new ArrayMap<>();

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mRunningAnimations = new ArrayMap<>();

    SurfaceAnimationRunner() {
        this(null /* callbackProvider */, null /* animatorFactory */, new Transaction());
    }

    @VisibleForTesting
    SurfaceAnimationRunner(@Nullable AnimationFrameCallbackProvider callbackProvider,
            AnimatorFactory animatorFactory, Transaction frameTransaction) {
        SurfaceAnimationThread.getHandler().runWithScissors(() -> mChoreographer = getSfInstance(),
                0 /* timeout */);
        mFrameTransaction = frameTransaction;
        mAnimationHandler = new AnimationHandler();
        mAnimationHandler.setProvider(callbackProvider != null
                ? callbackProvider
                : new SfVsyncFrameCallbackProvider(mChoreographer));
        mAnimatorFactory = animatorFactory != null
                ? animatorFactory
                : SfValueAnimator::new;
    }

    void startAnimation(AnimationSpec a, SurfaceControl animationLeash, Transaction t,
            Runnable finishCallback) {
        synchronized (mLock) {
            final RunningAnimation runningAnim = new RunningAnimation(a, animationLeash,
                    finishCallback);
            mPendingAnimations.put(animationLeash, runningAnim);
            mChoreographer.postFrameCallback(this::stepAnimation);

            // Some animations (e.g. move animations) require the initial transform to be applied
            // immediately.
            applyTransformation(runningAnim, t, 0 /* currentPlayTime */);
        }
    }

    void onAnimationCancelled(SurfaceControl leash) {
        synchronized (mLock) {
            if (mPendingAnimations.containsKey(leash)) {
                mPendingAnimations.remove(leash);
                return;
            }
            final RunningAnimation anim = mRunningAnimations.get(leash);
            if (anim != null) {
                mRunningAnimations.remove(leash);
                synchronized (mCancelLock) {
                    anim.mCancelled = true;
                }
                SurfaceAnimationThread.getHandler().post(() -> {
                    anim.mAnim.cancel();
                    applyTransaction();
                });
            }
        }
    }

    private void startPendingAnimationsLocked() {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            startAnimationLocked(mPendingAnimations.valueAt(i));
        }
        mPendingAnimations.clear();
    }

    private void startAnimationLocked(RunningAnimation a) {
        final ValueAnimator anim = mAnimatorFactory.makeAnimator();

        // Animation length is already expected to be scaled.
        anim.overrideDurationScale(1.0f);
        anim.setDuration(a.mAnimSpec.getDuration());
        anim.addUpdateListener(animation -> {
            synchronized (mCancelLock) {
                if (!a.mCancelled) {
                    applyTransformation(a, mFrameTransaction, anim.getCurrentPlayTime());
                }
            }

            // Transaction will be applied in the commit phase.
            scheduleApplyTransaction();
        });

        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                synchronized (mCancelLock) {
                    if (!a.mCancelled) {
                        mFrameTransaction.show(a.mLeash);
                    }
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mLock) {
                    mRunningAnimations.remove(a.mLeash);
                    synchronized (mCancelLock) {
                        if (!a.mCancelled) {

                            // Post on other thread that we can push final state without jank.
                            AnimationThread.getHandler().post(a.mFinishCallback);
                        }
                    }
                }
            }
        });
        anim.start();
        if (a.mAnimSpec.canSkipFirstFrame()) {
            // If we can skip the first frame, we start one frame later.
            anim.setCurrentPlayTime(mChoreographer.getFrameIntervalNanos() / NANOS_PER_MS);
        }

        // Immediately start the animation by manually applying an animation frame. Otherwise, the
        // start time would only be set in the next frame, leading to a delay.
        anim.doAnimationFrame(mChoreographer.getFrameTime());
        a.mAnim = anim;
        mRunningAnimations.put(a.mLeash, a);
    }

    private void applyTransformation(RunningAnimation a, Transaction t, long currentPlayTime) {
        a.mAnimSpec.apply(t, a.mLeash, currentPlayTime);
    }

    private void stepAnimation(long frameTimeNanos) {
        synchronized (mLock) {
            startPendingAnimationsLocked();
        }
    }

    private void scheduleApplyTransaction() {
        if (!mApplyScheduled) {
            mChoreographer.postCallback(CALLBACK_TRAVERSAL, mApplyTransactionRunnable,
                    null /* token */);
            mApplyScheduled = true;
        }
    }

    private void applyTransaction() {
        mFrameTransaction.setAnimationTransaction();
        mFrameTransaction.apply();
        mApplyScheduled = false;
    }

    private static final class RunningAnimation {
        final AnimationSpec mAnimSpec;
        final SurfaceControl mLeash;
        final Runnable mFinishCallback;
        ValueAnimator mAnim;

        @GuardedBy("mCancelLock")
        private boolean mCancelled;

        RunningAnimation(AnimationSpec animSpec, SurfaceControl leash, Runnable finishCallback) {
            mAnimSpec = animSpec;
            mLeash = leash;
            mFinishCallback = finishCallback;
        }
    }

    @VisibleForTesting
    interface AnimatorFactory {
        ValueAnimator makeAnimator();
    }

    /**
     * Value animator that uses sf-vsync signal to tick.
     */
    private class SfValueAnimator extends ValueAnimator {

        SfValueAnimator() {
            setFloatValues(0f, 1f);
        }

        @Override
        public AnimationHandler getAnimationHandler() {
            return mAnimationHandler;
        }
    }
}
