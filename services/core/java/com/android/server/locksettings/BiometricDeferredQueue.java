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

package com.android.server.locksettings;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.service.gatekeeper.IGateKeeperService;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.widget.VerifyCredentialResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class that handles biometric-related work in the {@link LockSettingsService} area, for example
 * resetLockout.
 */
@SuppressWarnings("deprecation")
public class BiometricDeferredQueue {
    private static final String TAG = "BiometricDeferredQueue";

    @NonNull private final Context mContext;
    @NonNull private final SyntheticPasswordManager mSpManager;
    @NonNull private final Handler mHandler;
    @Nullable private FingerprintManager mFingerprintManager;
    @Nullable private FaceManager mFaceManager;

    // Entries added by LockSettingsService once a user's synthetic password is known. At this point
    // things are still keyed by userId.
    @NonNull private final ArrayList<UserAuthInfo> mPendingResetLockouts;

    /**
     * Authentication info for a successful user unlock via Synthetic Password. This can be used to
     * perform multiple operations (e.g. resetLockout for multiple HALs/Sensors) by sending the
     * Gatekeeper Password to Gatekeer multiple times, each with a sensor-specific challenge.
     */
    private static class UserAuthInfo {
        final int userId;
        @NonNull final byte[] gatekeeperPassword;

        UserAuthInfo(int userId, @NonNull byte[] gatekeeperPassword) {
            this.userId = userId;
            this.gatekeeperPassword = gatekeeperPassword;
        }
    }

    /**
     * Per-authentication callback.
     */
    private static class FaceResetLockoutTask implements FaceManager.GenerateChallengeCallback {
        interface FinishCallback {
            void onFinished();
        }

        @NonNull FinishCallback finishCallback;
        @NonNull FaceManager faceManager;
        @NonNull SyntheticPasswordManager spManager;
        @NonNull Set<Integer> sensorIds; // IDs of sensors waiting for challenge
        @NonNull List<UserAuthInfo> pendingResetLockuts;

        FaceResetLockoutTask(
                @NonNull FinishCallback finishCallback,
                @NonNull FaceManager faceManager,
                @NonNull SyntheticPasswordManager spManager,
                @NonNull Set<Integer> sensorIds,
                @NonNull List<UserAuthInfo> pendingResetLockouts) {
            this.finishCallback = finishCallback;
            this.faceManager = faceManager;
            this.spManager = spManager;
            this.sensorIds = sensorIds;
            this.pendingResetLockuts = pendingResetLockouts;
        }

        @Override
        public void onChallengeInterrupted(int sensorId) {
            Slog.w(TAG, "Challenge interrupted, sensor: " + sensorId);
            // Consider re-attempting generateChallenge/resetLockout/revokeChallenge
            // when onChallengeInterruptFinished is invoked
        }

        @Override
        public void onChallengeInterruptFinished(int sensorId) {
            Slog.w(TAG, "Challenge interrupt finished, sensor: " + sensorId);
        }

        @Override
        public void onGenerateChallengeResult(int sensorId, long challenge) {
            if (!sensorIds.contains(sensorId)) {
                Slog.e(TAG, "Unknown sensorId received: " + sensorId);
                return;
            }

            // Challenge received for a sensor. For each sensor, reset lockout for all users.
            for (UserAuthInfo userAuthInfo : pendingResetLockuts) {
                Slog.d(TAG, "Resetting face lockout for sensor: " + sensorId
                        + ", user: " + userAuthInfo.userId);
                final VerifyCredentialResponse response = spManager.verifyChallengeInternal(
                        getGatekeeperService(), userAuthInfo.gatekeeperPassword, challenge,
                        userAuthInfo.userId);
                if (response == null) {
                    Slog.wtf(TAG, "VerifyChallenge failed, null response");
                    continue;
                }
                if (response.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                    Slog.wtf(TAG, "VerifyChallenge failed, response: "
                            + response.getResponseCode());
                }
                faceManager.resetLockout(sensorId, userAuthInfo.userId,
                        response.getGatekeeperHAT());
            }

            sensorIds.remove(sensorId);
            faceManager.revokeChallenge(sensorId);

            if (sensorIds.isEmpty()) {
                Slog.d(TAG, "Done requesting resetLockout for all face sensors");
                finishCallback.onFinished();
            }
        }

        synchronized IGateKeeperService getGatekeeperService() {
            final IBinder service = ServiceManager.getService(Context.GATEKEEPER_SERVICE);
            if (service == null) {
                Slog.e(TAG, "Unable to acquire GateKeeperService");
                return null;
            }
            return IGateKeeperService.Stub.asInterface(service);
        }
    }

