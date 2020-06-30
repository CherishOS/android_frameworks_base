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

package com.android.server.biometrics.sensors.iris;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.iris.IIrisService;

import com.android.server.biometrics.sensors.BiometricServiceBase;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.FingerprintService;

import java.util.List;

/**
 * A service to manage multiple clients that want to access the Iris HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * iris-related events.
 *
 * TODO: The vendor is expected to fill in the service. See
 * {@link FingerprintService}
 *
 * @hide
 */
public class IrisService extends BiometricServiceBase {

    private static final String TAG = "IrisService";

    /**
     * Receives the incoming binder calls from IrisManager.
     */
    private final class IrisServiceWrapper extends IIrisService.Stub {
        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            initializeConfigurationInternal(sensorId);
        }
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public IrisService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        super.onStart();
        publishBinderService(Context.IRIS_SERVICE, new IrisServiceWrapper());
    }

    @Override
    protected void doTemplateCleanupForUser(int userId) {

    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected BiometricUtils getBiometricUtils() {
        return null;
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        return false;
    }

    @Override
    protected void updateActiveGroup(int userId, String clientPackage) {

    }

    @Override
    protected boolean hasEnrolledBiometrics(int userId) {
        return false;
    }

    @Override
    protected String getManageBiometricPermission() {
        return null;
    }

    @Override
    protected void checkUseBiometricPermission() {

    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
        return false;
    }

    @Override
    protected List<? extends BiometricAuthenticator.Identifier> getEnrolledTemplates(int userId) {
        return null;
    }

    @Override
    protected int statsModality() {
        return BiometricsProtoEnums.MODALITY_IRIS;
    }

    @Override
    protected int getLockoutMode(int userId) {
        return LockoutTracker.LOCKOUT_NONE;
    }
}
