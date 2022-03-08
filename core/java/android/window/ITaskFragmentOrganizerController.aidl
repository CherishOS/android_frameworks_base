/**
 * Copyright (c) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.window;

import android.view.RemoteAnimationDefinition;
import android.window.ITaskFragmentOrganizer;

/** @hide */
interface ITaskFragmentOrganizerController {

    /**
     * Registers a TaskFragmentOrganizer to manage TaskFragments.
     */
    void registerOrganizer(in ITaskFragmentOrganizer organizer);

    /**
     * Unregisters a previously registered TaskFragmentOrganizer.
     */
    void unregisterOrganizer(in ITaskFragmentOrganizer organizer);

    /**
     * Registers remote animations per transition type for the organizer. It will override the
     * animations if the transition only contains windows that belong to the organized
     * TaskFragments.
     */
    void registerRemoteAnimations(in ITaskFragmentOrganizer organizer,
        in RemoteAnimationDefinition definition);

    /**
     * Unregisters remote animations per transition type for the organizer.
     */
    void unregisterRemoteAnimations(in ITaskFragmentOrganizer organizer);

    /**
      * Checks if an activity organized by a {@link android.window.TaskFragmentOrganizer} and
      * only occupies a portion of Task bounds.
      */
    boolean isActivityEmbedded(in IBinder activityToken);
}