    @Nullable private FaceResetLockoutTask mFaceResetLockoutTask;

    private final FaceResetLockoutTask.FinishCallback mFaceFinishCallback = () -> {
        mFaceResetLockoutTask = null;
    };

    BiometricDeferredQueue(@NonNull Context context, @NonNull SyntheticPasswordManager spManager,
            @NonNull Handler handler) {
        mContext = context;
        mSpManager = spManager;
        mHandler = handler;
        mPendingResetLockouts = new ArrayList<>();
    }

    public void systemReady(@Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager) {
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
    }

    /**
     * Adds a request for resetLockout on all biometric sensors for the user specified. The queue
     * owner must invoke {@link #processPendingLockoutResets()} at some point to kick off the
     * operations.
     *
     * Note that this should only ever be invoked for successful authentications, otherwise it will
     * consume a Gatekeeper authentication attempt and potentially wipe the user/device.
     *
     * @param userId The user that the operation will apply for.
     * @param gatekeeperPassword The Gatekeeper Password
     */
    void addPendingLockoutResetForUser(int userId, @NonNull byte[] gatekeeperPassword) {
        mHandler.post(() -> {
            Slog.d(TAG, "addPendingLockoutResetForUser: " + userId);
            mPendingResetLockouts.add(new UserAuthInfo(userId, gatekeeperPassword));
        });
    }

    void processPendingLockoutResets() {
        mHandler.post(() -> {
            Slog.d(TAG, "processPendingLockoutResets: " + mPendingResetLockouts.size());
            processPendingLockoutsForFingerprint(new ArrayList<>(mPendingResetLockouts));
            processPendingLockoutsForFace(new ArrayList<>(mPendingResetLockouts));
            mPendingResetLockouts.clear();
        });
    }

    private void processPendingLockoutsForFingerprint(List<UserAuthInfo> pendingResetLockouts) {
        if (mFingerprintManager != null) {
            final List<FingerprintSensorProperties> fingerprintSensorProperties =
                    mFingerprintManager.getSensorProperties();
            for (FingerprintSensorProperties prop : fingerprintSensorProperties) {
                if (!prop.resetLockoutRequiresHardwareAuthToken) {
                    for (UserAuthInfo user : pendingResetLockouts) {
                        mFingerprintManager.resetLockout(prop.sensorId, user.userId,
                                null /* hardwareAuthToken */);
                    }
                } else {
                    Slog.e(TAG, "Fingerprint resetLockout with HAT not supported yet");
                    // TODO(b/152414803): Implement this when resetLockout is implemented below
                    //  the framework.
                }
            }
        }
    }

    /**
     * For devices on {@link android.hardware.biometrics.face.V1_0} which only support a single
     * in-flight challenge, we generate a single challenge to reset lockout for all profiles. This
     * hopefully reduces/eliminates issues such as overwritten challenge, incorrectly revoked
     * challenge, or other race conditions.
     *
     * TODO(b/162965646) This logic can be avoided if multiple in-flight challenges are supported.
     *  Though it will need to continue to exist to support existing HIDLs, each profile that
     *  requires resetLockout could have its own challenge, and the `mPendingResetLockouts` queue
     *  can be avoided.
     */
    private void processPendingLockoutsForFace(List<UserAuthInfo> pendingResetLockouts) {
        if (mFaceManager != null) {
            if (mFaceResetLockoutTask != null) {
                // This code will need to be updated if this problem ever occurs.
                Slog.w(TAG, "mFaceGenerateChallengeCallback not null, previous operation may be"
                        + " stuck");
            }
            final List<FaceSensorProperties> faceSensorProperties =
                    mFaceManager.getSensorProperties();
            final Set<Integer> sensorIds = new ArraySet<>();
            for (FaceSensorProperties prop : faceSensorProperties) {
                sensorIds.add(prop.sensorId);
            }

            mFaceResetLockoutTask = new FaceResetLockoutTask(mFaceFinishCallback, mFaceManager,
                    mSpManager, sensorIds, pendingResetLockouts);
            for (final FaceSensorProperties prop : faceSensorProperties) {
                // Generate a challenge for each sensor. The challenge does not need to be
                // per-user, since the HAT returned by gatekeeper contains userId.
                mFaceManager.generateChallenge(prop.sensorId, mFaceResetLockoutTask);
            }
        }
    }
}
