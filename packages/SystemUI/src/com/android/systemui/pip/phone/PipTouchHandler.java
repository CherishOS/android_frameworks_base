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
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import static com.android.systemui.Interpolators.FAST_OUT_LINEAR_IN;
import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.systemui.Interpolators.LINEAR_OUT_SLOW_IN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.IPinnedStackController;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.os.BackgroundThread;
import com.android.internal.policy.PipMotionHelper;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.tuner.TunerService;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler implements TunerService.Tunable {
    private static final String TAG = "PipTouchHandler";
    private static final boolean DEBUG_ALLOW_OUT_OF_BOUNDS_STACK = false;

    private static final String TUNER_KEY_DRAG_TO_DISMISS = "pip_drag_to_dismiss";
    private static final String TUNER_KEY_ALLOW_MINIMIZE = "pip_allow_minimize";

    private static final int SNAP_STACK_DURATION = 225;
    private static final int DISMISS_STACK_DURATION = 375;
    private static final int EXPAND_STACK_DURATION = 225;
    private static final int MINIMIZE_STACK_MAX_DURATION = 200;

    // The fraction of the stack width that the user has to drag offscreen to minimize the PIP
    private static final float MINIMIZE_OFFSCREEN_FRACTION = 0.15f;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IWindowManager mWindowManager;
    private final ViewConfiguration mViewConfig;
    private final PipMenuListener mMenuListener = new PipMenuListener();
    private IPinnedStackController mPinnedStackController;

    private PipInputEventReceiver mInputEventReceiver;
    private PipMenuActivityController mMenuController;
    private PipDismissViewController mDismissViewController;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private PipMotionHelper mMotionHelper;

    // Allow dragging the PIP to a location to close it
    private boolean mEnableDragToDismiss = false;
    // Allow the PIP to be "docked" slightly offscreen
    private boolean mEnableMinimizing = true;

    private final Rect mStableInsets = new Rect();
    private final Rect mPinnedStackBounds = new Rect();
    private final Rect mBoundedPinnedStackBounds = new Rect();
    private ValueAnimator mPinnedStackBoundsAnimator = null;
    private ValueAnimator.AnimatorUpdateListener mUpdatePinnedStackBoundsListener =
            new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mPinnedStackBounds.set((Rect) animation.getAnimatedValue());
        }
    };

    // Behaviour states
    private boolean mIsTappingThrough;
    private boolean mIsMinimized;

    // Touch state
    private final PipTouchState mTouchState;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final PipTouchGesture[] mGestures;

    // Temporary vars
    private final Rect mTmpBounds = new Rect();

    /**
     * Input handler used for Pip windows.
     */
    private final class PipInputEventReceiver extends InputEventReceiver {

        public PipInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = true;
            try {
                // To be implemented for input handling over Pip windows
                if (event instanceof MotionEvent) {
                    MotionEvent ev = (MotionEvent) event;
                    handled = handleTouchEvent(ev);
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuVisibilityChanged(boolean visible) {
            if (!visible) {
                mIsTappingThrough = false;
                registerInputConsumer();
            } else {
                unregisterInputConsumer();
            }
        }

        @Override
        public void onPipExpand() {
            if (!mIsMinimized) {
                expandPinnedStackToFullscreen();
            }
        }

        @Override
        public void onPipMinimize() {
            setMinimizedState(true);
            animateToClosestMinimizedTarget();
        }

        @Override
        public void onPipDismiss() {
            BackgroundThread.getHandler().post(PipTouchHandler.this::dismissPinnedStack);
        }
    }

    public PipTouchHandler(Context context, PipMenuActivityController menuController,
            IActivityManager activityManager, IWindowManager windowManager) {

        // Initialize the Pip input consumer
        mContext = context;
        mActivityManager = activityManager;
        mWindowManager = windowManager;
        mViewConfig = ViewConfiguration.get(context);
        mMenuController = menuController;
        mMenuController.addListener(mMenuListener);
        mDismissViewController = new PipDismissViewController(context);
        mSnapAlgorithm = new PipSnapAlgorithm(mContext);
        mTouchState = new PipTouchState(mViewConfig);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 2f);
        mGestures = new PipTouchGesture[] {
                mDragToDismissGesture, mTapThroughGesture, mMinimizeGesture, mDefaultMovementGesture
        };
        mMotionHelper = new PipMotionHelper(BackgroundThread.getHandler());
        registerInputConsumer();
        setSnapToEdge(true);

        // Register any tuner settings changes
        TunerService.get(context).addTunable(this, TUNER_KEY_DRAG_TO_DISMISS,
                TUNER_KEY_ALLOW_MINIMIZE);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (newValue == null) {
            // Reset back to default
            mEnableDragToDismiss = false;
            mEnableMinimizing = true;
            setMinimizedState(false);
            return;
        }
        switch (key) {
            case TUNER_KEY_DRAG_TO_DISMISS:
                mEnableDragToDismiss = Integer.parseInt(newValue) != 0;
                break;
            case TUNER_KEY_ALLOW_MINIMIZE:
                mEnableMinimizing = Integer.parseInt(newValue) != 0;
                break;
        }
    }

    public void onActivityPinned() {
        // Reset some states once we are pinned
        if (mIsTappingThrough) {
            mIsTappingThrough = false;
            registerInputConsumer();
        }
        if (mIsMinimized) {
            setMinimizedState(false);
        }
    }

    public void onConfigurationChanged() {
        mSnapAlgorithm.onConfigurationChanged();
        updateBoundedPinnedStackBounds(false /* updatePinnedStackBounds */);
    }

    public void onMinimizedStateChanged(boolean isMinimized) {
        mIsMinimized = isMinimized;
    }

    public void onSnapToEdgeStateChanged(boolean isSnapToEdge) {
        mSnapAlgorithm.setSnapToEdge(isSnapToEdge);
    }

    private boolean handleTouchEvent(MotionEvent ev) {
        // Skip touch handling until we are bound to the controller
        if (mPinnedStackController == null) {
            return true;
        }

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // Cancel any existing animations on the pinned stack
                if (mPinnedStackBoundsAnimator != null) {
                    mPinnedStackBoundsAnimator.cancel();
                }

                updateBoundedPinnedStackBounds(true /* updatePinnedStackBounds */);
                for (PipTouchGesture gesture : mGestures) {
                    gesture.onDown(mTouchState);
                }
                try {
                    mPinnedStackController.setInInteractiveMode(true);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not set dragging state", e);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (PipTouchGesture gesture : mGestures) {
                    if (gesture.onMove(mTouchState)) {
                        break;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateBoundedPinnedStackBounds(false /* updatePinnedStackBounds */);

                for (PipTouchGesture gesture : mGestures) {
                    if (gesture.onUp(mTouchState)) {
                        break;
                    }
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                try {
                    mPinnedStackController.setInInteractiveMode(false);
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not set dragging state", e);
                }
                break;
            }
        }
        return !mIsTappingThrough;
    }

    /**
     * @return whether the current touch state is a horizontal drag offscreen.
     */
    private boolean isDraggingOffscreen(PipTouchState touchState) {
        PointF lastDelta = touchState.getLastTouchDelta();
        PointF downDelta = touchState.getDownTouchDelta();
        float left = mPinnedStackBounds.left + lastDelta.x;
        return !(mBoundedPinnedStackBounds.left <= left && left <= mBoundedPinnedStackBounds.right)
                && Math.abs(downDelta.x) > Math.abs(downDelta.y);
    }

    /**
     * Registers the input consumer.
     */
    private void registerInputConsumer() {
        if (mInputEventReceiver == null) {
            final InputChannel inputChannel = new InputChannel();
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
                mWindowManager.createInputConsumer(INPUT_CONSUMER_PIP, inputChannel);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to create PIP input consumer", e);
            }
            mInputEventReceiver = new PipInputEventReceiver(inputChannel, Looper.myLooper());
        }
    }

    /**
     * Unregisters the input consumer.
     */
    private void unregisterInputConsumer() {
        if (mInputEventReceiver != null) {
            try {
                mWindowManager.destroyInputConsumer(INPUT_CONSUMER_PIP);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to destroy PIP input consumer", e);
            }
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
    }

    /**
     * Sets the controller to update the system of changes from user interaction.
     */
    void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    /**
     * Sets the snap-to-edge state and notifies the controller.
     */
    private void setSnapToEdge(boolean snapToEdge) {
        onSnapToEdgeStateChanged(snapToEdge);

        if (mPinnedStackController != null) {
            try {
                mPinnedStackController.setSnapToEdge(snapToEdge);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set snap mode to edge", e);
            }
        }
    }

    /**
     * Sets the minimized state and notifies the controller.
     */
    private void setMinimizedState(boolean isMinimized) {
        onMinimizedStateChanged(isMinimized);

        if (mPinnedStackController != null) {
            try {
                mPinnedStackController.setIsMinimized(isMinimized);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set minimized state", e);
            }
        }
    }

    /**
     * @return whether the given {@param pinnedStackBounds} indicates the PIP should be minimized.
     */
    private boolean shouldMinimizedPinnedStack() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        if (mPinnedStackBounds.left < 0) {
            float offscreenFraction = (float) -mPinnedStackBounds.left / mPinnedStackBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else if (mPinnedStackBounds.right > displaySize.x) {
            float offscreenFraction = (float) (mPinnedStackBounds.right - displaySize.x) /
                    mPinnedStackBounds.width();
            return offscreenFraction >= MINIMIZE_OFFSCREEN_FRACTION;
        } else {
            return false;
        }
    }

    /**
     * Flings the minimized PIP to the closest minimized snap target.
     */
    private void flingToMinimizedSnapTarget(float velocityY) {
        // We currently only allow flinging the minimized stack up and down, so just lock the
        // movement bounds to the current stack bounds horizontally
        Rect movementBounds = new Rect(mPinnedStackBounds.left, mBoundedPinnedStackBounds.top,
                mPinnedStackBounds.left, mBoundedPinnedStackBounds.bottom);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(movementBounds, mPinnedStackBounds,
                0 /* velocityX */, velocityY);
        if (!mPinnedStackBounds.equals(toBounds)) {
            mPinnedStackBoundsAnimator = mMotionHelper.createAnimationToBounds(mPinnedStackBounds,
                    toBounds, 0, FAST_OUT_SLOW_IN, mUpdatePinnedStackBoundsListener);
            mFlingAnimationUtils.apply(mPinnedStackBoundsAnimator, 0,
                    distanceBetweenRectOffsets(mPinnedStackBounds, toBounds),
                    velocityY);
            mPinnedStackBoundsAnimator.start();
        }
    }

    /**
     * Animates the PIP to the minimized state, slightly offscreen.
     */
    private void animateToClosestMinimizedTarget() {
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(mBoundedPinnedStackBounds,
                mPinnedStackBounds);
        mSnapAlgorithm.applyMinimizedOffset(toBounds, mBoundedPinnedStackBounds, displaySize,
                mStableInsets);
        mPinnedStackBoundsAnimator = mMotionHelper.createAnimationToBounds(mPinnedStackBounds,
                toBounds, MINIMIZE_STACK_MAX_DURATION, LINEAR_OUT_SLOW_IN,
                mUpdatePinnedStackBoundsListener);
        mPinnedStackBoundsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mMenuController.hideMenu();
            }
        });
        mPinnedStackBoundsAnimator.start();
    }

    /**
     * Flings the PIP to the closest snap target.
     */
    private void flingToSnapTarget(float velocity, float velocityX, float velocityY) {
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(mBoundedPinnedStackBounds,
                mPinnedStackBounds, velocityX, velocityY);
        if (!mPinnedStackBounds.equals(toBounds)) {
            mPinnedStackBoundsAnimator = mMotionHelper.createAnimationToBounds(mPinnedStackBounds,
                toBounds, 0, FAST_OUT_SLOW_IN, mUpdatePinnedStackBoundsListener);
            mFlingAnimationUtils.apply(mPinnedStackBoundsAnimator, 0,
                distanceBetweenRectOffsets(mPinnedStackBounds, toBounds),
                velocity);
            mPinnedStackBoundsAnimator.start();
        }
    }

    /**
     * Animates the PIP to the closest snap target.
     */
    private void animateToClosestSnapTarget() {
        Rect toBounds = mSnapAlgorithm.findClosestSnapBounds(mBoundedPinnedStackBounds,
                mPinnedStackBounds);
        if (!mPinnedStackBounds.equals(toBounds)) {
            mPinnedStackBoundsAnimator = mMotionHelper.createAnimationToBounds(mPinnedStackBounds,
                toBounds, SNAP_STACK_DURATION, FAST_OUT_SLOW_IN, mUpdatePinnedStackBoundsListener);
            mPinnedStackBoundsAnimator.start();
        }
    }

    /**
     * Animates the dismissal of the PIP over the dismiss target bounds.
     */
    private void animateDismissPinnedStack(Rect dismissBounds) {
        Rect toBounds = new Rect(dismissBounds.centerX(),
            dismissBounds.centerY(),
            dismissBounds.centerX() + 1,
            dismissBounds.centerY() + 1);
        mPinnedStackBoundsAnimator = mMotionHelper.createAnimationToBounds(mPinnedStackBounds,
            toBounds, DISMISS_STACK_DURATION, FAST_OUT_LINEAR_IN, mUpdatePinnedStackBoundsListener);
        mPinnedStackBoundsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                BackgroundThread.getHandler().post(PipTouchHandler.this::dismissPinnedStack);
            }
        });
        mPinnedStackBoundsAnimator.start();
    }

    /**
     * Resizes the pinned stack back to fullscreen.
     */
    void expandPinnedStackToFullscreen() {
        BackgroundThread.getHandler().post(() -> {
            try {
                mActivityManager.resizeStack(PINNED_STACK_ID, null /* bounds */,
                        true /* allowResizeInDockedMode */, true /* preserveWindows */,
                        true /* animate */, EXPAND_STACK_DURATION);
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu activity", e);
            }
        });
    }

    /**
     * Tries to the move the pinned stack to the given {@param bounds}.
     */
    private void movePinnedStack(Rect bounds) {
        if (!bounds.equals(mPinnedStackBounds)) {
            mPinnedStackBounds.set(bounds);
            mMotionHelper.resizeToBounds(mPinnedStackBounds);
        }
    }

    /**
     * Dismisses the pinned stack.
     */
    private void dismissPinnedStack() {
        try {
            mActivityManager.removeStack(PINNED_STACK_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to remove PIP", e);
        }
    }

    /**
     * Updates the movement bounds of the pinned stack.
     */
    private void updateBoundedPinnedStackBounds(boolean updatePinnedStackBounds) {
        try {
            StackInfo info = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (info != null) {
                if (updatePinnedStackBounds) {
                    mPinnedStackBounds.set(info.bounds);
                }
                mWindowManager.getStableInsets(info.displayId, mStableInsets);
                mBoundedPinnedStackBounds.set(mWindowManager.getPictureInPictureMovementBounds(
                        info.displayId));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch PIP movement bounds.", e);
        }
    }

    /**
     * @return the distance between points {@param p1} and {@param p2}.
     */
    private float distanceBetweenRectOffsets(Rect r1, Rect r2) {
        return PointF.length(r1.left - r2.left, r1.top - r2.top);
    }

    /**
     * Gesture controlling dragging over a target to dismiss the PIP.
     */
    private PipTouchGesture mDragToDismissGesture = new PipTouchGesture() {
        @Override
        public void onDown(PipTouchState touchState) {
            if (mEnableDragToDismiss) {
                // TODO: Consider setting a timer such at after X time, we show the dismiss
                //       target if the user hasn't already dragged some distance
                mDismissViewController.createDismissTarget();
            }
        }

        @Override
        boolean onMove(PipTouchState touchState) {
            if (mEnableDragToDismiss && touchState.startedDragging()) {
                mDismissViewController.showDismissTarget();
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (mEnableDragToDismiss) {
                try {
                    if (touchState.isDragging()) {
                        Rect dismissBounds = mDismissViewController.getDismissBounds();
                        PointF lastTouch = touchState.getLastTouchPosition();
                        if (dismissBounds.contains((int) lastTouch.x, (int) lastTouch.y)) {
                            animateDismissPinnedStack(dismissBounds);
                            return true;
                        }
                    }
                } finally {
                    mDismissViewController.destroyDismissTarget();
                }
            }
            return false;
        }
    };

    /**** Gestures ****/

    /**
     * Gesture controlling dragging the PIP slightly offscreen to minimize it.
     */
    private PipTouchGesture mMinimizeGesture = new PipTouchGesture() {
        @Override
        boolean onMove(PipTouchState touchState) {
            if (mEnableMinimizing) {
                boolean isDraggingOffscreen = isDraggingOffscreen(touchState);
                if (touchState.startedDragging() && isDraggingOffscreen) {
                    // Reset the minimized state once we drag horizontally
                    setMinimizedState(false);
                }

                if (touchState.allowDraggingOffscreen() && isDraggingOffscreen) {
                    // Move the pinned stack, but ignore the vertical movement
                    float left = mPinnedStackBounds.left + touchState.getLastTouchDelta().x;
                    mTmpBounds.set(mPinnedStackBounds);
                    mTmpBounds.offsetTo((int) left, mPinnedStackBounds.top);
                    if (!mTmpBounds.equals(mPinnedStackBounds)) {
                        mPinnedStackBounds.set(mTmpBounds);
                        mMotionHelper.resizeToBounds(mPinnedStackBounds);
                    }
                    return true;
                } else if (mIsMinimized && touchState.isDragging()) {
                    // Move the pinned stack, but ignore the horizontal movement
                    PointF lastDelta = touchState.getLastTouchDelta();
                    float top = mPinnedStackBounds.top + lastDelta.y;
                    top = Math.max(mBoundedPinnedStackBounds.top, Math.min(
                            mBoundedPinnedStackBounds.bottom, top));
                    mTmpBounds.set(mPinnedStackBounds);
                    mTmpBounds.offsetTo(mPinnedStackBounds.left, (int) top);
                    movePinnedStack(mTmpBounds);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (mEnableMinimizing) {
                if (touchState.isDragging()) {
                    if (isDraggingOffscreen(touchState)) {
                        if (shouldMinimizedPinnedStack()) {
                            setMinimizedState(true);
                            animateToClosestMinimizedTarget();
                            return true;
                        }
                    } else if (mIsMinimized) {
                        PointF vel = touchState.getVelocity();
                        if (vel.length() > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                            flingToMinimizedSnapTarget(vel.y);
                        } else {
                            animateToClosestMinimizedTarget();
                        }
                        return true;
                    }
                } else if (mIsMinimized) {
                    setMinimizedState(false);
                    animateToClosestSnapTarget();
                    return true;
                }
            }
            return false;
        }
    };

    /**
     * Gesture controlling tapping on the PIP to show an overlay.
     */
    private PipTouchGesture mTapThroughGesture = new PipTouchGesture() {
        @Override
        boolean onMove(PipTouchState touchState) {
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (!touchState.isDragging() && !mIsMinimized && !mIsTappingThrough) {
                mMenuController.showMenu();
                mIsTappingThrough = true;
                return true;
            }
            return false;
        }
    };

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private PipTouchGesture mDefaultMovementGesture = new PipTouchGesture() {
        @Override
        boolean onMove(PipTouchState touchState) {
            if (touchState.startedDragging()) {
                // For now, once the user has started a drag that the other gestures have not
                // intercepted, disallow those gestures from intercepting again to drag offscreen
                touchState.setDisallowDraggingOffscreen();
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                PointF lastDelta = touchState.getLastTouchDelta();
                float left = mPinnedStackBounds.left + lastDelta.x;
                float top = mPinnedStackBounds.top + lastDelta.y;
                if (!DEBUG_ALLOW_OUT_OF_BOUNDS_STACK) {
                    left = Math.max(mBoundedPinnedStackBounds.left, Math.min(
                            mBoundedPinnedStackBounds.right, left));
                    top = Math.max(mBoundedPinnedStackBounds.top, Math.min(
                            mBoundedPinnedStackBounds.bottom, top));
                }
                mTmpBounds.set(mPinnedStackBounds);
                mTmpBounds.offsetTo((int) left, (int) top);
                movePinnedStack(mTmpBounds);
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            if (touchState.isDragging()) {
                PointF vel = mTouchState.getVelocity();
                float velocity = PointF.length(vel.x, vel.y);
                if (velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                    flingToSnapTarget(velocity, vel.x, vel.y);
                } else {
                    animateToClosestSnapTarget();
                }
            } else {
                expandPinnedStackToFullscreen();
            }
            return true;
        }
    };
}
