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

package com.android.wm.shell.startingsurface;

import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.StartingWindowInfo;

import java.util.function.BiConsumer;
/**
 * Interface to engage starting window feature.
 */
public interface StartingSurface {
    /**
     * Called when a task need a starting window.
     */
    void addStartingWindow(StartingWindowInfo windowInfo, IBinder appToken);
    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation);
    /**
     * Called when the Task wants to copy the splash screen.
     * @param taskId
     */
    void copySplashScreenView(int taskId);

    /**
     * Registers the starting window listener.
     *
     * @param listener The callback when need a starting window.
     */
    void setStartingWindowListener(BiConsumer<Integer, Integer> listener);
}
