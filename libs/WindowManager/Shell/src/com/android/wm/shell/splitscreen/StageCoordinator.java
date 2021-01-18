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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.wm.shell.splitscreen.SplitScreen.SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.splitscreen.SplitScreen.SIDE_STAGE_POSITION_TOP_OR_LEFT;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.split.SplitLayout;

import java.io.PrintWriter;

/**
 * Coordinates the staging (visibility, sizing, ...) of the split-screen {@link MainStage} and
 * {@link SideStage} stages.
 * Some high-level rules:
 * - The {@link StageCoordinator} is only considered active if the {@link SideStage} contains at
 * least one child task.
 * - The {@link MainStage} should only have children if the coordinator is active.
 * - The {@link SplitLayout} divider is only visible if both the {@link MainStage}
 * and {@link SideStage} are visible.
 * - The {@link MainStage} configuration is fullscreen when the {@link SideStage} isn't visible.
 * This rules are mostly implemented in {@link #onStageVisibilityChanged(StageListenerImpl)} and
 * {@link #onStageHasChildrenChanged(StageListenerImpl).}
 */
class StageCoordinator implements SplitLayout.LayoutChangeListener,
        RootTaskDisplayAreaOrganizer.RootTaskDisplayAreaListener {

    private static final String TAG = StageCoordinator.class.getSimpleName();

    private final MainStage mMainStage;
    private final StageListenerImpl mMainStageListener = new StageListenerImpl();
    private final SideStage mSideStage;
    private final StageListenerImpl mSideStageListener = new StageListenerImpl();
    private @SplitScreen.SideStagePosition int mSideStagePosition =
            SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT;

    private final int mDisplayId;
    private SplitLayout mSplitLayout;
    private boolean mDividerVisible;
    private final SyncTransactionQueue mSyncQueue;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellTaskOrganizer mTaskOrganizer;
    private DisplayAreaInfo mDisplayAreaInfo;
    private final Context mContext;

    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mMainStage = new MainStage(mTaskOrganizer, mDisplayId, mMainStageListener, mSyncQueue);
        mSideStage = new SideStage(mTaskOrganizer, mDisplayId, mSideStageListener, mSyncQueue);
        mRootTDAOrganizer.registerListener(displayId, this);
    }

    @VisibleForTesting
    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
            MainStage mainStage, SideStage sideStage) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mMainStage = mainStage;
        mSideStage = sideStage;
        mRootTDAOrganizer.registerListener(displayId, this);
    }

    boolean isSplitScreenVisible() {
        return mSideStageListener.mVisible && mMainStageListener.mVisible;
    }

    boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @SplitScreen.SideStagePosition int sideStagePosition) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mSideStagePosition = sideStagePosition;
        mMainStage.activate(getMainStageBounds(), wct);
        mSideStage.addTask(task, getSideStageBounds(), wct);
        mTaskOrganizer.applyTransaction(wct);
        return true;
    }

    boolean removeFromSideStage(int taskId) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        /**
         * {@link MainStage} will be deactivated in {@link #onStageHasChildrenChanged} if the
         * {@link SideStage} no longer has children.
         */
        final boolean result = mSideStage.removeTask(taskId,
                mMainStage.isActive() ? mMainStage.mRootTaskInfo.token : null,
                wct);
        mTaskOrganizer.applyTransaction(wct);
        return result;
    }

    void setSideStagePosition(@SplitScreen.SideStagePosition int sideStagePosition) {
        mSideStagePosition = sideStagePosition;
        if (mSideStageListener.mVisible) {
            onStageVisibilityChanged(mSideStageListener);
        }
    }

    void setSideStageVisibility(boolean visible) {
        if (!mSideStageListener.mVisible == visible) return;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mSideStage.setVisibility(visible, wct);
        mTaskOrganizer.applyTransaction(wct);
    }

    private void onStageRootTaskVanished(StageListenerImpl stageListener) {
        if (stageListener == mMainStageListener || stageListener == mSideStageListener) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Deactivate the main stage if it no longer has a root task.
            mMainStage.deactivate(wct);
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private void onStageVisibilityChanged(StageListenerImpl stageListener) {
        final boolean sideStageVisible = mSideStageListener.mVisible;
        final boolean mainStageVisible = mMainStageListener.mVisible;
        // Divider is only visible if both the main stage and side stages are visible
        final boolean dividerVisible = sideStageVisible && mainStageVisible;

        if (mDividerVisible != dividerVisible) {
            mDividerVisible = dividerVisible;
            if (mDividerVisible) {
                mSplitLayout.init();
            } else {
                mSplitLayout.release();
            }
        }

        if (mainStageVisible) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            if (sideStageVisible) {
                // The main stage configuration should to follow split layout when side stage is
                // visible.
                mMainStage.updateConfiguration(
                        WINDOWING_MODE_MULTI_WINDOW, getMainStageBounds(), wct);
            } else {
                // We want the main stage configuration to be fullscreen when the side stage isn't
                // visible.
                mMainStage.updateConfiguration(WINDOWING_MODE_FULLSCREEN, null, wct);
            }
            // TODO: Change to `mSyncQueue.queue(wct)` once BLAST is stable.
            mTaskOrganizer.applyTransaction(wct);
        }

        mSyncQueue.runInSync(t -> {
            final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
            final SurfaceControl sideStageLeash = mSideStage.mRootLeash;
            final SurfaceControl mainStageLeash = mMainStage.mRootLeash;

            if (dividerLeash != null) {
                if (mDividerVisible) {
                    t.show(dividerLeash)
                            .setLayer(dividerLeash, Integer.MAX_VALUE)
                            .setPosition(dividerLeash,
                                    mSplitLayout.getDividerBounds().left,
                                    mSplitLayout.getDividerBounds().top);
                } else {
                    t.hide(dividerLeash);
                }
            }

            if (sideStageVisible) {
                final Rect sideStageBounds = getSideStageBounds();
                t.show(sideStageLeash)
                        .setPosition(sideStageLeash,
                                sideStageBounds.left, sideStageBounds.top)
                        .setWindowCrop(sideStageLeash,
                                sideStageBounds.width(), sideStageBounds.height());
            } else {
                t.hide(sideStageLeash);
            }

            if (mainStageVisible) {
                final Rect mainStageBounds = getMainStageBounds();
                t.show(mainStageLeash);
                if (sideStageVisible) {
                    t.setPosition(mainStageLeash, mainStageBounds.left, mainStageBounds.top)
                            .setWindowCrop(mainStageLeash,
                                    mainStageBounds.width(), mainStageBounds.height());
                } else {
                    // Clear window crop and position if side stage isn't visible.
                    t.setPosition(mainStageLeash, 0, 0)
                            .setWindowCrop(mainStageLeash, null);
                }
            } else {
                t.hide(mainStageLeash);
            }
        });
    }

    private void onStageHasChildrenChanged(StageListenerImpl stageListener) {
        if (stageListener == mSideStageListener) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            if (mSideStageListener.mHasChildren) {
                // Make sure the main stage is active.
                mMainStage.activate(getMainStageBounds(), wct);
            } else {
                // The side stage no long has children so we can deactivate the main stage.
                mMainStage.deactivate(wct);
            }
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    @Override
    public void onSnappedToDismiss(boolean snappedToEnd) {
        // TODO: What to do...what to do...
        mSplitLayout.resetDividerPosition();
        onBoundsChanged(mSplitLayout);
    }

    @Override
    public void onBoundsChanging(SplitLayout layout) {
        final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
        if (dividerLeash == null) return;
        final Rect mainStageBounds = getMainStageBounds();
        final Rect sideStageBounds = getSideStageBounds();

        mSyncQueue.runInSync(t -> t
                .setPosition(dividerLeash,
                        mSplitLayout.getDividerBounds().left, mSplitLayout.getDividerBounds().top)
                .setPosition(mMainStage.mRootLeash, mainStageBounds.left, mainStageBounds.top)
                .setPosition(mSideStage.mRootLeash, sideStageBounds.left, sideStageBounds.top)
                // Sets crop to prevent visible region of tasks overlap with each other when
                // re-positioning surfaces while resizing.
                .setWindowCrop(mMainStage.mRootLeash,
                        mainStageBounds.width(), mainStageBounds.height())
                .setWindowCrop(mSideStage.mRootLeash,
                        sideStageBounds.width(), sideStageBounds.height()));

    }

    @Override
    public void onDoubleTappedDivider() {
        setSideStagePosition(mSideStagePosition == SIDE_STAGE_POSITION_TOP_OR_LEFT
                ? SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT : SIDE_STAGE_POSITION_TOP_OR_LEFT);
    }

    @Override
    public void onBoundsChanged(SplitLayout layout) {
        final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
        if (dividerLeash == null) return;
        final Rect mainStageBounds = getMainStageBounds();
        final Rect sideStageBounds = getSideStageBounds();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mMainStage.setBounds(mainStageBounds, wct);
        mSideStage.setBounds(sideStageBounds, wct);
        mTaskOrganizer.applyTransaction(wct);

        mSyncQueue.runInSync(t -> t
                // Resets layer of divider bar to make sure it is always on top.
                .setLayer(dividerLeash, Integer.MAX_VALUE)
                .setPosition(dividerLeash,
                        mSplitLayout.getDividerBounds().left, mSplitLayout.getDividerBounds().top)
                .setPosition(mMainStage.mRootLeash,
                        mainStageBounds.left, mainStageBounds.top)
                .setPosition(mSideStage.mRootLeash,
                        sideStageBounds.left, sideStageBounds.top)
                // Resets crop to apply new surface bounds directly.
                .setWindowCrop(mMainStage.mRootLeash, null)
                .setWindowCrop(mSideStage.mRootLeash, null));
    }

    @Override
    public void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaInfo = displayAreaInfo;
        if (mSplitLayout == null) {
            mSplitLayout = new SplitLayout(TAG + "SplitDivider", mContext,
                    mDisplayAreaInfo.configuration, this,
                    b -> mRootTDAOrganizer.attachToDisplayArea(mDisplayId, b));
        }
    }

    @Override
    public void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
        throw new IllegalStateException("Well that was unexpected...");
    }

    @Override
    public void onDisplayAreaInfoChanged(DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaInfo = displayAreaInfo;
        if (mSplitLayout != null
                && mSplitLayout.updateConfiguration(mDisplayAreaInfo.configuration)) {
            onBoundsChanged(mSplitLayout);
        }
    }

    private Rect getSideStageBounds() {
        return mSideStagePosition == SIDE_STAGE_POSITION_TOP_OR_LEFT
                ? mSplitLayout.getBounds1() : mSplitLayout.getBounds2();
    }

    private Rect getMainStageBounds() {
        return mSideStagePosition == SIDE_STAGE_POSITION_TOP_OR_LEFT
                ? mSplitLayout.getBounds2() : mSplitLayout.getBounds1();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + TAG + " mDisplayId=" + mDisplayId);
        pw.println(innerPrefix + "mDividerVisible=" + mDividerVisible);
        pw.println(innerPrefix + "MainStage");
        pw.println(childPrefix + "isActive=" + mMainStage.isActive());
        mMainStageListener.dump(pw, childPrefix);
        pw.println(innerPrefix + "SideStage");
        mSideStageListener.dump(pw, childPrefix);
        pw.println(innerPrefix + "mSplitLayout=" + mSplitLayout);
    }

    class StageListenerImpl implements StageTaskListener.StageListenerCallbacks {
        boolean mHasRootTask = false;
        boolean mVisible = false;
        boolean mHasChildren = false;

        @Override
        public void onRootTaskAppeared() {
            mHasRootTask = true;
        }

        @Override
        public void onStatusChanged(boolean visible, boolean hasChildren) {
            if (!mHasRootTask) return;

            if (mHasChildren != hasChildren) {
                mHasChildren = hasChildren;
                StageCoordinator.this.onStageHasChildrenChanged(this);
            }
            if (mVisible != visible) {
                mVisible = visible;
                StageCoordinator.this.onStageVisibilityChanged(this);
            }
        }

        @Override
        public void onRootTaskVanished() {
            reset();
            StageCoordinator.this.onStageRootTaskVanished(this);
        }

        private void reset() {
            mHasRootTask = false;
            mVisible = false;
            mHasChildren = false;
        }

        public void dump(@NonNull PrintWriter pw, String prefix) {
            pw.println(prefix + "mHasRootTask=" + mHasRootTask);
            pw.println(prefix + "mVisible=" + mVisible);
            pw.println(prefix + "mHasChildren=" + mHasChildren);
        }
    }
}
