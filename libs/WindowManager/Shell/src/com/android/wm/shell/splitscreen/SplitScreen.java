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

package com.android.wm.shell.splitscreen;

import android.app.ActivityManager;

import androidx.annotation.NonNull;

import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;

/**
 * Interface to engage split-screen feature.
 */
@ExternalThread
public interface SplitScreen {
    /** Unpin a task in the side-stage of split-screen. */
    boolean pinTask(int taskId);
    /** Unpin a task in the side-stage of split-screen. */
    boolean pinTask(ActivityManager.RunningTaskInfo task);
    /** Unpin a task from the side-stage of split-screen. */
    boolean unpinTask(int taskId);
// TODO: Do we need show/hide side stage or is startActivity and sendToBack good enough?
    /** Dumps current status of split-screen. */
    void dump(@NonNull PrintWriter pw, String prefix);
    /** Called when the shell organizer has been registered. */
    void onOrganizerRegistered();
}
