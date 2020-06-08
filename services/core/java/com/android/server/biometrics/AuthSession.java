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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Random;

/**
 * Class that defines the states of an authentication session invoked via
 * {@link android.hardware.biometrics.BiometricPrompt}, as well as all of the necessary
 * state information for such a session.
 */
final class AuthSession implements IBinder.DeathRecipient {
    private static final String TAG = "BiometricService/AuthSession";

    /**
     * Authentication either just called and we have not transitioned to the CALLED state, or
     * authentication terminated (success or error).
     */
    static final int STATE_AUTH_IDLE = 0;
    /**
     * Authentication was called and we are waiting for the <Biometric>Services to return their
     * cookies before starting the hardware and showing the BiometricPrompt.
     */
    static final int STATE_AUTH_CALLED = 1;
    /**
     * Authentication started, BiometricPrompt is showing and the hardware is authenticating.
     */
    static final int STATE_AUTH_STARTED = 2;
    /**
     * Authentication is paused, waiting for the user to press "try again" button. Only
     * passive modalities such as Face or Iris should have this state. Note that for passive
     * modalities, the HAL enters the idle state after onAuthenticated(false) which differs from
     * fingerprint.
     */
    static final int STATE_AUTH_PAUSED = 3;
    /**
     * Paused, but "try again" was pressed. Sensors have new cookies and we're now waiting for all
     * cookies to be returned.
     */
    static final int STATE_AUTH_PAUSED_RESUMING = 4;
    /**
     * Authentication is successful, but we're waiting for the user to press "confirm" button.
     */
    static final int STATE_AUTH_PENDING_CONFIRM = 5;
    /**
     * Biometric authenticated, waiting for SysUI to finish animation
     */
    static final int STATE_AUTHENTICATED_PENDING_SYSUI = 6;
    /**
     * Biometric error, waiting for SysUI to finish animation
     */
    static final int STATE_ERROR_PENDING_SYSUI = 7;
    /**
     * Device credential in AuthController is showing
     */
    static final int STATE_SHOWING_DEVICE_CREDENTIAL = 8;
    /**
     * The client binder died, and sensors were authenticating at the time. Cancel has been
     * requested and we're waiting for the HAL(s) to send ERROR_CANCELED.
     */
    static final int STATE_CLIENT_DIED_CANCELLING = 9;

    @IntDef({
            STATE_AUTH_IDLE,
            STATE_AUTH_CALLED,
            STATE_AUTH_STARTED,
            STATE_AUTH_PAUSED,
            STATE_AUTH_PAUSED_RESUMING,
            STATE_AUTH_PENDING_CONFIRM,
            STATE_AUTHENTICATED_PENDING_SYSUI,
            STATE_ERROR_PENDING_SYSUI,
            STATE_SHOWING_DEVICE_CREDENTIAL})
    @Retention(RetentionPolicy.SOURCE)
    @interface SessionState {}

    /**
     * Notify the holder of the AuthSession that the caller/client's binder has died. The
     * holder (BiometricService) should schedule {@link AuthSession#onClientDied()} to be run
     * on its handler (instead of whatever thread invokes the death recipient callback).
     */
    interface ClientDeathReceiver {
        void onClientDied();
    }

    private final Context mContext;
    private final IStatusBarService mStatusBarService;
    private final IBiometricSysuiReceiver mSysuiReceiver;
    private final KeyStore mKeyStore;
    private final Random mRandom;
    private final ClientDeathReceiver mClientDeathReceiver;
    final PreAuthInfo mPreAuthInfo;

    // The following variables are passed to authenticateInternal, which initiates the
    // appropriate <Biometric>Services.
    @VisibleForTesting final IBinder mToken;
    // Info to be shown on BiometricDialog when all cookies are returned.
    @VisibleForTesting final PromptInfo mPromptInfo;
    private final long mOperationId;
    private final int mUserId;
    private final IBiometricSensorReceiver mSensorReceiver;
    // Original receiver from BiometricPrompt.
    private final IBiometricServiceReceiver mClientReceiver;
    private final String mOpPackageName;
    private final int mCallingUid;
    private final int mCallingPid;
    private final int mCallingUserId;
    private final boolean mDebugEnabled;

