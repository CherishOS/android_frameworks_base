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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalEnumerateClient;

import java.util.List;

/**
 * Face-specific internal enumerate client supporting the
 * {@link android.hardware.biometrics.face.V1_0} and {@link android.hardware.biometrics.face.V1_1}
 * HIDL interfaces.
 */
class FaceInternalEnumerateClient extends InternalEnumerateClient<IBiometricsFace> {
    private static final String TAG = "FaceInternalEnumerateClient";

    FaceInternalEnumerateClient(@NonNull Context context, @NonNull IBinder token, int userId,
            @NonNull String owner,
            @NonNull List<? extends BiometricAuthenticator.Identifier> enrolledList,
            @NonNull BiometricUtils utils, int sensorId) {
        super(context, token, userId, owner, enrolledList, utils, sensorId,
                BiometricsProtoEnums.MODALITY_FACE);
    }

    @Override
    protected void startHalOperation() {
        try {
            mDaemon.enumerate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enumerate", e);
            mFinishCallback.onClientFinished(this);
        }
    }

    @Override
    protected void stopHalOperation() {
        try {
            mDaemon.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            mFinishCallback.onClientFinished(this);
        }
    }
}
