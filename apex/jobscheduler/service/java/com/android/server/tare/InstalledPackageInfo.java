/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;

/** POJO to cache only the information about installed packages that TARE cares about. */
class InstalledPackageInfo {
    static final int NO_UID = -1;

    public final int uid;
    public final String packageName;
    public final boolean hasCode;

    InstalledPackageInfo(@NonNull PackageInfo packageInfo) {
        final ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        this.uid = applicationInfo == null ? NO_UID : applicationInfo.uid;
        this.packageName = packageInfo.packageName;
        this.hasCode = applicationInfo != null && applicationInfo.hasCode();
    }
}
