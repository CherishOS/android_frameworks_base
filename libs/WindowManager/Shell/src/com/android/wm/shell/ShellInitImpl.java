/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

/**
 * The entry point implementation into the shell for initializing shell internal state.
 */
public class ShellInitImpl {
    private static final String TAG = ShellInitImpl.class.getSimpleName();

    private final DisplayImeController mDisplayImeController;
    private final DragAndDropController mDragAndDropController;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final Optional<LegacySplitScreen> mLegacySplitScreenOptional;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    private final Optional<AppPairs> mAppPairsOptional;
    private final FullscreenTaskListener mFullscreenTaskListener;
    private final ShellExecutor mMainExecutor;
    private final Transitions mTransitions;

    private final InitImpl mImpl = new InitImpl();

    public static ShellInit create(DisplayImeController displayImeController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreen> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<AppPairs> appPairsOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Transitions transitions,
            ShellExecutor mainExecutor) {
        return new ShellInitImpl(displayImeController,
                dragAndDropController,
                shellTaskOrganizer,
                legacySplitScreenOptional,
                splitScreenOptional,
                appPairsOptional,
                fullscreenTaskListener,
                transitions,
                mainExecutor).mImpl;
    }

    private ShellInitImpl(DisplayImeController displayImeController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreen> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<AppPairs> appPairsOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Transitions transitions,
            ShellExecutor mainExecutor) {
        mDisplayImeController = displayImeController;
        mDragAndDropController = dragAndDropController;
        mShellTaskOrganizer = shellTaskOrganizer;
        mLegacySplitScreenOptional = legacySplitScreenOptional;
        mSplitScreenOptional = splitScreenOptional;
        mAppPairsOptional = appPairsOptional;
        mFullscreenTaskListener = fullscreenTaskListener;
        mTransitions = transitions;
        mMainExecutor = mainExecutor;
    }

    private void init() {
        // Start listening for display changes
        mDisplayImeController.startMonitorDisplays();

        mShellTaskOrganizer.addListenerForType(
                mFullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        // Register the shell organizer
        mShellTaskOrganizer.registerOrganizer();

        mAppPairsOptional.ifPresent(AppPairs::onOrganizerRegistered);
        mSplitScreenOptional.ifPresent(SplitScreenController::onOrganizerRegistered);

        // Bind the splitscreen impl to the drag drop controller
        mDragAndDropController.initialize(mSplitScreenOptional);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTransitions.register(mShellTaskOrganizer);
        }
    }

    @ExternalThread
    private class InitImpl implements ShellInit {
        @Override
        public void init() {
            try {
                mMainExecutor.executeBlocking(() -> ShellInitImpl.this.init());
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to initialize the Shell in 2s", e);
            }
        }
    }
}
