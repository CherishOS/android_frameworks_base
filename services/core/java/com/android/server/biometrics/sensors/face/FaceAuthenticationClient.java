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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.util.ArrayList;

/**
 * Face-specific authentication client supporting the {@link android.hardware.biometrics.face.V1_0}
 * and {@link android.hardware.biometrics.face.V1_1} HIDL interfaces.
 */
class FaceAuthenticationClient extends AuthenticationClient {

    private final IBiometricsFace mDaemon;
    private final NotificationManager mNotificationManager;
    private final UsageStats mUsageStats;

    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;

    private int mLastAcquire;
    // We need to track this state since it's possible for applications to request for
    // authentication while the device is already locked out. In that case, the client is created
    // but not started yet. The user shouldn't receive the error haptics in this case.
    private boolean mStarted;

    FaceAuthenticationClient(@NonNull Context context, @NonNull IBiometricsFace daemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int targetUserId, long operationId, boolean restricted, String owner, int cookie,
            boolean requireConfirmation, int sensorId, boolean isStrongBiometric, int statsClient,
            @NonNull TaskStackListener taskStackListener,
            @NonNull LockoutTracker lockoutTracker, @NonNull UsageStats usageStats) {
        super(context, token, listener, targetUserId, operationId, restricted, owner, cookie,
                requireConfirmation, sensorId, isStrongBiometric,
                BiometricsProtoEnums.MODALITY_FACE, statsClient, taskStackListener,
                lockoutTracker);
        mDaemon = daemon;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mUsageStats = usageStats;

        final Resources resources = getContext().getResources();
        mBiometricPromptIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_keyguard_ignorelist);
    }

    @Override
    public int start() {
        mStarted = true;
        return super.start();
    }

    @Override
    public int stop(boolean initiatedByClient) {
        mStarted = false;
        return super.stop(initiatedByClient);
    }

    @Override
    protected int startHalOperation() throws RemoteException {
        return mDaemon.authenticate(mOperationId);
    }

    @Override
    protected int stopHalOperation() throws RemoteException {
        return mDaemon.cancel();
    }

    private boolean wasUserDetected() {
        // Do not provide haptic feedback if the user was not detected, and an error (usually
        // ERROR_TIMEOUT) is received.
        return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED
                && mLastAcquire != FaceManager.FACE_ACQUIRED_SENSOR_DIRTY;
    }

    @Override
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        final boolean result = super.onAuthenticated(identifier, authenticated, token);

        if (authenticated) {
            mStarted = false;
        }

        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                authenticated,
                0 /* error */,
                0 /* vendorError */,
                getTargetUserId()));

        // For face, the authentication lifecycle ends either when
        // 1) Authenticated == true
        // 2) Error occurred
        // 3) Authenticated == false
        return result || !authenticated;
    }

    @Override
    public void onError(int error, int vendorCode) {
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(
                getStartTimeMs(),
                System.currentTimeMillis() - getStartTimeMs() /* latency */,
                false /* authenticated */,
                error,
                vendorCode,
                getTargetUserId()));

        switch (error) {
            case BiometricConstants.BIOMETRIC_ERROR_TIMEOUT:
                if (!wasUserDetected() && !isBiometricPrompt()) {
                    // No vibration if user was not detected on keyguard
                    break;
                }
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT:
            case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                if (mStarted) {
                    vibrateError();
                }
                break;
            default:
                break;
        }

        super.onError(error, vendorCode);
    }

    private int[] getAcquireIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreList : mKeyguardIgnoreList;
    }

    private int[] getAcquireVendorIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreListVendor : mKeyguardIgnoreListVendor;
    }

    private boolean shouldSend(int acquireInfo, int vendorCode) {
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            return !Utils.listContains(getAcquireVendorIgnorelist(), vendorCode);
        } else {
            return !Utils.listContains(getAcquireIgnorelist(), acquireInfo);
        }
    }

    @Override
    public boolean onAcquired(int acquireInfo, int vendorCode) {

        mLastAcquire = acquireInfo;

        if (acquireInfo == FaceManager.FACE_ACQUIRED_RECALIBRATE) {
            final String name =
                    getContext().getString(R.string.face_recalibrate_notification_name);
            final String title =
                    getContext().getString(R.string.face_recalibrate_notification_title);
            final String content =
                    getContext().getString(R.string.face_recalibrate_notification_content);

            final Intent intent = new Intent("android.settings.FACE_SETTINGS");
            intent.setPackage("com.android.settings");

            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(getContext(),
                    0 /* requestCode */, intent, 0 /* flags */, null /* options */,
                    UserHandle.CURRENT);

            final String channelName = "FaceEnrollNotificationChannel";

            NotificationChannel channel = new NotificationChannel(channelName, name,
                    NotificationManager.IMPORTANCE_HIGH);
            Notification notification = new Notification.Builder(getContext(), channelName)
                    .setSmallIcon(R.drawable.ic_lock)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSubText(name)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .setContentIntent(pendingIntent)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .build();

            mNotificationManager.createNotificationChannel(channel);
            mNotificationManager.notifyAsUser(FaceService.NOTIFICATION_TAG,
                    FaceService.NOTIFICATION_ID, notification,
                    UserHandle.CURRENT);
        }

        final boolean shouldSend = shouldSend(acquireInfo, vendorCode);
        return onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }
}
