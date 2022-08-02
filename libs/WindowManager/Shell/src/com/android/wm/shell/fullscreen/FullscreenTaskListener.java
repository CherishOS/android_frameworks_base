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

package com.android.wm.shell.fullscreen;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Point;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.Optional;

/**
  * Organizes tasks presented in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN}.
  */
public class FullscreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskListener";

    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Optional<RecentTasksController> mRecentTasksOptional;

    private final SparseArray<TaskData> mDataByTaskId = new SparseArray<>();

    /**
     * This constructor is used by downstream products.
     */
    public FullscreenTaskListener(SyncTransactionQueue syncQueue) {
        this(null /* shellInit */, null /* shellTaskOrganizer */, syncQueue, Optional.empty());
    }

    public FullscreenTaskListener(ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            Optional<RecentTasksController> recentTasksOptional) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mRecentTasksOptional = recentTasksOptional;
        // Note: Some derivative FullscreenTaskListener implementations do not use ShellInit
        if (shellInit != null) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mShellTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_FULLSCREEN);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mDataByTaskId.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        mDataByTaskId.put(taskInfo.taskId, new TaskData(leash, positionInParent));

        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        mSyncQueue.runInSync(t -> {
            // Reset several properties back to fullscreen (PiP, for example, leaves all these
            // properties in a bad state).
            t.setWindowCrop(leash, null);
            t.setPosition(leash, positionInParent.x, positionInParent.y);
            t.setAlpha(leash, 1f);
            t.setMatrix(leash, 1, 0, 0, 1);
            t.show(leash);
        });

        updateRecentsForVisibleFullscreenTask(taskInfo);
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;

        updateRecentsForVisibleFullscreenTask(taskInfo);

        final TaskData data = mDataByTaskId.get(taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        if (!positionInParent.equals(data.positionInParent)) {
            data.positionInParent.set(positionInParent.x, positionInParent.y);
            mSyncQueue.runInSync(t -> {
                t.setPosition(data.surface, positionInParent.x, positionInParent.y);
            });
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        if (mDataByTaskId.get(taskInfo.taskId) == null) {
            Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
            return;
        }

        mDataByTaskId.remove(taskInfo.taskId);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                taskInfo.taskId);
    }

    private void updateRecentsForVisibleFullscreenTask(RunningTaskInfo taskInfo) {
        mRecentTasksOptional.ifPresent(recentTasks -> {
            if (taskInfo.isVisible) {
                // Remove any persisted splits if either tasks are now made fullscreen and visible
                recentTasks.removeSplitPair(taskInfo.taskId);
            }
        });
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (!mDataByTaskId.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mDataByTaskId.get(taskId).surface;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mDataByTaskId.size() + " Tasks");
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_FULLSCREEN);
    }

    /**
     * Per-task data for each managed task.
     */
    private static class TaskData {
        public final SurfaceControl surface;
        public final Point positionInParent;

        public TaskData(SurfaceControl surface, Point positionInParent) {
            this.surface = surface;
            this.positionInParent = positionInParent;
        }
    }
}
