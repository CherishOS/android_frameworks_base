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

package com.android.server.biometrics.sensors.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face-related events.
 */
public class FaceService extends SystemService {

    protected static final String TAG = "FaceService";

    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final List<ServiceProvider> mServiceProviders;

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
        final List<FaceSensorPropertiesInternal> properties = getSensorProperties();
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
    private List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> properties = new ArrayList<>();
        for (ServiceProvider provider : mServiceProviders) {
            properties.addAll(provider.getSensorProperties());
        }
        return properties;
    }

    @NonNull
    private List<Face> getEnrolledFaces(int userId, String opPackageName) {
        final Pair<Integer, ServiceProvider> provider = getSingleProvider();
        if (provider == null) {
            Slog.w(TAG, "Null provider for getEnrolledFaces, caller: " + opPackageName);
            return Collections.emptyList();
        }

        return provider.second.getEnrolledFaces(provider.first, userId);
    }

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    private final class FaceServiceWrapper extends IFaceService.Stub {
        @Override // Binder call
        public List<FaceSensorPropertiesInternal> getSensorPropertiesInternal(
                String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final List<FaceSensorPropertiesInternal> properties =
                    FaceService.this.getSensorProperties();

            Slog.d(TAG, "Retrieved sensor properties for: " + opPackageName
                    + ", sensors: " + properties.size());
            return properties;
        }

        @Override // Binder call
        public void generateChallenge(IBinder token, int sensorId, int userId,
                IFaceServiceReceiver receiver, String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

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
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName, challenge);
        }

        @Override // Binder call
        public void enroll(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures, Surface surface) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return;
            }

            provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, disabledFeatures,
                    convertSurfaceToNativeHandle(surface));
        }

        @Override // Binder call
        public void enrollRemotely(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            // TODO(b/145027036): Implement this.
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelEnrollment");
                return;
            }

            provider.second.cancelEnrollment(provider.first, token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long operationId, int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            // TODO(b/152413782): If the sensor supports face detect and the device is encrypted or
            //  lockdown, something wrong happened. See similar path in FingerprintService.

            final boolean restricted = false; // Face APIs are private
            final int statsClient = Utils.isKeyguard(getContext(), opPackageName)
                    ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_UNKNOWN;

            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return;
            }

            provider.second.scheduleAuthenticate(provider.first, token, operationId, userId,
                    0 /* cookie */,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, restricted,
                    statsClient, isKeyguard);
        }

        @Override // Binder call
        public void detectFace(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFace called from non-sysui package: " + opPackageName);
                return;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFace invoked when user is not encrypted or lockdown");
                return;
            }

            // TODO(b/152413782): Implement this once it's supported in the HAL
        }

        @Override // Binder call
        public void prepareForAuthentication(int sensorId, boolean requireConfirmation,
                IBinder token, long operationId, int userId,
                IBiometricSensorReceiver sensorReceiver, String opPackageName, int cookie,
                int callingUid, int callingPid, int callingUserId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

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
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for startPreparedClient");
                return;
            }

            provider.startPreparedClient(sensorId, cookie);
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token);
        }

        @Override // Binder call
        public void cancelFaceDetect(final IBinder token, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFaceDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            // TODO(b/152413782): Implement this once it's supported in the HAL
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(int sensorId, final IBinder token,
                final String opPackageName, int callingUid, int callingPid, int callingUserId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int faceId, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }

            provider.second.scheduleRemove(provider.first, token, faceId, userId, receiver,
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
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            pw.println("Dumping for sensorId: " + props.sensorId
                                    + ", provider: " + provider.getClass().getSimpleName());
                            provider.dumpInternal(props.sensorId, pw);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final long token = Binder.clearCallingIdentity();
            try {
                final ServiceProvider provider = getProviderForSensor(sensorId);
                if (provider == null) {
                    Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                    return false;
                }
                return provider.isHardwareDetected(sensorId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public List<Face> getEnrolledFaces(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            return FaceService.this.getEnrolledFaces(userId, opPackageName);
        }

        @Override // Binder call
        public boolean hasEnrolledFaces(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            return !FaceService.this.getEnrolledFaces(userId, opPackageName).isEmpty();
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
        public void resetLockout(IBinder token, int sensorId, int userId, byte[] hardwareAuthToken,
                String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @Override
        public void setFeature(final IBinder token, int userId, int feature, boolean enabled,
                final byte[] hardwareAuthToken, IFaceServiceReceiver receiver,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for setFeature");
                return;
            }

            provider.second.scheduleSetFeature(provider.first, token, userId, feature, enabled,
                    hardwareAuthToken, receiver, opPackageName);
        }

        @Override
        public void getFeature(final IBinder token, int userId, int feature,
                IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for getFeature");
                return;
            }

            provider.second.scheduleGetFeature(provider.first, token, userId, feature,
                    new ClientMonitorCallbackConverter(receiver), opPackageName);
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId,
                @BiometricManager.Authenticators.Types int strength) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mServiceProviders.add(
                    new com.android.server.biometrics.sensors.face.hidl.Face10(getContext(),
                            sensorId, strength, mLockoutResetDispatcher));
        }
    }

    public FaceService(Context context) {
        super(context);
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mServiceProviders = new ArrayList<>();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FACE_SERVICE, new FaceServiceWrapper());
    }

    private native NativeHandle convertSurfaceToNativeHandle(Surface surface);
}
