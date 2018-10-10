/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
public class BiometricManager {

    private static final String TAG = "BiometricManager";

    /**
     * No error detected.
     */
    public static final int ERROR_NONE = BiometricConstants.BIOMETRIC_ERROR_NONE;

    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int ERROR_UNAVAILABLE = BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

    /**
     * The user does not have any biometrics enrolled.
     */
    public static final int ERROR_NO_BIOMETRICS = BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

    /**
     * There is no biometric hardware.
     */
    public static final int ERROR_NO_HARDWARE = BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

    @IntDef({ERROR_NONE, ERROR_UNAVAILABLE, ERROR_NO_BIOMETRICS, ERROR_NO_HARDWARE})
    @interface BiometricError {}

    private final Context mContext;
    private final IBiometricService mService;

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(Context context, IBiometricService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Determine if biometrics can be used. In other words, determine if {@link BiometricPrompt}
     * can be expected to be shown (hardware available, templates enrolled, user-enabled).
     *
     * @return Returns {@link #ERROR_NO_BIOMETRICS} if the user does not have any enrolled, or
     *     {@link #ERROR_UNAVAILABLE} if none are currently supported/enabled. Returns
     *     {@link #ERROR_NONE} if a biometric can currently be used (enrolled and available).
     */
    @RequiresPermission(USE_BIOMETRIC)
    public @BiometricError int canAuthenticate() {
        if (mService != null) {
            try {
                return mService.canAuthenticate(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "hasEnrolledBiometrics(): Service not connected");
            return ERROR_UNAVAILABLE;
        }
    }

    /**
     * Listens for changes to biometric eligibility on keyguard from user settings.
     * @param callback
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
        if (mService != null) {
            try {
                mService.registerEnabledOnKeyguardCallback(callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "registerEnabledOnKeyguardCallback(): Service not connected");
        }
    }

    /**
     * Sets the active user.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setActiveUser(int userId) {
        if (mService != null) {
            try {
                mService.setActiveUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "setActiveUser(): Service not connected");
        }
    }
}

