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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitor;

import java.util.ArrayList;

/**
 * Face-specific resetLockout client supporting the {@link android.hardware.biometrics.face.V1_0}
 * and {@link android.hardware.biometrics.face.V1_1} HIDL interfaces.
 */
public class FaceResetLockoutClient extends ClientMonitor {

    private static final String TAG = "FaceResetLockoutClient";

    private final IBiometricsFace mDaemon;
    private final ArrayList<Byte> mHardwareAuthToken;

    FaceResetLockoutClient(@NonNull FinishCallback finishCallback, @NonNull Context context,
            @NonNull IBiometricsFace daemon, int userId, String owner, int sensorId,
            byte[] hardwareAuthToken) {
        super(finishCallback, context, null /* token */, null /* listener */, userId,
                false /* restricted */, owner, 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mDaemon = daemon;

        mHardwareAuthToken = new ArrayList<>();
        for (byte b : hardwareAuthToken) {
            mHardwareAuthToken.add(b);
        }
    }

    @Override
    public void start() {
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            mDaemon.resetLockout(mHardwareAuthToken);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to reset lockout", e);
        }
        mFinishCallback.onClientFinished(this);
    }

    @Override
    protected void stopHalOperation() {
        // Not supported for resetLockout
    }
}
