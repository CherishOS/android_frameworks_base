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
 * limitations under the License
 */

package com.android.server.pm.dex;

import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;

import android.annotation.Nullable;

/**
 * Options used for dexopt invocations.
 */
public final class DexoptOptions {
    // When set, the profiles will be checked for updates before calling dexopt. If
    // the apps profiles didn't update in a meaningful way (decided by the compiler), dexopt
    // will be skipped.
    // Currently this only affects the optimization of primary apks. Secondary dex files
    // will always check the profiles for updates.
    public static final int DEXOPT_CHECK_FOR_PROFILES_UPDATES = 1 << 0;

    // When set, dexopt will execute unconditionally (even if not needed).
    public static final int DEXOPT_FORCE = 1 << 1;

    // Whether or not the invocation of dexopt is done after the boot is completed. This is used
    // in order to adjust the priority of the compilation thread.
    public static final int DEXOPT_BOOT_COMPLETE = 1 << 2;

    // When set, the dexopt invocation will optimize only the secondary dex files. If false, dexopt
    // will only consider the primary apk.
    public static final int DEXOPT_ONLY_SECONDARY_DEX = 1 << 3;

    // When set, dexopt will optimize only dex files that are used by other apps.
    // Currently, this flag is ignored for primary apks.
    public static final int DEXOPT_ONLY_SHARED_DEX = 1 << 4;

    // When set, dexopt will attempt to scale down the optimizations previously applied in order
    // save disk space.
    public static final int DEXOPT_DOWNGRADE = 1 << 5;

    // The name of package to optimize.
    private final String mPackageName;

    // The intended target compiler filter. Note that dexopt might adjust the filter before the
    // execution based on factors like: vmSafeMode and packageUsedByOtherApps.
    private final String mCompilerFilter;

    // The set of flags for the dexopt options. It's a mix of the DEXOPT_* flags.
    private final int mFlags;

    // When not null, dexopt will optimize only the split identified by this name.
    // It only applies for primary apk and it's always null if mOnlySecondaryDex is true.
    private final String mSplitName;

    public DexoptOptions(String packageName, String compilerFilter, int flags) {
        this(packageName, compilerFilter, /*splitName*/ null, flags);
    }

    public DexoptOptions(String packageName, int compilerReason, int flags) {
        this(packageName, getCompilerFilterForReason(compilerReason), flags);
    }

    public DexoptOptions(String packageName, String compilerFilter, String splitName, int flags) {
        int validityMask =
                DEXOPT_CHECK_FOR_PROFILES_UPDATES |
                DEXOPT_FORCE |
                DEXOPT_BOOT_COMPLETE |
                DEXOPT_ONLY_SECONDARY_DEX |
                DEXOPT_ONLY_SHARED_DEX |
                DEXOPT_DOWNGRADE;
        if ((flags & (~validityMask)) != 0) {
            throw new IllegalArgumentException("Invalid flags : " + Integer.toHexString(flags));
        }

        mPackageName = packageName;
        mCompilerFilter = compilerFilter;
        mFlags = flags;
        mSplitName = splitName;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public boolean isCheckForProfileUpdates() {
        return (mFlags & DEXOPT_CHECK_FOR_PROFILES_UPDATES) != 0;
    }

    public String getCompilerFilter() {
        return mCompilerFilter;
    }

    public boolean isForce() {
        return (mFlags & DEXOPT_FORCE) != 0;
    }

    public boolean isBootComplete() {
        return (mFlags & DEXOPT_BOOT_COMPLETE) != 0;
    }

    public boolean isDexoptOnlySecondaryDex() {
        return (mFlags & DEXOPT_ONLY_SECONDARY_DEX) != 0;
    }

    public boolean isDexoptOnlySharedDex() {
        return (mFlags & DEXOPT_ONLY_SHARED_DEX) != 0;
    }

    public boolean isDowngrade() {
        return (mFlags & DEXOPT_DOWNGRADE) != 0;
    }

    public String getSplitName() {
        return mSplitName;
    }
}
