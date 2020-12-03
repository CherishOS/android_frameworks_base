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
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.BiometricServiceCallback;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 */
public class FingerprintService extends SystemService implements BiometricServiceCallback {

    protected static final String TAG = "FingerprintService";

    private final AppOpsManager mAppOps;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    private final FingerprintServiceWrapper mServiceWrapper;
    @NonNull private List<ServiceProvider> mServiceProviders;

    /**
     * Receives the incoming binder calls from FingerprintManager.
     */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), TEST_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);

            if (provider == null) {
                Slog.w(TAG, "Null provider for createTestSession, sensorId: " + sensorId);
                return null;
            }

            return provider.createTestSession(sensorId, opPackageName);
        }

        @Override
        public byte[] dumpSensorServiceStateProto(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ProtoOutputStream proto = new ProtoOutputStream();
            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider != null) {
                provider.dumpProtoState(sensorId, proto);
            }
            proto.flush();
            return proto.getBytes();
        }

        @Override // Binder call
        public List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal(
                @NonNull String opPackageName) {
            if (getContext().checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                    != PackageManager.PERMISSION_GRANTED) {
                Utils.checkPermission(getContext(), TEST_BIOMETRIC);
            }

            final List<FingerprintSensorPropertiesInternal> properties =
                    FingerprintService.this.getSensorProperties();

            Slog.d(TAG, "Retrieved sensor properties for: " + opPackageName
                    + ", sensors: " + properties.size());
            return properties;
        }

        @Override
        public FingerprintSensorPropertiesInternal getSensorProperties(int sensorId,
                @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for getSensorProperties, sensorId: " + sensorId
                        + ", caller: " + opPackageName);
                return null;
            }
            return provider.getSensorProperties(sensorId);
        }

        @Override // Binder call
        public void generateChallenge(IBinder token, int sensorId, int userId,
                IFingerprintServiceReceiver receiver, String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for generateChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleGenerateChallenge(sensorId, userId, token, receiver, opPackageName);
        }

        @Override // Binder call
        public void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName,
                long challenge) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName,
                    challenge);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] hardwareAuthToken, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return;
            }

            provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName);
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelEnrollment");
                return;
            }

            provider.second.cancelEnrollment(provider.first, token);
        }

        @SuppressWarnings("deprecation")
        @Override // Binder call
        public void authenticate(final IBinder token, final long operationId,
                @FingerprintManager.SensorId final int sensorId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(opPackageName, true /* requireForeground */, callingUid,
                    callingPid, callingUserId)) {
                Slog.w(TAG, "Authenticate rejecting package: " + opPackageName);
                return;
            }

            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            // Clear calling identity when checking LockPatternUtils for StrongAuth flags.
            final long identity = Binder.clearCallingIdentity();
            try {
                if (isKeyguard && Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                    // If this happens, something in KeyguardUpdateMonitor is wrong.
                    // SafetyNet for b/79776455
                    EventLog.writeEvent(0x534e4554, "79776455");
                    Slog.e(TAG, "Authenticate invoked when user is encrypted or lockdown");
                    return;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            final boolean restricted = getContext().checkCallingPermission(MANAGE_FINGERPRINT)
                    != PackageManager.PERMISSION_GRANTED;
            final int statsClient = isKeyguard ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER;

            final Pair<Integer, ServiceProvider> provider;
            if (sensorId == FingerprintManager.SENSOR_ID_ANY) {
                provider = getSingleProvider();
            } else {
                Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
                provider = new Pair<>(sensorId, getProviderForSensor(sensorId));
            }
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return;
            }

            provider.second.scheduleAuthenticate(provider.first, token, operationId, userId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), opPackageName,
                    restricted, statsClient, isKeyguard);
        }

        @Override
        public void detectFingerprint(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFingerprint called from non-sysui package: " + opPackageName);
                return;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFingerprint invoked when user is not encrypted or lockdown");
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFingerprint");
                return;
            }

            provider.second.scheduleFingerDetect(provider.first, token, userId,
                    new ClientMonitorCallbackConverter(receiver), opPackageName,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @Override // Binder call
        public void prepareForAuthentication(int sensorId, IBinder token, long operationId,
                int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
                int cookie) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for prepareForAuthentication");
                return;
            }

            final boolean restricted = true; // BiometricPrompt is always restricted
            provider.scheduleAuthenticate(sensorId, token, operationId, userId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), opPackageName, restricted,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT, false /* isKeyguard */);
        }

        @Override // Binder call
        public void startPreparedClient(int sensorId, int cookie) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for startPreparedClient");
                return;
            }

            provider.startPreparedClient(sensorId, cookie);
        }


        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(opPackageName, true /* requireForeground */, callingUid,
                    callingPid, callingUserId)) {
                Slog.w(TAG, "cancelAuthentication rejecting package: " + opPackageName);
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token);
        }

        @Override // Binder call
        public void cancelFingerprintDetect(final IBinder token, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFingerprintDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            // For IBiometricsFingerprint2.1, cancelling fingerprint detect is the same as
            // cancelling authentication.
            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelFingerprintDetect");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final int sensorId, final IBinder token,
                final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }
            provider.second.scheduleRemove(provider.first, token, receiver, fingerId, userId,
                    opPackageName);
        }

        @Override
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mLockoutResetDispatcher.addCallback(callback, opPackageName);
        }

        @Override // Binder call
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 1 && "--proto".equals(args[0]) && "--state".equals(args[1])) {
                    final ProtoOutputStream proto = new ProtoOutputStream(fd);
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            pw.println("Dumping for sensorId: " + props.sensorId
                                    + ", provider: " + provider.getClass().getSimpleName());
                            provider.dumpInternal(props.sensorId, pw);
                            pw.println();
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetectedDeprecated(String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                final Pair<Integer, ServiceProvider> provider = getSingleProvider();
                if (provider == null) {
                    Slog.w(TAG, "Null provider for isHardwareDetectedDeprecated, caller: "
                            + opPackageName);
                    return false;
                }
                return provider.second.isHardwareDetected(provider.first);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                return false;
            }

            return provider.isHardwareDetected(sensorId);
        }

        @Override // Binder call
        public void rename(final int fingerId, final int userId, final String name) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for rename");
                return;
            }

            provider.second.rename(provider.first, fingerId, userId, name);
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            return FingerprintService.this.getEnrolledFingerprintsDeprecated(userId, opPackageName);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprintsDeprecated(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }
            return !FingerprintService.this.getEnrolledFingerprintsDeprecated(userId, opPackageName)
                    .isEmpty();
        }

        @Override // Binder call
        public boolean hasEnrolledTemplatesForAnySensor(int userId,
                @NonNull List<FingerprintSensorPropertiesInternal> sensors,
                @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            for (FingerprintSensorPropertiesInternal prop : sensors) {
                final ServiceProvider provider = getProviderForSensor(prop.sensorId);
                if (provider == null) {
                    Slog.w(TAG, "Null provider for sensorId: " + prop.sensorId
                            + ", caller: " + opPackageName);
                    continue;
                }

                if (!provider.getEnrolledFingerprints(prop.sensorId, userId).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasEnrolledFingerprints(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for hasEnrolledFingerprints, caller: " + opPackageName);
                return false;
            }

            return provider.getEnrolledFingerprints(sensorId, userId).size() > 0;
        }

        @Override // Binder call
        public @LockoutTracker.LockoutMode int getLockoutModeForUser(int sensorId, int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getLockoutModeForUser");
                return LockoutTracker.LOCKOUT_NONE;
            }
            return provider.getLockoutModeForUser(sensorId, userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int sensorId, int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getAuthenticatorId");
                return 0;
            }
            return provider.getAuthenticatorId(sensorId, userId);
        }

        @Override // Binder call
        public void resetLockout(IBinder token, int sensorId, int userId,
                @Nullable byte [] hardwareAuthToken, String opPackageName) {
            Utils.checkPermission(getContext(), RESET_FINGERPRINT_LOCKOUT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.second.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @Override
        public boolean isClientActive() {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            return mGestureAvailabilityDispatcher.isAnySensorActive();
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityDispatcher.registerCallback(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityDispatcher.removeCallback(callback);
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId,
                @BiometricManager.Authenticators.Types int strength) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Fingerprint21 fingerprint21;
            if ((Build.IS_USERDEBUG || Build.IS_ENG)
                    && getContext().getResources().getBoolean(R.bool.allow_test_udfps)
                    && Settings.Secure.getIntForUser(getContext().getContentResolver(),
                    Fingerprint21UdfpsMock.CONFIG_ENABLE_TEST_UDFPS, 0 /* default */,
                    UserHandle.USER_CURRENT) != 0) {
                fingerprint21 = Fingerprint21UdfpsMock.newInstance(getContext(), sensorId,
                        strength, mLockoutResetDispatcher, mGestureAvailabilityDispatcher);
            } else {
                fingerprint21 = Fingerprint21.newInstance(getContext(), sensorId, strength,
                        mLockoutResetDispatcher, mGestureAvailabilityDispatcher);
            }
            mServiceProviders.add(fingerprint21);
        }

        @Override
        public void onPointerDown(int sensorId, int x, int y, float minor, float major) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerDown, sensorId: " + sensorId);
                return;
            }
            provider.onPointerDown(sensorId, x, y, minor, major);
        }

        @Override
        public void onPointerUp(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerUp, sensorId: " + sensorId);
                return;
            }
            provider.onPointerUp(sensorId);
        }

        @Override
        public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            for (ServiceProvider provider : mServiceProviders) {
                provider.setUdfpsOverlayController(controller);
            }
        }
    }

    public FingerprintService(Context context) {
        super(context);
        mServiceWrapper = new FingerprintServiceWrapper();
        mAppOps = context.getSystemService(AppOpsManager.class);
        mGestureAvailabilityDispatcher = new GestureAvailabilityDispatcher();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mServiceProviders = new ArrayList<>();
    }

    @Override
    public void onBiometricServiceReady() {
        final IBiometricService biometricService = IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE));

        final String[] instances = ServiceManager.getDeclaredInstances(IFingerprint.DESCRIPTOR);
        if (instances == null || instances.length == 0) {
            return;
        }

        // If for some reason the HAL is not started before the system service, do not block
        // the rest of system server. Put this on a background thread.
        final ServiceThread thread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                true /* allowIo */);
        thread.start();
        final Handler handler = new Handler(thread.getLooper());

        handler.post(() -> {
            for (String instance : instances) {
                final String fqName = IFingerprint.DESCRIPTOR + "/" + instance;
                final IFingerprint fp = IFingerprint.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(fqName));
                try {
                    final SensorProps[] props = fp.getSensorProps();
                    final FingerprintProvider provider =
                            new FingerprintProvider(getContext(), props, instance,
                                    mLockoutResetDispatcher, mGestureAvailabilityDispatcher);
                    mServiceProviders.add(provider);

                    // Register each sensor individually with BiometricService
                    for (SensorProps prop : props) {
                        final int sensorId = prop.commonProps.sensorId;
                        @BiometricManager.Authenticators.Types int strength =
                                Utils.propertyStrengthToAuthenticatorStrength(
                                        prop.commonProps.sensorStrength);
                        final FingerprintAuthenticator authenticator =
                                new FingerprintAuthenticator(mServiceWrapper, sensorId);
                        try {
                            biometricService.registerAuthenticator(sensorId,
                                    TYPE_FINGERPRINT, strength, authenticator);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception when registering sensorId: "
                                    + sensorId);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception when initializing instance: " + fqName);
                }
            }
        });
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, mServiceWrapper);
    }

    @Nullable
    private ServiceProvider getProviderForSensor(int sensorId) {
        for (ServiceProvider provider : mServiceProviders) {
            if (provider.containsSensor(sensorId)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * For devices with only a single provider, returns that provider. If no providers, or multiple
     * providers exist, returns null.
     */
    @Nullable
    private Pair<Integer, ServiceProvider> getSingleProvider() {
        final List<FingerprintSensorPropertiesInternal> properties = getSensorProperties();
        if (properties.size() != 1) {
            Slog.e(TAG, "Multiple sensors found: " + properties.size());
            return null;
        }

        // Theoretically we can just return the first provider, but maybe this is easier to
        // understand.
        final int sensorId = properties.get(0).sensorId;
        for (ServiceProvider provider : mServiceProviders) {
            if (provider.containsSensor(sensorId)) {
                return new Pair<>(sensorId, provider);
            }
        }

        Slog.e(TAG, "Single sensor, but provider not found");
        return null;
    }

    @NonNull
    private List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        final List<FingerprintSensorPropertiesInternal> properties = new ArrayList<>();

        for (ServiceProvider provider : mServiceProviders) {
            properties.addAll(provider.getSensorProperties());
        }
        return properties;
    }

    @NonNull
    private List<Fingerprint> getEnrolledFingerprintsDeprecated(int userId, String opPackageName) {
        final Pair<Integer, ServiceProvider> provider = getSingleProvider();
        if (provider == null) {
            Slog.w(TAG, "Null provider for getEnrolledFingerprintsDeprecated, caller: "
                    + opPackageName);
            return Collections.emptyList();
        }

        return provider.second.getEnrolledFingerprints(provider.first, userId);
    }

    /**
     * Checks for public API invocations to ensure that permissions, etc are granted/correct.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean canUseFingerprint(String opPackageName, boolean requireForeground, int uid,
            int pid, int userId) {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC);
        }

        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            return true; // System process (BiometricService, etc) is always allowed
        }
        if (Utils.isKeyguard(getContext(), opPackageName)) {
            return true;
        }
        if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; not a current user or profile");
            return false;
        }
        if (!checkAppOps(uid, opPackageName)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            return false;
        }
        if (requireForeground && !Utils.isForeground(uid, pid)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; not in foreground");
            return false;
        }
        return true;
    }

    private boolean checkAppOps(int uid, String opPackageName) {
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
}
