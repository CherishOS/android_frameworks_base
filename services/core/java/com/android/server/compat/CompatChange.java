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

package com.android.server.compat;

import android.annotation.Nullable;
import android.compat.annotation.EnabledAfter;
import android.content.pm.ApplicationInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the state of a single compatibility change.
 *
 * <p>A compatibility change has a default setting, determined by the {@code enableAfterTargetSdk}
 * and {@code disabled} constructor parameters. If a change is {@code disabled}, this overrides any
 * target SDK criteria set. These settings can be overridden for a specific package using
 * {@link #addPackageOverride(String, boolean)}.
 *
 * <p>Note, this class is not thread safe so callers must ensure thread safety.
 */
public final class CompatChange {

    private final long mChangeId;
    @Nullable private final String mName;
    private final int mEnableAfterTargetSdk;
    private final boolean mDisabled;
    private Map<String, Boolean> mPackageOverrides;

    public CompatChange(long changeId) {
        this(changeId, null, -1, false);
    }

    /**
     * @param changeId Unique ID for the change. See {@link android.compat.Compatibility}.
     * @param name Short descriptive name.
     * @param enableAfterTargetSdk {@code targetSdkVersion} restriction. See {@link EnabledAfter};
     *                             -1 if the change is always enabled.
     * @param disabled If {@code true}, overrides any {@code enableAfterTargetSdk} set.
     */
    public CompatChange(long changeId, @Nullable String name, int enableAfterTargetSdk,
            boolean disabled) {
        mChangeId = changeId;
        mName = name;
        mEnableAfterTargetSdk = enableAfterTargetSdk;
        mDisabled = disabled;
    }

    long getId() {
        return mChangeId;
    }

    @Nullable
    String getName() {
        return mName;
    }

    /**
     * Force the enabled state of this change for a given package name. The change will only take
     * effect after that packages process is killed and restarted.
     *
     * <p>Note, this method is not thread safe so callers must ensure thread safety.
     *
     * @param pname Package name to enable the change for.
     * @param enabled Whether or not to enable the change.
     */
    void addPackageOverride(String pname, boolean enabled) {
        if (mPackageOverrides == null) {
            mPackageOverrides = new HashMap<>();
        }
        mPackageOverrides.put(pname, enabled);
    }

    /**
     * Remove any package override for the given package name, restoring the default behaviour.
     *
     * <p>Note, this method is not thread safe so callers must ensure thread safety.
     *
     * @param pname Package name to reset to defaults for.
     */
    void removePackageOverride(String pname) {
        if (mPackageOverrides != null) {
            mPackageOverrides.remove(pname);
        }
    }

    /**
     * Find if this change is enabled for the given package, taking into account any overrides that
     * exist.
     *
     * @param app Info about the app in question
     * @return {@code true} if the change should be enabled for the package.
     */
    boolean isEnabled(ApplicationInfo app) {
        if (app.isSystemApp()) {
            // All changes are enabled for system apps, and we do not support overrides.
            // Compatibility issues for system apps should be addressed in the app itself when
            // the compatibility change is made.
            return true;
        }
        if (mPackageOverrides != null && mPackageOverrides.containsKey(app.packageName)) {
            return mPackageOverrides.get(app.packageName);
        }
        if (mDisabled) {
            return false;
        }
        if (mEnableAfterTargetSdk != -1) {
            return app.targetSdkVersion > mEnableAfterTargetSdk;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ChangeId(")
                .append(mChangeId);
        if (mName != null) {
            sb.append("; name=").append(mName);
        }
        if (mEnableAfterTargetSdk != -1) {
            sb.append("; enableAfterTargetSdk=").append(mEnableAfterTargetSdk);
        }
        if (mDisabled) {
            sb.append("; disabled");
        }
        if (mPackageOverrides != null && mPackageOverrides.size() > 0) {
            sb.append("; packageOverrides=").append(mPackageOverrides);
        }
        return sb.append(")").toString();
    }
}
