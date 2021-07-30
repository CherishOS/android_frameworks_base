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

package com.android.server.pm;

import android.content.pm.IPackageLoadingProgressCallback;

/**
 * Loading progress callback, used to listen for progress changes and update package setting
 */
final class IncrementalProgressListener extends IPackageLoadingProgressCallback.Stub {
    private final String mPackageName;
    private final PackageManagerService mPm;
    IncrementalProgressListener(String packageName, PackageManagerService pm) {
        mPackageName = packageName;
        mPm = pm;
    }

    @Override
    public void onPackageLoadingProgressChanged(float progress) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(mPackageName);
            if (ps == null) {
                return;
            }
            ps.setLoadingProgress(progress);
        }
    }
}
