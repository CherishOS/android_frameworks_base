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

package com.android.systemui.bubbles;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * View that can display a task.
 */
// TODO: Place in com.android.wm.shell vs. com.android.wm.shell.bubbles on shell migration.
public class TaskView extends SurfaceView implements SurfaceHolder.Callback,
        ShellTaskOrganizer.TaskListener {

    public interface Listener {
        /** Called when the container is ready for launching activities. */
        default void onInitialized() {}

        /** Called when the container can no longer launch activities. */
        default void onReleased() {}

        /** Called when a task is created inside the container. */
        default void onTaskCreated(int taskId, ComponentName name) {}

        /** Called when a task visibility changes. */
        default void onTaskVisibilityChanged(int taskId, boolean visible) {}

        /** Called when a task is about to be removed from the stack inside the container. */
        default void onTaskRemovalStarted(int taskId) {}

        /** Called when a task is created inside the container. */
        default void onBackPressedOnTaskRoot(int taskId) {}
    }

    private final CloseGuard mGuard = CloseGuard.get();

    private final ShellTaskOrganizer mTaskOrganizer;

    private ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mTaskToken;
    private SurfaceControl mTaskLeash;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private boolean mSurfaceCreated;
    private boolean mIsInitialized;
    private Listener mListener;
    private final Executor mExecutor;

    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRootRect = new Rect();

    public TaskView(Context context, ShellTaskOrganizer organizer, Executor executor) {
        super(context, null, 0, 0, true /* disableBackgroundLayer */);

        mExecutor = executor;
        mTaskOrganizer = organizer;
        setUseAlpha();
        getHolder().addCallback(this);
        mGuard.open("release");
    }

    /**
     * Only one listener may be set on the view, throws an exception otherwise.
     */
    public void setListener(Listener listener) {
        if (mListener != null) {
            throw new IllegalStateException(
                    "Trying to set a listener when one has already been set");
        }
        mListener = listener;
    }

    /**
     * Launch an activity represented by {@link ShortcutInfo}.
     * <p>The owner of this container must be allowed to access the shortcut information,
     * as defined in {@link LauncherApps#hasShortcutHostPermission()} to use this method.
     *
     * @param shortcut the shortcut used to launch the activity.
     * @param options options for the activity.
     * @param sourceBounds the rect containing the source bounds of the clicked icon to open
     *                     this shortcut.
     */
    public void startShortcutActivity(@NonNull ShortcutInfo shortcut,
            @NonNull ActivityOptions options, @Nullable Rect sourceBounds) {
        prepareActivityOptions(options);
        LauncherApps service = mContext.getSystemService(LauncherApps.class);
        try {
            service.startShortcut(shortcut, sourceBounds, options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Launch a new activity.
     *
     * @param pendingIntent Intent used to launch an activity.
     * @param fillInIntent Additional Intent data, see {@link Intent#fillIn Intent.fillIn()}
     * @param options options for the activity.
     */
    public void startActivity(@NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options) {
        prepareActivityOptions(options);
        try {
            pendingIntent.send(mContext, 0 /* code */, fillInIntent,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void prepareActivityOptions(ActivityOptions options) {
        final Binder launchCookie = new Binder();
        mTaskOrganizer.setPendingLaunchCookieListener(launchCookie, this);
        options.setLaunchCookie(launchCookie);
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setTaskAlwaysOnTop(true);
    }

    /**
     * Call when view position or size has changed. Do not call when animating.
     */
    public void onLocationChanged() {
        if (mTaskToken == null) {
            return;
        }
        // Update based on the screen bounds
        getBoundsOnScreen(mTmpRect);
        getRootView().getBoundsOnScreen(mTmpRootRect);
        if (!mTmpRootRect.contains(mTmpRect)) {
            mTmpRect.offsetTo(0, 0);
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mTaskToken, mTmpRect);
        // TODO(b/151449487): Enable synchronization
        mTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Release this container if it is initialized.
     */
    public void release() {
        performRelease();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
                performRelease();
            }
        } finally {
            super.finalize();
        }
    }

    private void performRelease() {
        getHolder().removeCallback(this);
        mTaskOrganizer.removeListener(this);
        resetTaskInfo();
        mGuard.close();
        if (mListener != null && mIsInitialized) {
            mListener.onReleased();
            mIsInitialized = false;
        }
    }

    private void resetTaskInfo() {
        mTaskInfo = null;
        mTaskToken = null;
        mTaskLeash = null;
    }

    private void updateTaskVisibility() {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(mTaskToken, !mSurfaceCreated /* hidden */);
        mTaskOrganizer.applyTransaction(wct);
        // TODO(b/151449487): Only call callback once we enable synchronization
        if (mListener != null) {
            mListener.onTaskVisibilityChanged(mTaskInfo.taskId, mSurfaceCreated);
        }
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash) {
        mExecutor.execute(() -> {
            mTaskInfo = taskInfo;
            mTaskToken = taskInfo.token;
            mTaskLeash = leash;

            if (mSurfaceCreated) {
                // Surface is ready, so just reparent the task to this surface control
                mTransaction.reparent(mTaskLeash, getSurfaceControl())
                        .show(mTaskLeash)
                        .apply();
            } else {
                // The surface has already been destroyed before the task has appeared,
                // so go ahead and hide the task entirely
                updateTaskVisibility();
            }

            // TODO: Synchronize show with the resize
            onLocationChanged();
            setResizeBackgroundColor(taskInfo.taskDescription.getBackgroundColor());

            if (mListener != null) {
                mListener.onTaskCreated(taskInfo.taskId, taskInfo.baseActivity);
            }
        });
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        mExecutor.execute(() -> {
            if (mTaskToken == null || !mTaskToken.equals(taskInfo.token)) return;

            if (mListener != null) {
                mListener.onTaskRemovalStarted(taskInfo.taskId);
            }

            // Unparent the task when this surface is destroyed
            mTransaction.reparent(mTaskLeash, null).apply();
            resetTaskInfo();
        });
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        mExecutor.execute(() -> {
            mTaskInfo.taskDescription = taskInfo.taskDescription;
            setResizeBackgroundColor(taskInfo.taskDescription.getBackgroundColor());
        });
    }

    @Override
    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        mExecutor.execute(() -> {
            if (mTaskToken == null || !mTaskToken.equals(taskInfo.token)) return;
            if (mListener != null) {
                mListener.onBackPressedOnTaskRoot(taskInfo.taskId);
            }
        });
    }

    @Override
    public void dump(@androidx.annotation.NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }

    @Override
    public String toString() {
        return "TaskView" + ":" + (mTaskInfo != null ? mTaskInfo.taskId : "null");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        if (mListener != null && !mIsInitialized) {
            mIsInitialized = true;
            mListener.onInitialized();
        }
        if (mTaskToken == null) {
            // Nothing to update, task is not yet available
            return;
        }
        // Reparent the task when this surface is created
        mTransaction.reparent(mTaskLeash, getSurfaceControl())
                .show(mTaskLeash)
                .apply();
        updateTaskVisibility();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mTaskToken == null) {
            return;
        }
        onLocationChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceCreated = false;
        if (mTaskToken == null) {
            // Nothing to update, task is not yet available
            return;
        }

        // Unparent the task when this surface is destroyed
        mTransaction.reparent(mTaskLeash, null).apply();
        updateTaskVisibility();
    }
}