    // The current state, which can be either idle, called, or started
    private @SessionState int mState = STATE_AUTH_IDLE;
    // For explicit confirmation, do not send to keystore until the user has confirmed
    // the authentication.
    private byte[] mTokenEscrow;
    // Waiting for SystemUI to complete animation
    private int mErrorEscrow;
    private int mVendorCodeEscrow;

    // Timestamp when authentication started
    private long mStartTimeMs;
    // Timestamp when hardware authentication occurred
    private long mAuthenticatedTimeMs;

    AuthSession(Context context, IStatusBarService statusBarService,
            IBiometricSysuiReceiver sysuiReceiver, KeyStore keystore, Random random,
            ClientDeathReceiver clientDeathReceiver, PreAuthInfo preAuthInfo, IBinder token,
            long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
            IBiometricServiceReceiver clientReceiver, String opPackageName, PromptInfo promptInfo,
            int callingUid, int callingPid, int callingUserId, boolean debugEnabled) {
        mContext = context;
        mStatusBarService = statusBarService;
        mSysuiReceiver = sysuiReceiver;
        mKeyStore = keystore;
        mRandom = random;
        mClientDeathReceiver = clientDeathReceiver;
        mPreAuthInfo = preAuthInfo;
        mToken = token;
        mOperationId = operationId;
        mUserId = userId;
        mSensorReceiver = sensorReceiver;
        mClientReceiver = clientReceiver;
        mOpPackageName = opPackageName;
        mPromptInfo = promptInfo;
        mCallingUid = callingUid;
        mCallingPid = callingPid;
        mCallingUserId = callingUserId;
        mDebugEnabled = debugEnabled;

        try {
            mClientReceiver.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to link to death");
        }

        setSensorsToStateUnknown();
    }

    @Override
    public void binderDied() {
        Slog.e(TAG, "Binder died, session: " + this);
        mClientDeathReceiver.onClientDied();
    }

    /**
     * @return bitmask representing the modalities that are running or could be running for the
     * current session.
     */
    private @BiometricAuthenticator.Modality int getEligibleModalities() {
        return mPreAuthInfo.getEligibleModalities();
    }

