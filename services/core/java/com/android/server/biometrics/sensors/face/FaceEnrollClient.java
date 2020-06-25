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

import android.content.Context;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Face-specific enroll client supporting the {@link android.hardware.biometrics.face.V1_0}
 * and {@link android.hardware.biometrics.face.V1_1} HIDL interfaces.
 */
public class FaceEnrollClient extends EnrollClient {

    private static final String TAG = "FaceEnrollClient";

    private final IBiometricsFace mDaemon;
    private final int[] mDisabledFeatures;
    private final NativeHandle mSurfaceHandle;
    private final int[] mEnrollIgnoreList;
    private final int[] mEnrollIgnoreListVendor;

    FaceEnrollClient(Context context, IBiometricsFace daemon, IBinder token,
            ClientMonitorCallbackConverter listener, int userId, byte[] cryptoToken,
            boolean restricted, String owner, BiometricUtils utils, int[] disabledFeatures,
            int timeoutSec, int statsModality, NativeHandle surfaceHandle, int sensorId) {
        super(context, token, listener, userId, cryptoToken, restricted, owner, utils,
                timeoutSec, statsModality, sensorId, false /* shouldVibrate */);
        mDaemon = daemon;
        mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
        mSurfaceHandle = surfaceHandle;
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
    }

    @Override
    public boolean onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend;
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            shouldSend = !Utils.listContains(mEnrollIgnoreListVendor, vendorCode);
        } else {
            shouldSend = !Utils.listContains(mEnrollIgnoreList, acquireInfo);
        }
        return onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    @Override
    protected int startHalOperation() throws RemoteException {
        final ArrayList<Byte> token = new ArrayList<>();
        for (byte b : mHardwareAuthToken) {
            token.add(b);
        }
        final ArrayList<Integer> disabledFeatures = new ArrayList<>();
        for (int disabledFeature : mDisabledFeatures) {
            disabledFeatures.add(disabledFeature);
        }

        android.hardware.biometrics.face.V1_1.IBiometricsFace daemon11 =
                android.hardware.biometrics.face.V1_1.IBiometricsFace.castFrom(mDaemon);
        if (daemon11 != null) {
            return daemon11.enroll_1_1(token, mTimeoutSec, disabledFeatures, mSurfaceHandle);
        } else if (mSurfaceHandle == null) {
            return mDaemon.enroll(token, mTimeoutSec, disabledFeatures);
        } else {
            Slog.e(TAG, "enroll(): surface is only supported in @1.1 HAL");
            return BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS;
        }
    }

    @Override
    protected int stopHalOperation() throws RemoteException {
        return mDaemon.cancel();
    }
}
