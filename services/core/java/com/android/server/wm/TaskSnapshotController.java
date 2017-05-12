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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.ENABLE_TASK_SNAPSHOTS;
import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_NEVER;
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_RARELY;
import static android.graphics.PixelFormat.RGBA_8888;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.StackId;
import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Canvas;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Environment;
import android.util.ArraySet;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy.StartingSurface;

import com.google.android.collect.Sets;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.TaskSnapshotSurface.SystemBarBackgroundPainter;

import java.io.PrintWriter;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) of the corresponding task and
 * put it into our cache. Internally we use gralloc buffers to be able to draw them wherever we
 * like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of a task, and draw
 * them in their own process.
 * <p>
 * When we task becomes visible again, we show a starting window with the snapshot as the content to
 * make app transitions more responsive.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class TaskSnapshotController {

    /**
     * Return value for {@link #getSnapshotMode}: We are allowed to take a real screenshot to be
     * used as the snapshot.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_REAL = 0;

    /**
     * Return value for {@link #getSnapshotMode}: We are not allowed to take a real screenshot but
     * we should try to use the app theme to create a dummy representation of the app.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_APP_THEME = 1;

    /**
     * Return value for {@link #getSnapshotMode}: We aren't allowed to take any snapshot.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_NONE = 2;

    private final WindowManagerService mService;

    private final TaskSnapshotCache mCache;
    private final TaskSnapshotPersister mPersister = new TaskSnapshotPersister(
            Environment::getDataSystemCeDirectory);
    private final TaskSnapshotLoader mLoader = new TaskSnapshotLoader(mPersister);
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();

    TaskSnapshotController(WindowManagerService service) {
        mService = service;
        mCache = new TaskSnapshotCache(mService, mLoader);
    }

    void systemReady() {
        mPersister.start();
    }

    void onTransitionStarting() {
        handleClosingApps(mService.mClosingApps);
    }

    /**
     * Called when the visibility of an app changes outside of the regular app transition flow.
     */
    void notifyAppVisibilityChanged(AppWindowToken appWindowToken, boolean visible) {
        if (!visible) {
            handleClosingApps(Sets.newArraySet(appWindowToken));
        }
    }

    private void handleClosingApps(ArraySet<AppWindowToken> closingApps) {
        if (!ENABLE_TASK_SNAPSHOTS || ActivityManager.isLowRamDeviceStatic()) {
            return;
        }

        // We need to take a snapshot of the task if and only if all activities of the task are
        // either closing or hidden.
        getClosingTasks(closingApps, mTmpTasks);
        for (int i = mTmpTasks.size() - 1; i >= 0; i--) {
            final Task task = mTmpTasks.valueAt(i);
            final int mode = getSnapshotMode(task);
            final TaskSnapshot snapshot;
            switch (mode) {
                case SNAPSHOT_MODE_NONE:
                    continue;
                case SNAPSHOT_MODE_APP_THEME:
                    snapshot = drawAppThemeSnapshot(task);
                    break;
                case SNAPSHOT_MODE_REAL:
                    snapshot = snapshotTask(task);
                    break;
                default:
                    snapshot = null;
                    break;
            }
            if (snapshot != null) {
                mCache.putSnapshot(task, snapshot);
                mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
                if (task.getController() != null) {
                    task.getController().reportSnapshotChanged(snapshot);
                }
            }
        }
    }

    /**
     * Retrieves a snapshot. If {@param restoreFromDisk} equals {@code true}, DO HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean reducedResolution) {
        return mCache.getSnapshot(taskId, userId, restoreFromDisk, reducedResolution);
    }

    /**
     * Creates a starting surface for {@param token} with {@param snapshot}. DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    StartingSurface createStartingSurface(AppWindowToken token,
            TaskSnapshot snapshot) {
        return TaskSnapshotSurface.create(mService, token, snapshot);
    }

    private TaskSnapshot snapshotTask(Task task) {
        final AppWindowToken top = task.getTopChild();
        if (top == null) {
            return null;
        }
        final WindowState mainWindow = top.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        final GraphicBuffer buffer = top.mDisplayContent.screenshotApplicationsToBuffer(top.token,
                -1, -1, false, 1.0f, false, true);
        if (buffer == null) {
            return null;
        }
        return new TaskSnapshot(buffer, top.getConfiguration().orientation,
                minRect(mainWindow.mContentInsets, mainWindow.mStableInsets), false /* reduced */,
                1f /* scale */);
    }

    private Rect minRect(Rect rect1, Rect rect2) {
        return new Rect(Math.min(rect1.left, rect2.left),
                Math.min(rect1.top, rect2.top),
                Math.min(rect1.right, rect2.right),
                Math.min(rect1.bottom, rect2.bottom));
    }

    /**
     * Retrieves all closing tasks based on the list of closing apps during an app transition.
     */
    @VisibleForTesting
    void getClosingTasks(ArraySet<AppWindowToken> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final AppWindowToken atoken = closingApps.valueAt(i);
            final Task task = atoken.getTask();

            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (task != null && !task.isVisible()) {
                outClosingTasks.add(task);
            }
        }
    }

    @VisibleForTesting
    int getSnapshotMode(Task task) {
        final AppWindowToken topChild = task.getTopChild();
        if (StackId.isHomeOrRecentsStack(task.mStack.mStackId)) {
            return SNAPSHOT_MODE_NONE;
        } else if (topChild != null && topChild.shouldUseAppThemeSnapshot()) {
            return SNAPSHOT_MODE_APP_THEME;
        } else {
            return SNAPSHOT_MODE_REAL;
        }
    }

    /**
     * If we are not allowed to take a real screenshot, this attempts to represent the app as best
     * as possible by using the theme's window background.
     */
    private TaskSnapshot drawAppThemeSnapshot(Task task) {
        final AppWindowToken topChild = task.getTopChild();
        if (topChild == null) {
            return null;
        }
        final WindowState mainWindow = topChild.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        final int color = task.getTaskDescription().getBackgroundColor();
        final int statusBarColor = task.getTaskDescription().getStatusBarColor();
        final int navigationBarColor = task.getTaskDescription().getNavigationBarColor();
        final GraphicBuffer buffer = GraphicBuffer.create(mainWindow.getFrameLw().width(),
                mainWindow.getFrameLw().height(),
                RGBA_8888, USAGE_HW_TEXTURE | USAGE_SW_WRITE_RARELY | USAGE_SW_READ_NEVER);
        if (buffer == null) {
            return null;
        }
        final Canvas c = buffer.lockCanvas();
        c.drawColor(color);
        final LayoutParams attrs = mainWindow.getAttrs();
        final SystemBarBackgroundPainter decorPainter = new SystemBarBackgroundPainter(attrs.flags,
                attrs.privateFlags, attrs.systemUiVisibility, statusBarColor, navigationBarColor);
        decorPainter.setInsets(mainWindow.mContentInsets, mainWindow.mStableInsets);
        decorPainter.drawDecors(c, null /* statusBarExcludeFrame */);
        buffer.unlockCanvasAndPost(c);
        return new TaskSnapshot(buffer, topChild.getConfiguration().orientation,
                mainWindow.mStableInsets, false /* reduced */, 1.0f /* scale */);
    }

    /**
     * Called when an {@link AppWindowToken} has been removed.
     */
    void onAppRemoved(AppWindowToken wtoken) {
        mCache.onAppRemoved(wtoken);
    }

    /**
     * Called when the process of an {@link AppWindowToken} has died.
     */
    void onAppDied(AppWindowToken wtoken) {
        mCache.onAppDied(wtoken);
    }

    void notifyTaskRemovedFromRecents(int taskId, int userId) {
        mCache.onTaskRemoved(taskId);
        mPersister.onTaskRemovedFromRecents(taskId, userId);
    }

    /**
     * See {@link TaskSnapshotPersister#removeObsoleteFiles}
     */
    void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        mPersister.removeObsoleteFiles(persistentTaskIds, runningUserIds);
    }

    /**
     * Temporarily pauses/unpauses persisting of task snapshots.
     *
     * @param paused Whether task snapshot persisting should be paused.
     */
    void setPersisterPaused(boolean paused) {
        mPersister.setPaused(paused);
    }

    void dump(PrintWriter pw, String prefix) {
        mCache.dump(pw, prefix);
    }
}
