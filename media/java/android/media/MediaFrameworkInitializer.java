/*
 * Copyright 2020 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Class for performing registration for all media services
 *
 * TODO (b/160513103): Move this class when moving media service code to APEX
 * @hide
 */
public class MediaFrameworkInitializer {
    private MediaFrameworkInitializer() {
    }

    private static volatile MediaServiceManager sMediaServiceManager;

    /**
     * Sets an instance of {@link MediaServiceManager} that allows
     * the media mainline module to register/obtain media binder services. This is called
     * by the platform during the system initialization.
     *
     * @param mediaServiceManager instance of {@link MediaServiceManager} that allows
     * the media mainline module to register/obtain media binder services.
     */
    public static void setMediaServiceManager(
            @NonNull MediaServiceManager mediaServiceManager) {
        Preconditions.checkState(sMediaServiceManager == null,
                "setMediaServiceManager called twice!");
        sMediaServiceManager = Objects.requireNonNull(mediaServiceManager);
    }

    /** @hide */
    public static MediaServiceManager getMediaServiceManager() {
        return sMediaServiceManager;
    }
}
