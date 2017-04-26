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

package com.android.systemui.pip.phone;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

import static com.android.systemui.Interpolators.FAST_OUT_LINEAR_IN;
import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.systemui.Interpolators.LINEAR_OUT_SLOW_IN;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.animation.Interpolator;

import com.android.internal.os.BackgroundThread;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.internal.view.SurfaceFlingerVsyncChoreographer;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.PrintWriter;

/**
 * A helper to animate and manipulate the PiP.
 */
public class PipMotionHelper {

    private static final String TAG = "PipMotionHelper";

    private static final RectEvaluator RECT_EVALUATOR = new RectEvaluator(new Rect());

    private static final int DEFAULT_MOVE_STACK_DURATION = 225;
    private static final int SNAP_STACK_DURATION = 225;
    private static final int DRAG_TO_TARGET_DISMISS_STACK_DURATION = 375;
    private static final int DRAG_TO_DISMISS_STACK_DURATION = 175;
    private static final int SHRINK_STACK_FROM_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_MENU_DURATION = 250;
    private static final int EXPAND_STACK_TO_FULLSCREEN_DURATION = 300;
    private static final int MINIMIZE_STACK_MAX_DURATION = 200;
    private static final int IME_SHIFT_DURATION = 300;

    // The fraction of the stack width that the user has to drag offscreen to minimize the PiP
    private static final float MINIMIZE_OFFSCREEN_FRACTION = 0.3f;
    // The fraction of the stack height that the user has to drag offscreen to dismiss the PiP
    private static final float DISMISS_OFFSCREEN_FRACTION = 0.3f;

    private Context mContext;
    private IActivityManager mActivityManager;
    private SurfaceFlingerVsyncChoreographer mVsyncChoreographer;
    private Handler mHandler;

    private PipSnapAlgorithm mSnapAlgorithm;
    private FlingAnimationUtils mFlingAnimationUtils;

    private final Rect mBounds = new Rect();
    private final Rect mStableInsets = new Rect();

