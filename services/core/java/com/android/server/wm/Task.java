/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static com.android.server.EventLogTags.WM_TASK_REMOVED;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.RESIZE_TASK;

import android.app.ActivityManager.StackId;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.server.EventLogTags;

import java.io.PrintWriter;

class Task extends WindowContainer<AppWindowToken> implements DimLayer.DimLayerUser {
    static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_WM;
    // Return value from {@link setBounds} indicating no change was made to the Task bounds.
    private static final int BOUNDS_CHANGE_NONE = 0;
    // Return value from {@link setBounds} indicating the position of the Task bounds changed.
    private static final int BOUNDS_CHANGE_POSITION = 1;
    // Return value from {@link setBounds} indicating the size of the Task bounds changed.
    private static final int BOUNDS_CHANGE_SIZE = 1 << 1;

    // TODO: Track parent marks like this in WindowContainer.
    TaskStack mStack;
    final int mTaskId;
    final int mUserId;
    private boolean mDeferRemoval = false;
    final WindowManagerService mService;

    // Content limits relative to the DisplayContent this sits in.
    private Rect mBounds = new Rect();
    final Rect mPreparedFrozenBounds = new Rect();
    final Configuration mPreparedFrozenMergedConfig = new Configuration();

    // Bounds used to calculate the insets.
    private final Rect mTempInsetBounds = new Rect();

    // Device rotation as of the last time {@link #mBounds} was set.
    private int mRotation;

    // Whether mBounds is fullscreen
    private boolean mFillsParent = true;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    private int mResizeMode;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;
    private int mDragResizeMode;

    private boolean mHomeTask;

    // Whether this task is an on-top launcher task, which is determined by the root activity.
    private boolean mIsOnTopLauncher;

    Task(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
            Configuration overrideConfig, boolean isOnTopLauncher, int resizeMode, boolean homeTask,
            TaskWindowContainerController controller) {
        mTaskId = taskId;
        mStack = stack;
        mUserId = userId;
        mService = service;
        mIsOnTopLauncher = isOnTopLauncher;
        mResizeMode = resizeMode;
        mHomeTask = homeTask;
        setController(controller);
        setBounds(bounds, overrideConfig);
    }

    DisplayContent getDisplayContent() {
        return mStack.getDisplayContent();
    }

    @Override
    void addChild(AppWindowToken wtoken, int addPos) {
        final int lastPos = mChildren.size();
        if (addPos >= lastPos) {
            addPos = lastPos;
        } else {
            for (int pos = 0; pos < lastPos && pos < addPos; ++pos) {
                if (mChildren.get(pos).removed) {
                    // addPos assumes removed tokens are actually gone.
                    ++addPos;
                }
            }
        }

        final WindowContainer parent = wtoken.getParent();
        if (parent != null) {
            parent.removeChild(wtoken);
        }
        super.addChild(wtoken, addPos);
        wtoken.mTask = this;
        mDeferRemoval = false;
    }

