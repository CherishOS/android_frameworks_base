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

package com.android.systemui.chooser;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.systemui.R;

public class ChooserHelper {

    private static final String TAG = "ChooserHelper";

    static void onChoose(Activity activity) {
        final Intent thisIntent = activity.getIntent();
        final Bundle thisExtras = thisIntent.getExtras();
        final Intent chosenIntent = thisIntent.getParcelableExtra(Intent.EXTRA_INTENT);
        final Bundle options = thisIntent.getParcelableExtra(ActivityManager.EXTRA_OPTIONS);
        final IBinder permissionToken =
                thisExtras.getBinder(ActivityManager.EXTRA_PERMISSION_TOKEN);
        final boolean ignoreTargetSecurity =
                thisIntent.getBooleanExtra(ActivityManager.EXTRA_IGNORE_TARGET_SECURITY, false);
        final int userId = thisIntent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        activity.startActivityAsCaller(
                chosenIntent, options, permissionToken, ignoreTargetSecurity, userId);
    }
}
