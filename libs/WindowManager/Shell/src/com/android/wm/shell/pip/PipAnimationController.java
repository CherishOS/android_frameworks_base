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

package com.android.wm.shell.pip;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.graphics.Rect;
import android.view.Choreographer;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.wm.shell.animation.Interpolators;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controller class of PiP animations (both from and to PiP mode).
 */
public class PipAnimationController {
    private static final float FRACTION_START = 0f;
    private static final float FRACTION_END = 1f;

    public static final int ANIM_TYPE_BOUNDS = 0;
    public static final int ANIM_TYPE_ALPHA = 1;

    @IntDef(prefix = { "ANIM_TYPE_" }, value = {
            ANIM_TYPE_BOUNDS,
            ANIM_TYPE_ALPHA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnimationType {}

    public static final int TRANSITION_DIRECTION_NONE = 0;
    public static final int TRANSITION_DIRECTION_SAME = 1;
    public static final int TRANSITION_DIRECTION_TO_PIP = 2;
    public static final int TRANSITION_DIRECTION_LEAVE_PIP = 3;
    public static final int TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN = 4;
    public static final int TRANSITION_DIRECTION_REMOVE_STACK = 5;
    public static final int TRANSITION_DIRECTION_SNAP_AFTER_RESIZE = 6;

    @IntDef(prefix = { "TRANSITION_DIRECTION_" }, value = {
            TRANSITION_DIRECTION_NONE,
            TRANSITION_DIRECTION_SAME,
            TRANSITION_DIRECTION_TO_PIP,
            TRANSITION_DIRECTION_LEAVE_PIP,
            TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN,
            TRANSITION_DIRECTION_REMOVE_STACK,
            TRANSITION_DIRECTION_SNAP_AFTER_RESIZE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionDirection {}

    public static boolean isInPipDirection(@TransitionDirection int direction) {
        return direction == TRANSITION_DIRECTION_TO_PIP;
    }

    public static boolean isOutPipDirection(@TransitionDirection int direction) {
        return direction == TRANSITION_DIRECTION_LEAVE_PIP
                || direction == TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN;
    }

    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;

    private PipTransitionAnimator mCurrentAnimator;

    private ThreadLocal<AnimationHandler> mSfAnimationHandlerThreadLocal =
            ThreadLocal.withInitial(() -> {
                AnimationHandler handler = new AnimationHandler();
                handler.setProvider(new SfVsyncFrameCallbackProvider());
                return handler;
            });

    public PipAnimationController(PipSurfaceTransactionHelper helper) {
        mSurfaceTransactionHelper = helper;
    }

    @SuppressWarnings("unchecked")
    @VisibleForTesting
    public PipTransitionAnimator getAnimator(SurfaceControl leash,
            Rect destinationBounds, float alphaStart, float alphaEnd) {
        if (mCurrentAnimator == null) {
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofAlpha(leash, destinationBounds, alphaStart, alphaEnd));
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_ALPHA
                && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.updateEndValue(alphaEnd);
        } else {
            mCurrentAnimator.cancel();
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofAlpha(leash, destinationBounds, alphaStart, alphaEnd));
        }
        return mCurrentAnimator;
    }

    @SuppressWarnings("unchecked")
    /**
     * Construct and return an animator that animates from the {@param startBounds} to the
     * {@param endBounds} with the given {@param direction}. If {@param direction} is type
     * {@link ANIM_TYPE_BOUNDS}, then {@param sourceHintRect} will be used to animate
     * in a better, more smooth manner.
     *
     * In the case where one wants to start animation during an intermediate animation (for example,
     * if the user is currently doing a pinch-resize, and upon letting go now PiP needs to animate
     * to the correct snap fraction region), then provide the base bounds, which is current PiP
     * leash bounds before transformation/any animation. This is so when we try to construct
     * the different transformation matrices for the animation, we are constructing this based off
     * the PiP original bounds, rather than the {@param startBounds}, which is post-transformed.
     */
    @VisibleForTesting
    public PipTransitionAnimator getAnimator(SurfaceControl leash, Rect baseBounds,
            Rect startBounds, Rect endBounds, Rect sourceHintRect,
            @PipAnimationController.TransitionDirection int direction) {
        if (mCurrentAnimator == null) {
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, startBounds, startBounds, endBounds,
                            sourceHintRect, direction));
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_ALPHA
                && mCurrentAnimator.isRunning()) {
            // If we are still animating the fade into pip, then just move the surface and ensure
            // we update with the new destination bounds, but don't interrupt the existing animation
            // with a new bounds
            mCurrentAnimator.setDestinationBounds(endBounds);
        } else if (mCurrentAnimator.getAnimationType() == ANIM_TYPE_BOUNDS
                && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.setDestinationBounds(endBounds);
            // construct new Rect instances in case they are recycled
            mCurrentAnimator.updateEndValue(new Rect(endBounds));
        } else {
            mCurrentAnimator.cancel();
            mCurrentAnimator = setupPipTransitionAnimator(
                    PipTransitionAnimator.ofBounds(leash, baseBounds, startBounds, endBounds,
                            sourceHintRect, direction));
        }
        return mCurrentAnimator;
    }

