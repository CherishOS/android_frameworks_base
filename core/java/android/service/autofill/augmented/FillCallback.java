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
 * limitations under the License.
 */
package android.service.autofill.augmented;

import static android.service.autofill.augmented.AugmentedAutofillService.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.util.Log;

/**
 * Callback used to indicate at {@link FillRequest} has been fulfilled.
 *
 * @hide
 */
@SystemApi
public final class FillCallback {

    private static final String TAG = FillCallback.class.getSimpleName();

    private final AutofillProxy mProxy;

    FillCallback(@NonNull AutofillProxy proxy) {
        mProxy = proxy;
    }

    /**
     * Sets the response associated with the request.
     *
     * @param response response associated with the request, or {@code null} if the service
     * could not provide autofill for the request.
     */
    public void onSuccess(@Nullable FillResponse response) {
        if (DEBUG) Log.d(TAG, "onSuccess(): " + response);

        mProxy.report(AutofillProxy.REPORT_EVENT_ON_SUCCESS);
        if (response == null) return;

        final FillWindow fillWindow = response.getFillWindow();
        if (fillWindow != null) {
            fillWindow.show();
        }
        // TODO(b/111330312): properly implement on server-side by updating the Session state
        // accordingly (and adding CTS tests)
    }
}
