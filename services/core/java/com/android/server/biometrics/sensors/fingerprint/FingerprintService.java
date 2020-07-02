/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.fingerprint.FingerprintServiceDumpProto;
import com.android.server.biometrics.fingerprint.FingerprintUserStatsProto;
import com.android.server.biometrics.fingerprint.PerformanceStatsProto;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricServiceBase;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.GenerateChallengeClient;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalClient;
import com.android.server.biometrics.sensors.RevokeChallengeClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 *
 * @hide
 */
public class FingerprintService extends BiometricServiceBase {

    protected static final String TAG = "FingerprintService";
    private static final boolean DEBUG = true;
    private static final String FP_DATA_DIR = "fpdata";

    /**
     * Receives the incoming binder calls from FingerprintManager.
     */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        private static final int ENROLL_TIMEOUT_SEC = 60;

        /**
         * The following methods contain common code which is shared in biometrics/common.
         */

        @Override // Binder call
        public void generateChallenge(IBinder token, IFingerprintServiceReceiver receiver,
                String opPackageName) throws RemoteException {
            checkPermission(MANAGE_FINGERPRINT);

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "Unable to generateChallenge, daemon null");
                receiver.onChallengeGenerated(0L);
                return;
            }

            final GenerateChallengeClient client = new FingerprintGenerateChallengeClient(
                    mClientFinishCallback, getContext(), daemon, token,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, getSensorId());
            generateChallengeInternal(client);
        }

        @Override // Binder call
        public void revokeChallenge(IBinder token, String owner) {
            checkPermission(MANAGE_FINGERPRINT);

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "startPostEnroll: no fingerprint HAL!");
                return;
            }

            final RevokeChallengeClient client = new FingerprintRevokeChallengeClient(
                    mClientFinishCallback, getContext(), daemon, token, owner, getSensorId());
            revokeChallengeInternal(client);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int userId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName, Surface surface) throws RemoteException {
            checkPermission(MANAGE_FINGERPRINT);
            updateActiveGroup(userId);

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "Unable to enroll, daemon null");
                receiver.onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return;
            }

            final boolean restricted = isRestricted();
            final EnrollClient client = new FingerprintEnrollClient(mClientFinishCallback,
                    getContext(), daemon, token, new ClientMonitorCallbackConverter(receiver),
                    userId, cryptoToken, restricted, opPackageName, getBiometricUtils(),
                    ENROLL_TIMEOUT_SEC, statsModality(), getSensorId(), true /* shouldVibrate */);

            enrollInternal(client, userId);
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            cancelEnrollmentInternal(token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long opId, final int userId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName, Surface surface) throws RemoteException {
            updateActiveGroup(userId);

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "Unable to authenticate, daemon null");
                receiver.onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return;
            }

            final boolean isStrongBiometric;
            final long ident = Binder.clearCallingIdentity();
            try {
                isStrongBiometric = isStrongBiometric();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }

            final boolean restricted = isRestricted();
            final int statsClient = isKeyguard(opPackageName) ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER;
            final AuthenticationClient client = new FingerprintAuthenticationClient(
                    mClientFinishCallback, getContext(), daemon, token,
                    new ClientMonitorCallbackConverter(receiver), userId, opId, restricted,
                    opPackageName, 0 /* cookie */, false /* requireConfirmation */, getSensorId(),
                    isStrongBiometric, surface, statsClient, mTaskStackListener, mLockoutTracker);
            authenticateInternal(client, opPackageName);
        }

        @Override // Binder call
        public void prepareForAuthentication(IBinder token, long opId, int userId,
                IBiometricSensorReceiver sensorReceiver, String opPackageName,
                int cookie, int callingUid, int callingPid, int callingUserId,
                Surface surface) throws RemoteException {
            checkPermission(MANAGE_BIOMETRIC);
            updateActiveGroup(userId);

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "Unable to prepare for authentication, daemon null");
                sensorReceiver.onError(getSensorId(), cookie,
                        BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return;
            }

            final boolean restricted = true; // BiometricPrompt is always restricted
            final AuthenticationClient client = new FingerprintAuthenticationClient(
                    mClientFinishCallback, getContext(), daemon, token,
                    new ClientMonitorCallbackConverter(sensorReceiver), userId, opId,
                    restricted, opPackageName, cookie, false /* requireConfirmation */,
                    getSensorId(), isStrongBiometric(), surface,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT, mTaskStackListener,
                    mLockoutTracker);
            authenticateInternal(client, opPackageName, callingUid, callingPid,
                    callingUserId);
        }

        @Override // Binder call
        public void startPreparedClient(int cookie) {
            checkPermission(MANAGE_BIOMETRIC);
            startCurrentClient(cookie);
        }


        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            cancelAuthenticationInternal(token, opPackageName);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final IBinder token, final String opPackageName,
                int callingUid, int callingPid, int callingUserId) {
            checkPermission(MANAGE_BIOMETRIC);
            // Cancellation is from system server in this case.
            cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid,
                    callingUserId, false /* fromClient */);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName)
                throws RemoteException {
            checkPermission(MANAGE_FINGERPRINT);
            updateActiveGroup(userId);

            if (token == null) {
                Slog.w(TAG, "remove(): token is null");
                return;
            }

            final IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "Unable to remove, daemon null");
                receiver.onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                return;
            }

            final boolean restricted = isRestricted();
            final RemovalClient client = new FingerprintRemovalClient(mClientFinishCallback,
                    getContext(), daemon, token, new ClientMonitorCallbackConverter(receiver),
                    fingerId, userId, restricted, opPackageName, getBiometricUtils(),
                    getSensorId(), statsModality());
            removeInternal(client);
        }

        @Override
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback)
                throws RemoteException {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            FingerprintService.super.addLockoutResetCallback(callback);
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 0 && "--proto".equals(args[0])) {
                    dumpProto(fd);
                } else {
                    dumpInternal(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * The following methods don't use any common code from BiometricService
         */

        // TODO: refactor out common code here
        @Override // Binder call
        public boolean isHardwareDetected(String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                IBiometricsFingerprint daemon = getFingerprintDaemon();
                return daemon != null;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void rename(final int fingerId, final int userId, final String name) {
            checkPermission(MANAGE_FINGERPRINT);
            if (!isCurrentUserOrProfile(userId)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    getBiometricUtils().renameBiometricForUser(getContext(), userId, fingerId,
                            name);
                }
            });
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            return FingerprintService.this.getEnrolledTemplates(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            return FingerprintService.this.hasEnrolledBiometrics(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            return FingerprintService.this.getAuthenticatorId(callingUserId);
        }

        @Override // Binder call
        public void resetLockout(int userId, byte [] hardwareAuthToken) throws RemoteException {
            checkPermission(RESET_FINGERPRINT_LOCKOUT);
            if (!FingerprintService.this.hasEnrolledBiometrics(userId)) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled for user: " + userId);
                return;
            }

            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(() -> {
                mLockoutTracker.resetFailedAttemptsForUser(true /* clearAttemptCounter */, userId);
            });
        }

        @Override
        public boolean isClientActive() {
            checkPermission(MANAGE_FINGERPRINT);
            synchronized(FingerprintService.this) {
                return (getCurrentClient() != null) || (getPendingClient() != null);
            }
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.add(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.remove(callback);
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            initializeConfigurationInternal(sensorId);
        }

        @Override
        public void onFingerDown(int x, int y, float minor, float major) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "onFingerDown | daemon is null");
            } else {
                android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint extension =
                        android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint.castFrom(
                                daemon);
                if (extension == null) {
                    Slog.v(TAG, "onFingerDown | failed to cast the HIDL to V2_3");
                } else {
                    try {
                        extension.onFingerDown(x, y, minor, major);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "onFingerDown | RemoteException: ", e);
                    }
                }
            }
        }

        @Override
        public void onFingerUp() {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "onFingerUp | daemon is null");
            } else {
                android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint extension =
                        android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint.castFrom(
                                daemon);
                if (extension == null) {
                    Slog.v(TAG, "onFingerUp | failed to cast the HIDL to V2_3");
                } else {
                    try {
                        extension.onFingerUp();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "onFingerUp | RemoteException: ", e);
                    }
                }
            }
        }

        @Override
        public boolean isUdfps(int sensorId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.e(TAG, "isUdfps | daemon is null");
            } else {
                android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint extension =
                        android.hardware.biometrics.fingerprint.V2_3.IBiometricsFingerprint.castFrom(
                                daemon);
                if (extension == null) {
                    Slog.v(TAG, "isUdfps | failed to cast the HIDL to V2_3");
                } else {
                    try {
                        return extension.isUdfps(sensorId);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "isUdfps | RemoteException: ", e);
                    }
                }
            }
            return false;
        }

        @Override
        public void showUdfpsOverlay() {
            if (mUdfpsOverlayController == null) {
                Slog.e(TAG, "showUdfpsOverlay | mUdfpsOverlayController is null");
                return;
            }
            try {
                mUdfpsOverlayController.showUdfpsOverlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "showUdfpsOverlay | RemoteException: ", e);
            }
        }

        @Override
        public void hideUdfpsOverlay() {
            if (mUdfpsOverlayController == null) {
                Slog.e(TAG, "hideUdfpsOverlay | mUdfpsOverlayController is null");
                return;
            }
            try {
                mUdfpsOverlayController.hideUdfpsOverlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "hideUdfpsOverlay | RemoteException: ", e);
            }
        }

        public void setUdfpsOverlayController(IUdfpsOverlayController controller) {
            mUdfpsOverlayController = controller;
        }
    }

    private final LockoutFrameworkImpl mLockoutTracker;
    private final CopyOnWriteArrayList<IFingerprintClientActiveCallback> mClientActiveCallbacks =
            new CopyOnWriteArrayList<>();
    private IUdfpsOverlayController mUdfpsOverlayController;

    @GuardedBy("this")
    private IBiometricsFingerprint mDaemon;

    private final LockoutFrameworkImpl.LockoutResetCallback mLockoutResetCallback = userId -> {
        notifyLockoutResetMonitors();
    };

    /**
     * Receives callbacks from the HAL.
     */
    private IBiometricsFingerprintClientCallback mDaemonCallback =
            new IBiometricsFingerprintClientCallback.Stub() {
        @Override
        public void onEnrollResult(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                final Fingerprint fingerprint =
                        new Fingerprint(getBiometricUtils().getUniqueName(getContext(), groupId),
                                groupId, fingerId, deviceId);
                FingerprintService.super.handleEnrollResult(fingerprint, remaining);
            });
        }

        @Override
        public void onAcquired(final long deviceId, final int acquiredInfo, final int vendorCode) {
            onAcquired_2_2(deviceId, acquiredInfo, vendorCode);
        }

        @Override
        public void onAcquired_2_2(long deviceId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                FingerprintService.super.handleAcquired(acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int fingerId, final int groupId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleAuthenticated(fp, token);
            });
        }

        @Override
        public void onError(final long deviceId, final int error, final int vendorCode) {
            mHandler.post(() -> {
                FingerprintService.super.handleError(error, vendorCode);
                // TODO: this chunk of code should be common to all biometric services
                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    // If we get HW_UNAVAILABLE, try to connect again later...
                    Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                    synchronized (this) {
                        mDaemon = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleRemoved(fp, remaining);
            });
        }

        @Override
        public void onEnumerate(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleEnumerate(fp, remaining);
            });

        }
    };

    public FingerprintService(Context context) {
        super(context);
        mLockoutTracker = new LockoutFrameworkImpl(context, mLockoutResetCallback);
    }

    @Override
    public void onStart() {
        super.onStart();
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        SystemServerInitThreadPool.submit(this::getFingerprintDaemon, TAG + ".onStart");
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected BiometricUtils getBiometricUtils() {
        return FingerprintUtils.getInstance();
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_fingerprintMaxTemplatesPerUser);
        final int enrolled = FingerprintService.this.getEnrolledTemplates(userId).size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many fingerprints registered");
            return true;
        }
        return false;
    }

    @Override
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        mDaemon = null;
    }

    @Override
    protected void updateActiveGroup(int userId) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();

        if (daemon != null) {
            try {
                if (userId != mCurrentUserId) {
                    int firstSdkInt = Build.VERSION.FIRST_SDK_INT;
                    if (firstSdkInt < Build.VERSION_CODES.BASE) {
                        Slog.e(TAG, "First SDK version " + firstSdkInt + " is invalid; must be " +
                                "at least VERSION_CODES.BASE");
                    }
                    File baseDir;
                    if (firstSdkInt <= Build.VERSION_CODES.O_MR1) {
                        baseDir = Environment.getUserSystemDirectory(userId);
                    } else {
                        baseDir = Environment.getDataVendorDeDirectory(userId);
                    }

                    File fpDir = new File(baseDir, FP_DATA_DIR);
                    if (!fpDir.exists()) {
                        if (!fpDir.mkdir()) {
                            Slog.v(TAG, "Cannot make directory: " + fpDir.getAbsolutePath());
                            return;
                        }
                        // Calling mkdir() from this process will create a directory with our
                        // permissions (inherited from the containing dir). This command fixes
                        // the label.
                        if (!SELinux.restorecon(fpDir)) {
                            Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }

                    daemon.setActiveGroup(userId, fpDir.getAbsolutePath());
                    mCurrentUserId = userId;
                }
                mAuthenticatorIds.put(userId,
                        hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId() : 0L);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    @Override
    protected boolean hasEnrolledBiometrics(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId).size() > 0;
    }

    @Override
    protected String getManageBiometricPermission() {
        return MANAGE_FINGERPRINT;
    }

    @Override
    protected void checkUseBiometricPermission() {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermission(USE_BIOMETRIC);
        }
    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
        boolean appOpsOk = false;
        if (mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        } else if (mAppOps.noteOp(AppOpsManager.OP_USE_FINGERPRINT, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        }
        return appOpsOk;
    }

    @Override
    protected List<Fingerprint> getEnrolledTemplates(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
    }

    @Override
    protected void notifyClientActiveCallbacks(boolean isActive) {
        List<IFingerprintClientActiveCallback> callbacks = mClientActiveCallbacks;
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onClientActiveChanged(isActive);
            } catch (RemoteException re) {
                // If the remote is dead, stop notifying it
                mClientActiveCallbacks.remove(callbacks.get(i));
            }
        }
    }

    @Override
    protected int statsModality() {
        return BiometricsProtoEnums.MODALITY_FINGERPRINT;
    }

    @Override
    protected @LockoutTracker.LockoutMode int getLockoutMode(int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    @Override
    protected void doTemplateCleanupForUser(int userId) {
        final IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.e(TAG, "daemon null, skipping template cleanup");
            return;
        }

        final boolean restricted = !hasPermission(getManageBiometricPermission());
        final List<? extends BiometricAuthenticator.Identifier> enrolledList =
                getEnrolledTemplates(userId);
        final FingerprintInternalCleanupClient client = new FingerprintInternalCleanupClient(
                mClientFinishCallback, getContext(), daemon, userId, restricted,
                getContext().getOpPackageName(), getSensorId(), statsModality(), enrolledList,
                getBiometricUtils());
        cleanupInternal(client);
    }

    /** Gets the fingerprint daemon */
    private synchronized IBiometricsFingerprint getFingerprintDaemon() {
        if (mDaemon == null) {
            Slog.v(TAG, "mDaemon was null, reconnect to fingerprint");
            try {
                mDaemon = IBiometricsFingerprint.getService();
            } catch (java.util.NoSuchElementException e) {
                // Service doesn't exist or cannot be opened. Logged below.
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get biometric interface", e);
            }
            if (mDaemon == null) {
                Slog.w(TAG, "fingerprint HIDL not available");
                return null;
            }

            mDaemon.asBinder().linkToDeath(this, 0);

            long halId = 0;
            try {
                halId = mDaemon.setNotify(mDaemonCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open fingerprint HAL", e);
                mDaemon = null; // try again later!
            }

            if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + halId);
            if (halId != 0) {
                loadAuthenticatorIds();
                final int userId = ActivityManager.getCurrentUser();
                updateActiveGroup(userId);
                doTemplateCleanupForUser(userId);
            } else {
                Slog.w(TAG, "Failed to open Fingerprint HAL!");
                MetricsLogger.count(getContext(), "fingerprintd_openhal_error", 1);
                mDaemon = null;
            }
        }
        return mDaemon;
    }

    private native NativeHandle convertSurfaceToNativeHandle(Surface surface);

    private void dumpInternal(PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(getSensorId());

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = getBiometricUtils().getBiometricsForUser(getContext(), userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure fingerprint transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", performanceTracker.getAcceptCryptoForUser(userId));
                set.put("rejectCrypto", performanceTracker.getRejectCryptoForUser(userId));
                set.put("acquireCrypto", performanceTracker.getAcquireCryptoForUser(userId));
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());
    }

    private void dumpProto(FileDescriptor fd) {
        PerformanceTracker tracker =
                PerformanceTracker.getInstanceForSensorId(getSensorId());

        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FingerprintServiceDumpProto.USERS);

            proto.write(FingerprintUserStatsProto.USER_ID, userId);
            proto.write(FingerprintUserStatsProto.NUM_FINGERPRINTS,
                    getBiometricUtils().getBiometricsForUser(getContext(), userId).size());

            // Normal fingerprint authentications (e.g. lockscreen)
            long countsToken = proto.start(FingerprintUserStatsProto.NORMAL);
            proto.write(PerformanceStatsProto.ACCEPT, tracker.getAcceptForUser(userId));
            proto.write(PerformanceStatsProto.REJECT, tracker.getRejectForUser(userId));
            proto.write(PerformanceStatsProto.ACQUIRE, tracker.getAcquireForUser(userId));
            proto.write(PerformanceStatsProto.LOCKOUT, tracker.getTimedLockoutForUser(userId));
            proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT,
                    tracker.getPermanentLockoutForUser(userId));
            proto.end(countsToken);

            // Statistics about secure fingerprint transactions (e.g. to unlock password
            // storage, make secure purchases, etc.)
            countsToken = proto.start(FingerprintUserStatsProto.CRYPTO);
            proto.write(PerformanceStatsProto.ACCEPT, tracker.getAcceptCryptoForUser(userId));
            proto.write(PerformanceStatsProto.REJECT, tracker.getRejectCryptoForUser(userId));
            proto.write(PerformanceStatsProto.ACQUIRE, tracker.getAcquireCryptoForUser(userId));
            proto.write(PerformanceStatsProto.LOCKOUT, 0); // meaningless for crypto
            proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, 0); // meaningless for crypto
            proto.end(countsToken);

            proto.end(userToken);
        }
        proto.flush();
        tracker.clear();
    }
}
