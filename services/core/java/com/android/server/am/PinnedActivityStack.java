/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.app.RemoteAction;
import android.graphics.Rect;

import com.android.server.am.ActivityStackSupervisor.ActivityContainer;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.PinnedStackWindowListener;
import com.android.server.wm.StackWindowController;

import java.util.ArrayList;
import java.util.List;

/**
 * State and management of the pinned stack of activities.
 */
class PinnedActivityStack extends ActivityStack<PinnedStackWindowController>
        implements PinnedStackWindowListener {

    PinnedActivityStack(ActivityContainer activityContainer,
            RecentTasks recentTasks, boolean onTop) {
        super(activityContainer, recentTasks, onTop);
    }

    @Override
    PinnedStackWindowController createStackWindowController(int displayId, boolean onTop,
            Rect outBounds) {
        return new PinnedStackWindowController(mStackId, this, displayId, onTop, outBounds);
    }

    void animateResizePinnedStack(Rect sourceBounds, Rect destBounds, int animationDuration) {
        getWindowContainerController().animateResizePinnedStack(sourceBounds, destBounds,
                animationDuration);
    }

    void setPictureInPictureAspectRatio(float aspectRatio) {
        getWindowContainerController().setPictureInPictureAspectRatio(aspectRatio);
    }

    void setPictureInPictureActions(List<RemoteAction> actions) {
        getWindowContainerController().setPictureInPictureActions(actions);
    }

    boolean isBoundsAnimatingToFullscreen() {
        return getWindowContainerController().isBoundsAnimatingToFullscreen();
    }

    @Override
    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds) {
        // It is guaranteed that the activities requiring the update will be in the pinned stack at
        // this point (either reparented before the animation into PiP, or before reparenting after
        // the animation out of PiP)
        synchronized(this) {
            ArrayList<TaskRecord> tasks = getAllTasks();
            for (int i = 0; i < tasks.size(); i++ ) {
                mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(tasks.get(i),
                        targetStackBounds, true /* immediate */);
            }
        }
    }
}
