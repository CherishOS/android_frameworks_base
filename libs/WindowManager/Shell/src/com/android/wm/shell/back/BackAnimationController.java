/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.back;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackEvent;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellMainThread;

/**
 * Controls the window animation run when a user initiates a back gesture.
 */
public class BackAnimationController implements RemoteCallable<BackAnimationController> {

    private static final String BACK_PREDICTABILITY_PROP = "persist.debug.back_predictability";
    public static final boolean IS_ENABLED = SystemProperties
            .getInt(BACK_PREDICTABILITY_PROP, 0) > 0;
    private static final String BACK_PREDICTABILITY_PROGRESS_THRESHOLD_PROP =
            "persist.debug.back_predictability_progress_threshold";
    private static final int PROGRESS_THRESHOLD = SystemProperties
            .getInt(BACK_PREDICTABILITY_PROGRESS_THRESHOLD_PROP, -1);
    private static final String TAG = "BackAnimationController";

    /**
     * Location of the initial touch event of the back gesture.
     */
    private final PointF mInitTouchLocation = new PointF();

    /**
     * Raw delta between {@link #mInitTouchLocation} and the last touch location.
     */
    private final Point mTouchEventDelta = new Point();
    private final ShellExecutor mShellExecutor;

    /** True when a back gesture is ongoing */
    private boolean mBackGestureStarted = false;

    /** @see #setTriggerBack(boolean) */
    private boolean mTriggerBack;

    @Nullable
    private BackNavigationInfo mBackNavigationInfo;
    private final SurfaceControl.Transaction mTransaction;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    @Nullable
    private IOnBackInvokedCallback mBackToLauncherCallback;
    private float mTriggerThreshold;
    private float mProgressThreshold;

    public BackAnimationController(
            @ShellMainThread ShellExecutor shellExecutor,
            Context context) {
        this(shellExecutor, new SurfaceControl.Transaction(), ActivityTaskManager.getService(),
                context);
    }

    @VisibleForTesting
    BackAnimationController(@NonNull ShellExecutor shellExecutor,
            @NonNull SurfaceControl.Transaction transaction,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context) {
        mShellExecutor = shellExecutor;
        mTransaction = transaction;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
    }

    public BackAnimation getBackAnimationImpl() {
        return mBackAnimation;
    }

