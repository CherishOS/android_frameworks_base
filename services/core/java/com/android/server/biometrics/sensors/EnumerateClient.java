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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class to keep track of the enumeration state for a given client.
 */
public abstract class EnumerateClient extends ClientMonitor implements EnumerateConsumer {

    private static final String TAG = "Biometrics/EnumerateClient";

    public EnumerateClient(@NonNull Context context, @NonNull IBinder token,
            @Nullable ClientMonitorCallbackConverter listener, int userId,
            boolean restricted, @NonNull String owner, int sensorId, int statsModality) {
        super(context, token, listener, userId, restricted, owner, 0 /* cookie */, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_ENUMERATE,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
    }

    @Override
    public int start() {
        // The biometric template ids will be removed when we get confirmation from the HAL
        try {
            final int result = startHalOperation();
            if (result != 0) {
                Slog.w(TAG, "start enumerate for user " + getTargetUserId()
                    + " failed, result=" + result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startEnumeration failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopEnumerate: already cancelled!");
            return 0;
        }

        try {
            final int result = stopHalOperation();
            if (result != 0) {
                Slog.w(TAG, "stop enumeration failed, result=" + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "stopEnumeration failed", e);
            return BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS;
        }

        // We don't actually stop enumerate, but inform the client that the cancel operation
        // succeeded so we can start the next operation.
        if (initiatedByClient) {
            onError(BiometricConstants.BIOMETRIC_ERROR_CANCELED, 0 /* vendorCode */);
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        try {
            if (getListener() != null) {
                getListener().onEnumerated(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify enumerated:", e);
        }
        return remaining == 0;
    }
}