    private void setSensorsToStateUnknown() {
        // Generate random cookies to pass to the services that should prepare to start
        // authenticating. Store the cookie here and wait for all services to "ack"
        // with the cookie. Once all cookies are received, we can show the prompt
        // and let the services start authenticating. The cookie should be non-zero.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            sensor.goToStateUnknown();
        }
    }

    private void setSensorsToStateWaitingForCookie() throws RemoteException {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            final int cookie = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            final boolean requireConfirmation = isConfirmationRequired(sensor);
            sensor.goToStateWaitingForCookie(requireConfirmation, mToken, mOperationId,
                    mUserId, mSensorReceiver, mOpPackageName, cookie, mCallingUid, mCallingPid,
                    mCallingUserId);
        }
    }

    void goToInitialState() throws RemoteException {
        if (mPreAuthInfo.credentialAvailable && mPreAuthInfo.eligibleSensors.isEmpty()) {
            // Only device credential should be shown. In this case, we don't need to wait,
            // since LockSettingsService/Gatekeeper is always ready to check for credential.
            // SystemUI invokes that path.
            mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;

            mStatusBarService.showAuthenticationDialog(
                    mPromptInfo,
                    mSysuiReceiver,
                    0 /* biometricModality */,
                    false /* requireConfirmation */,
                    mUserId,
                    mOpPackageName,
                    mOperationId);
        } else if (!mPreAuthInfo.eligibleSensors.isEmpty()) {
            // Some combination of biometric or biometric|credential is requested
            setSensorsToStateWaitingForCookie();
            mState = AuthSession.STATE_AUTH_CALLED;
        } else {
            // No authenticators requested. This should never happen - an exception should have
            // been thrown earlier in the pipeline.
            throw new IllegalStateException("No authenticators requested");
        }
    }

    void onCookieReceived(int cookie) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            sensor.goToStateCookieReturnedIfCookieMatches(cookie);
        }

        if (allCookiesReceived()) {
            mStartTimeMs = System.currentTimeMillis();
            startAllPreparedSensors();

            // No need to request the UI if we're coming from the paused state.
            if (mState != STATE_AUTH_PAUSED_RESUMING) {
                try {
                    // If any sensor requires confirmation, request it to be shown.
                    final boolean requireConfirmation = isConfirmationRequiredByAnyEligibleSensor();
                    final @BiometricAuthenticator.Modality int modality =
                            getEligibleModalities();
                    mStatusBarService.showAuthenticationDialog(mPromptInfo,
                            mSysuiReceiver,
                            modality,
                            requireConfirmation,
                            mUserId,
                            mOpPackageName,
                            mOperationId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
            mState = STATE_AUTH_STARTED;
        }
    }

    private boolean isConfirmationRequired(BiometricSensor sensor) {
        return sensor.confirmationSupported()
                && (sensor.confirmationAlwaysRequired(mUserId)
                || mPreAuthInfo.confirmationRequested);
    }

    private boolean isConfirmationRequiredByAnyEligibleSensor() {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (isConfirmationRequired(sensor)) {
                return true;
            }
        }
        return false;
    }

    private void startAllPreparedSensors() {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            try {
                sensor.startSensor();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to start prepared client, sensor ID: "
                        + sensor.id, e);
            }
        }
    }

    private void cancelAllSensors() {
        // TODO: For multiple modalities, send a single ERROR_CANCELED only when all
        // drivers have canceled authentication. We'd probably have to add a state for
        // STATE_CANCELING for when we're waiting for final ERROR_CANCELED before
        // sending the final error callback to the application.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            try {
                sensor.goToStateCancelling(mToken, mOpPackageName, mCallingUid, mCallingPid,
                        mCallingUserId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to cancel authentication");
            }
        }
    }

    /**
     * @return true if this AuthSession is finished, e.g. should be set to null.
     */
    boolean onErrorReceived(int sensorId, int cookie, @BiometricConstants.Errors int error,
            int vendorCode) throws RemoteException {

        if (!containsCookie(cookie)) {
            Slog.e(TAG, "Unknown/expired cookie: " + cookie);
            return false;
        }

        // TODO: The sensor-specific state is not currently used, this would need to be updated if
        // multiple authenticators are running.
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.getSensorState() == BiometricSensor.STATE_AUTHENTICATING) {
                sensor.goToStoppedStateIfCookieMatches(cookie, error);
            }
        }

        mErrorEscrow = error;
        mVendorCodeEscrow = vendorCode;

        final @BiometricAuthenticator.Modality int modality = sensorIdToModality(sensorId);

        switch (mState) {
            case STATE_AUTH_CALLED: {
                // If any error is received while preparing the auth session (lockout, etc),
                // and if device credential is allowed, just show the credential UI.
                if (isAllowDeviceCredential()) {
                    @BiometricManager.Authenticators.Types int authenticators =
                            mPromptInfo.getAuthenticators();
                    // Disallow biometric and notify SystemUI to show the authentication prompt.
                    authenticators = Utils.removeBiometricBits(authenticators);
                    mPromptInfo.setAuthenticators(authenticators);

                    mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;

                    mStatusBarService.showAuthenticationDialog(
                            mPromptInfo,
                            mSysuiReceiver,
                            0 /* biometricModality */,
                            false /* requireConfirmation */,
                            mUserId,
                            mOpPackageName,
                            mOperationId);
                } else {
                    mClientReceiver.onError(modality, error, vendorCode);
                    return true;
                }
                break;
            }

            case STATE_AUTH_STARTED: {
                final boolean errorLockout = error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                        || error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
                if (isAllowDeviceCredential() && errorLockout) {
                    // SystemUI handles transition from biometric to device credential.
                    mState = AuthSession.STATE_SHOWING_DEVICE_CREDENTIAL;
                    mStatusBarService.onBiometricError(modality, error, vendorCode);
                } else if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
                    mStatusBarService.hideAuthenticationDialog();
                    // TODO: If multiple authenticators are simultaneously running, this will
                    // need to be modified. Send the error to the client here, instead of doing
                    // a round trip to SystemUI.
                    mClientReceiver.onError(modality, error, vendorCode);
                    return true;
                } else {
                    mState = AuthSession.STATE_ERROR_PENDING_SYSUI;
                    mStatusBarService.onBiometricError(modality, error, vendorCode);
                }
                break;
            }

            case STATE_AUTH_PAUSED: {
                // In the "try again" state, we should forward canceled errors to
                // the client and clean up. The only error we should get here is
                // ERROR_CANCELED due to another client kicking us out.
                mClientReceiver.onError(modality, error, vendorCode);
                mStatusBarService.hideAuthenticationDialog();
                return true;
            }

            case STATE_SHOWING_DEVICE_CREDENTIAL:
                Slog.d(TAG, "Biometric canceled, ignoring from state: " + mState);
                break;

            case STATE_CLIENT_DIED_CANCELLING:
                mStatusBarService.hideAuthenticationDialog();
                return true;

            default:
                Slog.e(TAG, "Unhandled error state, mState: " + mState);
                break;
        }

        return false;
    }

    void onAcquired(int sensorId, int acquiredInfo, int vendorCode) {
        final String message = getAcquiredMessageForSensor(sensorId, acquiredInfo, vendorCode);
        Slog.d(TAG, "sensorId: " + sensorId + " acquiredInfo: " + acquiredInfo
                + " message: " + message);
        if (message == null) {
            return;
        }

        try {
            mStatusBarService.onBiometricHelp(message);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    void onSystemEvent(int event) {
        if (!mPromptInfo.isReceiveSystemEvents()) {
            return;
        }

        try {
            mClientReceiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onTryAgainPressed() {
        if (mState != STATE_AUTH_PAUSED) {
            Slog.w(TAG, "onTryAgainPressed, state: " + mState);
        }

        try {
            setSensorsToStateWaitingForCookie();
            mState = STATE_AUTH_PAUSED_RESUMING;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException: " + e);
        }
    }

    void onAuthenticationSucceeded(int sensorId, boolean strong,
            byte[] token) {
        if (strong) {
            mTokenEscrow = token;
        } else {
            if (token != null) {
                Slog.w(TAG, "Dropping authToken for non-strong biometric, id: " + sensorId);
            }
        }

        try {
            // Notify SysUI that the biometric has been authenticated. SysUI already knows
            // the implicit/explicit state and will react accordingly.
            mStatusBarService.onBiometricAuthenticated();

            final boolean requireConfirmation = isConfirmationRequiredByAnyEligibleSensor();

            if (!requireConfirmation) {
                mState = STATE_AUTHENTICATED_PENDING_SYSUI;
            } else {
                mAuthenticatedTimeMs = System.currentTimeMillis();
                mState = STATE_AUTH_PENDING_CONFIRM;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onAuthenticationRejected() {
        try {
            mStatusBarService.onBiometricError(TYPE_NONE,
                    BiometricConstants.BIOMETRIC_PAUSED_REJECTED, 0 /* vendorCode */);

            // TODO: This logic will need to be updated if BP is multi-modal
            if (hasPausableBiometric()) {
                // Pause authentication. onBiometricAuthenticated(false) causes the
                // dialog to show a "try again" button for passive modalities.
                mState = AuthSession.STATE_AUTH_PAUSED;
            }

            mClientReceiver.onAuthenticationFailed();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onAuthenticationTimedOut(int sensorId, int cookie, int error, int vendorCode) {
        try {
            mStatusBarService.onBiometricError(sensorIdToModality(sensorId), error, vendorCode);
            mState = STATE_AUTH_PAUSED;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException", e);
        }
    }

    void onDeviceCredentialPressed() {
        // Cancel authentication. Skip the token/package check since we are cancelling
        // from system server. The interface is permission protected so this is fine.
        cancelBiometricOnly();
        mState = STATE_SHOWING_DEVICE_CREDENTIAL;
    }

    /**
     * @return true if this session is finished and should be set to null.
     */
    boolean onClientDied() {
        try {
            if (mState == STATE_AUTH_STARTED) {
                mState = STATE_CLIENT_DIED_CANCELLING;
                cancelAllSensors();
                return false;
            } else {
                mStatusBarService.hideAuthenticationDialog();
                return true;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception: " + e);
            return true;
        }
    }

    private void logOnDialogDismissed(@BiometricPrompt.DismissedReason int reason) {
        if (reason == BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED) {
            // Explicit auth, authentication confirmed.
            // Latency in this case is authenticated -> confirmed. <Biometric>Service
            // should have the first half (first acquired -> authenticated).
            final long latency = System.currentTimeMillis() - mAuthenticatedTimeMs;

            if (LoggableMonitor.DEBUG) {
                Slog.v(LoggableMonitor.TAG, "Confirmed! Modality: " + statsModality()
                        + ", User: " + mUserId
                        + ", IsCrypto: " + isCrypto()
                        + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                        + ", RequireConfirmation: " + mPreAuthInfo.confirmationRequested
                        + ", State: " + FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED
                        + ", Latency: " + latency);
            }

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                    statsModality(),
                    mUserId,
                    isCrypto(),
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mPreAuthInfo.confirmationRequested,
                    FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED,
                    latency,
                    mDebugEnabled);
        } else {
            final long latency = System.currentTimeMillis() - mStartTimeMs;

            int error = reason == BiometricPrompt.DISMISSED_REASON_NEGATIVE
                    ? BiometricConstants.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                    : reason == BiometricPrompt.DISMISSED_REASON_USER_CANCEL
                            ? BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED
                            : 0;
            if (LoggableMonitor.DEBUG) {
                Slog.v(LoggableMonitor.TAG, "Dismissed! Modality: " + statsModality()
                        + ", User: " + mUserId
                        + ", IsCrypto: " + isCrypto()
                        + ", Action: " + BiometricsProtoEnums.ACTION_AUTHENTICATE
                        + ", Client: " + BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT
                        + ", Error: " + error
                        + ", Latency: " + latency);
            }
            // Auth canceled
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                    statsModality(),
                    mUserId,
                    isCrypto(),
                    BiometricsProtoEnums.ACTION_AUTHENTICATE,
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    error,
                    0 /* vendorCode */,
                    mDebugEnabled,
                    latency);
        }
    }

    void onDialogDismissed(@BiometricPrompt.DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        logOnDialogDismissed(reason);
        try {
            switch (reason) {
                case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                    if (credentialAttestation != null) {
                        mKeyStore.addAuthToken(credentialAttestation);
                    } else {
                        Slog.e(TAG, "credentialAttestation is null");
                    }
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
                case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                    if (mTokenEscrow != null) {
                        mKeyStore.addAuthToken(mTokenEscrow);
                    } else {
                        Slog.e(TAG, "mTokenEscrow is null");
                    }
                    mClientReceiver.onAuthenticationSucceeded(
                            Utils.getAuthenticationTypeForResult(reason));
                    break;

                case BiometricPrompt.DISMISSED_REASON_NEGATIVE:
                    mClientReceiver.onDialogDismissed(reason);
                    cancelBiometricOnly();
                    break;

                case BiometricPrompt.DISMISSED_REASON_USER_CANCEL:
                    mClientReceiver.onError(
                            getEligibleModalities(),
                            BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED,
                            0 /* vendorCode */
                    );
                    cancelBiometricOnly();
                    break;

                case BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED:
                case BiometricPrompt.DISMISSED_REASON_ERROR:
                    mClientReceiver.onError(
                            getEligibleModalities(),
                            mErrorEscrow,
                            mVendorCodeEscrow
                    );
                    break;

                default:
                    Slog.w(TAG, "Unhandled reason: " + reason);
                    break;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Cancels authentication for the entire authentication session. The caller will receive
     * {@link BiometricPrompt#BIOMETRIC_ERROR_CANCELED} at some point.
     *
     * @param force if true, will immediately dismiss the dialog and send onError to the client
     * @return true if this AuthSession is finished, e.g. should be set to null
     */
    boolean onCancelAuthSession(boolean force) {
        if (mState == STATE_AUTH_STARTED && !force) {
            cancelAllSensors();
            // Wait for ERROR_CANCELED to be returned from the sensors
            return false;
        } else {
            // If we're in a state where biometric sensors are not running (e.g. pending confirm,
            // showing device credential, etc), we need to dismiss the dialog and send our own
            // ERROR_CANCELED to the client, since we won't be getting an onError from the driver.
            try {
                // Send error to client
                mClientReceiver.onError(
                        getEligibleModalities(),
                        BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                        0 /* vendorCode */
                );
                mStatusBarService.hideAuthenticationDialog();
                return true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
            }
        }
        return false;
    }

    /**
     * Cancels biometric authentication only. AuthSession may either be going into
     * {@link #STATE_SHOWING_DEVICE_CREDENTIAL} or dismissed.
     */
    private void cancelBiometricOnly() {
        if (mState == STATE_AUTH_STARTED) {
            cancelAllSensors();
        }
    }

    boolean isCrypto() {
        return mOperationId != 0;
    }

    private boolean containsCookie(int cookie) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.getCookie() == cookie) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowDeviceCredential() {
        return Utils.isCredentialRequested(mPromptInfo);
    }

    boolean allCookiesReceived() {
        final int remainingCookies = mPreAuthInfo.numSensorsWaitingForCookie();
        Slog.d(TAG, "Remaining cookies: " + remainingCookies);
        return remainingCookies == 0;
    }

    private boolean hasPausableBiometric() {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensor.modality == TYPE_FACE) {
                return true;
            }
        }
        return false;
    }

    @SessionState int getState() {
        return mState;
    }

    private int statsModality() {
        int modality = 0;

        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if ((sensor.modality & BiometricAuthenticator.TYPE_FINGERPRINT) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_FINGERPRINT;
            }
            if ((sensor.modality & BiometricAuthenticator.TYPE_IRIS) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_IRIS;
            }
            if ((sensor.modality & BiometricAuthenticator.TYPE_FACE) != 0) {
                modality |= BiometricsProtoEnums.MODALITY_FACE;
            }
        }

        return modality;
    }

    private @BiometricAuthenticator.Modality int sensorIdToModality(int sensorId) {
        for (BiometricSensor sensor : mPreAuthInfo.eligibleSensors) {
            if (sensorId == sensor.id) {
                return sensor.modality;
            }
        }
        Slog.e(TAG, "Unknown sensor: " + sensorId);
        return TYPE_NONE;
    }

    private String getAcquiredMessageForSensor(int sensorId, int acquiredInfo, int vendorCode) {
        final @BiometricAuthenticator.Modality int modality = sensorIdToModality(sensorId);
        switch (modality) {
            case BiometricAuthenticator.TYPE_FINGERPRINT:
                return FingerprintManager.getAcquiredString(mContext, acquiredInfo, vendorCode);
            case BiometricAuthenticator.TYPE_FACE:
                return FaceManager.getAcquiredString(mContext, acquiredInfo, vendorCode);
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return "State: " + mState
                + "\nisCrypto: " + isCrypto()
                + "\nPreAuthInfo: " + mPreAuthInfo;
    }
}
