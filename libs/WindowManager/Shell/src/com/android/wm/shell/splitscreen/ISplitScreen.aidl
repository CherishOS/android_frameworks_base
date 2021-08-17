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

package com.android.wm.shell.splitscreen;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.window.IRemoteTransition;

import com.android.wm.shell.splitscreen.ISplitScreenListener;

/**
 * Interface that is exposed to remote callers to manipulate the splitscreen feature.
 */
interface ISplitScreen {

    /**
     * Registers a split screen listener.
     */
    oneway void registerSplitScreenListener(in ISplitScreenListener listener) = 1;

    /**
     * Unregisters a split screen listener.
     */
    oneway void unregisterSplitScreenListener(in ISplitScreenListener listener) = 2;

    /**
     * Hides the side-stage if it is currently visible.
     */
    oneway void setSideStageVisibility(boolean visible) = 3;

    /**
     * Removes a task from the side stage.
     */
    oneway void removeFromSideStage(int taskId) = 4;

    /**
     * Removes the split-screen stages.
     */
    oneway void exitSplitScreen() = 5;

    /**
     * @param exitSplitScreenOnHide if to exit split-screen if both stages are not visible.
     */
    oneway void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) = 6;

    /**
     * Starts a task in a stage.
     */
    oneway void startTask(int taskId, int stage, int position, in Bundle options) = 7;

    /**
     * Starts a shortcut in a stage.
     */
    oneway void startShortcut(String packageName, String shortcutId, int stage, int position,
            in Bundle options, in UserHandle user) = 8;

    /**
     * Starts an activity in a stage.
     */
    oneway void startIntent(in PendingIntent intent, in Intent fillInIntent, int stage,
            int position, in Bundle options) = 9;

    /**
     * Starts tasks simultaneously in one transition. The first task in the list will be in the
     * main-stage and on the left/top.
     */
    oneway void startTasks(int mainTaskId, in Bundle mainOptions, int sideTaskId,
            in Bundle sideOptions, int sidePosition, in IRemoteTransition remoteTransition) = 10;
}
