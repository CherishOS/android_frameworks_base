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

package com.android.systemui.statusbar.car.privacy;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.Objects;

/**
 * Class to hold the data for the applications that are using the AppOps permissions.
 */
public class PrivacyApplication {
    private static final String TAG = "PrivacyApplication";

    private String mPackageName;
    private Drawable mIcon;
    private String mApplicationName;

    public PrivacyApplication(String packageName, Context context) {
        mPackageName = packageName;
        try {
            ApplicationInfo app = context.getPackageManager()
                    .getApplicationInfoAsUser(packageName, 0,
                            ActivityManager.getCurrentUser());
            mIcon = context.getPackageManager().getApplicationIcon(app);
            mApplicationName = context.getPackageManager().getApplicationLabel(app).toString();
        } catch (PackageManager.NameNotFoundException e) {
            mApplicationName = packageName;
            Log.e(TAG, "Failed to to find package name", e);
        }
    }

    /**
     * Gets the application name.
     */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Gets the application name.
     */
    public String getApplicationName() {
        return mApplicationName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrivacyApplication that = (PrivacyApplication) o;
        return mPackageName.equals(that.mPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName);
    }
}
