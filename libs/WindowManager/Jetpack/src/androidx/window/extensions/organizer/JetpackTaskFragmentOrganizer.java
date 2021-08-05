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

package androidx.window.extensions.organizer;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.Activity;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TaskFragmentAppearedInfo;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Platform default Extensions implementation of {@link TaskFragmentOrganizer} to organize
 * task fragments.
 *
 * All calls into methods of this class are expected to be on the UI thread.
 */
class JetpackTaskFragmentOrganizer extends TaskFragmentOrganizer {

    /** Mapping from the client assigned unique token to the {@link TaskFragmentInfo}. */
    private final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();

    /** Mapping from the client assigned unique token to the TaskFragment {@link SurfaceControl}. */
    private final Map<IBinder, SurfaceControl> mFragmentLeashes = new ArrayMap<>();

    /**
     * Mapping from the client assigned unique token to the TaskFragment parent
     * {@link Configuration}.
     */
    final Map<IBinder, Configuration> mFragmentParentConfigs = new ArrayMap<>();

    private final TaskFragmentCallback mCallback;

    /**
     * Callback that notifies the controller about changes to task fragments.
     */
    interface TaskFragmentCallback {
        void onTaskFragmentAppeared(@NonNull TaskFragmentAppearedInfo taskFragmentAppearedInfo);
        void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo);
        void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo);
        void onTaskFragmentParentInfoChanged(@NonNull IBinder fragmentToken,
                @NonNull Configuration parentConfig);
    }

    /**
     * @param executor  callbacks from WM Core are posted on this executor. It should be tied to the
     *                  UI thread that all other calls into methods of this class are also on.
     */
    JetpackTaskFragmentOrganizer(@NonNull Executor executor, TaskFragmentCallback callback) {
        super(executor);
        mCallback = callback;
    }

    /**
     * Starts a new Activity and puts it into split with an existing Activity side-by-side.
     * @param launchingFragmentToken    token for the launching TaskFragment. If it exists, it will
     *                                  be resized based on {@param launchingFragmentBounds}.
     *                                  Otherwise, we will create a new TaskFragment with the given
     *                                  token for the {@param launchingActivity}.
     * @param launchingFragmentBounds   the initial bounds for the launching TaskFragment.
     * @param launchingActivity the Activity to put on the left hand side of the split as the
     *                          primary.
     * @param secondaryFragmentToken    token to create the secondary TaskFragment with.
     * @param secondaryFragmentBounds   the initial bounds for the secondary TaskFragment
     * @param activityIntent    Intent to start the secondary Activity with.
     * @param activityOptions   ActivityOptions to start the secondary Activity with.
     */
    void startActivityToSide(IBinder launchingFragmentToken, Rect launchingFragmentBounds,
            Activity launchingActivity, IBinder secondaryFragmentToken,
            Rect secondaryFragmentBounds,  Intent activityIntent,
            @Nullable Bundle activityOptions) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final IBinder ownerToken = launchingActivity.getActivityToken();

        // Create or resize the launching TaskFragment.
        if (mFragmentInfos.containsKey(launchingFragmentToken)) {
            resizeTaskFragment(wct, launchingFragmentToken, launchingFragmentBounds);
        } else {
            createTaskFragmentAndReparentActivity(wct, launchingFragmentToken, ownerToken,
                    launchingFragmentBounds, WINDOWING_MODE_MULTI_WINDOW, launchingActivity);
        }

        // Create a TaskFragment for the secondary activity.
        createTaskFragmentAndStartActivity(wct, secondaryFragmentToken, ownerToken,
                secondaryFragmentBounds, WINDOWING_MODE_MULTI_WINDOW, activityIntent,
                activityOptions);

        // Set adjacent to each other so that the containers below will be invisible.
        wct.setAdjacentTaskFragments(launchingFragmentToken, secondaryFragmentToken);

        applyTransaction(wct);
    }

    /**
     * Expands an existing TaskFragment to fill parent.
     * @param wct WindowContainerTransaction in which the task fragment should be resized.
     * @param fragmentToken token of an existing TaskFragment.
     */
    void expandTaskFragment(WindowContainerTransaction wct, IBinder fragmentToken) {
        resizeTaskFragment(wct, fragmentToken, new Rect());
        wct.setAdjacentTaskFragments(fragmentToken, null);
    }

    /**
     * Expands an existing TaskFragment to fill parent.
     * @param fragmentToken token of an existing TaskFragment.
     */
    void expandTaskFragment(IBinder fragmentToken) {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        expandTaskFragment(wct, fragmentToken);
        applyTransaction(wct);
    }

    /**
     * Expands an Activity to fill parent by moving it to a new TaskFragment.
     * @param fragmentToken token to create new TaskFragment with.
     * @param activity      activity to move to the fill-parent TaskFragment.
     */
    void expandActivity(IBinder fragmentToken, Activity activity) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        createTaskFragmentAndReparentActivity(
                wct, fragmentToken, activity.getActivityToken(), new Rect(),
                WINDOWING_MODE_UNDEFINED, activity);
        applyTransaction(wct);
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    void createTaskFragment(WindowContainerTransaction wct, IBinder fragmentToken,
            IBinder ownerToken, @NonNull Rect bounds, @WindowingMode int windowingMode) {
        final TaskFragmentCreationParams fragmentOptions =
                createFragmentOptions(fragmentToken, ownerToken, bounds, windowingMode);
        wct.createTaskFragment(fragmentOptions);
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    private void createTaskFragmentAndReparentActivity(
            WindowContainerTransaction wct, IBinder fragmentToken, IBinder ownerToken,
            @NonNull Rect bounds, @WindowingMode int windowingMode, Activity activity) {
        createTaskFragment(wct, fragmentToken, ownerToken, bounds, windowingMode);
        wct.reparentActivityToTaskFragment(fragmentToken, activity.getActivityToken());
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    private void createTaskFragmentAndStartActivity(
            WindowContainerTransaction wct, IBinder fragmentToken, IBinder ownerToken,
            @NonNull Rect bounds, @WindowingMode int windowingMode, Intent activityIntent,
            @Nullable Bundle activityOptions) {
        createTaskFragment(wct, fragmentToken, ownerToken, bounds, windowingMode);
        wct.startActivityInTaskFragment(fragmentToken, ownerToken, activityIntent, activityOptions);
    }

    TaskFragmentCreationParams createFragmentOptions(IBinder fragmentToken, IBinder ownerToken,
            Rect bounds, @WindowingMode int windowingMode) {
        if (mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "There is an existing TaskFragment with fragmentToken=" + fragmentToken);
        }

        return new TaskFragmentCreationParams.Builder(
                getOrganizerToken(),
                fragmentToken,
                ownerToken)
                .setInitialBounds(bounds)
                .setWindowingMode(windowingMode)
                .build();
    }

    void resizeTaskFragment(WindowContainerTransaction wct, IBinder fragmentToken,
            @Nullable Rect bounds) {
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        if (bounds == null) {
            bounds = new Rect();
        }
        wct.setBounds(mFragmentInfos.get(fragmentToken).getToken(), bounds);
    }

    void deleteTaskFragment(WindowContainerTransaction wct, IBinder fragmentToken) {
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        wct.deleteTaskFragment(mFragmentInfos.get(fragmentToken).getToken());
    }

    @Override
    public void onTaskFragmentAppeared(@NonNull TaskFragmentAppearedInfo taskFragmentAppearedInfo) {
        final TaskFragmentInfo info = taskFragmentAppearedInfo.getTaskFragmentInfo();
        final IBinder fragmentToken = info.getFragmentToken();
        final SurfaceControl leash = taskFragmentAppearedInfo.getLeash();
        mFragmentInfos.put(fragmentToken, info);
        mFragmentLeashes.put(fragmentToken, leash);

        if (mCallback != null) {
            mCallback.onTaskFragmentAppeared(taskFragmentAppearedInfo);
        }
    }

    @Override
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {
        final IBinder fragmentToken = taskFragmentInfo.getFragmentToken();
        mFragmentInfos.put(fragmentToken, taskFragmentInfo);

        if (mCallback != null) {
            mCallback.onTaskFragmentInfoChanged(taskFragmentInfo);
        }
    }

    @Override
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {
        mFragmentInfos.remove(taskFragmentInfo.getFragmentToken());
        mFragmentLeashes.remove(taskFragmentInfo.getFragmentToken());
        mFragmentParentConfigs.remove(taskFragmentInfo.getFragmentToken());

        if (mCallback != null) {
            mCallback.onTaskFragmentVanished(taskFragmentInfo);
        }
    }

    @Override
    public void onTaskFragmentParentInfoChanged(
            @NonNull IBinder fragmentToken, @NonNull Configuration parentConfig) {
        mFragmentParentConfigs.put(fragmentToken, parentConfig);

        if (mCallback != null) {
            mCallback.onTaskFragmentParentInfoChanged(fragmentToken, parentConfig);
        }
    }
}