    PipTransitionAnimator getCurrentAnimator() {
        return mCurrentAnimator;
    }

    private PipTransitionAnimator setupPipTransitionAnimator(PipTransitionAnimator animator) {
        animator.setSurfaceTransactionHelper(mSurfaceTransactionHelper);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.setFloatValues(FRACTION_START, FRACTION_END);
        animator.setAnimationHandler(mSfAnimationHandlerThreadLocal.get());
        return animator;
    }

    /**
     * Additional callback interface for PiP animation
     */
    public static class PipAnimationCallback {
        /**
         * Called when PiP animation is started.
         */
        public void onPipAnimationStart(PipTransitionAnimator animator) {}

        /**
         * Called when PiP animation is ended.
         */
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipTransitionAnimator animator) {}

        /**
         * Called when PiP animation is cancelled.
         */
        public void onPipAnimationCancel(PipTransitionAnimator animator) {}
    }

    /**
     * Animator for PiP transition animation which supports both alpha and bounds animation.
     * @param <T> Type of property to animate, either alpha (float) or bounds (Rect)
     */
    public abstract static class PipTransitionAnimator<T> extends ValueAnimator implements
            ValueAnimator.AnimatorUpdateListener,
            ValueAnimator.AnimatorListener {
        private final SurfaceControl mLeash;
        private final @AnimationType int mAnimationType;
        private final Rect mDestinationBounds = new Rect();

        private T mBaseValue;
        protected T mCurrentValue;
        protected T mStartValue;
        private T mEndValue;
        private PipAnimationCallback mPipAnimationCallback;
        private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
                mSurfaceControlTransactionFactory;
        private PipSurfaceTransactionHelper mSurfaceTransactionHelper;
        private @TransitionDirection int mTransitionDirection;

        private PipTransitionAnimator(SurfaceControl leash, @AnimationType int animationType,
                Rect destinationBounds, T baseValue, T startValue, T endValue) {
            mLeash = leash;
            mAnimationType = animationType;
            mDestinationBounds.set(destinationBounds);
            mBaseValue = baseValue;
            mStartValue = startValue;
            mEndValue = endValue;
            addListener(this);
            addUpdateListener(this);
            mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
            mTransitionDirection = TRANSITION_DIRECTION_NONE;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mCurrentValue = mStartValue;
            onStartTransaction(mLeash, newSurfaceControlTransaction());
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationStart(this);
            }
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            applySurfaceControlTransaction(mLeash, newSurfaceControlTransaction(),
                    animation.getAnimatedFraction());
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentValue = mEndValue;
            final SurfaceControl.Transaction tx = newSurfaceControlTransaction();
            onEndTransaction(mLeash, tx, mTransitionDirection);
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationEnd(tx, this);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (mPipAnimationCallback != null) {
                mPipAnimationCallback.onPipAnimationCancel(this);
            }
        }

        @Override public void onAnimationRepeat(Animator animation) {}

        @VisibleForTesting
        @AnimationType public int getAnimationType() {
            return mAnimationType;
        }

        @VisibleForTesting
        public PipTransitionAnimator<T> setPipAnimationCallback(PipAnimationCallback callback) {
            mPipAnimationCallback = callback;
            return this;
        }
        @VisibleForTesting
        @TransitionDirection public int getTransitionDirection() {
            return mTransitionDirection;
        }

        @VisibleForTesting
        public PipTransitionAnimator<T> setTransitionDirection(@TransitionDirection int direction) {
            if (direction != TRANSITION_DIRECTION_SAME) {
                mTransitionDirection = direction;
            }
            return this;
        }

        T getStartValue() {
            return mStartValue;
        }

        T getBaseValue() {
            return mBaseValue;
        }

        @VisibleForTesting
        public T getEndValue() {
            return mEndValue;
        }

        Rect getDestinationBounds() {
            return mDestinationBounds;
        }

        void setDestinationBounds(Rect destinationBounds) {
            mDestinationBounds.set(destinationBounds);
            if (mAnimationType == ANIM_TYPE_ALPHA) {
                onStartTransaction(mLeash, newSurfaceControlTransaction());
            }
        }

        void setCurrentValue(T value) {
            mCurrentValue = value;
        }

        boolean shouldApplyCornerRadius() {
            return !isOutPipDirection(mTransitionDirection);
        }

        boolean inScaleTransition() {
            if (mAnimationType != ANIM_TYPE_BOUNDS) return false;
            final int direction = getTransitionDirection();
            return !isInPipDirection(direction) && !isOutPipDirection(direction);
        }

        /**
         * Updates the {@link #mEndValue}.
         *
         * NOTE: Do not forget to call {@link #setDestinationBounds(Rect)} for bounds animation.
         * This is typically used when we receive a shelf height adjustment during the bounds
         * animation. In which case we can update the end bounds and keep the existing animation
         * running instead of cancelling it.
         */
        public void updateEndValue(T endValue) {
            mEndValue = endValue;
        }

        /**
         * @return {@link SurfaceControl.Transaction} instance with vsync-id.
         */
        protected SurfaceControl.Transaction newSurfaceControlTransaction() {
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            tx.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());
            return tx;
        }

        @VisibleForTesting
        public void setSurfaceControlTransactionFactory(
                PipSurfaceTransactionHelper.SurfaceControlTransactionFactory factory) {
            mSurfaceControlTransactionFactory = factory;
        }

        PipSurfaceTransactionHelper getSurfaceTransactionHelper() {
            return mSurfaceTransactionHelper;
        }

        void setSurfaceTransactionHelper(PipSurfaceTransactionHelper helper) {
            mSurfaceTransactionHelper = helper;
        }

        void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {}

        void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx,
                @TransitionDirection int transitionDirection) {}

        abstract void applySurfaceControlTransaction(SurfaceControl leash,
                SurfaceControl.Transaction tx, float fraction);

        static PipTransitionAnimator<Float> ofAlpha(SurfaceControl leash,
                Rect destinationBounds, float startValue, float endValue) {
            return new PipTransitionAnimator<Float>(leash, ANIM_TYPE_ALPHA,
                    destinationBounds, startValue, startValue, endValue) {
                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final float alpha = getStartValue() * (1 - fraction) + getEndValue() * fraction;
                    setCurrentValue(alpha);
                    getSurfaceTransactionHelper().alpha(tx, leash, alpha);
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    if (getTransitionDirection() == TRANSITION_DIRECTION_REMOVE_STACK) {
                        // while removing the pip stack, no extra work needs to be done here.
                        return;
                    }
                    getSurfaceTransactionHelper()
                            .resetScale(tx, leash, getDestinationBounds())
                            .crop(tx, leash, getDestinationBounds())
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.show(leash);
                    tx.apply();
                }

                @Override
                public void updateEndValue(Float endValue) {
                    super.updateEndValue(endValue);
                    mStartValue = mCurrentValue;
                }
            };
        }

        static PipTransitionAnimator<Rect> ofBounds(SurfaceControl leash,
                Rect baseValue, Rect startValue, Rect endValue, Rect sourceHintRect,
                @PipAnimationController.TransitionDirection int direction) {
            // Just for simplicity we'll interpolate between the source rect hint insets and empty
            // insets to calculate the window crop
            final Rect initialSourceValue;
            if (isOutPipDirection(direction)) {
                initialSourceValue = new Rect(endValue);
            } else {
                initialSourceValue = new Rect(baseValue);
            }

            final Rect sourceHintRectInsets;
            if (sourceHintRect == null) {
                sourceHintRectInsets = null;
            } else {
                sourceHintRectInsets = new Rect(sourceHintRect.left - initialSourceValue.left,
                        sourceHintRect.top - initialSourceValue.top,
                        initialSourceValue.right - sourceHintRect.right,
                        initialSourceValue.bottom - sourceHintRect.bottom);
            }
            final Rect sourceInsets = new Rect(0, 0, 0, 0);

            // construct new Rect instances in case they are recycled
            return new PipTransitionAnimator<Rect>(leash, ANIM_TYPE_BOUNDS,
                    endValue, new Rect(baseValue), new Rect(startValue), new Rect(endValue)) {
                private final RectEvaluator mRectEvaluator = new RectEvaluator(new Rect());
                private final RectEvaluator mInsetsEvaluator = new RectEvaluator(new Rect());

                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final Rect base = getBaseValue();
                    final Rect start = getStartValue();
                    final Rect end = getEndValue();
                    Rect bounds = mRectEvaluator.evaluate(fraction, start, end);
                    setCurrentValue(bounds);
                    if (inScaleTransition() || sourceHintRect == null) {

                        if (isOutPipDirection(direction)) {
                            getSurfaceTransactionHelper().scale(tx, leash, end, bounds);
                        } else {
                            getSurfaceTransactionHelper().scale(tx, leash, base, bounds);
                        }
                    } else {
                        final Rect insets;
                        if (isOutPipDirection(direction)) {
                            insets = mInsetsEvaluator.evaluate(fraction, sourceHintRectInsets,
                                    sourceInsets);
                        } else {
                            insets = mInsetsEvaluator.evaluate(fraction, sourceInsets,
                                    sourceHintRectInsets);
                        }
                        getSurfaceTransactionHelper().scaleAndCrop(tx, leash,
                                initialSourceValue, bounds, insets);
                    }
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    getSurfaceTransactionHelper()
                            .alpha(tx, leash, 1f)
                            .round(tx, leash, shouldApplyCornerRadius());
                    tx.show(leash);
                    tx.apply();
                }

                @Override
                void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx,
                        int transitionDirection) {
                    // NOTE: intentionally does not apply the transaction here.
                    // this end transaction should get executed synchronously with the final
                    // WindowContainerTransaction in task organizer
                    final Rect destBounds = getDestinationBounds();
                    getSurfaceTransactionHelper().resetScale(tx, leash, destBounds);
                    if (transitionDirection == TRANSITION_DIRECTION_LEAVE_PIP) {
                        // Leaving to fullscreen, reset crop to null.
                        tx.setPosition(leash, destBounds.left, destBounds.top);
                        tx.setWindowCrop(leash, 0, 0);
                    } else {
                        getSurfaceTransactionHelper().crop(tx, leash, destBounds);
                    }
                }

                @Override
                public void updateEndValue(Rect endValue) {
                    super.updateEndValue(endValue);
                    if (mStartValue != null && mCurrentValue != null) {
                        mStartValue.set(mCurrentValue);
                    }
                }
            };
        }
    }
}
