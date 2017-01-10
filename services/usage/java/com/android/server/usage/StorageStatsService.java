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

package com.android.server.usage;

import android.app.AppOpsManager;
import android.app.usage.ExternalStorageStats;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageStats;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

public class StorageStatsService extends IStorageStatsManager.Stub {
    private static final String TAG = "StorageStatsService";

    private static final String PROP_VERIFY_STORAGE = "fw.verify_storage";

    // TODO: pivot all methods to manual mode when quota isn't supported

    public static class Lifecycle extends SystemService {
        private StorageStatsService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new StorageStatsService(getContext());
            publishBinderService(Context.STORAGE_STATS_SERVICE, mService);
        }
    }

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final UserManager mUser;
    private final PackageManager mPackage;
    private final StorageManager mStorage;
    private final Installer mInstaller;

    public StorageStatsService(Context context) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mUser = context.getSystemService(UserManager.class);
        mPackage = context.getSystemService(PackageManager.class);
        mStorage = context.getSystemService(StorageManager.class);
        mInstaller = new Installer(context);
    }

    private void enforcePermission(int callingUid, String callingPackage) {
        final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                callingUid, callingPackage);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return;
            case AppOpsManager.MODE_DEFAULT:
                mContext.enforceCallingPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, TAG);
            default:
                throw new SecurityException("Blocked by mode " + mode);
        }
    }

    @Override
    public long getTotalBytes(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            // TODO: round total size to nearest power of two
            return mStorage.getPrimaryStorageSize();
        } else {
            final VolumeInfo vol = mStorage.findVolumeByUuid(volumeUuid);
            return vol.disk.size;
        }
    }

    @Override
    public long getFreeBytes(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        long cacheBytes = 0;
        for (UserInfo user : mUser.getUsers()) {
            final StorageStats stats = queryStatsForUser(volumeUuid, user.id, null);
            cacheBytes += stats.cacheBytes;
        }

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            return Environment.getDataDirectory().getFreeSpace() + cacheBytes;
        } else {
            final VolumeInfo vol = mStorage.findVolumeByUuid(volumeUuid);
            return vol.getPath().getFreeSpace() + cacheBytes;
        }
    }

    @Override
    public StorageStats queryStatsForUid(String volumeUuid, int uid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);
        if (UserHandle.getUserId(uid) != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        final int userId = UserHandle.getUserId(uid);
        final int appId = UserHandle.getUserId(uid);

        final String[] packageNames = mPackage.getPackagesForUid(uid);
        final long[] ceDataInodes = new long[packageNames.length];
        final String[] codePaths = new String[packageNames.length];

        for (int i = 0; i < packageNames.length; i++) {
            try {
                codePaths[i] = mPackage.getApplicationInfoAsUser(packageNames[i], 0,
                        userId).getCodePath();
            } catch (NameNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        final PackageStats stats = new PackageStats(TAG);
        try {
            mInstaller.getAppSize(volumeUuid, packageNames, userId, Installer.FLAG_USE_QUOTA,
                    appId, ceDataInodes, codePaths, stats);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final PackageStats manualStats = new PackageStats(TAG);
                mInstaller.getAppSize(volumeUuid, packageNames, userId, 0,
                        appId, ceDataInodes, codePaths, manualStats);
                checkEquals("UID " + uid, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new IllegalStateException(e);
        }
        return translate(stats);
    }

    @Override
    public StorageStats queryStatsForUser(String volumeUuid, int userId, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        int[] appIds = null;
        for (ApplicationInfo app : mPackage.getInstalledApplicationsAsUser(0, userId)) {
            final int appId = UserHandle.getAppId(app.uid);
            if (!ArrayUtils.contains(appIds, appId)) {
                appIds = ArrayUtils.appendInt(appIds, appId);
            }
        }

        final PackageStats stats = new PackageStats(TAG);
        try {
            mInstaller.getUserSize(volumeUuid, userId, Installer.FLAG_USE_QUOTA, appIds, stats);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final PackageStats manualStats = new PackageStats(TAG);
                mInstaller.getUserSize(volumeUuid, userId, 0, appIds, manualStats);
                checkEquals("User " + userId, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new IllegalStateException(e);
        }
        return translate(stats);
    }

    @Override
    public ExternalStorageStats queryExternalStatsForUser(String volumeUuid, int userId,
            String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        final long[] stats;
        try {
            stats = mInstaller.getExternalSize(volumeUuid, userId, Installer.FLAG_USE_QUOTA);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final long[] manualStats = mInstaller.getExternalSize(volumeUuid, userId, 0);
                checkEquals("External " + userId, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new IllegalStateException(e);
        }

        final ExternalStorageStats res = new ExternalStorageStats();
        res.totalBytes = stats[0];
        res.audioBytes = stats[1];
        res.videoBytes = stats[2];
        res.imageBytes = stats[3];
        return res;
    }

    private static void checkEquals(String msg, long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            checkEquals(msg + "[" + i + "]", a[i], b[i]);
        }
    }

    private static void checkEquals(String msg, PackageStats a, PackageStats b) {
        checkEquals(msg + " codeSize", a.codeSize, b.codeSize);
        checkEquals(msg + " dataSize", a.dataSize, b.dataSize);
        checkEquals(msg + " cacheSize", a.cacheSize, b.cacheSize);
        checkEquals(msg + " externalCodeSize", a.externalCodeSize, b.externalCodeSize);
        checkEquals(msg + " externalDataSize", a.externalDataSize, b.externalDataSize);
        checkEquals(msg + " externalCacheSize", a.externalCacheSize, b.externalCacheSize);
    }

    private static void checkEquals(String msg, long expected, long actual) {
        if (expected != actual) {
            Log.e(TAG, msg + " expected " + expected + " actual " + actual);
        }
    }

    private static StorageStats translate(PackageStats stats) {
        final StorageStats res = new StorageStats();
        res.codeBytes = stats.codeSize + stats.externalCodeSize;
        res.dataBytes = stats.dataSize + stats.externalDataSize;
        res.cacheBytes = stats.cacheSize + stats.externalCacheSize;
        return res;
    }
}
