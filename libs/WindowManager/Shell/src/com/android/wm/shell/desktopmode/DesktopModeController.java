/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executor;

/**
 * Handles windowing changes when desktop mode system setting changes
 */
public class DesktopModeController implements RemoteCallable<DesktopModeController>,
        Transitions.TransitionHandler {

    private final Context mContext;
    private final ShellController mShellController;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final Transitions mTransitions;
    private final DesktopModeTaskRepository mDesktopModeTaskRepository;
    private final ShellExecutor mMainExecutor;
    private final DesktopModeImpl mDesktopModeImpl = new DesktopModeImpl();
    private final SettingsObserver mSettingsObserver;

    public DesktopModeController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Transitions transitions,
            DesktopModeTaskRepository desktopModeTaskRepository,
            @ShellMainThread Handler mainHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        mContext = context;
        mShellController = shellController;
        mShellTaskOrganizer = shellTaskOrganizer;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mTransitions = transitions;
        mDesktopModeTaskRepository = desktopModeTaskRepository;
        mMainExecutor = mainExecutor;
        mSettingsObserver = new SettingsObserver(mContext, mainHandler);
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopModeController");
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_DESKTOP_MODE,
                this::createExternalInterface, this);
        mSettingsObserver.observe();
        if (DesktopModeStatus.isActive(mContext)) {
            updateDesktopModeActive(true);
        }
        mTransitions.addHandler(this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    /**
     * Get connection interface between sysui and shell
     */
    public DesktopMode asDesktopMode() {
        return mDesktopModeImpl;
    }

    /**
     * Creates a new instance of the external interface to pass to another process.
     */
    private ExternalInterfaceBinder createExternalInterface() {
        return new IDesktopModeImpl(this);
    }

    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    public void addListener(DesktopModeTaskRepository.VisibleTasksListener listener,
            Executor callbackExecutor) {
        mDesktopModeTaskRepository.addVisibleTasksListener(listener, callbackExecutor);
    }

    @VisibleForTesting
    void updateDesktopModeActive(boolean active) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "updateDesktopModeActive: active=%s", active);

        int displayId = mContext.getDisplayId();

        WindowContainerTransaction wct = new WindowContainerTransaction();
        // Reset freeform windowing mode that is set per task level (tasks should inherit
        // container value)
        wct.merge(mShellTaskOrganizer.prepareClearFreeformForStandardTasks(displayId),
                true /* transfer */);
        int targetWindowingMode;
        if (active) {
            targetWindowingMode = WINDOWING_MODE_FREEFORM;
        } else {
            targetWindowingMode = WINDOWING_MODE_FULLSCREEN;
            // Clear any resized bounds
            wct.merge(mShellTaskOrganizer.prepareClearBoundsForStandardTasks(displayId),
                    true /* transfer */);
        }
        prepareWindowingModeChange(wct, displayId, targetWindowingMode);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTransitions.startTransition(TRANSIT_CHANGE, wct, null);
        } else {
            mRootTaskDisplayAreaOrganizer.applyTransaction(wct);
        }
    }

    private void prepareWindowingModeChange(WindowContainerTransaction wct,
            int displayId, @WindowConfiguration.WindowingMode int windowingMode) {
        DisplayAreaInfo displayAreaInfo = mRootTaskDisplayAreaOrganizer
                .getDisplayAreaInfo(displayId);
        if (displayAreaInfo == null) {
            ProtoLog.e(WM_SHELL_DESKTOP_MODE,
                    "unable to update windowing mode for display %d display not found", displayId);
            return;
        }

        ProtoLog.d(WM_SHELL_DESKTOP_MODE,
                "setWindowingMode: displayId=%d current wmMode=%d new wmMode=%d", displayId,
                displayAreaInfo.configuration.windowConfiguration.getWindowingMode(),
                windowingMode);

        wct.setWindowingMode(displayAreaInfo.token, windowingMode);
    }

    /**
     * Show apps on desktop
     */
    WindowContainerTransaction showDesktopApps() {
        ArraySet<Integer> activeTasks = mDesktopModeTaskRepository.getActiveTasks();
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "bringDesktopAppsToFront: tasks=%s", activeTasks.size());
        ArrayList<RunningTaskInfo> taskInfos = new ArrayList<>();
        for (Integer taskId : activeTasks) {
            RunningTaskInfo taskInfo = mShellTaskOrganizer.getRunningTaskInfo(taskId);
            if (taskInfo != null) {
                taskInfos.add(taskInfo);
            }
        }
        // Order by lastActiveTime, descending
        taskInfos.sort(Comparator.comparingLong(task -> -task.lastActiveTime));
        WindowContainerTransaction wct = new WindowContainerTransaction();
        for (RunningTaskInfo task : taskInfos) {
            wct.reorder(task.token, true);
        }

        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            mShellTaskOrganizer.applyTransaction(wct);
        }

        return wct;
    }

    /**
     * Turn desktop mode on or off
     * @param active the desired state for desktop mode setting
     */
    public void setDesktopModeActive(boolean active) {
        int value = active ? 1 : 0;
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.DESKTOP_MODE, value);
    }

    /**
     * Returns the windowing mode of the display area with the specified displayId.
     * @param displayId
     * @return
     */
    public int getDisplayAreaWindowingMode(int displayId) {
        return mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)
                .configuration.windowConfiguration.getWindowingMode();
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // This handler should never be the sole handler, so should not animate anything.
        return false;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        // Only do anything if we are in desktop mode and opening a task/app in freeform
        if (!DesktopModeStatus.isActive(mContext)) {
            ProtoLog.d(WM_SHELL_DESKTOP_MODE,
                    "skip shell transition request: desktop mode not active");
            return null;
        }
        if (request.getType() != TRANSIT_OPEN) {
            ProtoLog.d(WM_SHELL_DESKTOP_MODE,
                    "skip shell transition request: only supports TRANSIT_OPEN");
            return null;
        }
        if (request.getTriggerTask() == null
                || request.getTriggerTask().getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            ProtoLog.d(WM_SHELL_DESKTOP_MODE, "skip shell transition request: not freeform task");
            return null;
        }
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "handle shell transition request: %s", request);

        WindowContainerTransaction wct = mTransitions.dispatchRequest(transition, request, this);
        if (wct == null) {
            wct = new WindowContainerTransaction();
        }
        wct.merge(showDesktopApps(), true /* transfer */);
        wct.reorder(request.getTriggerTask().token, true /* onTop */);

        return wct;
    }

    /**
     * A {@link ContentObserver} for listening to changes to {@link Settings.System#DESKTOP_MODE}
     */
    private final class SettingsObserver extends ContentObserver {

        private final Uri mDesktopModeSetting = Settings.System.getUriFor(
                Settings.System.DESKTOP_MODE);

        private final Context mContext;

        SettingsObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        public void observe() {
            // TODO(b/242867463): listen for setting change for all users
            mContext.getContentResolver().registerContentObserver(mDesktopModeSetting,
                    false /* notifyForDescendants */, this /* observer */, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            if (mDesktopModeSetting.equals(uri)) {
                ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Received update for desktop mode setting");
                desktopModeSettingChanged();
            }
        }

        private void desktopModeSettingChanged() {
            boolean enabled = DesktopModeStatus.isActive(mContext);
            updateDesktopModeActive(enabled);
        }
    }

    /**
     * The interface for calls from outside the shell, within the host process.
     */
    @ExternalThread
    private final class DesktopModeImpl implements DesktopMode {

        @Override
        public void addListener(DesktopModeTaskRepository.VisibleTasksListener listener,
                Executor callbackExecutor) {
            mMainExecutor.execute(() -> {
                DesktopModeController.this.addListener(listener, callbackExecutor);
            });
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IDesktopModeImpl extends IDesktopMode.Stub
            implements ExternalInterfaceBinder {

        private DesktopModeController mController;

        IDesktopModeImpl(DesktopModeController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mController = null;
        }

        public void showDesktopApps() {
            executeRemoteCallWithTaskPermission(mController, "showDesktopApps",
                    DesktopModeController::showDesktopApps);
        }
    }
}
