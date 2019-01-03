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

package com.android.server.rollback;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.StringParceledListSlice;
import android.content.rollback.IRollbackManager;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerServiceUtils;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of service that manages APK level rollbacks.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub {

    private static final String TAG = "RollbackManager";

    // Rollbacks expire after 48 hours.
    // TODO: How to test rollback expiration works properly?
    private static final long ROLLBACK_LIFETIME_DURATION_MILLIS = 48 * 60 * 60 * 1000;

    // Lock used to synchronize accesses to in-memory rollback data
    // structures. By convention, methods with the suffix "Locked" require
    // mLock is held when they are called.
    private final Object mLock = new Object();

    // Package rollback data available to be used for rolling back a package.
    // This list is null until the rollback data has been loaded.
    @GuardedBy("mLock")
    private List<PackageRollbackData> mAvailableRollbacks;

    // The list of recently executed rollbacks.
    // This list is null until the rollback data has been loaded.
    @GuardedBy("mLock")
    private List<RollbackInfo> mRecentlyExecutedRollbacks;

    // Data for available rollbacks and recently executed rollbacks is
    // persisted in storage. Assuming the rollback data directory is
    // /data/rollback, we use the following directory structure
    // to store this data:
    //   /data/rollback/
    //      available/
    //          com.package.A-XXX/
    //              base.apk
    //              rollback.json
    //          com.package.B-YYY/
    //              base.apk
    //              rollback.json
    //      recently_executed.json
    // TODO: Use AtomicFile for rollback.json and recently_executed.json.
    private final File mRollbackDataDir;
    private final File mAvailableRollbacksDir;
    private final File mRecentlyExecutedRollbacksFile;

    private final Context mContext;
    private final HandlerThread mHandlerThread;

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();

        mRollbackDataDir = new File(Environment.getDataDirectory(), "rollback");
        mAvailableRollbacksDir = new File(mRollbackDataDir, "available");
        mRecentlyExecutedRollbacksFile = new File(mRollbackDataDir, "recently_executed.json");

        // Kick off loading of the rollback data from strorage in a background
        // thread.
        // TODO: Consider loading the rollback data directly here instead, to
        // avoid the need to call ensureRollbackDataLoaded every time before
        // accessing the rollback data?
        // TODO: Test that this kicks off initial scheduling of rollback
        // expiration.
        getHandler().post(() -> ensureRollbackDataLoaded());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    onPackageReplaced(packageName);
                }
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    onPackageFullyRemoved(packageName);
                }
            }
        }, filter, null, getHandler());

        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction(Intent.ACTION_PACKAGE_ENABLE_ROLLBACK);
        try {
            enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.e(TAG, "addDataType", e);
        }

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_PACKAGE_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int token = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN, -1);
                    int installFlags = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_INSTALL_FLAGS, 0);
                    File newPackageCodePath = new File(intent.getData().getPath());

                    getHandler().post(() -> {
                        boolean success = enableRollback(installFlags, newPackageCodePath);
                        int ret = PackageManagerInternal.ENABLE_ROLLBACK_SUCCEEDED;
                        if (!success) {
                            ret = PackageManagerInternal.ENABLE_ROLLBACK_FAILED;
                        }

                        PackageManagerInternal pm = LocalServices.getService(
                                PackageManagerInternal.class);
                        pm.setEnableRollbackCode(token, ret);
                    });

                    // We're handling the ordered broadcast. Abort the
                    // broadcast because there is no need for it to go to
                    // anyone else.
                    abortBroadcast();
                }
            }
        }, enableRollbackFilter, null, getHandler());
    }

    @Override
    public RollbackInfo getAvailableRollback(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getAvailableRollback");

        PackageRollbackInfo.PackageVersion installedVersion =
                getInstalledPackageVersion(packageName);
        if (installedVersion == null) {
            return null;
        }

        synchronized (mLock) {
            // TODO: Have ensureRollbackDataLoadedLocked return the list of
            // available rollbacks, to hopefully avoid forgetting to call it?
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                PackageRollbackData data = mAvailableRollbacks.get(i);
                if (data.info.packageName.equals(packageName)
                        && data.info.higherVersion.equals(installedVersion)) {
                    // TODO: For atomic installs, check all dependent packages
                    // for available rollbacks and include that info here.
                    return new RollbackInfo(data.info);
                }
            }
        }

        return null;
    }

    @Override
    public StringParceledListSlice getPackagesWithAvailableRollbacks() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getPackagesWithAvailableRollbacks");

        // TODO: This may return packages whose rollback is out of date or
        // expired.  Presumably that's okay because the package rollback could
        // be expired anyway between when the caller calls this method and
        // when the caller calls getAvailableRollback for more details.
        final Set<String> packageNames = new HashSet<>();
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                PackageRollbackData data = mAvailableRollbacks.get(i);
                packageNames.add(data.info.packageName);
            }
        }
        return new StringParceledListSlice(new ArrayList<>(packageNames));
    }

    @Override
    public ParceledListSlice<RollbackInfo> getRecentlyExecutedRollbacks() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getRecentlyExecutedRollbacks");

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>(mRecentlyExecutedRollbacks);
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public void executeRollback(RollbackInfo rollback, String callerPackageName,
            IntentSender statusReceiver) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "executeRollback");

        final int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(callingUid, callerPackageName);

        getHandler().post(() ->
                executeRollbackInternal(rollback, callerPackageName, statusReceiver));
    }

    /**
     * Performs the actual work to execute a rollback.
     * The work is done on the current thread. This may be a long running
     * operation.
     */
    private void executeRollbackInternal(RollbackInfo rollback,
            String callerPackageName, IntentSender statusReceiver) {
        String packageName = rollback.targetPackage.packageName;
        Log.i(TAG, "Initiating rollback of " + packageName);

        PackageRollbackInfo.PackageVersion installedVersion =
                getInstalledPackageVersion(packageName);
        if (installedVersion == null) {
            // TODO: Test this case
            sendFailure(statusReceiver, "Target package to roll back is not installed");
            return;
        }

        if (!rollback.targetPackage.higherVersion.equals(installedVersion)) {
            // TODO: Test this case
            sendFailure(statusReceiver, "Target package version to roll back not installed.");
            return;
        }

        // TODO: We assume that between now and the time we commit the
        // downgrade install, the currently installed package version does not
        // change. This is not safe to assume, particularly in the case of a
        // rollback racing with a roll-forward fix of a buggy package.
        // Figure out how to ensure we don't commit the rollback if
        // roll forward happens at the same time.
        PackageRollbackData data = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                PackageRollbackData available = mAvailableRollbacks.get(i);
                // TODO: Check if available.info.lowerVersion matches
                // rollback.targetPackage.lowerVersion?
                if (available.info.packageName.equals(packageName)
                        && available.info.higherVersion.equals(installedVersion)) {
                    data = available;
                    break;
                }
            }
        }

        if (data == null) {
            sendFailure(statusReceiver, "Rollback not available");
            return;
        }

        // Get a context for the caller to use to install the downgraded
        // version of the package.
        Context context = null;
        try {
            context = mContext.createPackageContext(callerPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            sendFailure(statusReceiver, "Invalid callerPackageName");
            return;
        }

        PackageManager pm = context.getPackageManager();
        try {
            PackageInstaller.Session session = null;

            PackageInstaller packageInstaller = pm.getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAllowDowngrade(true);
            int sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            // TODO: Will it always be called "base.apk"? What about splits?
            File baseApk = new File(data.backupDir, "base.apk");
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(baseApk,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                final long token = Binder.clearCallingIdentity();
                try {
                    session.write("base.apk", 0, baseApk.length(), fd);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            Intent result = receiver.getResult();
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status != PackageInstaller.STATUS_SUCCESS) {
                sendFailure(statusReceiver, "Rollback downgrade install failed: "
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                return;
            }

            addRecentlyExecutedRollback(rollback);
            sendSuccess(statusReceiver);

            Intent broadcast = new Intent(Intent.ACTION_PACKAGE_ROLLBACK_EXECUTED,
                    Uri.fromParts("package", packageName, Manifest.permission.MANAGE_ROLLBACKS));

            // TODO: This call emits the warning "Calling a method in the
            // system process without a qualified user". Fix that.
            mContext.sendBroadcast(broadcast);
        } catch (IOException e) {
            Log.e(TAG, "Unable to roll back " + packageName, e);
            sendFailure(statusReceiver, "IOException: " + e.toString());
            return;
        }
    }

    @Override
    public void reloadPersistedData() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "reloadPersistedData");

        synchronized (mLock) {
            mAvailableRollbacks = null;
            mRecentlyExecutedRollbacks = null;
        }
        getHandler().post(() -> ensureRollbackDataLoaded());
    }

    @Override
    public void expireRollbackForPackage(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "expireRollbackForPackage");

        // TODO: Should this take a package version number in addition to
        // package name? For now, just remove all rollbacks matching the
        // package name. This method is only currently used to facilitate
        // testing anyway.
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<PackageRollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                PackageRollbackData data = iter.next();
                if (data.info.packageName.equals(packageName)) {
                    iter.remove();
                    removeFile(data.backupDir);
                }
            }
        }
    }

    /**
     * Load rollback data from storage if it has not already been loaded.
     * After calling this funciton, mAvailableRollbacks and
     * mRecentlyExecutedRollbacks will be non-null.
     */
    private void ensureRollbackDataLoaded() {
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
        }
    }

    /**
     * Load rollback data from storage if it has not already been loaded.
     * After calling this function, mAvailableRollbacks and
     * mRecentlyExecutedRollbacks will be non-null.
     */
    @GuardedBy("mLock")
    private void ensureRollbackDataLoadedLocked() {
        if (mAvailableRollbacks == null) {
            loadRollbackDataLocked();
        }
    }

    /**
     * Load rollback data from storage.
     * Note: We do potentially heavy IO here while holding mLock, because we
     * have to have the rollback data loaded before we can do anything else
     * meaningful.
     */
    @GuardedBy("mLock")
    private void loadRollbackDataLocked() {
        mAvailableRollbacksDir.mkdirs();
        mAvailableRollbacks = new ArrayList<>();
        for (File rollbackDir : mAvailableRollbacksDir.listFiles()) {
            if (rollbackDir.isDirectory()) {
                // TODO: How to detect and clean up an invalid rollback
                // directory? We don't know if it's invalid because something
                // went wrong, or if it's only temporarily invalid because
                // it's in the process of being created.
                try {
                    File jsonFile = new File(rollbackDir, "rollback.json");
                    String jsonString = IoUtils.readFileAsString(jsonFile.getAbsolutePath());
                    JSONObject jsonObject = new JSONObject(jsonString);
                    String packageName = jsonObject.getString("packageName");
                    long higherVersionCode = jsonObject.getLong("higherVersionCode");
                    long lowerVersionCode = jsonObject.getLong("lowerVersionCode");
                    Instant timestamp = Instant.parse(jsonObject.getString("timestamp"));
                    PackageRollbackData data = new PackageRollbackData(
                            new PackageRollbackInfo(packageName,
                                new PackageRollbackInfo.PackageVersion(higherVersionCode),
                                new PackageRollbackInfo.PackageVersion(lowerVersionCode)),
                            rollbackDir, timestamp);
                    mAvailableRollbacks.add(data);
                } catch (IOException | JSONException | DateTimeParseException e) {
                    Log.e(TAG, "Unable to read rollback data at " + rollbackDir, e);
                }
            }
        }

        mRecentlyExecutedRollbacks = new ArrayList<>();
        if (mRecentlyExecutedRollbacksFile.exists()) {
            try {
                // TODO: How to cope with changes to the format of this file from
                // when RollbackStore is updated in the future?
                String jsonString = IoUtils.readFileAsString(
                        mRecentlyExecutedRollbacksFile.getAbsolutePath());
                JSONObject object = new JSONObject(jsonString);
                JSONArray array = object.getJSONArray("recentlyExecuted");
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject element = array.getJSONObject(i);
                    String packageName = element.getString("packageName");
                    long higherVersionCode = element.getLong("higherVersionCode");
                    long lowerVersionCode = element.getLong("lowerVersionCode");
                    PackageRollbackInfo target = new PackageRollbackInfo(packageName,
                            new PackageRollbackInfo.PackageVersion(higherVersionCode),
                            new PackageRollbackInfo.PackageVersion(lowerVersionCode));
                    RollbackInfo rollback = new RollbackInfo(target);
                    mRecentlyExecutedRollbacks.add(rollback);
                }
            } catch (IOException | JSONException e) {
                // TODO: What to do here? Surely we shouldn't just forget about
                // everything after the point of exception?
                Log.e(TAG, "Failed to read recently executed rollbacks", e);
            }
        }

        scheduleExpiration(0);
    }

    /**
     * Called when a package has been replaced with a different version.
     * Removes all backups for the package not matching the currently
     * installed package version.
     */
    private void onPackageReplaced(String packageName) {
        // TODO: Could this end up incorrectly deleting a rollback for a
        // package that is about to be installed?
        PackageRollbackInfo.PackageVersion installedVersion =
                getInstalledPackageVersion(packageName);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<PackageRollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                PackageRollbackData data = iter.next();
                if (data.info.packageName.equals(packageName)
                        && !data.info.higherVersion.equals(installedVersion)) {
                    iter.remove();
                    removeFile(data.backupDir);
                }
            }
        }
    }

    /**
     * Called when a package has been completely removed from the device.
     * Removes all backups and rollback history for the given package.
     */
    private void onPackageFullyRemoved(String packageName) {
        expireRollbackForPackage(packageName);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackInfo> iter = mRecentlyExecutedRollbacks.iterator();
            boolean changed = false;
            while (iter.hasNext()) {
                RollbackInfo rollback = iter.next();
                if (packageName.equals(rollback.targetPackage.packageName)) {
                    iter.remove();
                    changed = true;
                }
            }

            if (changed) {
                saveRecentlyExecutedRollbacksLocked();
            }
        }
    }

    /**
     * Write the list of recently executed rollbacks to storage.
     * Note: This happens while mLock is held, which should be okay because we
     * expect executed rollbacks to be modified only in exceptional cases.
     */
    @GuardedBy("mLock")
    private void saveRecentlyExecutedRollbacksLocked() {
        try {
            JSONObject json = new JSONObject();
            JSONArray array = new JSONArray();
            json.put("recentlyExecuted", array);

            for (int i = 0; i < mRecentlyExecutedRollbacks.size(); ++i) {
                RollbackInfo rollback = mRecentlyExecutedRollbacks.get(i);
                JSONObject element = new JSONObject();
                element.put("packageName", rollback.targetPackage.packageName);
                element.put("higherVersionCode", rollback.targetPackage.higherVersion.versionCode);
                element.put("lowerVersionCode", rollback.targetPackage.lowerVersion.versionCode);
                array.put(element);
            }

            PrintWriter pw = new PrintWriter(mRecentlyExecutedRollbacksFile);
            pw.println(json.toString());
            pw.close();
        } catch (IOException | JSONException e) {
            // TODO: What to do here?
            Log.e(TAG, "Failed to save recently executed rollbacks", e);
        }
    }

    /**
     * Records that the given package has been recently rolled back.
     */
    private void addRecentlyExecutedRollback(RollbackInfo rollback) {
        // TODO: if the list of rollbacks gets too big, trim it to only those
        // that are necessary to keep track of.
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            mRecentlyExecutedRollbacks.add(rollback);
            saveRecentlyExecutedRollbacksLocked();
        }
    }

    /**
     * Notifies an IntentSender of failure.
     *
     * @param statusReceiver where to send the failure
     * @param message the failure message.
     */
    private void sendFailure(IntentSender statusReceiver, String message) {
        Log.e(TAG, message);
        try {
            // TODO: More context on which rollback failed?
            // TODO: More refined failure code?
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
            statusReceiver.sendIntent(mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Nowhere to send the result back to, so don't bother.
        }
    }

    /**
     * Notifies an IntentSender of success.
     */
    private void sendSuccess(IntentSender statusReceiver) {
        try {
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
            statusReceiver.sendIntent(mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Nowhere to send the result back to, so don't bother.
        }
    }

    // Check to see if anything needs expiration, and if so, expire it.
    // Schedules future expiration as appropriate.
    // TODO: Handle cases where the user changes time on the device.
    private void runExpiration() {
        Instant now = Instant.now();
        Instant oldest = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();

            Iterator<PackageRollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                PackageRollbackData data = iter.next();
                if (!now.isBefore(data.timestamp.plusMillis(ROLLBACK_LIFETIME_DURATION_MILLIS))) {
                    iter.remove();
                    removeFile(data.backupDir);
                } else if (oldest == null || oldest.isAfter(data.timestamp)) {
                    oldest = data.timestamp;
                }
            }
        }

        if (oldest != null) {
            scheduleExpiration(now.until(oldest.plusMillis(ROLLBACK_LIFETIME_DURATION_MILLIS),
                        ChronoUnit.MILLIS));
        }
    }

    /**
     * Schedules an expiration check to be run after the given duration in
     * milliseconds has gone by.
     */
    private void scheduleExpiration(long duration) {
        getHandler().postDelayed(() -> runExpiration(), duration);
    }

    private Handler getHandler() {
        return mHandlerThread.getThreadHandler();
    }

    /**
     * Called via broadcast by the package manager when a package is being
     * staged for install with rollback enabled. Called before the package has
     * been installed.
     *
     * @param id the id of the enable rollback request
     * @param installFlags information about what is being installed.
     * @param newPackageCodePath path to the package about to be installed.
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    private boolean enableRollback(int installFlags, File newPackageCodePath) {
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            Log.e(TAG, "Rollbacks not supported for instant app install");
            return false;
        }
        if ((installFlags & PackageManager.INSTALL_APEX) != 0) {
            Log.e(TAG, "Rollbacks not supported for apex install");
            return false;
        }

        // Get information about the package to be installed.
        PackageParser.PackageLite newPackage = null;
        try {
            newPackage = PackageParser.parsePackageLite(newPackageCodePath, 0);
        } catch (PackageParser.PackageParserException e) {
            Log.e(TAG, "Unable to parse new package", e);
            return false;
        }

        String packageName = newPackage.packageName;
        Log.i(TAG, "Enabling rollback for install of " + packageName);

        PackageRollbackInfo.PackageVersion newVersion =
                new PackageRollbackInfo.PackageVersion(newPackage.versionCode);

        // Get information about the currently installed package.
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        PackageParser.Package installedPackage = pm.getPackage(packageName);
        if (installedPackage == null) {
            // TODO: Support rolling back fresh package installs rather than
            // fail here. Test this case.
            Log.e(TAG, packageName + " is not installed");
            return false;
        }
        PackageRollbackInfo.PackageVersion installedVersion =
                new PackageRollbackInfo.PackageVersion(installedPackage.getLongVersionCode());

        File backupDir;
        try {
            backupDir = Files.createTempDirectory(
                mAvailableRollbacksDir.toPath(), packageName + "-").toFile();
        } catch (IOException e) {
            Log.e(TAG, "Unable to create rollback for " + packageName, e);
            return false;
        }

        // TODO: Should the timestamp be for when we commit the install, not
        // when we create the pending one?
        Instant timestamp = Instant.now();
        try {
            JSONObject json = new JSONObject();
            json.put("packageName", packageName);
            json.put("higherVersionCode", newVersion.versionCode);
            json.put("lowerVersionCode", installedVersion.versionCode);
            json.put("timestamp", timestamp.toString());

            File jsonFile = new File(backupDir, "rollback.json");
            PrintWriter pw = new PrintWriter(jsonFile);
            pw.println(json.toString());
            pw.close();
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to create rollback for " + packageName, e);
            removeFile(backupDir);
            return false;
        }

        // TODO: Copy by hard link instead to save on cpu and storage space?
        int status = PackageManagerServiceUtils.copyPackage(installedPackage.codePath, backupDir);
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            Log.e(TAG, "Unable to copy package for rollback for " + packageName);
            removeFile(backupDir);
            return false;
        }

        PackageRollbackData data = new PackageRollbackData(
                new PackageRollbackInfo(packageName, newVersion, installedVersion),
                backupDir, timestamp);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            mAvailableRollbacks.add(data);
        }

        return true;
    }

    // TODO: Don't copy this from PackageManagerShellCommand like this?
    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Deletes a file completely.
     * If the file is a directory, its contents are deleted as well.
     * Has no effect if the directory does not exist.
     */
    private void removeFile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                removeFile(child);
            }
        }
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * Gets the version of the package currently installed.
     * Returns null if the package is not currently installed.
     */
    private PackageRollbackInfo.PackageVersion getInstalledPackageVersion(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = pm.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        return new PackageRollbackInfo.PackageVersion(pkgInfo.getLongVersionCode());
    }
}
