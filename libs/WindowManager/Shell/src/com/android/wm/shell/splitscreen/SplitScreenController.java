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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Controls split screen feature.
 */
public class SplitScreenController implements SplitScreen,
        DisplayController.OnDisplaysChangedListener {
    static final boolean DEBUG = false;

    private static final String TAG = "SplitScreenCtrl";
    private static final int DEFAULT_APP_TRANSITION_DURATION = 336;

    private final Context mContext;
    private final DisplayChangeController.OnDisplayChangingListener mRotationController;
    private final DisplayController mDisplayController;
    private final DisplayImeController mImeController;
    private final DividerImeController mImePositionProcessor;
    private final DividerState mDividerState = new DividerState();
    private final ForcedResizableInfoActivityController mForcedResizableController;
    private final Handler mHandler;
    private final SplitScreenTaskListener mSplits;
    private final SystemWindows mSystemWindows;
    final TransactionPool mTransactionPool;
    private final WindowManagerProxy mWindowManagerProxy;
    private final TaskOrganizer mTaskOrganizer;

    private final ArrayList<WeakReference<Consumer<Boolean>>> mDockedStackExistsListeners =
            new ArrayList<>();
    private final ArrayList<WeakReference<BiConsumer<Rect, Rect>>> mBoundsChangedListeners =
            new ArrayList<>();


    private DividerWindowManager mWindowManager;
    private DividerView mView;

    // Keeps track of real-time split geometry including snap positions and ime adjustments
    private SplitDisplayLayout mSplitLayout;

    // Transient: this contains the layout calculated for a new rotation requested by WM. This is
    // kept around so that we can wait for a matching configuration change and then use the exact
    // layout that we sent back to WM.
    private SplitDisplayLayout mRotateSplitLayout;

    private boolean mIsKeyguardShowing;
    private boolean mVisible = false;
    private boolean mMinimized = false;
    private boolean mAdjustedForIme = false;
    private boolean mHomeStackResizable = false;

    public SplitScreenController(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController imeController, Handler handler, TransactionPool transactionPool,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue) {
        mContext = context;
        mDisplayController = displayController;
        mSystemWindows = systemWindows;
        mImeController = imeController;
        mHandler = handler;
        mForcedResizableController = new ForcedResizableInfoActivityController(context, this);
        mTransactionPool = transactionPool;
        mWindowManagerProxy = new WindowManagerProxy(syncQueue, shellTaskOrganizer);
        mTaskOrganizer = shellTaskOrganizer;
        mSplits = new SplitScreenTaskListener(this, shellTaskOrganizer, syncQueue);
        mImePositionProcessor = new DividerImeController(mSplits, mTransactionPool, mHandler,
                shellTaskOrganizer);
        mRotationController =
                (display, fromRotation, toRotation, wct) -> {
                    if (!mSplits.isSplitScreenSupported() || mWindowManagerProxy == null) {
                        return;
                    }
                    WindowContainerTransaction t = new WindowContainerTransaction();
                    DisplayLayout displayLayout =
                            new DisplayLayout(mDisplayController.getDisplayLayout(display));
                    SplitDisplayLayout sdl =
                            new SplitDisplayLayout(mContext, displayLayout, mSplits);
                    sdl.rotateTo(toRotation);
                    mRotateSplitLayout = sdl;
                    final int position = isDividerVisible()
                            ? (mMinimized ? mView.mSnapTargetBeforeMinimized.position
                            : mView.getCurrentPosition())
                            // snap resets to middle target when not in split-mode
                            : sdl.getSnapAlgorithm().getMiddleTarget().position;
                    DividerSnapAlgorithm snap = sdl.getSnapAlgorithm();
                    final DividerSnapAlgorithm.SnapTarget target =
                            snap.calculateNonDismissingSnapTarget(position);
                    sdl.resizeSplits(target.position, t);

                    if (isSplitActive() && mHomeStackResizable) {
                        mWindowManagerProxy
                                .applyHomeTasksMinimized(sdl, mSplits.mSecondary.token, t);
                    }
                    if (mWindowManagerProxy.queueSyncTransactionIfWaiting(t)) {
                        // Because sync transactions are serialized, its possible for an "older"
                        // bounds-change to get applied after a screen rotation. In that case, we
                        // want to actually defer on that rather than apply immediately. Of course,
                        // this means that the bounds may not change until after the rotation so
                        // the user might see some artifacts. This should be rare.
                        Slog.w(TAG, "Screen rotated while other operations were pending, this may"
                                + " result in some graphical artifacts.");
                    } else {
                        wct.merge(t, true /* transfer */);
                    }
                };

        mWindowManager = new DividerWindowManager(mSystemWindows);
        mDisplayController.addDisplayWindowListener(this);
        // Don't initialize the divider or anything until we get the default display.
    }

    void onSplitScreenSupported() {
        // Set starting tile bounds based on middle target
        final WindowContainerTransaction tct = new WindowContainerTransaction();
        int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
        mSplitLayout.resizeSplits(midPos, tct);
        mTaskOrganizer.applyTransaction(tct);
    }

    @Override
    public boolean isSplitScreenSupported() {
        return mSplits.isSplitScreenSupported();
    }

    @Override
    public void onKeyguardVisibilityChanged(boolean showing) {
        if (!isSplitActive() || mView == null) {
            return;
        }
        mView.setHidden(showing);
        if (!showing) {
            mImePositionProcessor.updateAdjustForIme();
        }
        mIsKeyguardShowing = showing;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mSplitLayout = new SplitDisplayLayout(mDisplayController.getDisplayContext(displayId),
                mDisplayController.getDisplayLayout(displayId), mSplits);
        mImeController.addPositionProcessor(mImePositionProcessor);
        mDisplayController.addDisplayChangingController(mRotationController);
        if (!ActivityTaskManager.supportsSplitScreenMultiWindow(mContext)) {
            removeDivider();
            return;
        }
        try {
            mSplits.init();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register docked stack listener", e);
            removeDivider();
            return;
        }
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        if (displayId != DEFAULT_DISPLAY || !mSplits.isSplitScreenSupported()) {
            return;
        }
        mSplitLayout = new SplitDisplayLayout(mDisplayController.getDisplayContext(displayId),
                mDisplayController.getDisplayLayout(displayId), mSplits);
        if (mRotateSplitLayout == null) {
            int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
            final WindowContainerTransaction tct = new WindowContainerTransaction();
            mSplitLayout.resizeSplits(midPos, tct);
            mTaskOrganizer.applyTransaction(tct);
        } else if (mSplitLayout.mDisplayLayout.rotation()
                == mRotateSplitLayout.mDisplayLayout.rotation()) {
            mSplitLayout.mPrimary = new Rect(mRotateSplitLayout.mPrimary);
            mSplitLayout.mSecondary = new Rect(mRotateSplitLayout.mSecondary);
            mRotateSplitLayout = null;
        }
        if (isSplitActive()) {
            update(newConfig);
        }
    }

    /** Posts task to handler dealing with divider. */
    void post(Runnable task) {
        mHandler.post(task);
    }

    @Override
    public DividerView getDividerView() {
        return mView;
    }

    @Override
    public boolean isMinimized() {
        return mMinimized;
    }

    @Override
    public boolean isHomeStackResizable() {
        return mHomeStackResizable;
    }

    @Override
    public boolean isDividerVisible() {
        return mView != null && mView.getVisibility() == View.VISIBLE;
    }

    /**
     * This indicates that at-least one of the splits has content. This differs from
     * isDividerVisible because the divider is only visible once *everything* is in split mode
     * while this only cares if some things are (eg. while entering/exiting as well).
     */
    private boolean isSplitActive() {
        return mSplits.mPrimary != null && mSplits.mSecondary != null
                && (mSplits.mPrimary.topActivityType != ACTIVITY_TYPE_UNDEFINED
                || mSplits.mSecondary.topActivityType != ACTIVITY_TYPE_UNDEFINED);
    }

    private void addDivider(Configuration configuration) {
        Context dctx = mDisplayController.getDisplayContext(mContext.getDisplayId());
        mView = (DividerView)
                LayoutInflater.from(dctx).inflate(R.layout.docked_stack_divider, null);
        DisplayLayout displayLayout = mDisplayController.getDisplayLayout(mContext.getDisplayId());
        mView.injectDependencies(this, mWindowManager, mDividerState, mForcedResizableController,
                mSplits, mSplitLayout, mImePositionProcessor, mWindowManagerProxy);
        mView.setVisibility(mVisible ? View.VISIBLE : View.INVISIBLE);
        mView.setMinimizedDockStack(mMinimized, mHomeStackResizable, null /* transaction */);
        final int size = dctx.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? size : displayLayout.width();
        final int height = landscape ? displayLayout.height() : size;
        mWindowManager.add(mView, width, height, mContext.getDisplayId());
    }

    private void removeDivider() {
        if (mView != null) {
            mView.onDividerRemoved();
        }
        mWindowManager.remove();
    }

    private void update(Configuration configuration) {
        final boolean isDividerHidden = mView != null && mIsKeyguardShowing;

        removeDivider();
        addDivider(configuration);

        if (mMinimized) {
            mView.setMinimizedDockStack(true, mHomeStackResizable, null /* transaction */);
            updateTouchable();
        }
        mView.setHidden(isDividerHidden);
    }

    void onTaskVanished() {
        mHandler.post(this::removeDivider);
    }

    private void updateVisibility(final boolean visible) {
        if (DEBUG) Slog.d(TAG, "Updating visibility " + mVisible + "->" + visible);
        if (mVisible != visible) {
            mVisible = visible;
            mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

            if (visible) {
                mView.enterSplitMode(mHomeStackResizable);
                // Update state because animations won't finish.
                mWindowManagerProxy.runInSync(
                        t -> mView.setMinimizedDockStack(mMinimized, mHomeStackResizable, t));

            } else {
                mView.exitSplitMode();
                mWindowManagerProxy.runInSync(
                        t -> mView.setMinimizedDockStack(false, mHomeStackResizable, t));
            }
            // Notify existence listeners
            synchronized (mDockedStackExistsListeners) {
                mDockedStackExistsListeners.removeIf(wf -> {
                    Consumer<Boolean> l = wf.get();
                    if (l != null) l.accept(visible);
                    return l == null;
                });
            }
        }
    }

    @Override
    public void setMinimized(final boolean minimized) {
        if (DEBUG) Slog.d(TAG, "posting ext setMinimized " + minimized + " vis:" + mVisible);
        mHandler.post(() -> {
            if (DEBUG) Slog.d(TAG, "run posted ext setMinimized " + minimized + " vis:" + mVisible);
            if (!mVisible) {
                return;
            }
            setHomeMinimized(minimized);
        });
    }

    private void setHomeMinimized(final boolean minimized) {
        if (DEBUG) {
            Slog.d(TAG, "setHomeMinimized  min:" + mMinimized + "->" + minimized + " hrsz:"
                    + mHomeStackResizable + " split:" + isDividerVisible());
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        final boolean minimizedChanged = mMinimized != minimized;
        // Update minimized state
        if (minimizedChanged) {
            mMinimized = minimized;
        }
        // Always set this because we could be entering split when mMinimized is already true
        wct.setFocusable(mSplits.mPrimary.token, !mMinimized);

        // Sync state to DividerView if it exists.
        if (mView != null) {
            final int displayId = mView.getDisplay() != null
                    ? mView.getDisplay().getDisplayId() : DEFAULT_DISPLAY;
            // pause ime here (before updateMinimizedDockedStack)
            if (mMinimized) {
                mImePositionProcessor.pause(displayId);
            }
            if (minimizedChanged) {
                // This conflicts with IME adjustment, so only call it when things change.
                mView.setMinimizedDockStack(minimized, getAnimDuration(), mHomeStackResizable);
            }
            if (!mMinimized) {
                // afterwards so it can end any animations started in view
                mImePositionProcessor.resume(displayId);
            }
        }
        updateTouchable();

        // If we are only setting focusability, a sync transaction isn't necessary (in fact it
        // can interrupt other animations), so see if it can be submitted on pending instead.
        if (!mWindowManagerProxy.queueSyncTransactionIfWaiting(wct)) {
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    void setAdjustedForIme(boolean adjustedForIme) {
        if (mAdjustedForIme == adjustedForIme) {
            return;
        }
        mAdjustedForIme = adjustedForIme;
        updateTouchable();
    }

    private void updateTouchable() {
        mWindowManager.setTouchable(!mAdjustedForIme);
    }

    @Override
    public void onActivityForcedResizable(String packageName, int taskId, int reason) {
        mForcedResizableController.activityForcedResizable(packageName, taskId, reason);
    }

    @Override
    public void onActivityDismissingSplitScreen() {
        mForcedResizableController.activityDismissingSplitScreen();
    }

    @Override
    public void onActivityLaunchOnSecondaryDisplayFailed() {
        mForcedResizableController.activityLaunchOnSecondaryDisplayFailed();
    }

    @Override
    public void onUndockingTask() {
        if (mView != null) {
            mView.onUndockingTask();
        }
    }

    @Override
    public void onAppTransitionFinished() {
        if (mView == null) {
            return;
        }
        mForcedResizableController.onAppTransitionFinished();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("  mVisible="); pw.println(mVisible);
        pw.print("  mMinimized="); pw.println(mMinimized);
        pw.print("  mAdjustedForIme="); pw.println(mAdjustedForIme);
    }

    long getAnimDuration() {
        float transitionScale = Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                mContext.getResources().getFloat(
                        com.android.internal.R.dimen
                                .config_appTransitionAnimationDurationScaleDefault));
        final long transitionDuration = DEFAULT_APP_TRANSITION_DURATION;
        return (long) (transitionDuration * transitionScale);
    }

    @Override
    public void registerInSplitScreenListener(Consumer<Boolean> listener) {
        listener.accept(isDividerVisible());
        synchronized (mDockedStackExistsListeners) {
            mDockedStackExistsListeners.add(new WeakReference<>(listener));
        }
    }

    @Override
    public void registerBoundsChangeListener(BiConsumer<Rect, Rect> listener) {
        synchronized (mBoundsChangedListeners) {
            mBoundsChangedListeners.add(new WeakReference<>(listener));
        }
    }

    @Override
    public boolean splitPrimaryTask() {
        try {
            if (ActivityTaskManager.getService().getLockTaskModeState() == LOCK_TASK_MODE_PINNED
                    || isSplitActive()) {
                return false;
            }

            // Try fetching the top running task.
            final List<RunningTaskInfo> runningTasks =
                    ActivityTaskManager.getService().getTasks(1 /* maxNum */);
            if (runningTasks == null || runningTasks.isEmpty()) {
                return false;
            }
            // Note: The set of running tasks from the system is ordered by recency.
            final RunningTaskInfo topRunningTask = runningTasks.get(0);

            final int activityType = topRunningTask.configuration.windowConfiguration
                    .getActivityType();
            if (activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS) {
                return false;
            }

            if (!topRunningTask.supportsSplitScreenMultiWindow) {
                Toast.makeText(mContext, R.string.dock_non_resizeble_failed_to_dock_text,
                        Toast.LENGTH_SHORT).show();
                return false;
            }

            return ActivityTaskManager.getService().setTaskWindowingModeSplitScreenPrimary(
                    topRunningTask.taskId, true /* onTop */);
        } catch (RemoteException e) {
            return false;
        }
    }

    /** Notifies the bounds of split screen changed. */
    void notifyBoundsChanged(Rect secondaryWindowBounds, Rect secondaryWindowInsets) {
        synchronized (mBoundsChangedListeners) {
            mBoundsChangedListeners.removeIf(wf -> {
                BiConsumer<Rect, Rect> l = wf.get();
                if (l != null) l.accept(secondaryWindowBounds, secondaryWindowInsets);
                return l == null;
            });
        }
    }

    void startEnterSplit() {
        update(mDisplayController.getDisplayContext(
                mContext.getDisplayId()).getResources().getConfiguration());
        // Set resizable directly here because applyEnterSplit already resizes home stack.
        mHomeStackResizable = mWindowManagerProxy.applyEnterSplit(mSplits, mSplitLayout);
    }

    void startDismissSplit() {
        mWindowManagerProxy.applyDismissSplit(mSplits, mSplitLayout, true /* dismissOrMaximize */);
        updateVisibility(false /* visible */);
        mMinimized = false;
        // Resets divider bar position to undefined, so new divider bar will apply default position
        // next time entering split mode.
        mDividerState.mRatioPositionBeforeMinimized = 0;
        removeDivider();
        mImePositionProcessor.reset();
    }

    void ensureMinimizedSplit() {
        setHomeMinimized(true /* minimized */);
        if (mView != null && !isDividerVisible()) {
            // Wasn't in split-mode yet, so enter now.
            if (DEBUG) {
                Slog.d(TAG, " entering split mode with minimized=true");
            }
            updateVisibility(true /* visible */);
        }
    }

    void ensureNormalSplit() {
        setHomeMinimized(false /* minimized */);
        if (mView != null && !isDividerVisible()) {
            // Wasn't in split-mode, so enter now.
            if (DEBUG) {
                Slog.d(TAG, " enter split mode unminimized ");
            }
            updateVisibility(true /* visible */);
        }
    }

    SplitDisplayLayout getSplitLayout() {
        return mSplitLayout;
    }

    WindowManagerProxy getWmProxy() {
        return mWindowManagerProxy;
    }

    @Override
    public WindowContainerToken getSecondaryRoot() {
        if (mSplits == null || mSplits.mSecondary == null) {
            return null;
        }
        return mSplits.mSecondary.token;
    }
}
