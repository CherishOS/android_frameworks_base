/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.CompatChanges;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.BlockedAppStreamingActivity;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;


/**
 * A controller to control the policies of the windows that can be displayed on the virtual display.
 */
public class GenericWindowPolicyController extends DisplayWindowPolicyController {

    private static final String TAG = "GenericWindowPolicyController";

    /** Interface to listen running applications change on virtual display. */
    public interface RunningAppsChangedListener {
        /**
         * Notifies the running applications change.
         */
        void onRunningAppsChanged(ArraySet<Integer> runningUids);
    }

    private static final ComponentName BLOCKED_APP_STREAMING_COMPONENT =
            new ComponentName("android", BlockedAppStreamingActivity.class.getName());

    /**
     * If required, allow the secure activity to display on remote device since
     * {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE = 201712607L;
    @NonNull
    private final ArraySet<UserHandle> mAllowedUsers;
    @Nullable
    private final ArraySet<ComponentName> mAllowedActivities;
    @Nullable
    private final ArraySet<ComponentName> mBlockedActivities;
    private final Object mGenericWindowPolicyControllerLock = new Object();
    private Consumer<ActivityInfo> mActivityBlockedCallback;

    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    final ArraySet<Integer> mRunningUids = new ArraySet<>();
    @Nullable private final ActivityListener mActivityListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private RunningAppsChangedListener mRunningAppsChangedListener;

    /**
     * Creates a window policy controller that is generic to the different use cases of virtual
     * device.
     *
     * @param windowFlags The window flags that this controller is interested in.
     * @param systemWindowFlags The system window flags that this controller is interested in.
     * @param allowedUsers The set of users that are allowed to stream in this display.
     * @param activityListener Activity listener to listen for activity changes. The display ID
     *   is not populated in this callback and is always {@link Display#INVALID_DISPLAY}.
     */
    public GenericWindowPolicyController(int windowFlags, int systemWindowFlags,
            @NonNull ArraySet<UserHandle> allowedUsers,
            @Nullable Set<ComponentName> allowedActivities,
            @Nullable Set<ComponentName> blockedActivities,
            @NonNull ActivityListener activityListener,
            @NonNull Consumer<ActivityInfo> activityBlockedCallback) {
        mAllowedUsers = allowedUsers;
        mAllowedActivities = allowedActivities == null ? null : new ArraySet<>(allowedActivities);
        mBlockedActivities = blockedActivities == null ? null : new ArraySet<>(blockedActivities);
        mActivityBlockedCallback = activityBlockedCallback;
        setInterestedWindowFlags(windowFlags, systemWindowFlags);
        mActivityListener = activityListener;
    }

    /** Sets listener for running applications change. */
    public void setRunningAppsChangedListener(@Nullable RunningAppsChangedListener listener) {
        mRunningAppsChangedListener = listener;
    }

    @Override
    public boolean canContainActivities(@NonNull List<ActivityInfo> activities) {
        // Can't display all the activities if any of them don't want to be displayed.
        final int activityCount = activities.size();
        for (int i = 0; i < activityCount; i++) {
            final ActivityInfo aInfo = activities.get(i);
            if (!canContainActivity(aInfo, /* windowFlags= */ 0, /* systemWindowFlags= */ 0)) {
                mActivityBlockedCallback.accept(aInfo);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        if (!canContainActivity(activityInfo, windowFlags, systemWindowFlags)) {
            mActivityBlockedCallback.accept(activityInfo);
            return false;
        }
        return true;
    }

    @Override
    public void onTopActivityChanged(ComponentName topActivity, int uid) {
        if (mActivityListener != null) {
            // Post callback on the main thread so it doesn't block activity launching
            mHandler.post(() ->
                    mActivityListener.onTopActivityChanged(Display.INVALID_DISPLAY, topActivity));
        }
    }

    @Override
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mRunningUids.clear();
            mRunningUids.addAll(runningUids);
            if (mActivityListener != null && mRunningUids.isEmpty()) {
                // Post callback on the main thread so it doesn't block activity launching
                mHandler.post(() -> mActivityListener.onDisplayEmpty(Display.INVALID_DISPLAY));
            }
        }
        if (mRunningAppsChangedListener != null) {
            mRunningAppsChangedListener.onRunningAppsChanged(runningUids);
        }
    }

    /**
     * Returns true if an app with the given UID has an activity running on the virtual display for
     * this controller.
     */
    boolean containsUid(int uid) {
        synchronized (mGenericWindowPolicyControllerLock) {
            return mRunningUids.contains(uid);
        }
    }

    private boolean canContainActivity(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        if ((activityInfo.flags & FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES) == 0) {
            return false;
        }
        ComponentName activityComponent = activityInfo.getComponentName();
        if (BLOCKED_APP_STREAMING_COMPONENT.equals(activityComponent)) {
            // The error dialog alerting users that streaming is blocked is always allowed.
            return true;
        }
        final UserHandle activityUser =
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid);
        if (!mAllowedUsers.contains(activityUser)) {
            Slog.d(TAG, "Virtual device activity not allowed from user " + activityUser);
            return false;
        }
        if (mBlockedActivities != null && mBlockedActivities.contains(activityComponent)) {
            Slog.d(TAG, "Virtual device blocking launch of " + activityComponent);
            return false;
        }
        if (mAllowedActivities != null && !mAllowedActivities.contains(activityComponent)) {
            Slog.d(TAG, activityComponent + " is not in the allowed list.");
            return false;
        }
        if (!CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE,
                activityInfo.packageName, activityUser)) {
            // TODO(b/201712607): Add checks for the apps that use SurfaceView#setSecure.
            if ((windowFlags & FLAG_SECURE) != 0) {
                return false;
            }
            if ((systemWindowFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                return false;
            }
        }
        return true;
    }
}
