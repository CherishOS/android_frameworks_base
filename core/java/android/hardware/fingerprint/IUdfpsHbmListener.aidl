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
package android.hardware.fingerprint;

/**
 * A listener for the high-brightness mode (HBM) transitions. This allows other components to
 * perform certain actions when the HBM is toggled on or off. For example, a display manager
 * implementation can subscribe to these events from UdfpsController and adjust the display's
 * refresh rate when the HBM is enabled.
 *
 * @hide
 */
oneway interface IUdfpsHbmListener {

    /** HBM that applies to the whole screen. */
    const int GLOBAL_HBM = 0;

    /** HBM that only applies to a portion of the screen. */
    const int LOCAL_HBM = 1;

    /**
     * UdfpsController will call this method when the HBM is enabled.
     *
     * @param hbmType The type of HBM that was enabled. See
     *        {@link com.android.systemui.biometrics.UdfpsHbmTypes}.
     * @param displayId The displayId for which the HBM is enabled. See
     *        {@link android.view.Display#getDisplayId()}.
     */
    void onHbmEnabled(int hbmType, int displayId);

    /**
     * UdfpsController will call this method when the HBM is disabled.
     *
     * @param hbmType The type of HBM that was disabled. See
     *        {@link com.android.systemui.biometrics.UdfpsHbmTypes}.
     * @param displayId The displayId for which the HBM is disabled. See
     *        {@link android.view.Display#getDisplayId()}.
     */
    void onHbmDisabled(int hbmType, int displayId);
}

