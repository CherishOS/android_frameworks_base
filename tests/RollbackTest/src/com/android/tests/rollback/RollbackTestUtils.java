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

package com.android.tests.rollback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.support.test.InstrumentationRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utilities to facilitate testing rollbacks.
 */
class RollbackTestUtils {

    private static final String TAG = "RollbackTest";

    static RollbackManager getRollbackManager() {
        Context context = InstrumentationRegistry.getContext();
        RollbackManager rm = (RollbackManager) context.getSystemService(Context.ROLLBACK_SERVICE);
        if (rm == null) {
            throw new AssertionError("Failed to get RollbackManager");
        }
        return rm;
    }

    /**
     * Returns the version of the given package installed on device.
     * Returns -1 if the package is not currently installed.
     */
    static long getInstalledVersion(String packageName) {
        Context context = InstrumentationRegistry.getContext();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static void assertStatusSuccess(Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status > 0) {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message == null ? "UNKNOWN FAILURE" : message);
        }
    }

    /**
     * Uninstalls the given package.
     * Does nothing if the package is not installed.
     * @throws AssertionError if package can't be uninstalled.
     */
    static void uninstall(String packageName) throws InterruptedException, IOException {
        // No need to uninstall if the package isn't installed.
        if (getInstalledVersion(packageName) == -1) {
            return;
        }

        Context context = InstrumentationRegistry.getContext();
        PackageManager packageManager = context.getPackageManager();
        PackageInstaller packageInstaller = packageManager.getPackageInstaller();
        packageInstaller.uninstall(packageName, LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    /**
     * Execute the given rollback.
     * @throws AssertionError if the rollback fails.
     */
    static void rollback(RollbackInfo rollback) throws InterruptedException {
        RollbackManager rm = getRollbackManager();
        rm.executeRollback(rollback, LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    /**
     * Installs the apk with the given name.
     *
     * @param resourceName name of class loader resource for the apk to
     *        install.
     * @param enableRollback if rollback should be enabled.
     * @throws AssertionError if the installation fails.
     */
    static void install(String resourceName, boolean enableRollback)
            throws InterruptedException, IOException {
        Context context = InstrumentationRegistry.getContext();
        PackageInstaller.Session session = null;
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (enableRollback) {
            params.setEnableRollback();
        }
        int sessionId = packageInstaller.createSession(params);
        session = packageInstaller.openSession(sessionId);

        ClassLoader loader = RollbackTest.class.getClassLoader();
        try (OutputStream packageInSession = session.openWrite("package", 0, -1);
             InputStream is = loader.getResourceAsStream(resourceName);) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
            }
        }

        // Commit the session (this will start the installation workflow).
        session.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    /**
     * Installs the apks with the given resource names as an atomic set.
     *
     * @param enableRollback if rollback should be enabled.
     * @param resourceNames names of the class loader resource for the apks to
     *        install.
     * @throws AssertionError if the installation fails.
     */
    static void installMultiPackage(boolean enableRollback, String... resourceNames)
            throws InterruptedException, IOException {
        Context context = InstrumentationRegistry.getContext();
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

        PackageInstaller.SessionParams multiPackageParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        multiPackageParams.setMultiPackage();
        if (enableRollback) {
            // TODO: Do we set this on the parent params, the child params, or
            // both?
            multiPackageParams.setEnableRollback();
        }
        int multiPackageId = packageInstaller.createSession(multiPackageParams);
        PackageInstaller.Session multiPackage = packageInstaller.openSession(multiPackageId);

        for (String resourceName : resourceNames) {
            PackageInstaller.Session session = null;
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (enableRollback) {
                params.setEnableRollback();
            }
            int sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            ClassLoader loader = RollbackTest.class.getClassLoader();
            try (OutputStream packageInSession = session.openWrite("package", 0, -1);
                 InputStream is = loader.getResourceAsStream(resourceName);) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) >= 0) {
                    packageInSession.write(buffer, 0, n);
                }
            }
            multiPackage.addChildSessionId(sessionId);
        }

        // Commit the session (this will start the installation workflow).
        multiPackage.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    static void adoptShellPermissionIdentity(String... permissions) {
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity(permissions);
    }

    static void dropShellPermissionIdentity() {
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation()
            .dropShellPermissionIdentity();
    }
}
