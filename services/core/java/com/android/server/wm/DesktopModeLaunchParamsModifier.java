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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
import com.android.window.flags.Flags;
/**
 * The class that defines default launch params for tasks in desktop mode
 */
public class DesktopModeLaunchParamsModifier implements LaunchParamsModifier {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "DesktopModeLaunchParamsModifier" : TAG_ATM;
    private static final boolean DEBUG = false;

    public static final float DESKTOP_MODE_INITIAL_BOUNDS_SCALE =
            SystemProperties
                    .getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f;

    /**
     * Flag to indicate whether to restrict desktop mode to supported devices.
     */
    @VisibleForTesting
    static final String ENFORCE_DEVICE_RESTRICTIONS_KEY =
            "persist.wm.debug.desktop_mode_enforce_device_restrictions";

    /**
     * Flag to indicate whether to restrict desktop mode to supported devices.
     */
    private static final boolean ENFORCE_DEVICE_RESTRICTIONS = SystemProperties.getBoolean(
            ENFORCE_DEVICE_RESTRICTIONS_KEY, true);

    private StringBuilder mLogBuilder;

    private final Context mContext;

    DesktopModeLaunchParamsModifier(@NonNull Context context) {
        mContext = context;
    }

    @Override
    public int onCalculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {

        initLogBuilder(task, activity);
        int result = calculate(task, layout, activity, source, options, request, phase,
                currentParams, outParams);
        outputLog();
        return result;
    }

    private int calculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {

        if (!canEnterDesktopMode(mContext)) {
            appendLog("desktop mode is not enabled, continuing");
            return RESULT_CONTINUE;
        }

        if (task == null) {
            appendLog("task null, skipping");
            return RESULT_SKIP;
        }
        if (!task.isActivityTypeStandardOrUndefined()) {
            appendLog("not standard or undefined activity type, skipping");
            return RESULT_SKIP;
        }
        if (phase < PHASE_WINDOWING_MODE) {
            appendLog("not in windowing mode or bounds phase, skipping");
            return RESULT_SKIP;
        }

        // Copy over any values
        outParams.set(currentParams);

        // In Proto2, trampoline task launches of an existing background task can result in the
        // previous windowing mode to be restored even if the desktop mode state has changed.
        // Let task launches inherit the windowing mode from the source task if available, which
        // should have the desired windowing mode set by WM Shell. See b/286929122.
        if (source != null && source.getTask() != null) {
            final Task sourceTask = source.getTask();
            outParams.mWindowingMode = sourceTask.getWindowingMode();
            appendLog("inherit-from-source=" + outParams.mWindowingMode);
        }

        if (phase == PHASE_WINDOWING_MODE) {
            return RESULT_DONE;
        }

        // TODO(b/336998072) - Find a better solution to this that makes use of the logic from
        //  TaskLaunchParamsModifier. Put logic in common utils, return RESULT_CONTINUE, inherit
        //  from parent class, etc.
        if (outParams.mPreferredTaskDisplayArea == null && task.getRootTask() != null) {
            appendLog("display-from-task=" + task.getRootTask().getDisplayId());
            outParams.mPreferredTaskDisplayArea = task.getRootTask().getDisplayArea();
        }

        if (phase == PHASE_DISPLAY_AREA) {
            return RESULT_DONE;
        }

        if (!currentParams.mBounds.isEmpty()) {
            appendLog("currentParams has bounds set, not overriding");
            return RESULT_SKIP;
        }

        calculateAndCentreInitialBounds(task, outParams);

        appendLog("setting desktop mode task bounds to %s", outParams.mBounds);

        return RESULT_DONE;
    }

    /**
     * Calculates the initial height and width of a task in desktop mode and centers it within the
     * window bounds.
     */
    private void calculateAndCentreInitialBounds(Task task,
            LaunchParamsController.LaunchParams outParams) {
        // TODO(b/319819547): Account for app constraints so apps do not become letterboxed
        final Rect windowBounds = task.getDisplayArea().getBounds();
        final int width = (int) (windowBounds.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int height = (int) (windowBounds.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        outParams.mBounds.right = width;
        outParams.mBounds.bottom = height;
        outParams.mBounds.offset(windowBounds.centerX() - outParams.mBounds.centerX(),
                windowBounds.centerY() - outParams.mBounds.centerY());
    }

    private void initLogBuilder(Task task, ActivityRecord activity) {
        if (DEBUG) {
            mLogBuilder = new StringBuilder(
                    "DesktopModeLaunchParamsModifier: task=" + task + " activity=" + activity);
        }
    }

    private void appendLog(String format, Object... args) {
        if (DEBUG) mLogBuilder.append(" ").append(String.format(format, args));
    }

    private void outputLog() {
        if (DEBUG) Slog.d(TAG, mLogBuilder.toString());
    }

    /** Whether desktop mode is enabled. */
    static boolean isDesktopModeEnabled() {
        return Flags.enableDesktopWindowingMode();
    }

    /**
     * Return {@code true} if desktop mode should be restricted to supported devices.
     */
    @VisibleForTesting
    static boolean enforceDeviceRestrictions() {
        return ENFORCE_DEVICE_RESTRICTIONS;
    }

    /**
     * Return {@code true} if the current device supports desktop mode.
     */
    // TODO(b/337819319): use a companion object instead.
    @VisibleForTesting
    static boolean isDesktopModeSupported(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.config_isDesktopModeSupported);
    }

    /**
     * Return {@code true} if desktop mode can be entered on the current device.
     */
    static boolean canEnterDesktopMode(@NonNull Context context) {
        return isDesktopModeEnabled()
                && (!enforceDeviceRestrictions() || isDesktopModeSupported(context));
    }
}
