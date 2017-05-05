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

package com.android.server.timezone;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import android.app.timezone.Callback;
import android.app.timezone.DistroFormatVersion;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.ICallback;
import android.app.timezone.IRulesManager;
import android.app.timezone.RulesManager;
import android.app.timezone.RulesState;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.tzdata.shared2.DistroException;
import libcore.tzdata.shared2.DistroVersion;
import libcore.tzdata.shared2.StagedDistroOperation;
import libcore.tzdata.update2.TimeZoneDistroInstaller;

// TODO(nfuller) Add EventLog calls where useful in the system server.
// TODO(nfuller) Check logging best practices in the system server.
// TODO(nfuller) Check error handling best practices in the system server.
public final class RulesManagerService extends IRulesManager.Stub {

    private static final String TAG = "timezone.RulesManagerService";

    /** The distro format supported by this device. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final DistroFormatVersion DISTRO_FORMAT_VERSION_SUPPORTED =
            new DistroFormatVersion(
                    DistroVersion.CURRENT_FORMAT_MAJOR_VERSION,
                    DistroVersion.CURRENT_FORMAT_MINOR_VERSION);

    public static class Lifecycle extends SystemService {
        private RulesManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = RulesManagerService.create(getContext());
            mService.start();

            publishBinderService(Context.TIME_ZONE_RULES_MANAGER_SERVICE, mService);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String REQUIRED_UPDATER_PERMISSION =
            android.Manifest.permission.UPDATE_TIME_ZONE_RULES;
    private static final File SYSTEM_TZ_DATA_FILE = new File("/system/usr/share/zoneinfo/tzdata");
    private static final File TZ_DATA_DIR = new File("/data/misc/zoneinfo");

    private final AtomicBoolean mOperationInProgress = new AtomicBoolean(false);
    private final PermissionHelper mPermissionHelper;
    private final PackageTracker mPackageTracker;
    private final Executor mExecutor;
    private final TimeZoneDistroInstaller mInstaller;
    private final FileDescriptorHelper mFileDescriptorHelper;

    private static RulesManagerService create(Context context) {
        RulesManagerServiceHelperImpl helper = new RulesManagerServiceHelperImpl(context);
        return new RulesManagerService(
                helper /* permissionHelper */,
                helper /* executor */,
                helper /* fileDescriptorHelper */,
                PackageTracker.create(context),
                new TimeZoneDistroInstaller(TAG, SYSTEM_TZ_DATA_FILE, TZ_DATA_DIR));
    }

    // A constructor that can be used by tests to supply mocked / faked dependencies.
    RulesManagerService(PermissionHelper permissionHelper,
            Executor executor,
            FileDescriptorHelper fileDescriptorHelper, PackageTracker packageTracker,
            TimeZoneDistroInstaller timeZoneDistroInstaller) {
        mPermissionHelper = permissionHelper;
        mExecutor = executor;
        mFileDescriptorHelper = fileDescriptorHelper;
        mPackageTracker = packageTracker;
        mInstaller = timeZoneDistroInstaller;
    }

    public void start() {
        mPackageTracker.start();
    }

    @Override // Binder call
    public RulesState getRulesState() {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

        synchronized(this) {
            String systemRulesVersion;
            try {
                systemRulesVersion = mInstaller.getSystemRulesVersion();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to read system rules", e);
                return null;
            }

            boolean operationInProgress = this.mOperationInProgress.get();

            // Determine the staged operation status, if possible.
            DistroRulesVersion stagedDistroRulesVersion = null;
            int stagedOperationStatus = RulesState.STAGED_OPERATION_UNKNOWN;
            if (!operationInProgress) {
                StagedDistroOperation stagedDistroOperation;
                try {
                    stagedDistroOperation = mInstaller.getStagedDistroOperation();
                    if (stagedDistroOperation == null) {
                        stagedOperationStatus = RulesState.STAGED_OPERATION_NONE;
                    } else if (stagedDistroOperation.isUninstall) {
                        stagedOperationStatus = RulesState.STAGED_OPERATION_UNINSTALL;
                    } else {
                        // Must be an install.
                        stagedOperationStatus = RulesState.STAGED_OPERATION_INSTALL;
                        DistroVersion stagedDistroVersion = stagedDistroOperation.distroVersion;
                        stagedDistroRulesVersion = new DistroRulesVersion(
                                stagedDistroVersion.rulesVersion,
                                stagedDistroVersion.revision);
                    }
                } catch (DistroException | IOException e) {
                    Slog.w(TAG, "Failed to read staged distro.", e);
                }
            }

            // Determine the installed distro state, if possible.
            DistroVersion installedDistroVersion;
            int distroStatus = RulesState.DISTRO_STATUS_UNKNOWN;
            DistroRulesVersion installedDistroRulesVersion = null;
            if (!operationInProgress) {
                try {
                    installedDistroVersion = mInstaller.getInstalledDistroVersion();
                    if (installedDistroVersion == null) {
                        distroStatus = RulesState.DISTRO_STATUS_NONE;
                        installedDistroRulesVersion = null;
                    } else {
                        distroStatus = RulesState.DISTRO_STATUS_INSTALLED;
                        installedDistroRulesVersion = new DistroRulesVersion(
                                installedDistroVersion.rulesVersion,
                                installedDistroVersion.revision);
                    }
                } catch (DistroException | IOException e) {
                    Slog.w(TAG, "Failed to read installed distro.", e);
                }
            }
            return new RulesState(systemRulesVersion, DISTRO_FORMAT_VERSION_SUPPORTED,
                    operationInProgress, stagedOperationStatus, stagedDistroRulesVersion,
                    distroStatus, installedDistroRulesVersion);
        }
    }

    @Override
    public int requestInstall(
            ParcelFileDescriptor timeZoneDistro, byte[] checkTokenBytes, ICallback callback) {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        synchronized (this) {
            if (timeZoneDistro == null) {
                throw new NullPointerException("timeZoneDistro == null");
            }
            if (callback == null) {
                throw new NullPointerException("observer == null");
            }
            if (mOperationInProgress.get()) {
                return RulesManager.ERROR_OPERATION_IN_PROGRESS;
            }
            mOperationInProgress.set(true);

            // Execute the install asynchronously.
            mExecutor.execute(new InstallRunnable(timeZoneDistro, checkToken, callback));

            return RulesManager.SUCCESS;
        }
    }

    private class InstallRunnable implements Runnable {

        private final ParcelFileDescriptor mTimeZoneDistro;
        private final CheckToken mCheckToken;
        private final ICallback mCallback;

        InstallRunnable(
                ParcelFileDescriptor timeZoneDistro, CheckToken checkToken, ICallback callback) {
            mTimeZoneDistro = timeZoneDistro;
            mCheckToken = checkToken;
            mCallback = callback;
        }

        @Override
        public void run() {
            // Adopt the ParcelFileDescriptor into this try-with-resources so it is closed
            // when we are done.
            boolean success = false;
            try {
                byte[] distroBytes =
                        RulesManagerService.this.mFileDescriptorHelper.readFully(mTimeZoneDistro);
                int installerResult = mInstaller.stageInstallWithErrorCode(distroBytes);
                int resultCode = mapInstallerResultToApiCode(installerResult);
                sendFinishedStatus(mCallback, resultCode);

                // All the installer failure modes are currently non-recoverable and won't be
                // improved by trying again. Therefore success = true.
                success = true;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to install distro.", e);
                sendFinishedStatus(mCallback, Callback.ERROR_UNKNOWN_FAILURE);
            } finally {
                // Notify the package tracker that the operation is now complete.
                mPackageTracker.recordCheckResult(mCheckToken, success);

                mOperationInProgress.set(false);
            }
        }

        private int mapInstallerResultToApiCode(int installerResult) {
            switch (installerResult) {
                case TimeZoneDistroInstaller.INSTALL_SUCCESS:
                    return Callback.SUCCESS;
                case TimeZoneDistroInstaller.INSTALL_FAIL_BAD_DISTRO_STRUCTURE:
                    return Callback.ERROR_INSTALL_BAD_DISTRO_STRUCTURE;
                case TimeZoneDistroInstaller.INSTALL_FAIL_RULES_TOO_OLD:
                    return Callback.ERROR_INSTALL_RULES_TOO_OLD;
                case TimeZoneDistroInstaller.INSTALL_FAIL_BAD_DISTRO_FORMAT_VERSION:
                    return Callback.ERROR_INSTALL_BAD_DISTRO_FORMAT_VERSION;
                case TimeZoneDistroInstaller.INSTALL_FAIL_VALIDATION_ERROR:
                    return Callback.ERROR_INSTALL_VALIDATION_ERROR;
                default:
                    return Callback.ERROR_UNKNOWN_FAILURE;
            }
        }
    }

    @Override
    public int requestUninstall(byte[] checkTokenBytes, ICallback callback) {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);

        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        synchronized(this) {
            if (callback == null) {
                throw new NullPointerException("callback == null");
            }

            if (mOperationInProgress.get()) {
                return RulesManager.ERROR_OPERATION_IN_PROGRESS;
            }
            mOperationInProgress.set(true);

            // Execute the uninstall asynchronously.
            mExecutor.execute(new UninstallRunnable(checkToken, callback));

            return RulesManager.SUCCESS;
        }
    }

    private class UninstallRunnable implements Runnable {

        private final CheckToken mCheckToken;
        private final ICallback mCallback;

        public UninstallRunnable(CheckToken checkToken, ICallback callback) {
            mCheckToken = checkToken;
            mCallback = callback;
        }

        @Override
        public void run() {
            boolean success = false;
            try {
                success = mInstaller.stageUninstall();
                // Right now we just have success (0) / failure (1). All clients should be checking
                // against SUCCESS. More granular failures may be added in future.
                int resultCode = success ? Callback.SUCCESS
                        : Callback.ERROR_UNKNOWN_FAILURE;
                sendFinishedStatus(mCallback, resultCode);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to uninstall distro.", e);
                sendFinishedStatus(mCallback, Callback.ERROR_UNKNOWN_FAILURE);
            } finally {
                // Notify the package tracker that the operation is now complete.
                mPackageTracker.recordCheckResult(mCheckToken, success);

                mOperationInProgress.set(false);
            }
        }
    }

    private void sendFinishedStatus(ICallback callback, int resultCode) {
        try {
            callback.onFinished(resultCode);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to notify observer of result", e);
        }
    }

    @Override
    public void requestNothing(byte[] checkTokenBytes, boolean success) {
        mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        mPackageTracker.recordCheckResult(checkToken, success);
    }

    private static CheckToken createCheckTokenOrThrow(byte[] checkTokenBytes) {
        CheckToken checkToken;
        try {
            checkToken = CheckToken.fromByteArray(checkTokenBytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read token bytes "
                    + Arrays.toString(checkTokenBytes), e);
        }
        return checkToken;
    }
}