    private ValueAnimator mBoundsAnimator = null;
    private ValueAnimator.AnimatorUpdateListener mUpdateBoundsListener =
            new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mBounds.set((Rect) animation.getAnimatedValue());
                }
            };

    public PipMotionHelper(Context context, IActivityManager activityManager,
            PipSnapAlgorithm snapAlgorithm, FlingAnimationUtils flingAnimationUtils) {
        mContext = context;
        mHandler = BackgroundThread.getHandler();
        mActivityManager = activityManager;
        mSnapAlgorithm = snapAlgorithm;
        mFlingAnimationUtils = flingAnimationUtils;
        mVsyncChoreographer = new SurfaceFlingerVsyncChoreographer(mHandler, mContext.getDisplay(),
                Choreographer.getInstance());
        onConfigurationChanged();
    }

    /**
     * Updates whenever the configuration changes.
     */
    void onConfigurationChanged() {
        mSnapAlgorithm.onConfigurationChanged();
        SystemServicesProxy.getInstance(mContext).getStableInsets(mStableInsets);
    }

    /**
     * Synchronizes the current bounds with the pinned stack.
     */
    void synchronizePinnedStackBounds() {
        cancelAnimations();
        try {
            StackInfo stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (stackInfo != null) {
                mBounds.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get pinned stack bounds");
        }
    }

    /**
     * Tries to the move the pinned stack to the given {@param bounds}.
     */
    void movePip(Rect toBounds) {
        cancelAnimations();
        resizePipUnchecked(toBounds);
        mBounds.set(toBounds);
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPip() {
        cancelAnimations();
        mHandler.post(() -> {
            try {
                mActivityManager.resizeStack(PINNED_STACK_ID, null /* bounds */,
                        true /* allowResizeInDockedMode */, true /* preserveWindows */,
                        true /* animate */, EXPAND_STACK_TO_FULLSCREEN_DURATION);
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PiP menu activity", e);
            }
        });
    }

    /**
     * Dismisses the pinned stack.
     */
    void dismissPip() {
        cancelAnimations();
        mHandler.post(() -> {
            try {
                mActivityManager.removeStack(PINNED_STACK_ID);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove PiP", e);
            }
        });
    }

    /**
     * @return the PiP bounds.
     */
    Rect getBounds() {
        return mBounds;
    }

    /**
     * @return the closest minimized PiP bounds.
     */
    Rect getClosestMinimizedBounds(Rect stackBounds, Rect movementBounds) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, stackBounds);
        mSnapAlgorithm.applyMinimizedOffset(toBounds, movementBounds, displaySize, mStableInsets);
        return toBounds;
    }

    /**
     * @return whether the PiP at the current bounds should be minimized.
     */
    boolean shouldMinimizePip() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        if (mBounds.left < 0) {
            float offscreenFraction = (float) -mBounds.left / mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else if (mBounds.right > displaySize.x) {
            float offscreenFraction = (float) (mBounds.right - displaySize.x) /
                    mBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else {
            return false;
        }
    }

    /**
     * @return whether the PiP at the current bounds should be dismissed.
     */
    boolean shouldDismissPip() {
        Point displaySize = new Point();
        mContext.getDisplay().getSize(displaySize);
        if (mBounds.bottom > displaySize.y) {
            float offscreenFraction = (float) (mBounds.bottom - displaySize.y) / mBounds.height();
            return offscreenFraction >= DISMISS_OFFSCREEN_FRACTION;
        }
        return false;
    }

    /**
     * Flings the minimized PiP to the closest minimized snap target.
     */
    Rect flingToMinimizedState(float velocityY, Rect movementBounds) {
        cancelAnimations();
        // We currently only allow flinging the minimized stack up and down, so just lock the
        // movement bounds to the current stack bounds horizontally
        movementBounds = new Rect(mBounds.left, movementBounds.top, mBounds.left,
                movementBounds.bottom);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds,
                0 /* velocityX */, velocityY);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, 0, FAST_OUT_SLOW_IN,
                    mUpdateBoundsListener);
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds),
                    velocityY);
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Animates the PiP to the minimized state, slightly offscreen.
     */
    Rect animateToClosestMinimizedState(Rect movementBounds,
            AnimatorUpdateListener updateListener) {
        cancelAnimations();
        Rect toBounds = getClosestMinimizedBounds(mBounds, movementBounds);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds,
                    MINIMIZE_STACK_MAX_DURATION, LINEAR_OUT_SLOW_IN, mUpdateBoundsListener);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Flings the PiP to the closest snap target.
     */
    Rect flingToSnapTarget(float velocity, float velocityX, float velocityY, Rect movementBounds,
            AnimatorUpdateListener updateListener, AnimatorListener listener) {
        cancelAnimations();
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds,
                velocityX, velocityY);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, 0, FAST_OUT_SLOW_IN,
                    mUpdateBoundsListener);
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds),
                    velocity);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            if (listener != null){
                mBoundsAnimator.addListener(listener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Animates the PiP to the closest snap target.
     */
    Rect animateToClosestSnapTarget(Rect movementBounds, AnimatorUpdateListener updateListener,
            AnimatorListener listener) {
        cancelAnimations();
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mBounds);
        if (!mBounds.equals(toBounds)) {
            mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, SNAP_STACK_DURATION,
                    FAST_OUT_SLOW_IN, mUpdateBoundsListener);
            if (updateListener != null) {
                mBoundsAnimator.addUpdateListener(updateListener);
            }
            if (listener != null){
                mBoundsAnimator.addListener(listener);
            }
            mBoundsAnimator.start();
        }
        return toBounds;
    }

    /**
     * Animates the PiP to the expanded state to show the menu.
     */
    float animateToExpandedState(Rect expandedBounds, Rect movementBounds,
            Rect expandedMovementBounds) {
        float savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds), movementBounds);
        mSnapAlgorithm.applySnapFraction(expandedBounds, expandedMovementBounds, savedSnapFraction);
        resizeAndAnimatePipUnchecked(expandedBounds, EXPAND_STACK_TO_MENU_DURATION);
        return savedSnapFraction;
    }

    /**
     * Animates the PiP from the expanded state to the normal state after the menu is hidden.
     */
    void animateToUnexpandedState(Rect normalBounds, float savedSnapFraction,
            Rect normalMovementBounds, Rect currentMovementBounds, boolean minimized,
            boolean immediate) {
        if (savedSnapFraction < 0f) {
            // If there are no saved snap fractions, then just use the current bounds
            savedSnapFraction = mSnapAlgorithm.getSnapFraction(new Rect(mBounds),
                    currentMovementBounds);
        }
        mSnapAlgorithm.applySnapFraction(normalBounds, normalMovementBounds, savedSnapFraction);
        if (minimized) {
            normalBounds = getClosestMinimizedBounds(normalBounds, normalMovementBounds);
        }
        if (immediate) {
            movePip(normalBounds);
        } else {
            resizeAndAnimatePipUnchecked(normalBounds, SHRINK_STACK_FROM_MENU_DURATION);
        }
    }

    /**
     * Animates the PiP to offset it from the IME.
     */
    void animateToIMEOffset(Rect toBounds) {
        cancelAnimations();
        resizeAndAnimatePipUnchecked(toBounds, IME_SHIFT_DURATION);
    }

    /**
     * Animates the dismissal of the PiP off the edge of the screen.
     */
    Rect animateDismiss(Rect pipBounds, float velocityX, float velocityY,
            AnimatorUpdateListener listener) {
        cancelAnimations();
        final float velocity = PointF.length(velocityX, velocityY);
        final boolean isFling = velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond();
        Point p = getDismissEndPoint(pipBounds, velocityX, velocityY, isFling);
        Rect toBounds = new Rect(pipBounds);
        toBounds.offsetTo(p.x, p.y);
        mBoundsAnimator = createAnimationToBounds(mBounds, toBounds, DRAG_TO_DISMISS_STACK_DURATION,
                FAST_OUT_LINEAR_IN, mUpdateBoundsListener);
        mBoundsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dismissPip();
            }
        });
        if (isFling) {
            mFlingAnimationUtils.apply(mBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mBounds, toBounds), velocity);
        }
        if (listener != null) {
            mBoundsAnimator.addUpdateListener(listener);
        }
        mBoundsAnimator.start();
        return toBounds;
    }

    /**
     * Cancels all existing animations.
     */
    void cancelAnimations() {
        if (mBoundsAnimator != null) {
            mBoundsAnimator.cancel();
            mBoundsAnimator = null;
        }
    }

    /**
     * Creates an animation to move the PiP to give given {@param toBounds}.
     */
    private ValueAnimator createAnimationToBounds(Rect fromBounds, Rect toBounds, int duration,
            Interpolator interpolator, ValueAnimator.AnimatorUpdateListener updateListener) {
        ValueAnimator anim = ValueAnimator.ofObject(RECT_EVALUATOR, fromBounds, toBounds);
        anim.setDuration(duration);
        anim.setInterpolator(interpolator);
        anim.addUpdateListener((ValueAnimator animation) -> {
            resizePipUnchecked((Rect) animation.getAnimatedValue());
        });
        if (updateListener != null) {
            anim.addUpdateListener(updateListener);
        }
        return anim;
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizePipUnchecked(Rect toBounds) {
        if (!toBounds.equals(mBounds)) {
            mVsyncChoreographer.scheduleAtSfVsync(() -> {
                try {
                    mActivityManager.resizePinnedStack(toBounds, null /* tempPinnedTaskBounds */);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not resize pinned stack to bounds: " + toBounds, e);
                }
            });
        }
    }

    /**
     * Directly resizes the PiP to the given {@param bounds}.
     */
    private void resizeAndAnimatePipUnchecked(Rect toBounds, int duration) {
        if (!toBounds.equals(mBounds)) {
            mHandler.post(() -> {
                try {
                    StackInfo stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                    if (stackInfo == null) {
                        // In the case where we've already re-expanded or dismissed the PiP, then
                        // just skip the resize
                        return;
                    }

                    mActivityManager.resizeStack(PINNED_STACK_ID, toBounds,
                            false /* allowResizeInDockedMode */, true /* preserveWindows */,
                            true /* animate */, duration);
                    mBounds.set(toBounds);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not animate resize pinned stack to bounds: " + toBounds, e);
                }
            });
        }
    }

    /**
     * @return the coordinates the PIP should animate to based on the direction of velocity when
     *         dismissing.
     */
    private Point getDismissEndPoint(Rect pipBounds, float velX, float velY, boolean isFling) {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        final float bottomBound = displaySize.y + pipBounds.height() * .1f;
        if (isFling && velX != 0 && velY != 0) {
            // Line is defined by: y = mx + b, m = slope, b = y-intercept
            // Find the slope
            final float slope = velY / velX;
            // Sub in slope and PiP position to solve for y-intercept: b = y - mx
            final float yIntercept = pipBounds.top - slope * pipBounds.left;
            // Now find the point on this line when y = bottom bound: x = (y - b) / m
            final float x = (bottomBound - yIntercept) / slope;
            return new Point((int) x, (int) bottomBound);
        } else {
            // If it wasn't a fling the velocity on 'up' is not reliable for direction of movement,
            // just animate downwards.
            return new Point(pipBounds.left, (int) bottomBound);
        }
    }

    /**
     * @return whether the gesture it towards the dismiss area based on the velocity when
     *         dismissing.
     */
    public boolean isGestureToDismissArea(Rect pipBounds, float velX, float velY,
            boolean isFling) {
        Point endpoint = getDismissEndPoint(pipBounds, velX, velY, isFling);
        // Center the point
        endpoint.x += pipBounds.width() / 2;
        endpoint.y += pipBounds.height() / 2;

        // The dismiss area is the middle third of the screen, half the PIP's height from the bottom
        Point size = new Point();
        mContext.getDisplay().getRealSize(size);
        final int left = size.x / 3;
        Rect dismissArea = new Rect(left, size.y - (pipBounds.height() / 2), left * 2,
                size.y + pipBounds.height());
        return dismissArea.contains(endpoint.x, endpoint.y);
    }

    /**
     * @return the distance between points {@param p1} and {@param p2}.
     */
    private float distanceBetweenRectOffsets(Rect r1, Rect r2) {
        return PointF.length(r1.left - r2.left, r1.top - r2.top);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mStableInsets=" + mStableInsets);
    }
}
