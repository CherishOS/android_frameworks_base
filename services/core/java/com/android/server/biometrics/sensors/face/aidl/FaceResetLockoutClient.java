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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * Face-specific resetLockout client for the {@link IFace} AIDL HAL interface.
 * Updates the framework's lockout cache and notifies clients such as Keyguard when lockout is
 * cleared.
 */
public class FaceResetLockoutClient extends ClientMonitor<ISession> {

    private static final String TAG = "FaceResetLockoutClient";

    private final HardwareAuthToken mHardwareAuthToken;
    private final LockoutCache mLockoutCache;
    private final LockoutResetDispatcher mLockoutResetDispatcher;

    FaceResetLockoutClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, int userId, String owner, int sensorId,
            @NonNull byte[] hardwareAuthToken, @NonNull LockoutCache lockoutTracker,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_UNKNOWN,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
        mHardwareAuthToken = HardwareAuthTokenUtils.toHardwareAuthToken(hardwareAuthToken);
        mLockoutCache = lockoutTracker;
        mLockoutResetDispatcher = lockoutResetDispatcher;
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().resetLockout(mSequentialId, mHardwareAuthToken);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to reset lockout", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    void onLockoutCleared() {
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_NONE);
        mLockoutResetDispatcher.notifyLockoutResetCallbacks(getSensorId());
        mCallback.onClientFinished(this, true /* success */);
    }
}
