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

package com.android.systemui.onehanded;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller class of OneHanded animations (both from and to OneHanded mode).
 */
public class OneHandedAnimationController {
    private static final float FRACTION_START = 0f;
    private static final float FRACTION_END = 1f;

    public static final int TRANSITION_DIRECTION_NONE = 0;
    public static final int TRANSITION_DIRECTION_TRIGGER = 1;
    public static final int TRANSITION_DIRECTION_EXIT = 2;

    @IntDef(prefix = {"TRANSITION_DIRECTION_"}, value = {
            TRANSITION_DIRECTION_NONE,
            TRANSITION_DIRECTION_TRIGGER,
            TRANSITION_DIRECTION_EXIT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionDirection {
    }

    private final Interpolator mOvershootInterpolator;
    private final OneHandedSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final HashMap<SurfaceControl, OneHandedTransitionAnimator> mAnimatorMap =
            new HashMap<>();

    /**
     * Constructor of OneHandedAnimationController
     */
    @Inject
    public OneHandedAnimationController(
            OneHandedSurfaceTransactionHelper surfaceTransactionHelper) {
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mOvershootInterpolator = new OvershootInterpolator();
    }

    @SuppressWarnings("unchecked")
    OneHandedTransitionAnimator getAnimator(SurfaceControl leash, Rect startBounds,
            Rect endBounds) {
        final OneHandedTransitionAnimator animator = mAnimatorMap.get(leash);
        if (animator == null) {
            mAnimatorMap.put(leash, setupOneHandedTransitionAnimator(
                    OneHandedTransitionAnimator.ofBounds(leash, startBounds, endBounds)));
        } else if (animator.isRunning()) {
            animator.updateEndValue(endBounds);
        } else {
            animator.cancel();
            mAnimatorMap.put(leash, setupOneHandedTransitionAnimator(
                    OneHandedTransitionAnimator.ofBounds(leash, startBounds, endBounds)));
        }
        return mAnimatorMap.get(leash);
    }

    HashMap<SurfaceControl, OneHandedTransitionAnimator> getAnimatorMap() {
        return mAnimatorMap;
    }

    boolean isAnimatorsConsumed() {
        return mAnimatorMap.isEmpty();
    }

    void removeAnimator(SurfaceControl key) {
        final OneHandedTransitionAnimator animator = mAnimatorMap.remove(key);
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    OneHandedTransitionAnimator setupOneHandedTransitionAnimator(
            OneHandedTransitionAnimator animator) {
        animator.setSurfaceTransactionHelper(mSurfaceTransactionHelper);
        animator.setInterpolator(mOvershootInterpolator);
        animator.setFloatValues(FRACTION_START, FRACTION_END);
        return animator;
    }

    /**
     * Animator for OneHanded transition animation which supports both alpha and bounds animation.
     *
     * @param <T> Type of property to animate, either offset (float)
     */
    public abstract static class OneHandedTransitionAnimator<T> extends ValueAnimator implements
            ValueAnimator.AnimatorUpdateListener,
            ValueAnimator.AnimatorListener {

        private final SurfaceControl mLeash;
        private T mStartValue;
        private T mEndValue;
        private T mCurrentValue;

        private final List<OneHandedAnimationCallback> mOneHandedAnimationCallbacks =
                new ArrayList<>();
        private OneHandedSurfaceTransactionHelper mSurfaceTransactionHelper;
        private OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
                mSurfaceControlTransactionFactory;

        private @TransitionDirection int mTransitionDirection;
        private int mTransitionOffset;

        private OneHandedTransitionAnimator(SurfaceControl leash, T startValue, T endValue) {
            mLeash = leash;
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
            mOneHandedAnimationCallbacks.forEach(
                    (callback) -> {
                        callback.onOneHandedAnimationStart(this);
                    }
            );
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentValue = mEndValue;
            final SurfaceControl.Transaction tx = newSurfaceControlTransaction();
            onEndTransaction(mLeash, tx);
            mOneHandedAnimationCallbacks.forEach(
                    (callback) -> {
                        callback.onOneHandedAnimationEnd(tx, this);
                    }
            );
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCurrentValue = mEndValue;
            mOneHandedAnimationCallbacks.forEach(
                    (callback) -> {
                        callback.onOneHandedAnimationCancel(this);
                    }
            );
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            applySurfaceControlTransaction(mLeash, newSurfaceControlTransaction(),
                    animation.getAnimatedFraction());
            mOneHandedAnimationCallbacks.forEach(
                    (callback) -> {
                        callback.onTutorialAnimationUpdate(((Rect) mCurrentValue).top);
                    }
            );
        }

        void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
        }

        void onEndTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
        }

        abstract void applySurfaceControlTransaction(SurfaceControl leash,
                SurfaceControl.Transaction tx, float fraction);

        OneHandedSurfaceTransactionHelper getSurfaceTransactionHelper() {
            return mSurfaceTransactionHelper;
        }

        void setSurfaceTransactionHelper(OneHandedSurfaceTransactionHelper helper) {
            mSurfaceTransactionHelper = helper;
        }

        OneHandedTransitionAnimator<T> setOneHandedAnimationCallbacks(
                OneHandedAnimationCallback callback) {
            mOneHandedAnimationCallbacks.add(callback);
            return this;
        }

        SurfaceControl getLeash() {
            return mLeash;
        }

        Rect getDestinationBounds() {
            return (Rect) mEndValue;
        }

        int getDestinationOffset() {
            return ((Rect) mEndValue).top - ((Rect) mStartValue).top;
        }

        @TransitionDirection
        int getTransitionDirection() {
            return mTransitionDirection;
        }

        OneHandedTransitionAnimator<T> setTransitionDirection(int direction) {
            mTransitionDirection = direction;
            return this;
        }

        OneHandedTransitionAnimator<T> setTransitionOffset(int offset) {
            mTransitionOffset = offset;
            return this;
        }

        T getStartValue() {
            return mStartValue;
        }

        T getEndValue() {
            return mEndValue;
        }

        void setCurrentValue(T value) {
            mCurrentValue = value;
        }

        /**
         * Updates the {@link #mEndValue}.
         */
        void updateEndValue(T endValue) {
            mEndValue = endValue;
        }

        SurfaceControl.Transaction newSurfaceControlTransaction() {
            return mSurfaceControlTransactionFactory.getTransaction();
        }

        @VisibleForTesting
        static OneHandedTransitionAnimator<Rect> ofBounds(SurfaceControl leash,
                Rect startValue, Rect endValue) {

            return new OneHandedTransitionAnimator<Rect>(leash, new Rect(startValue),
                    new Rect(endValue)) {

                private final Rect mTmpRect = new Rect();

                private int getCastedFractionValue(float start, float end, float fraction) {
                    return (int) (start * (1 - fraction) + end * fraction + .5f);
                }

                @Override
                void applySurfaceControlTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, float fraction) {
                    final Rect start = getStartValue();
                    final Rect end = getEndValue();
                    mTmpRect.set(
                            getCastedFractionValue(start.left, end.left, fraction),
                            getCastedFractionValue(start.top, end.top, fraction),
                            getCastedFractionValue(start.right, end.right, fraction),
                            getCastedFractionValue(start.bottom, end.bottom, fraction));
                    setCurrentValue(mTmpRect);
                    getSurfaceTransactionHelper().crop(tx, leash, mTmpRect)
                            .round(tx, leash);
                    tx.apply();
                }

                @Override
                void onStartTransaction(SurfaceControl leash, SurfaceControl.Transaction tx) {
                    getSurfaceTransactionHelper()
                            .alpha(tx, leash, 1f)
                            .translate(tx, leash, getEndValue().top - getStartValue().top)
                            .round(tx, leash);
                    tx.apply();
                }
            };
        }
    }
}
