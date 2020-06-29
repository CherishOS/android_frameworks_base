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
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;

/**
 * Face-specific generateChallenge client supporting the
 * {@link android.hardware.biometrics.face.V1_0} and {@link android.hardware.biometrics.face.V1_1}
 * HIDL interfaces.
 */
public class FaceGenerateChallengeClient extends GenerateChallengeClient {

    private static final String TAG = "FaceGenerateChallengeClient";
    private static final int CHALLENGE_TIMEOUT_SEC = 600; // 10 minutes

    private final IBiometricsFace mDaemon;

    FaceGenerateChallengeClient(@NonNull FinishCallback finishCallback,
            @NonNull Context context, @NonNull IBiometricsFace daemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, @NonNull String owner, int sensorId) {
        super(finishCallback, context, token, listener, owner, sensorId);
        mDaemon = daemon;
    }

    @Override
    protected void startHalOperation() {
        try {
            mChallenge = mDaemon.generateChallenge(CHALLENGE_TIMEOUT_SEC).value;
        } catch (RemoteException e) {
            Slog.e(TAG, "generateChallenge failed", e);
        }
    }
}
