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

package com.android.wm.shell.pip;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.media.session.MediaController;

import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.pip.tv.PipController;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Interface to engage picture in picture feature.
 */
public interface Pip {
    /**
     * Registers {@link com.android.wm.shell.pip.tv.PipController.Listener} that gets called.
     * whenever receiving notification on changes in PIP.
     */
    default void addListener(PipController.Listener listener) {
    }

    /**
     * Registers a {@link PipController.MediaListener} to PipController.
     */
    default void addMediaListener(PipController.MediaListener listener) {
    }

    /**
     * Closes PIP (PIPed activity and PIP system UI).
     */
    default void closePip() {
    }

    /**
     * Dump the current state and information if need.
     *
     * @param pw The stream to dump information to.
     */
    default void dump(PrintWriter pw) {
    }

    /**
     * Expand PIP, it's possible that specific request to activate the window via Alt-tab.
     */
    default void expandPip() {
    }

    /**
     * Get current play back state. (e.g: Used in TV)
     *
     * @return The state of defined in PipController.
     */
    default int getPlaybackState() {
        return -1;
    }

    /**
     * Get the touch handler which manages all the touch handling for PIP on the Phone,
     * including moving, dismissing and expanding the PIP. (Do not used in TV)
     *
     * @return
     */
    default @Nullable PipTouchHandler getPipTouchHandler() {
        return null;
    }

    /**
     * Get MediaController.
     *
     * @return The MediaController instance.
     */
    default MediaController getMediaController() {
        return null;
    }

    /**
     * Hides the PIP menu.
     */
    void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback);

    /**
     * Returns {@code true} if PIP is shown.
     */
    default boolean isPipShown() {
        return false;
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    default void movePipToFullscreen() {
    }

    /**
     * Called whenever an Activity is moved to the pinned stack from another stack.
     */
    default void onActivityPinned(String packageName) {
    }

    /**
     * Called whenever an Activity is moved from the pinned stack to another stack
     */
    default void onActivityUnpinned(ComponentName topActivity) {
    }

    /**
     * Called whenever IActivityManager.startActivity is called on an activity that is already
     * running, but the task is either brought to the front or a new Intent is delivered to it.
     *
     * @param task        information about the task the activity was relaunched into
     * @param clearedTask whether or not the launch activity also cleared the task as a part of
     *                    starting
     */
    default void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
            boolean clearedTask) {
    }

    /**
     * Called when display size or font size of settings changed
     */
    default void onDensityOrFontScaleChanged() {
    }

    /**
     * Called when overlay package change invoked.
     */
    default void onOverlayChanged() {
    }

    /**
     * Registers the session listener for the current user.
     */
    default void registerSessionListenerForCurrentUser() {
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI state valid or not.
     * @param flag Current SysUI state.
     */
    default void onSystemUiStateChanged(boolean isSysUiStateValid, int flag) {
    }

    /**
     * Called when task stack changed.
     */
    default void onTaskStackChanged() {
    }

    /**
     * Removes a {@link PipController.Listener} from PipController.
     */
    default void removeListener(PipController.Listener listener) {
    }

    /**
     * Removes a {@link PipController.MediaListener} from PipController.
     */
    default void removeMediaListener(PipController.MediaListener listener) {
    }

    /**
     * Resize the Pip to the appropriate size for the input state.
     *
     * @param state In Pip state also used to determine the new size for the Pip.
     */
    default void resizePinnedStack(int state) {
    }

    /**
     * Resumes resizing operation on the Pip that was previously suspended.
     *
     * @param reason The reason resizing operations on the Pip was suspended.
     */
    default void resumePipResizing(int reason) {
    }

    /**
     * Sets both shelf visibility and its height.
     *
     * @param visible visibility of shelf.
     * @param height  to specify the height for shelf.
     */
    default void setShelfHeight(boolean visible, int height) {
    }

    /**
     * Registers the pinned stack animation listener.
     *
     * @param callback The callback of pinned stack animation.
     */
    default void setPinnedStackAnimationListener(Consumer<Boolean> callback) {
    }

    /**
     * Set the pinned stack with {@link PipAnimationController.AnimationType}
     *
     * @param animationType The pre-defined {@link PipAnimationController.AnimationType}
     */
    default void setPinnedStackAnimationType(int animationType) {
    }

    /**
     * Called when showing Pip menu.
     */
    void showPictureInPictureMenu();

    /**
     * Suspends resizing operation on the Pip until {@link #resumePipResizing} is called.
     *
     * @param reason The reason for suspending resizing operations on the Pip.
     */
    default void suspendPipResizing(int reason) {
    }

    /**
     * Called by Launcher when swiping an auto-pip enabled Activity to home starts
     * @param componentName {@link ComponentName} represents the Activity entering PiP
     * @param activityInfo {@link ActivityInfo} tied to the Activity
     * @param pictureInPictureParams {@link PictureInPictureParams} tied to the Activity
     * @param launcherRotation Rotation Launcher is in
     * @param shelfHeight Shelf height when landing PiP window onto Launcher
     * @return Destination bounds of PiP window based on the parameters passed in
     */
    default Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams,
            int launcherRotation, int shelfHeight) {
        return null;
    }

    /**
     * Called by Launcher when swiping an auto-pip enable Activity to home finishes
     * @param componentName {@link ComponentName} represents the Activity entering PiP
     * @param destinationBounds Destination bounds of PiP window
     */
    default void stopSwipePipToHome(ComponentName componentName, Rect destinationBounds) {
        return;
    }
}
