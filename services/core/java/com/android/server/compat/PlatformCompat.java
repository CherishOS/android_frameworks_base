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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat extends IPlatformCompat.Stub {

    private static final String TAG = "Compatibility";

    private final Context mContext;
    private final ChangeReporter mChangeReporter;

    public PlatformCompat(Context context) {
        mContext = context;
        mChangeReporter = new ChangeReporter(
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__SOURCE__SYSTEM_SERVER);
    }

    @Override
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        reportChange(changeId, appInfo.uid,
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED);
    }

    @Override
    public void reportChangeByPackageName(long changeId, String packageName) {
        ApplicationInfo appInfo = getApplicationInfo(packageName);
        if (appInfo == null) {
            return;
        }
        reportChange(changeId, appInfo);
    }

    @Override
    public void reportChangeByUid(long changeId, int uid) {
        reportChange(changeId, uid, StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED);
    }

    @Override
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        if (CompatConfig.get().isChangeEnabled(changeId, appInfo)) {
            reportChange(changeId, appInfo.uid,
                    StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__ENABLED);
            return true;
        }
        reportChange(changeId, appInfo.uid,
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__DISABLED);
        return false;
    }

    @Override
    public boolean isChangeEnabledByPackageName(long changeId, String packageName) {
        ApplicationInfo appInfo = getApplicationInfo(packageName);
        if (appInfo == null) {
            return true;
        }
        return isChangeEnabled(changeId, appInfo);
    }

    @Override
    public boolean isChangeEnabledByUid(long changeId, int uid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return true;
        }
        boolean enabled = true;
        for (String packageName : packages) {
            enabled = enabled && isChangeEnabledByPackageName(changeId, packageName);
        }
        return enabled;
    }

    @Override
    public void setOverrides(CompatibilityChangeConfig overrides, String packageName) {
        CompatConfig.get().addOverrides(overrides, packageName);
    }

    @Override
    public void clearOverrides(String packageName) {
        CompatConfig config = CompatConfig.get();
        config.removePackageOverrides(packageName);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, "platform_compat", pw)) return;
        CompatConfig.get().dumpConfig(pw);
    }

    /**
     * Clears information stored about events reported on behalf of an app.
     * To be called once upon app start or end. A second call would be a no-op.
     * @param appInfo the app to reset
     */
    public void resetReporting(ApplicationInfo appInfo) {
        mChangeReporter.resetReportedChanges(appInfo.uid);
    }

    private ApplicationInfo getApplicationInfo(String packageName) {
        try {
            return mContext.getPackageManager().getApplicationInfoAsUser(packageName, 0,
                    Process.myUid());
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "No installed package " + packageName);
        }
        return null;
    }

    private void reportChange(long changeId, int uid, int state) {
        mChangeReporter.reportChange(uid, changeId, state);
    }
}
