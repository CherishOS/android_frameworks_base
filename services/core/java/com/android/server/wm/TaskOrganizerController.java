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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_CONFIGS;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_WINDOW_CONFIGS;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.TaskDescription;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;

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

    // The set of modes that are currently supports
    // TODO: Remove once the task organizer can support all modes
    @VisibleForTesting
    static final int[] UNSUPPORTED_WINDOWING_MODES = {
            WINDOWING_MODE_UNDEFINED,
            WINDOWING_MODE_FREEFORM
    };

    private class DeathRecipient implements IBinder.DeathRecipient {
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer) {
            mTaskOrganizer = organizer;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.remove(
                        mTaskOrganizer.asBinder());
                if (state != null) {
                    state.dispose();
                }
            }
        }
    }

    /**
     * A wrapper class around ITaskOrganizer to ensure that the calls are made in the right
     * lifecycle order since we may be updating the visibility of task surface controls in a pending
     * transaction before they are presented to the task org.
     */
    private class TaskOrganizerCallbacks {
        final ITaskOrganizer mTaskOrganizer;
        final Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

        TaskOrganizerCallbacks(ITaskOrganizer taskOrg,
                Consumer<Runnable> deferTaskOrgCallbacksConsumer) {
            mDeferTaskOrgCallbacksConsumer = deferTaskOrgCallbacksConsumer;
            mTaskOrganizer = taskOrg;
        }

        IBinder getBinder() {
            return mTaskOrganizer.asBinder();
        }

        SurfaceControl prepareLeash(Task task, boolean visible, String reason) {
            SurfaceControl outSurfaceControl = new SurfaceControl(task.getSurfaceControl(), reason);
            if (!task.mCreatedByOrganizer && !visible) {
                // To prevent flashes, we hide the task prior to sending the leash to the
                // task org if the task has previously hidden (ie. when entering PIP)
                mTransaction.hide(outSurfaceControl);
                mTransaction.apply();
            }
            return outSurfaceControl;
        }

        void onTaskAppeared(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task appeared taskId=%d", task.mTaskId);
            final boolean visible = task.isVisible();
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                try {
                    mTaskOrganizer.onTaskAppeared(taskInfo, prepareLeash(task, visible,
                            "TaskOrganizerController.onTaskAppeared"));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskAppeared callback", e);
                }
            });
        }


        void onTaskVanished(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task vanished taskId=%d", task.mTaskId);
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                try {
                    mTaskOrganizer.onTaskVanished(taskInfo);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskVanished callback", e);
                }
            });
        }

        void onTaskInfoChanged(Task task, ActivityManager.RunningTaskInfo taskInfo) {
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task info changed taskId=%d", task.mTaskId);
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                if (!task.isOrganized()) {
                    // This is safe to ignore if the task is no longer organized
                    return;
                }
                try {
                    // Purposely notify of task info change immediately instead of deferring (like
                    // appear and vanish) to allow info changes (such as new PIP params) to flow
                    // without waiting.
                    mTaskOrganizer.onTaskInfoChanged(taskInfo);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception sending onTaskInfoChanged callback", e);
                }
            });
        }

        void onBackPressedOnTaskRoot(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task back pressed on root taskId=%d",
                    task.mTaskId);
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            mDeferTaskOrgCallbacksConsumer.accept(() -> {
                if (!task.isOrganized()) {
                    // This is safe to ignore if the task is no longer organized
                    return;
                }
                try {
                    mTaskOrganizer.onBackPressedOnTaskRoot(task.getTaskInfo());
                } catch (Exception e) {
                    Slog.e(TAG, "Exception sending onBackPressedOnTaskRoot callback", e);
                }
            });
        }
    }

    private class TaskOrganizerState {
        private final TaskOrganizerCallbacks mOrganizer;
        private final DeathRecipient mDeathRecipient;
        private final ArrayList<Task> mOrganizedTasks = new ArrayList<>();
        private final int mUid;

        TaskOrganizerState(ITaskOrganizer organizer, int uid) {
            final Consumer<Runnable> deferTaskOrgCallbacksConsumer =
                    mDeferTaskOrgCallbacksConsumer != null
                            ? mDeferTaskOrgCallbacksConsumer
                            : mService.mWindowManager.mAnimator::addAfterPrepareSurfacesRunnable;
            mOrganizer = new TaskOrganizerCallbacks(organizer, deferTaskOrgCallbacksConsumer);
            mDeathRecipient = new DeathRecipient(organizer);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskOrganizer failed to register death recipient");
            }
            mUid = uid;
        }

        /**
         * Register this task with this state, but doesn't trigger the task appeared callback to
         * the organizer.
         */
        SurfaceControl addTaskWithoutCallback(Task t, String reason) {
            t.mTaskAppearedSent = true;
            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }
            return mOrganizer.prepareLeash(t, t.isVisible(), reason);
        }

        void addTask(Task t) {
            if (t.mTaskAppearedSent) return;

            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }
            if (t.taskAppearedReady()) {
                t.mTaskAppearedSent = true;
                mOrganizer.onTaskAppeared(t);
            }
        }

        void removeTask(Task t) {
            if (t.mTaskAppearedSent) {
                t.migrateToNewSurfaceControl();
                t.mTaskAppearedSent = false;
                mOrganizer.onTaskVanished(t);
            }
            mOrganizedTasks.remove(t);
            mInterceptBackPressedOnRootTasks.remove(t.mTaskId);
        }

        void dispose() {
            // Move organizer from managing specific windowing modes
            mTaskOrganizers.remove(mOrganizer.mTaskOrganizer);

            // Update tasks currently managed by this organizer to the next one available if
            // possible.
            while (!mOrganizedTasks.isEmpty()) {
                final Task t = mOrganizedTasks.get(0);
                t.updateTaskOrganizerState(true /* forceUpdate */);
                if (mOrganizedTasks.contains(t)) {
                    removeTask(t);
                }
            }

            // Remove organizer state after removing tasks so we get a chance to send
            // onTaskVanished.
            mTaskOrganizerStates.remove(asBinder());
        }

        void unlinkDeath() {
            mOrganizer.getBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    }

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;

    // List of task organizers by priority
    private final LinkedList<ITaskOrganizer> mTaskOrganizers = new LinkedList<>();
    private final HashMap<IBinder, TaskOrganizerState> mTaskOrganizerStates = new HashMap<>();
    private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
    private final ArrayList<Task> mPendingTaskInfoChanges = new ArrayList<>();
    // Set of organized tasks (by taskId) that dispatch back pressed to their organizers
    private final HashSet<Integer> mInterceptBackPressedOnRootTasks = new HashSet();

    private SurfaceControl.Transaction mTransaction;
    private RunningTaskInfo mTmpTaskInfo;
    private Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceTaskPermission(String func) {
        mService.enforceTaskPermission(func);
    }

    /**
     * Specifies the consumer to run to defer the task org callbacks. Can be overridden while
     * testing to allow the callbacks to be sent synchronously.
     */
    @VisibleForTesting
    public void setDeferTaskOrgCallbacksConsumer(Consumer<Runnable> consumer) {
        mDeferTaskOrgCallbacksConsumer = consumer;
    }
    /**
     * Register a TaskOrganizer to manage tasks as they enter the a supported windowing mode.
     */
    @Override
    public ParceledListSlice<TaskAppearedInfo> registerTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("registerTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Register task organizer=%s uid=%d",
                        organizer.asBinder(), uid);

                // Defer initializing the transaction since the transaction factory can be set up
                // by the tests after construction of the controller
                if (mTransaction == null) {
                    mTransaction = mService.mWindowManager.mTransactionFactory.get();
                }

                if (!mTaskOrganizerStates.containsKey(organizer.asBinder())) {
                    mTaskOrganizers.add(organizer);
                    mTaskOrganizerStates.put(organizer.asBinder(),
                            new TaskOrganizerState(organizer, uid));
                }

                final ArrayList<TaskAppearedInfo> taskInfos = new ArrayList<>();
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                mService.mRootWindowContainer.forAllTasks((task) -> {
                    if (ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, task.getWindowingMode())) {
                        return;
                    }

                    boolean returnTask = !task.mCreatedByOrganizer;
                    task.updateTaskOrganizerState(true /* forceUpdate */,
                            returnTask /* skipTaskAppeared */);
                    if (returnTask) {
                        SurfaceControl outSurfaceControl = state.addTaskWithoutCallback(task,
                                "TaskOrganizerController.registerTaskOrganizer");
                        taskInfos.add(new TaskAppearedInfo(task.getTaskInfo(), outSurfaceControl));
                    }
                });
                return new ParceledListSlice<>(taskInfos);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("unregisterTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                if (state == null) {
                    return;
                }
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Unregister task organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                state.unlinkDeath();
                state.dispose();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * @return the task organizer key for a given windowing mode.
     */
    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        return isSupportedWindowingMode(windowingMode)
                ? mTaskOrganizers.peekLast()
                : null;
    }

    boolean isSupportedWindowingMode(int winMode) {
        return !ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, winMode);
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        state.addTask(task);
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null) {
            state.removeTask(task);
        }
    }

    @Override
    public void createRootTask(int displayId, int windowingMode, @Nullable IBinder launchCookie) {
        enforceTaskPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    ProtoLog.e(WM_DEBUG_WINDOW_ORGANIZER,
                            "createRootTask unknown displayId=%d", displayId);
                    return;
                }

                createRootTask(display, windowingMode, launchCookie);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @VisibleForTesting
    Task createRootTask(DisplayContent display, int windowingMode, @Nullable IBinder launchCookie) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Create root task displayId=%d winMode=%d",
                display.mDisplayId, windowingMode);
        // We want to defer the task appear signal until the task is fully created and attached to
        // to the hierarchy so that the complete starting configuration is in the task info we send
        // over to the organizer.
        final Task task = display.getDefaultTaskDisplayArea().createStack(windowingMode,
                ACTIVITY_TYPE_UNDEFINED, false /* onTop */, null /* info */, new Intent(),
                true /* createdByOrganizer */, true /* deferTaskAppear */, launchCookie);
        task.setDeferTaskAppear(false /* deferTaskAppear */);
        return task;
    }

    @Override
    public boolean deleteRootTask(WindowContainerToken token) {
        enforceTaskPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = WindowContainer.fromBinder(token.asBinder()).asTask();
                if (task == null) return false;
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException(
                            "Attempt to delete task not created by organizer task=" + task);
                }

                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Delete root task display=%d winMode=%d",
                        task.getDisplayId(), task.getWindowingMode());
                task.removeImmediately();
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
                || mTmpTaskInfo.isResizeable != lastInfo.isResizeable
                || !Objects.equals(
                        mTmpTaskInfo.letterboxActivityBounds,
                        lastInfo.letterboxActivityBounds)
                || !Objects.equals(
                        mTmpTaskInfo.positionInParent,
                        lastInfo.positionInParent)
                || mTmpTaskInfo.pictureInPictureParams != lastInfo.pictureInPictureParams
                || mTmpTaskInfo.getWindowingMode() != lastInfo.getWindowingMode()
                || !TaskDescription.equals(mTmpTaskInfo.taskDescription, lastInfo.taskDescription);
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

        if (task.isOrganized()) {
            // Because we defer sending taskAppeared() until the app has drawn, we may receive a
            // configuration change before the state actually has the task registered. As such we
            // should ignore these change events to the organizer until taskAppeared(). If the task
            // was created by the organizer, then we always send the info change.
            final TaskOrganizerState state = mTaskOrganizerStates.get(
                    task.mTaskOrganizer.asBinder());
            if (state != null) {
                state.mOrganizer.onTaskInfoChanged(task, newInfo);
            }
        }
    }

    @Override
    public WindowContainerToken getImeTarget(int displayId) {
        enforceTaskPermission("getImeTarget()");
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
                return task.getRootTask().mRemoteToken.toWindowContainerToken();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setLaunchRoot(int displayId, @Nullable WindowContainerToken token) {
        enforceTaskPermission("setLaunchRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                TaskDisplayArea defaultTaskDisplayArea = mService.mRootWindowContainer
                        .getDisplayContent(displayId).getDefaultTaskDisplayArea();
                if (defaultTaskDisplayArea == null) {
                    return;
                }
                Task task = token == null
                        ? null : WindowContainer.fromBinder(token.asBinder()).asTask();
                if (task == null) {
                    defaultTaskDisplayArea.mLaunchRootTask = null;
                    return;
                }
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException("Attempt to set task not created by "
                            + "organizer as launch root task=" + task);
                }
                if (task.getDisplayArea() == null
                        || task.getDisplayArea().getDisplayId() != displayId) {
                    throw new RuntimeException("Can't set launch root for display " + displayId
                            + " to task on display " + task.getDisplayContent().getDisplayId());
                }
                task.getDisplayArea().mLaunchRootTask = task;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<RunningTaskInfo> getChildTasks(WindowContainerToken parent,
            @Nullable int[] activityTypes) {
        enforceTaskPermission("getChildTasks()");
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
                final Task task = container.asTask();
                if (task == null) {
                    Slog.e(TAG, container + " is not a task...");
                    return null;
                }
                // For now, only support returning children of tasks created by the organizer.
                if (!task.mCreatedByOrganizer) {
                    Slog.w(TAG, "Can only get children of root tasks created via createRootTask");
                    return null;
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int i = task.getChildCount() - 1; i >= 0; --i) {
                    final Task child = task.getChildAt(i).asTask();
                    if (child == null) continue;
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, child.getActivityType())) {
                        continue;
                    }
                    out.add(child.getTaskInfo());
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RunningTaskInfo> getRootTasks(int displayId, @Nullable int[] activityTypes) {
        enforceTaskPermission("getRootTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Display " + displayId + " doesn't exist");
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                dc.forAllTaskDisplayAreas(taskDisplayArea -> {
                    for (int sNdx = taskDisplayArea.getStackCount() - 1; sNdx >= 0; --sNdx) {
                        final Task task = taskDisplayArea.getStackAt(sNdx);
                        if (activityTypes != null
                                && !ArrayUtils.contains(activityTypes, task.getActivityType())) {
                            continue;
                        }
                        out.add(task.getTaskInfo());
                    }
                });
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setInterceptBackPressedOnTaskRoot(WindowContainerToken token,
            boolean interceptBackPressed) {
        enforceTaskPermission("setInterceptBackPressedOnTaskRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Set intercept back pressed on root=%b",
                        interceptBackPressed);
                final Task task = WindowContainer.fromBinder(token.asBinder()).asTask();
                if (task == null) {
                    Slog.w(TAG, "Could not resolve task from token");
                    return;
                }
                if (interceptBackPressed) {
                    mInterceptBackPressedOnRootTasks.add(task.mTaskId);
                } else {
                    mInterceptBackPressedOnRootTasks.remove(task.mTaskId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean handleInterceptBackPressedOnTaskRoot(Task task) {
        if (task == null || !task.isOrganized()
                || !mInterceptBackPressedOnRootTasks.contains(task.mTaskId)) {
            return false;
        }

        final TaskOrganizerState state = mTaskOrganizerStates.get(task.mTaskOrganizer.asBinder());
        state.mOrganizer.onBackPressedOnTaskRoot(task);
        return true;
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println("TaskOrganizerController:");
        for (final TaskOrganizerState state : mTaskOrganizerStates.values()) {
            final ArrayList<Task> tasks = state.mOrganizedTasks;
            pw.print(innerPrefix + "  ");
            pw.println(state.mOrganizer.mTaskOrganizer + " uid=" + state.mUid + ":");
            for (int k = 0; k < tasks.size(); k++) {
                final Task task = tasks.get(k);
                final int mode = task.getWindowingMode();
                if (ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, mode)) {
                    continue;
                }
                pw.println(innerPrefix + "    ("
                        + WindowConfiguration.windowingModeToString(mode) + ") " + task);
            }

        }
        pw.println();
    }
}
