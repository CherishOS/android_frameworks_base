/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.gpu;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Service to manage GPU related features.
 *
 * <p>GPU service is a core service that monitors, coordinates all GPU related features,
 * as well as collect metrics about the GPU and GPU driver.</p>
 */
public class GpuService extends SystemService {
    public static final String TAG = "GpuService";
    public static final boolean DEBUG = false;

    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";
    private static final String WHITELIST_FILENAME = "whitelist.txt";

    private final Context mContext;
    private final String mDriverPackageName;
    private final PackageManager mPackageManager;

    public GpuService(Context context) {
        super(context);

        mContext = context;
        mDriverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        mPackageManager = context.getPackageManager();
        if (mDriverPackageName != null && !mDriverPackageName.isEmpty()) {
            final IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(ACTION_PACKAGE_ADDED);
            packageFilter.addAction(ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL,
                                                packageFilter, null, null);
        }
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            if (mDriverPackageName == null || mDriverPackageName.isEmpty()) {
                return;
            }
            fetchGameDriverPackageProperties();
        }
    }

    private final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final Uri data = intent.getData();
            if (data == null && DEBUG) {
                Slog.e(TAG, "Cannot handle package broadcast with null data");
                return;
            }
            final String packageName = data.getSchemeSpecificPart();
            if (!packageName.equals(mDriverPackageName)) {
                return;
            }

            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

            switch (intent.getAction()) {
                case ACTION_PACKAGE_ADDED:
                case ACTION_PACKAGE_CHANGED:
                case ACTION_PACKAGE_REMOVED:
                    fetchGameDriverPackageProperties();
                    break;
                default:
                    // do nothing
                    break;
            }
        }
    }

    private void fetchGameDriverPackageProperties() {
        final ApplicationInfo driverInfo;
        try {
            driverInfo = mPackageManager.getApplicationInfo(mDriverPackageName,
                                                            PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.e(TAG, "driver package '" + mDriverPackageName + "' not installed");
            }
            return;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        if (driverInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Slog.w(TAG, "Driver package is not known to be compatible with O");
            }
            return;
        }

        try {
            final Context driverContext = mContext.createPackageContext(mDriverPackageName,
                                                                        Context.CONTEXT_RESTRICTED);
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(driverContext.getAssets().open(WHITELIST_FILENAME)));
            final ArrayList<String> whitelistedPackageNames = new ArrayList<>();
            for (String packageName; (packageName = reader.readLine()) != null; ) {
                whitelistedPackageNames.add(packageName);
            }
            Settings.Global.putString(mContext.getContentResolver(),
                                      Settings.Global.GAME_DRIVER_WHITELIST,
                                      String.join(",", whitelistedPackageNames));
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.w(TAG, "driver package '" + mDriverPackageName + "' not installed");
            }
        } catch (IOException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed to load whitelist driver package, abort.");
            }
        }
    }
}
