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

package com.android.wm.shell;

import android.app.ActivityManager;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

class FullscreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskOrg";

    private final TransactionPool mTransactionPool;

    private final ArraySet<Integer> mTasks = new ArraySet<>();

    FullscreenTaskListener(TransactionPool transactionPool) {
        mTransactionPool = transactionPool;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (mTasks) {
            if (mTasks.contains(taskInfo.taskId)) {
                throw new RuntimeException("Task appeared more than once: #" + taskInfo.taskId);
            }
            mTasks.add(taskInfo.taskId);
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                    taskInfo.taskId);
            t.show(leash);
            t.apply();
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        synchronized (mTasks) {
            if (!mTasks.remove(taskInfo.taskId)) {
                Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
                return;
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                    taskInfo.taskId);
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
    }
}