    private final BackAnimation mBackAnimation = new BackAnimationImpl();

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mShellExecutor;
    }

    private class BackAnimationImpl implements BackAnimation {
        private IBackAnimationImpl mBackAnimation;

        @Override
        public IBackAnimation createExternalInterface() {
            if (mBackAnimation != null) {
                mBackAnimation.invalidate();
            }
            mBackAnimation = new IBackAnimationImpl(BackAnimationController.this);
            return mBackAnimation;
        }

        @Override
        public void onBackMotion(MotionEvent event, @BackEvent.SwipeEdge int swipeEdge) {
            mShellExecutor.execute(() -> onMotionEvent(event, swipeEdge));
        }

        @Override
        public void setTriggerBack(boolean triggerBack) {
            mShellExecutor.execute(() -> BackAnimationController.this.setTriggerBack(triggerBack));
        }

        @Override
        public void setSwipeThresholds(float triggerThreshold, float progressThreshold) {
            mShellExecutor.execute(() -> BackAnimationController.this.setSwipeThresholds(
                    triggerThreshold, progressThreshold));
        }
    }

    private static class IBackAnimationImpl extends IBackAnimation.Stub {
        private BackAnimationController mController;

        IBackAnimationImpl(BackAnimationController controller) {
            mController = controller;
        }

        @Override
        public void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
            executeRemoteCallWithTaskPermission(mController, "setBackToLauncherCallback",
                    (controller) -> mController.setBackToLauncherCallback(callback));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> mController.clearBackToLauncherCallback());
        }

        @Override
        public void onBackToLauncherAnimationFinished() {
            executeRemoteCallWithTaskPermission(mController, "onBackToLauncherAnimationFinished",
                    (controller) -> mController.onBackToLauncherAnimationFinished());
        }

        void invalidate() {
            mController = null;
        }
    }

    @VisibleForTesting
    void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
        mBackToLauncherCallback = callback;
    }

    private void clearBackToLauncherCallback() {
        mBackToLauncherCallback = null;
    }

    private void onBackToLauncherAnimationFinished() {
        if (mBackNavigationInfo != null) {
            IOnBackInvokedCallback callback = mBackNavigationInfo.getOnBackInvokedCallback();
            if (mTriggerBack) {
                dispatchOnBackInvoked(callback);
            } else {
                dispatchOnBackCancelled(callback);
            }
        }
        finishAnimation();
    }

    /**
     * Called when a new motion event needs to be transferred to this
     * {@link BackAnimationController}
     */
    public void onMotionEvent(MotionEvent event, @BackEvent.SwipeEdge int swipeEdge) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            initAnimation(event);
        } else if (action == MotionEvent.ACTION_MOVE) {
            onMove(event, swipeEdge);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onGestureFinished();
        }
    }

    private void initAnimation(MotionEvent event) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "initAnimation mMotionStarted=%b", mBackGestureStarted);
        if (mBackGestureStarted) {
            Log.e(TAG, "Animation is being initialized but is already started.");
            return;
        }

        if (mBackNavigationInfo != null) {
            finishAnimation();
        }
        mInitTouchLocation.set(event.getX(), event.getY());
        mBackGestureStarted = true;

        try {
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation();
            onBackNavigationInfoReceived(mBackNavigationInfo);
        } catch (RemoteException remoteException) {
            Log.e(TAG, "Failed to initAnimation", remoteException);
            finishAnimation();
        }
    }

    private void onBackNavigationInfoReceived(@Nullable BackNavigationInfo backNavigationInfo) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Received backNavigationInfo:%s", backNavigationInfo);
        if (backNavigationInfo == null) {
            Log.e(TAG, "Received BackNavigationInfo is null.");
            finishAnimation();
            return;
        }
        int backType = backNavigationInfo.getType();
        IOnBackInvokedCallback targetCallback = null;
        if (backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY) {
            HardwareBuffer hardwareBuffer = backNavigationInfo.getScreenshotHardwareBuffer();
            if (hardwareBuffer != null) {
                displayTargetScreenshot(hardwareBuffer,
                        backNavigationInfo.getTaskWindowConfiguration());
            }
            mTransaction.apply();
        } else if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME) {
            targetCallback = mBackToLauncherCallback;
        } else if (backType == BackNavigationInfo.TYPE_CALLBACK) {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
        }
        dispatchOnBackStarted(targetCallback);
    }

    /**
     * Display the screenshot of the activity beneath.
     *
     * @param hardwareBuffer The buffer containing the screenshot.
     */
    private void displayTargetScreenshot(@NonNull HardwareBuffer hardwareBuffer,
            WindowConfiguration taskWindowConfiguration) {
        SurfaceControl screenshotSurface =
                mBackNavigationInfo == null ? null : mBackNavigationInfo.getScreenshotSurface();
        if (screenshotSurface == null) {
            Log.e(TAG, "BackNavigationInfo doesn't contain a surface for the screenshot. ");
            return;
        }

        // Scale the buffer to fill the whole Task
        float sx = 1;
        float sy = 1;
        float w = taskWindowConfiguration.getBounds().width();
        float h = taskWindowConfiguration.getBounds().height();

        if (w != hardwareBuffer.getWidth()) {
            sx = w / hardwareBuffer.getWidth();
        }

        if (h != hardwareBuffer.getHeight()) {
            sy = h / hardwareBuffer.getHeight();
        }
        mTransaction.setScale(screenshotSurface, sx, sy);
        mTransaction.setBuffer(screenshotSurface, hardwareBuffer);
        mTransaction.setVisibility(screenshotSurface, true);
    }

    private void onMove(MotionEvent event, @BackEvent.SwipeEdge int swipeEdge) {
        if (!mBackGestureStarted || mBackNavigationInfo == null) {
            return;
        }
        int deltaX = Math.round(event.getX() - mInitTouchLocation.x);
        int deltaY = Math.round(event.getY() - mInitTouchLocation.y);
        ProtoLog.v(WM_SHELL_BACK_PREVIEW, "Runner move: %d %d", deltaX, deltaY);
        float progressThreshold = PROGRESS_THRESHOLD >= 0 ? PROGRESS_THRESHOLD : mProgressThreshold;
        float progress = Math.min(Math.max(Math.abs(deltaX) / progressThreshold, 0), 1);
        int backType = mBackNavigationInfo.getType();
        RemoteAnimationTarget animationTarget = mBackNavigationInfo.getDepartingAnimationTarget();

        BackEvent backEvent = new BackEvent(0, 0, progress, swipeEdge, animationTarget);
        IOnBackInvokedCallback targetCallback = null;
        if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME) {
            targetCallback = mBackToLauncherCallback;
        } else if (backType == BackNavigationInfo.TYPE_CROSS_TASK
                || backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY) {
            if (animationTarget != null) {
                mTransaction.setPosition(animationTarget.leash, deltaX, deltaY);
                mTouchEventDelta.set(deltaX, deltaY);
                mTransaction.apply();
            }
        } else if (backType == BackNavigationInfo.TYPE_CALLBACK) {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
        }
        dispatchOnBackProgressed(targetCallback, backEvent);
    }

    private void onGestureFinished() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "onGestureFinished() mTriggerBack == %s", mTriggerBack);
        if (!mBackGestureStarted || mBackNavigationInfo == null) {
            return;
        }
        int backType = mBackNavigationInfo.getType();
        boolean shouldDispatchToLauncher = backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                && mBackToLauncherCallback != null;
        IOnBackInvokedCallback targetCallback = shouldDispatchToLauncher
                ? mBackToLauncherCallback
                : mBackNavigationInfo.getOnBackInvokedCallback();
        if (mTriggerBack) {
            dispatchOnBackInvoked(targetCallback);
        } else {
            dispatchOnBackCancelled(targetCallback);
        }
        if (backType == BackNavigationInfo.TYPE_CALLBACK) {
            finishAnimation();
        } else if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                && !shouldDispatchToLauncher) {
            // Launcher callback missing. Simply finish animation.
            finishAnimation();
        } else if (backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY
                || backType == BackNavigationInfo.TYPE_CROSS_TASK) {
            if (mTriggerBack) {
                prepareTransition();
            } else {
                resetPositionAnimated();
            }
        }
    }

    private static void dispatchOnBackStarted(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackStarted();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackStarted error: ", e);
        }
    }

    private static void dispatchOnBackInvoked(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackInvoked();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackInvoked error: ", e);
        }
    }

    private static void dispatchOnBackCancelled(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackCancelled();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackCancelled error: ", e);
        }
    }

    private static void dispatchOnBackProgressed(
            IOnBackInvokedCallback callback, BackEvent backEvent) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackProgressed(backEvent);
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackProgressed error: ", e);
        }
    }

    /**
     * Animate the top window leash to its initial position.
     */
    private void resetPositionAnimated() {
        mBackGestureStarted = false;
        // TODO(208786853) Handle overlap with a new coming gesture.
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Runner: Back not triggered, cancelling animation "
                + "mLastPos=%s mInitTouch=%s", mTouchEventDelta, mInitTouchLocation);

        // TODO(208427216) : Replace placeholder animation with an actual one.
        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f).setDuration(200);
        animation.addUpdateListener(animation1 -> {
            if (mBackNavigationInfo == null) {
                return;
            }
            float fraction = animation1.getAnimatedFraction();
            int deltaX = Math.round(mTouchEventDelta.x - (mTouchEventDelta.x * fraction));
            int deltaY = Math.round(mTouchEventDelta.y - (mTouchEventDelta.y * fraction));
            RemoteAnimationTarget animationTarget =
                    mBackNavigationInfo.getDepartingAnimationTarget();
            if (animationTarget != null) {
                mTransaction.setPosition(animationTarget.leash, deltaX, deltaY);
                mTransaction.apply();
            }
        });

        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: onAnimationEnd");
                finishAnimation();
            }
        });
        animation.start();
    }

    private void prepareTransition() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "prepareTransition()");
        mTriggerBack = false;
        mBackGestureStarted = false;
    }

    /**
     * Sets to true when the back gesture has passed the triggering threshold, false otherwise.
     */
    public void setTriggerBack(boolean triggerBack) {
        mTriggerBack = triggerBack;
    }

    private void setSwipeThresholds(float triggerThreshold, float progressThreshold) {
        mProgressThreshold = progressThreshold;
        mTriggerThreshold = triggerThreshold;
    }

    private void finishAnimation() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishAnimation()");
        mBackGestureStarted = false;
        mTouchEventDelta.set(0, 0);
        mInitTouchLocation.set(0, 0);
        BackNavigationInfo backNavigationInfo = mBackNavigationInfo;
        boolean triggerBack = mTriggerBack;
        mBackNavigationInfo = null;
        mTriggerBack = false;
        if (backNavigationInfo == null) {
            return;
        }
        RemoteAnimationTarget animationTarget = backNavigationInfo.getDepartingAnimationTarget();
        if (animationTarget != null && mTriggerBack) {
            if (animationTarget.leash != null && animationTarget.leash.isValid()) {
                mTransaction.remove(animationTarget.leash);
            }
        }
        SurfaceControl screenshotSurface = backNavigationInfo.getScreenshotSurface();
        if (screenshotSurface != null && screenshotSurface.isValid()) {
            mTransaction.remove(screenshotSurface);
        }
        mTransaction.apply();
        backNavigationInfo.onBackNavigationFinished(triggerBack);
    }
}