    private boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).hasWindowsAlive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    void removeIfPossible() {
        if (hasWindowsAlive() && mStack.isAnimating()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            mDeferRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeTask");
        mDeferRemoval = false;

        // Make sure to remove dim layer user first before removing task its from parent.
        DisplayContent content = getDisplayContent();
        if (content != null) {
            content.mDimLayerController.removeDimLayerUser(this);
        }

        super.removeImmediately();
    }

    void reparent(TaskStack stack, int position) {
        if (stack == mStack) {
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from stack=" + mStack);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "reParentTask");
        getParent().removeChild(this);
        stack.addTask(this, position, showForAllUsers(), false /* moveParents */);
    }

    /** @see com.android.server.am.ActivityManagerService#positionTaskInStack(int, int, int). */
    void positionTaskInStack(TaskStack stack, int position, Rect bounds,
            Configuration overrideConfig) {
        if (mStack == null) {
            // There is an assumption that task already has a stack at this point, so lets make
            // sure we comply with it.
            throw new IllegalStateException("Trying to position task that has no parent.");
        }
        if (stack != mStack) {
            // Task is already attached to a different stack. First we need to remove it from there
            // and add to top of the target stack. We will move it proper position afterwards.
            if (DEBUG_STACK) Slog.i(TAG, "positionTaskInStack: removing taskId=" + mTaskId
                    + " from stack=" + mStack);
            EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "positionTaskInStack");
            mStack.removeChild(this);
            stack.addTask(this, position);
        } else {
            stack.positionChildAt(position, this, true /* includingParents */);
        }

        resizeLocked(bounds, overrideConfig, false /* force */);

        for (int activityNdx = mChildren.size() - 1; activityNdx >= 0; --activityNdx) {
            mChildren.get(activityNdx).notifyMovedInStack();
        }
    }

    @Override
    void onParentSet() {
        // Update task bounds if needed.
        updateDisplayInfo(getDisplayContent());

        if (StackId.windowsAreScaleable(mStack.mStackId)) {
            // We force windows out of SCALING_MODE_FREEZE so that we can continue to animate them
            // while a resize is pending.
            forceWindowsScaleable(true /* force */);
        } else {
            forceWindowsScaleable(false /* force */);
        }
    }

    @Override
    void removeChild(AppWindowToken token) {
        if (!mChildren.contains(token)) {
            Slog.e(TAG, "removeChild: token=" + this + " not found.");
            return;
        }

        super.removeChild(token);

        if (mChildren.isEmpty()) {
            EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeAppToken: last token");
            if (mDeferRemoval) {
                removeIfPossible();
            }
        }
        token.mTask = null;
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mChildren.size(); appTokenNdx++) {
            mChildren.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    private int setBounds(Rect bounds, Configuration overrideConfig) {
        if (overrideConfig == null) {
            overrideConfig = Configuration.EMPTY;
        }
        if (bounds == null && !Configuration.EMPTY.equals(overrideConfig)) {
            throw new IllegalArgumentException("null bounds but non empty configuration: "
                    + overrideConfig);
        }
        if (bounds != null && Configuration.EMPTY.equals(overrideConfig)) {
            throw new IllegalArgumentException("non null bounds, but empty configuration");
        }
        boolean oldFullscreen = mFillsParent;
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (displayContent != null) {
            displayContent.getLogicalDisplayRect(mTmpRect);
            rotation = displayContent.getDisplayInfo().rotation;
            mFillsParent = bounds == null;
            if (mFillsParent) {
                bounds = mTmpRect;
            }
        }

        if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return BOUNDS_CHANGE_NONE;
        }
        if (mBounds.equals(bounds) && oldFullscreen == mFillsParent && mRotation == rotation) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;
        if (mBounds.left != bounds.left || mBounds.top != bounds.top) {
            boundsChange |= BOUNDS_CHANGE_POSITION;
        }
        if (mBounds.width() != bounds.width() || mBounds.height() != bounds.height()) {
            boundsChange |= BOUNDS_CHANGE_SIZE;
        }

        mBounds.set(bounds);

        mRotation = rotation;
        if (displayContent != null) {
            displayContent.mDimLayerController.updateDimLayer(this);
        }
        onOverrideConfigurationChanged(mFillsParent ? Configuration.EMPTY : overrideConfig);
        return boundsChange;
    }

    /**
     * Sets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void setTempInsetBounds(Rect tempInsetBounds) {
        if (tempInsetBounds != null) {
            mTempInsetBounds.set(tempInsetBounds);
        } else {
            mTempInsetBounds.setEmpty();
        }
    }

    /**
     * Gets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void getTempInsetBounds(Rect out) {
        out.set(mTempInsetBounds);
    }

    void setResizeable(int resizeMode) {
        mResizeMode = resizeMode;
    }

    boolean isResizeable() {
        return ActivityInfo.isResizeableMode(mResizeMode) || mService.mForceResizableTasks;
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean isOnTopLauncher() {
        return mIsOnTopLauncher;
    }

    boolean cropWindowsToStackBounds() {
        return isResizeable();
    }

    boolean isHomeTask() {
        return mHomeTask;
    }

    boolean resizeLocked(Rect bounds, Configuration overrideConfig, boolean forced) {
        int boundsChanged = setBounds(bounds, overrideConfig);
        if (forced) {
            boundsChanged |= BOUNDS_CHANGE_SIZE;
        }
        if (boundsChanged == BOUNDS_CHANGE_NONE) {
            return false;
        }
        if ((boundsChanged & BOUNDS_CHANGE_SIZE) == BOUNDS_CHANGE_SIZE) {
            onResize();
        } else {
            onMovedByResize();
        }
        return true;
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link AppWindowToken#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(mBounds);
        mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    /**
     * Align the task to the adjusted bounds.
     *
     * @param adjustedBounds Adjusted bounds to which the task should be aligned.
     * @param tempInsetBounds Insets bounds for the task.
     * @param alignBottom True if the task's bottom should be aligned to the adjusted
     *                    bounds's bottom; false if the task's top should be aligned
     *                    the adjusted bounds's top.
     */
    void alignToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds, boolean alignBottom) {
        // Task override config might be empty, while display or stack override config isn't, so
        // we have to check merged override config here.
        if (!isResizeable() || Configuration.EMPTY.equals(getMergedOverrideConfiguration())) {
            return;
        }

        getBounds(mTmpRect2);
        if (alignBottom) {
            int offsetY = adjustedBounds.bottom - mTmpRect2.bottom;
            mTmpRect2.offset(0, offsetY);
        } else {
            mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
        }
        setTempInsetBounds(tempInsetBounds);
        resizeLocked(mTmpRect2, getOverrideConfiguration(), false /* forced */);
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        final DisplayContent displayContent = mStack.getDisplayContent();
        return mFillsParent
                || !StackId.isTaskResizeableByDockedStack(mStack.mStackId)
                || displayContent == null
                || displayContent.getDockedStackIgnoringVisibility() != null;
    }

    /** Original bounds of the task if applicable, otherwise fullscreen rect. */
    void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // No need to adjust the output bounds if fullscreen or the docked stack is visible
            // since it is already what we want to represent to the rest of the system.
            out.set(mBounds);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack is
        // not currently visible. Go ahead a represent it as fullscreen to the rest of the system.
        mStack.getDisplayContent().getLogicalDisplayRect(out);
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    boolean getMaxVisibleBounds(Rect out) {
        boolean foundTop = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (token.mIsExiting || token.clientHidden || token.hiddenRequested) {
                continue;
            }
            final WindowState win = token.findMainWindow();
            if (win == null) {
                continue;
            }
            if (!foundTop) {
                out.set(win.mVisibleFrame);
                foundTop = true;
                continue;
            }
            if (win.mVisibleFrame.left < out.left) {
                out.left = win.mVisibleFrame.left;
            }
            if (win.mVisibleFrame.top < out.top) {
                out.top = win.mVisibleFrame.top;
            }
            if (win.mVisibleFrame.right > out.right) {
                out.right = win.mVisibleFrame.right;
            }
            if (win.mVisibleFrame.bottom > out.bottom) {
                out.bottom = win.mVisibleFrame.bottom;
            }
        }
        return foundTop;
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    @Override
    public void getDimBounds(Rect out) {
        final DisplayContent displayContent = mStack.getDisplayContent();
        // It doesn't matter if we in particular are part of the resize, since we couldn't have
        // a DimLayer anyway if we weren't visible.
        final boolean dockedResizing = displayContent != null
                && displayContent.mDividerControllerLocked.isResizing();
        if (useCurrentBounds()) {
            if (inFreeformWorkspace() && getMaxVisibleBounds(out)) {
                return;
            }

            if (!mFillsParent) {
                // When minimizing the docked stack when going home, we don't adjust the task bounds
                // so we need to intersect the task bounds with the stack bounds here.
                //
                // If we are Docked Resizing with snap points, the task bounds could be smaller than the stack
                // bounds and so we don't even want to use them. Even if the app should not be resized the Dim
                // should keep up with the divider.
                if (dockedResizing) {
                    mStack.getBounds(out);
                } else {
                    mStack.getBounds(mTmpRect);
                    mTmpRect.intersect(mBounds);
                }
                out.set(mTmpRect);
            } else {
                out.set(mBounds);
            }
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack is
        // not currently visible. Go ahead a represent it as fullscreen to the rest of the system.
        if (displayContent != null) {
            displayContent.getLogicalDisplayRect(out);
        }
    }

    void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (mDragResizing != dragResizing) {
            if (!DragResizeMode.isModeAllowedForStack(mStack.mStackId, dragResizeMode)) {
                throw new IllegalArgumentException("Drag resize mode not allow for stack stackId="
                        + mStack.mStackId + " dragResizeMode=" + dragResizeMode);
            }
            mDragResizing = dragResizing;
            mDragResizeMode = dragResizeMode;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    int getDragResizeMode() {
        return mDragResizeMode;
    }

    void updateDisplayInfo(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (mFillsParent) {
            setBounds(null, Configuration.EMPTY);
            return;
        }
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (mRotation == newRotation) {
            return;
        }

        // Device rotation changed.
        // - We don't want the task to move around on the screen when this happens, so update the
        //   task bounds so it stays in the same place.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        //   from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(mBounds);

        if (!StackId.isTaskResizeAllowed(mStack.mStackId)) {
            setBounds(mTmpRect2, getOverrideConfiguration());
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2, getOverrideConfiguration()) != BOUNDS_CHANGE_NONE) {
            // Post message to inform activity manager of the bounds change simulating a one-way
            // call. We do this to prevent a deadlock between window manager lock and activity
            // manager lock been held.
            mService.mH.obtainMessage(RESIZE_TASK, mTaskId,
                    RESIZE_MODE_SYSTEM_SCREEN_ROTATION, mBounds).sendToTarget();
        }
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).mAppAnimator.clearAnimation();
        }
    }

    /** Cancels any running thumbnail transitions associated with the task. */
    void cancelTaskThumbnailTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).mAppAnimator.clearThumbnail();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mChildren.size();
        return (tokensCount != 0) && mChildren.get(tokensCount - 1).mShowForAllUsers;
    }

    boolean inFreeformWorkspace() {
        return mStack != null && mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID;
    }

    boolean inPinnedWorkspace() {
        return mStack != null && mStack.mStackId == PINNED_STACK_ID;
    }

    boolean isFloating() {
        return StackId.tasksAreFloating(mStack.mStackId);
    }

    WindowState getTopVisibleAppMainWindow() {
        final AppWindowToken token = getTopVisibleAppToken();
        return token != null ? token.findMainWindow() : null;
    }

    AppWindowToken getTopVisibleAppToken() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (!token.mIsExiting && !token.clientHidden && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    @Override
    public boolean dimFullscreen() {
        return isFullscreen();
    }

    boolean isFullscreen() {
        if (useCurrentBounds()) {
            return mFillsParent;
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mStack.getDisplayContent().getDisplayInfo();
    }

    void forceWindowsScaleable(boolean force) {
        mService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                mChildren.get(i).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            mService.closeSurfaceTransaction();
        }
    }

    @Override
    boolean fillsParent() {
        return mFillsParent || !StackId.isTaskResizeAllowed(mStack.mStackId);
    }

    TaskWindowContainerController getController() {
        return (TaskWindowContainerController) super.getController();
    }

    @Override
    public String toString() {
        return "{taskId=" + mTaskId + " appTokens=" + mChildren + " mdr=" + mDeferRemoval + "}";
    }

    String getName() {
        return toShortString();
    }

    @Override
    public String toShortString() {
        return "Task=" + mTaskId;
    }

    public void dump(String prefix, PrintWriter pw) {
        final String doublePrefix = prefix + "  ";

        pw.println(prefix + "taskId=" + mTaskId);
        pw.println(doublePrefix + "mFillsParent=" + mFillsParent);
        pw.println(doublePrefix + "mBounds=" + mBounds.toShortString());
        pw.println(doublePrefix + "mdr=" + mDeferRemoval);
        pw.println(doublePrefix + "appTokens=" + mChildren);
        pw.println(doublePrefix + "mTempInsetBounds=" + mTempInsetBounds.toShortString());

        final String triplePrefix = doublePrefix + "  ";

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken wtoken = mChildren.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + wtoken);
            wtoken.dump(pw, triplePrefix);
        }

    }
}
