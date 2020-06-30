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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor implements RemovalConsumer {

    private static final String TAG = "Biometrics/RemovalClient";

    protected final int mBiometricId;
    private final BiometricUtils mBiometricUtils;

    public RemovalClient(@NonNull FinishCallback finishCallback, @NonNull Context context,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int biometricId, int userId, boolean restricted, @NonNull String owner,
            @NonNull BiometricUtils utils, int sensorId, int statsModality) {
        super(finishCallback, context, token, listener, userId, restricted, owner, 0 /* cookie */,
                sensorId, statsModality, BiometricsProtoEnums.ACTION_REMOVE,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricId = biometricId;
        mBiometricUtils = utils;
    }

    @Override
    public void start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        startHalOperation();
    }

    @Override
    public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (identifier.getBiometricId() != 0) {
            mBiometricUtils.removeBiometricForUser(getContext(), getTargetUserId(),
                    identifier.getBiometricId());
        }

        try {
            if (getListener() != null) {
                getListener().onRemoved(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }

        if (remaining == 0) {
            mFinishCallback.onClientFinished(this);
        }
    }
}
