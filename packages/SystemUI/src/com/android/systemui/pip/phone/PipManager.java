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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.view.DisplayInfo;
import android.view.IPinnedStackController;
import android.view.WindowContainerTransaction;

import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.pip.BasePipManager;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.PinnedStackListenerForwarder.PinnedStackListener;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.wm.DisplayWindowController;

import java.io.PrintWriter;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipManager implements BasePipManager {
    private static final String TAG = "PipManager";

    private static PipManager sPipController;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IActivityTaskManager mActivityTaskManager;
    private Handler mHandler = new Handler();

    private final PinnedStackListener mPinnedStackListener = new PipManagerPinnedStackListener();
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();
    private final Rect mTmpNormalBounds = new Rect();
    private final Rect mReentryBounds = new Rect();

    private PipBoundsHandler mPipBoundsHandler;
    private InputConsumerController mInputConsumerController;
    private PipMenuActivityController mMenuController;
    private PipMediaController mMediaController;
    private PipTouchHandler mTouchHandler;
    private PipAppOpsListener mAppOpsListener;

    /**
     * Handler for display rotation changes.
     */
    private final DisplayWindowController.OnDisplayWindowRotationController mRotationController = (
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) -> {
        final boolean changed = mPipBoundsHandler.onDisplayRotationChanged(mTmpNormalBounds,
                displayId, fromRotation, toRotation, t);
        if (changed) {
            updateMovementBounds(mTmpNormalBounds, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */);
        }
    };

    /**
     * Handler for system task stack changes.
     */
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            mTouchHandler.onActivityPinned();
            mMediaController.onActivityPinned();
            mMenuController.onActivityPinned();
            mAppOpsListener.onActivityPinned(packageName);

            Dependency.get(UiOffloadThread.class).execute(() -> {
                WindowManagerWrapper.getInstance().setPipVisibility(true);
            });
        }

        @Override
        public void onActivityUnpinned() {
            final Pair<ComponentName, Integer> topPipActivityInfo = PipUtils.getTopPinnedActivity(
                    mContext, mActivityManager);
            final ComponentName topActivity = topPipActivityInfo.first;
            mMenuController.onActivityUnpinned();
            mTouchHandler.onActivityUnpinned(topActivity);
            mAppOpsListener.onActivityUnpinned();

            Dependency.get(UiOffloadThread.class).execute(() -> {
                WindowManagerWrapper.getInstance().setPipVisibility(topActivity != null);
            });
        }

        @Override
        public void onPinnedStackAnimationStarted() {
            // Disable touches while the animation is running
            mTouchHandler.setTouchEnabled(false);
        }

        @Override
        public void onPinnedStackAnimationEnded() {
            // Re-enable touches after the animation completes
            mTouchHandler.setTouchEnabled(true);
            mTouchHandler.onPinnedStackAnimationEnded();
            mMenuController.onPinnedStackAnimationEnded();
        }

        @Override
        public void onPinnedActivityRestartAttempt(boolean clearedTask) {
            mTouchHandler.getMotionHelper().expandPip(clearedTask /* skipAnimation */);
        }
    };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipManagerPinnedStackListener extends PinnedStackListener {
        @Override
        public void onListenerRegistered(IPinnedStackController controller) {
            mHandler.post(() -> {
                mPipBoundsHandler.setPinnedStackController(controller);
                mTouchHandler.setPinnedStackController(controller);
            });
        }

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mHandler.post(() -> {
                mPipBoundsHandler.onImeVisibilityChanged(imeVisible, imeHeight);
                mTouchHandler.onImeVisibilityChanged(imeVisible, imeHeight);
            });
        }

        @Override
        public void onMinimizedStateChanged(boolean isMinimized) {
            mHandler.post(() -> {
                mPipBoundsHandler.onMinimizedStateChanged(isMinimized);
                mTouchHandler.setMinimizedState(isMinimized, true /* fromController */);
            });
        }

        @Override
        public void onMovementBoundsChanged(Rect animatingBounds, boolean fromImeAdjustment,
                boolean fromShelfAdjustment) {
            mHandler.post(() -> updateMovementBounds(animatingBounds, fromImeAdjustment,
                    fromShelfAdjustment));
        }

        @Override
        public void onActionsChanged(ParceledListSlice actions) {
            mHandler.post(() -> mMenuController.setAppActions(actions));
        }

        @Override
        public void onSaveReentryBounds(ComponentName componentName, Rect bounds) {
            mHandler.post(() -> {
                // On phones, the expansion animation that happens on pip tap before restoring
                // to fullscreen makes it so that the bounds received here are the expanded
                // bounds. We want to restore to the unexpanded bounds when re-entering pip,
                // so we save the bounds before expansion (normal) instead of the current
                // bounds.
                mReentryBounds.set(mTouchHandler.getNormalBounds());
                // Apply the snap fraction of the current bounds to the normal bounds.
                float snapFraction = mPipBoundsHandler.getSnapFraction(bounds);
                mPipBoundsHandler.applySnapFraction(mReentryBounds, snapFraction);
                // Save reentry bounds (normal non-expand bounds with current position applied).
                mPipBoundsHandler.onSaveReentryBounds(componentName, mReentryBounds);
            });
        }

        @Override
        public void onResetReentryBounds(ComponentName componentName) {
            mHandler.post(() -> mPipBoundsHandler.onResetReentryBounds(componentName));
        }

        @Override
        public void onDisplayInfoChanged(DisplayInfo displayInfo) {
            mHandler.post(() -> mPipBoundsHandler.onDisplayInfoChanged(displayInfo));
        }

        @Override
        public void onConfigurationChanged() {
            mHandler.post(() -> mPipBoundsHandler.onConfigurationChanged());
        }

        @Override
        public void onAspectRatioChanged(float aspectRatio) {
            mHandler.post(() -> mPipBoundsHandler.onAspectRatioChanged(aspectRatio));
        }

        @Override
        public void onPrepareAnimation(Rect sourceRectHint, float aspectRatio, Rect bounds) {
            mHandler.post(() -> {
                mPipBoundsHandler.onPrepareAnimation(sourceRectHint, aspectRatio, bounds);
            });
        }
    }

    private PipManager() {}

    /**
     * Initializes {@link PipManager}.
     */
    public void initialize(Context context, BroadcastDispatcher broadcastDispatcher,
            DisplayWindowController displayWindowController) {
        mContext = context;
        mActivityManager = ActivityManager.getService();
        mActivityTaskManager = ActivityTaskManager.getService();

        try {
            WindowManagerWrapper.getInstance().addPinnedStackListener(mPinnedStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

        mPipBoundsHandler = new PipBoundsHandler(context);
        mInputConsumerController = InputConsumerController.getPipInputConsumer();
        mMediaController = new PipMediaController(context, mActivityManager, broadcastDispatcher);
        mMenuController = new PipMenuActivityController(context, mActivityManager, mMediaController,
                mInputConsumerController);
        mTouchHandler = new PipTouchHandler(context, mActivityManager, mActivityTaskManager,
                mMenuController, mInputConsumerController, mPipBoundsHandler);
        mAppOpsListener = new PipAppOpsListener(context, mActivityManager,
                mTouchHandler.getMotionHelper());
        displayWindowController.addRotationController(mRotationController);

        // If SystemUI restart, and it already existed a pinned stack,
        // register the pip input consumer to ensure touch can send to it.
        try {
            ActivityManager.StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (stackInfo != null) {
                mInputConsumerController.registerInputConsumer();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the PIP per configuration changed.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        mTouchHandler.onConfigurationChanged();
    }

    /**
     * Expands the PIP.
     */
    @Override
    public void expandPip() {
        mTouchHandler.getMotionHelper().expandPip(false /* skipAnimation */);
    }

    /**
     * Hides the PIP menu.
     */
    @Override
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
        mMenuController.hideMenu(onStartCallback, onEndCallback);
    }

    /**
     * Sent from KEYCODE_WINDOW handler in PhoneWindowManager, to request the menu to be shown.
     */
    public void showPictureInPictureMenu() {
        mTouchHandler.showPictureInPictureMenu();
    }

    /**
     * Sets both shelf visibility and its height.
     */
    @Override
    public void setShelfHeight(boolean visible, int height) {
        mHandler.post(() -> {
            final boolean changed = mPipBoundsHandler.setShelfHeight(visible, height);
            if (changed) {
                mTouchHandler.onShelfVisibilityChanged(visible, height);
                updateMovementBounds(mPipBoundsHandler.getLastDestinationBounds(),
                        false /* fromImeAdjustment */, true /* fromShelfAdjustment */);
            }
        });
    }

    private void updateMovementBounds(Rect animatingBounds, boolean fromImeAdjustment,
            boolean fromShelfAdjustment) {
        // Populate inset / normal bounds and DisplayInfo from mPipBoundsHandler first.
        mPipBoundsHandler.onMovementBoundsChanged(mTmpInsetBounds, mTmpNormalBounds,
                animatingBounds, mTmpDisplayInfo);
        mTouchHandler.onMovementBoundsChanged(mTmpInsetBounds, mTmpNormalBounds,
                animatingBounds, fromImeAdjustment, fromShelfAdjustment,
                mTmpDisplayInfo.rotation);
    }

    /**
     * Gets an instance of {@link PipManager}.
     */
    public static PipManager getInstance() {
        if (sPipController == null) {
            sPipController = new PipManager();
        }
        return sPipController;
    }

    public void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        mInputConsumerController.dump(pw, innerPrefix);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
        mPipBoundsHandler.dump(pw, innerPrefix);
    }
}
