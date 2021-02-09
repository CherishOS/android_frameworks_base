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
import android.hardware.biometrics.face.EnrollmentType;
import android.hardware.biometrics.face.Feature;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.hardware.face.FaceEnrollFrame;
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
    private final boolean mDebugConsent;

    FaceEnrollClient(@NonNull Context context, @NonNull LazyDaemon<ISession> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String opPackageName,
            @NonNull BiometricUtils<Face> utils, @NonNull int[] disabledFeatures, int timeoutSec,
            @Nullable NativeHandle previewSurface, int sensorId, int maxTemplatesPerUser,
            boolean debugConsent) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, opPackageName, utils,
                timeoutSec, BiometricsProtoEnums.MODALITY_FACE, sensorId,
                false /* shouldVibrate */);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
        mMaxTemplatesPerUser = maxTemplatesPerUser;
        mDebugConsent = debugConsent;
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

    private boolean shouldSendAcquiredMessage(int acquireInfo, int vendorCode) {
        return acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR
                ? !Utils.listContains(mEnrollIgnoreListVendor, vendorCode)
                : !Utils.listContains(mEnrollIgnoreList, acquireInfo);
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    /**
     * Called each time a new frame is received during face enrollment.
     *
     * @param frame Information about the current frame.
     */
    public void onEnrollmentFrame(@NonNull FaceEnrollFrame frame) {
        // Log acquisition but don't send it to the client yet, since that's handled below.
        final int acquireInfo = frame.getData().getAcquiredInfo();
        final int vendorCode = frame.getData().getVendorCode();
        onAcquiredInternal(acquireInfo, vendorCode, false /* shouldSend */);

        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        if (shouldSend && getListener() != null) {
            try {
                getListener().onEnrollmentFrame(frame);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to send enrollment frame", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    protected void startHalOperation() {
        final ArrayList<Byte> token = new ArrayList<>();
        for (byte b : mHardwareAuthToken) {
            token.add(b);
        }

        try {
            // TODO(b/172593978): Pass features.
            // TODO(b/174619156): Handle accessibility enrollment.
            byte[] features;
            if (mDebugConsent) {
                features = new byte[1];
                features[0] = Feature.DEBUG;
            } else {
                features = new byte[0];
            }

            mCancellationSignal = getFreshDaemon().enroll(mSequentialId,
                    HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken),
                    EnrollmentType.DEFAULT, features, mPreviewSurface);
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
