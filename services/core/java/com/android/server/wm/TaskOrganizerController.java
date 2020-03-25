/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_CONFIGS;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_WINDOW_CONFIGS;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.window.ITaskOrganizerController;
import android.window.ITaskOrganizer;
import android.window.IWindowContainer;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController extends ITaskOrganizerController.Stub {
    private static final String TAG = "TaskOrganizerController";

    /**
     * Masks specifying which configurations are important to report back to an organizer when
     * changed.
     */
    private static final int REPORT_CONFIGS = CONTROLLABLE_CONFIGS;
    private static final int REPORT_WINDOW_CONFIGS = CONTROLLABLE_WINDOW_CONFIGS;

    private final WindowManagerGlobalLock mGlobalLock;

    private class DeathRecipient implements IBinder.DeathRecipient {
        int mWindowingMode;
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer, int windowingMode) {
            mTaskOrganizer = organizer;
            mWindowingMode = windowingMode;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state =
                    mTaskOrganizerStates.get(mTaskOrganizer.asBinder());
                state.releaseTasks();
                mTaskOrganizerStates.remove(mTaskOrganizer.asBinder());
                if (mTaskOrganizersForWindowingMode.get(mWindowingMode) == mTaskOrganizer) {
                    mTaskOrganizersForWindowingMode.remove(mWindowingMode);
                }
            }
        }
    };

    class TaskOrganizerState {
        ITaskOrganizer mOrganizer;
        DeathRecipient mDeathRecipient;
        int mWindowingMode;

        ArrayList<Task> mOrganizedTasks = new ArrayList<>();

        // Save the TaskOrganizer which we replaced registration for
        // so it can be re-registered if we unregister.
        TaskOrganizerState mReplacementFor;
        boolean mDisposed = false;


        TaskOrganizerState(ITaskOrganizer organizer, int windowingMode,
                @Nullable TaskOrganizerState replacing) {
            mOrganizer = organizer;
            mDeathRecipient = new DeathRecipient(organizer, windowingMode);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskOrganizer failed to register death recipient");
            }
            mWindowingMode = windowingMode;
            mReplacementFor = replacing;
        }

        void addTask(Task t) {
            mOrganizedTasks.add(t);
            try {
                mOrganizer.onTaskAppeared(t.getTaskInfo());
            } catch (Exception e) {
                Slog.e(TAG, "Exception sending taskAppeared callback" + e);
            }
        }

        void removeTask(Task t) {
            try {
                mOrganizer.onTaskVanished(t.getTaskInfo());
            } catch (Exception e) {
                Slog.e(TAG, "Exception sending taskVanished callback" + e);
            }
            mOrganizedTasks.remove(t);
        }

        void dispose() {
            mDisposed = true;
            releaseTasks();
            handleReplacement();
        }

        void releaseTasks() {
            for (int i = mOrganizedTasks.size() - 1; i >= 0; i--) {
                final Task t = mOrganizedTasks.get(i);
                t.taskOrganizerDied();
                removeTask(t);
            }
        }

        void handleReplacement() {
            if (mReplacementFor != null && !mReplacementFor.mDisposed) {
                mTaskOrganizersForWindowingMode.put(mWindowingMode, mReplacementFor);
            }
        }

        void unlinkDeath() {
            mDisposed = true;
            mOrganizer.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    };


    final HashMap<Integer, TaskOrganizerState> mTaskOrganizersForWindowingMode = new HashMap();
    final HashMap<IBinder, TaskOrganizerState> mTaskOrganizerStates = new HashMap();

    private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
    private final ArrayList<Task> mPendingTaskInfoChanges = new ArrayList<>();

    final ActivityTaskManagerService mService;

    RunningTaskInfo mTmpTaskInfo;

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * but will continue to organize it's existing tasks.
     */
    @Override
    public void registerTaskOrganizer(ITaskOrganizer organizer, int windowingMode) {
        if (windowingMode != WINDOWING_MODE_PINNED
                && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                && windowingMode != WINDOWING_MODE_MULTI_WINDOW) {
            throw new UnsupportedOperationException("As of now only Pinned/Split/Multiwindow"
                    + " windowing modes are supported for registerTaskOrganizer");
        }
        enforceStackPermission("registerTaskOrganizer()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (getTaskOrganizer(windowingMode) != null) {
                    Slog.w(TAG, "Task organizer already exists for windowing mode: "
                            + windowingMode);
                }
                final TaskOrganizerState previousState =
                        mTaskOrganizersForWindowingMode.get(windowingMode);
                final TaskOrganizerState state = new TaskOrganizerState(organizer, windowingMode,
                        previousState);
                mTaskOrganizersForWindowingMode.put(windowingMode, state);
                mTaskOrganizerStates.put(organizer.asBinder(), state);

                if (previousState == null) {
                    // Only in the case where this is the root task organizer for the given
                    // windowing mode, we add report all existing tasks in that mode to the new
                    // task organizer.
                    mService.mRootWindowContainer.forAllTasks((task) -> {
                        if (task.getWindowingMode() == windowingMode) {
                            task.updateTaskOrganizerState(true /* forceUpdate */);
                        }
                    });
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterTaskOrganizer(ITaskOrganizer organizer) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        state.unlinkDeath();
        if (mTaskOrganizersForWindowingMode.get(state.mWindowingMode) == state) {
            mTaskOrganizersForWindowingMode.remove(state.mWindowingMode);
        }
        state.dispose();
    }

    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        final TaskOrganizerState state = mTaskOrganizersForWindowingMode.get(windowingMode);
        if (state == null) {
            return null;
        }
        return state.mOrganizer;
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        state.addTask(task);
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        state.removeTask(task);
    }

    @Override
    public RunningTaskInfo createRootTask(int displayId, int windowingMode) {
        enforceStackPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    return null;
                }
                final int nextId = display.getNextStackId();
                TaskTile tile = new TaskTile(mService, nextId, windowingMode);
                display.addTile(tile);
                RunningTaskInfo out = tile.getTaskInfo();
                mLastSentTaskInfos.put(tile, out);
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean deleteRootTask(IWindowContainer token) {
        enforceStackPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                TaskTile tile = TaskTile.forToken(token.asBinder());
                if (tile == null) {
                    return false;
                }
                tile.removeImmediately();
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dispatchPendingTaskInfoChanges() {
        if (mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            return;
        }
        for (int i = 0, n = mPendingTaskInfoChanges.size(); i < n; ++i) {
            dispatchTaskInfoChanged(mPendingTaskInfoChanges.get(i), false /* force */);
        }
        mPendingTaskInfoChanges.clear();
    }

    void dispatchTaskInfoChanged(Task task, boolean force) {
        if (!force && mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            // Defer task info reporting while layout is deferred. This is because layout defer
            // blocks tend to do lots of re-ordering which can mess up animations in receivers.
            mPendingTaskInfoChanges.remove(task);
            mPendingTaskInfoChanges.add(task);
            return;
        }
        RunningTaskInfo lastInfo = mLastSentTaskInfos.get(task);
        if (mTmpTaskInfo == null) {
            mTmpTaskInfo = new RunningTaskInfo();
        }
        mTmpTaskInfo.configuration.unset();
        task.fillTaskInfo(mTmpTaskInfo);
        boolean changed = lastInfo == null
                || mTmpTaskInfo.topActivityType != lastInfo.topActivityType
                || mTmpTaskInfo.isResizable() != lastInfo.isResizable()
                || mTmpTaskInfo.pictureInPictureParams != lastInfo.pictureInPictureParams;
        if (!changed) {
            int cfgChanges = mTmpTaskInfo.configuration.diff(lastInfo.configuration);
            final int winCfgChanges = (cfgChanges & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                    ? (int) mTmpTaskInfo.configuration.windowConfiguration.diff(
                            lastInfo.configuration.windowConfiguration,
                            true /* compareUndefined */) : 0;
            if ((winCfgChanges & REPORT_WINDOW_CONFIGS) == 0) {
                cfgChanges &= ~ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
            }
            changed = (cfgChanges & REPORT_CONFIGS) != 0;
        }
        if (!(changed || force)) {
            return;
        }
        final RunningTaskInfo newInfo = mTmpTaskInfo;
        mLastSentTaskInfos.put(task, mTmpTaskInfo);
        // Since we've stored this, clean up the reference so a new one will be created next time.
        // Transferring it this way means we only have to construct new RunningTaskInfos when they
        // change.
        mTmpTaskInfo = null;

        if (task.mTaskOrganizer != null) {
            try {
                task.mTaskOrganizer.onTaskInfoChanged(newInfo);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public IWindowContainer getImeTarget(int displayId) {
        enforceStackPermission("getImeTarget()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent dc = mService.mWindowManager.mRoot
                        .getDisplayContent(displayId);
                if (dc == null || dc.mInputMethodTarget == null) {
                    return null;
                }
                // Avoid WindowState#getRootTask() so we don't attribute system windows to a task.
                final Task task = dc.mInputMethodTarget.getTask();
                if (task == null) {
                    return null;
                }
                ActivityStack rootTask = (ActivityStack) task.getRootTask();
                final TaskTile tile = rootTask.getTile();
                if (tile != null) {
                    rootTask = tile;
                }
                return rootTask.mRemoteToken;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setLaunchRoot(int displayId, @Nullable IWindowContainer tile) {
        enforceStackPermission("setLaunchRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    return;
                }
                TaskTile taskTile = tile == null ? null : TaskTile.forToken(tile.asBinder());
                if (taskTile == null) {
                    display.mLaunchTile = null;
                    return;
                }
                if (taskTile.getDisplay() != display) {
                    throw new RuntimeException("Can't set launch root for display " + displayId
                            + " to task on display " + taskTile.getDisplay().getDisplayId());
                }
                display.mLaunchTile = taskTile;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<RunningTaskInfo> getChildTasks(IWindowContainer parent,
            @Nullable int[] activityTypes) {
        enforceStackPermission("getChildTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (parent == null) {
                    throw new IllegalArgumentException("Can't get children of null parent");
                }
                final WindowContainer container = WindowContainer.fromBinder(parent.asBinder());
                if (container == null) {
                    Slog.e(TAG, "Can't get children of " + parent + " because it is not valid.");
                    return null;
                }
                // For now, only support returning children of persistent root tasks (of which the
                // only current implementation is TaskTile).
                if (!(container instanceof TaskTile)) {
                    Slog.w(TAG, "Can only get children of root tasks created via createRootTask");
                    return null;
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                // Tiles aren't real parents, so we need to go through stacks on the display to
                // ensure correct ordering.
                final DisplayContent dc = container.getDisplayContent();
                for (int i = dc.getStackCount() - 1; i >= 0; --i) {
                    final ActivityStack as = dc.getStackAt(i);
                    if (as.getTile() == container) {
                        if (activityTypes != null
                                && !ArrayUtils.contains(activityTypes, as.getActivityType())) {
                            continue;
                        }
                        out.add(as.getTaskInfo());
                    }
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RunningTaskInfo> getRootTasks(int displayId, @Nullable int[] activityTypes) {
        enforceStackPermission("getRootTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Display " + displayId + " doesn't exist");
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int i = dc.getStackCount() - 1; i >= 0; --i) {
                    final ActivityStack task = dc.getStackAt(i);
                    if (task.getTile() != null) {
                        // a tile is supposed to look like a parent, so don't include their
                        // "children" here. They can be accessed via getChildTasks()
                        continue;
                    }
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, task.getActivityType())) {
                        continue;
                    }
                    out.add(task.getTaskInfo());
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
