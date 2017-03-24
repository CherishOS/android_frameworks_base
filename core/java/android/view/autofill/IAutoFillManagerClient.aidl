/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view.autofill;

import java.util.List;

import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;

/**
 * Object running in the application process and responsible for autofilling it.
 *
 * @hide
 */
oneway interface IAutoFillManagerClient {
    /**
     * Notifies the client when the autofill enabled state changed.
     */
    void setState(boolean enabled);

    /**
      * Autofills the activity with the contents of a dataset.
      */
    void autofill(in IBinder windowToken, in List<AutofillId> ids, in List<AutofillValue> values);

    /**
      * Authenticates a fill response or a data set.
      */
    void authenticate(in IntentSender intent, in Intent fillInIntent);

    /**
     * Requests showing the fill UI.
     */
    void requestShowFillUi(in IBinder windowToken, in AutofillId id, int width,
            int height, in Rect anchorBounds, in IAutofillWindowPresenter presenter);

    /**
     * Requests hiding the fill UI.
     */
    void requestHideFillUi(in IBinder windowToken, in AutofillId id);

    /**
     * Nitifies no fill UI will be shown.
     */
    void notifyNoFillUi(in IBinder windowToken, in AutofillId id);
}
