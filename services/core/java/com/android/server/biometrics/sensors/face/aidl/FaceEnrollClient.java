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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Face-specific enroll client for the {@link IFace} AIDL HAL interface.
 */
public class FaceEnrollClient extends EnrollClient<ISession> {

    private static final String TAG = "FaceEnrollClient";

    @NonNull private final int[] mEnrollIgnoreList;
    @NonNull private final int[] mEnrollIgnoreListVendor;
    @Nullable private ICancellationSignal mCancellationSignal;
    @Nullable private android.hardware.common.NativeHandle mPreviewSurface;
    private final int mMaxTemplatesPerUser;

    FaceEnrollClient(@NonNull Context context, @NonNull LazyDaemon<ISession> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String opPackageName,
            @NonNull BiometricUtils<Face> utils, @NonNull int[] disabledFeatures, int timeoutSec,
            @Nullable NativeHandle previewSurface, int sensorId, int maxTemplatesPerUser) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, opPackageName, utils,
                timeoutSec, BiometricsProtoEnums.MODALITY_FACE, sensorId,
                false /* shouldVibrate */, true /* shouldLogMetrics */);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
        mMaxTemplatesPerUser = maxTemplatesPerUser;
        try {
            // We must manually close the duplicate handle after it's no longer needed.
            // The caller is responsible for closing the original handle.
            mPreviewSurface = AidlNativeHandleUtils.dup(previewSurface);
        } catch (IOException e) {
            mPreviewSurface = null;
            Slog.e(TAG, "Failed to dup previewSurface", e);
        }
    }

    @Override
    public void destroy() {
        try {
            AidlNativeHandleUtils.close(mPreviewSurface);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to close mPreviewSurface", e);
        }
        super.destroy();
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        return FaceUtils.getInstance(getSensorId()).getBiometricsForUser(getContext(),
                getTargetUserId()).size() >= mMaxTemplatesPerUser;
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend;
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            shouldSend = !Utils.listContains(mEnrollIgnoreListVendor, vendorCode);
        } else {
            shouldSend = !Utils.listContains(mEnrollIgnoreList, acquireInfo);
        }
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    @Override
    protected void startHalOperation() {
        final ArrayList<Byte> token = new ArrayList<>();
        for (byte b : mHardwareAuthToken) {
            token.add(b);
        }

        try {
            // TODO(b/172593978): Pass features.
            mCancellationSignal = getFreshDaemon().enroll(mSequentialId,
                    HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken),
                    mPreviewSurface);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
                onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }
}
