/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.biometrics;

/**
 * Callback interface for dialog views. These should be implemented by the controller (e.g.
 * FingerprintDialogImpl) and passed into their views (e.g. FingerprintDialogView).
 */
public interface DialogViewCallback {

    int DISMISSED_USER_CANCELED = 1;
    int DISMISSED_BUTTON_NEGATIVE = 2;
    int DISMISSED_BUTTON_POSITIVE = 3;

    int DISMISSED_AUTHENTICATED = 4;
    int DISMISSED_ERROR = 5;

    /**
     * Invoked when the dialog is dismissed
     * @param reason
     */
    void onDismissed(int reason);

    /**
     * Invoked when the "try again" button is clicked
     */
    void onTryAgainPressed();
}
