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

import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
@SystemService(Context.BIOMETRIC_SERVICE)
public class BiometricManager {

    private static final String TAG = "BiometricManager";

    /**
     * No error detected.
     */
    public static final int BIOMETRIC_SUCCESS =
            BiometricConstants.BIOMETRIC_SUCCESS;

    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE =
            BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

    /**
     * The user does not have any biometrics enrolled.
     */
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED =
            BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

    /**
     * There is no biometric hardware.
     */
    public static final int BIOMETRIC_ERROR_NO_HARDWARE =
            BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

    /**
     * A security vulnerability has been discovered and the sensor is unavailable until a
     * security update has addressed this issue. This error can be received if for example,
     * authentication was requested with {@link Authenticators#BIOMETRIC_STRONG}, but the
     * sensor's strength can currently only meet {@link Authenticators#BIOMETRIC_WEAK}.
     */
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED =
            BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;

    /**
     * @hide
     */
    @IntDef({BIOMETRIC_SUCCESS,
            BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BIOMETRIC_ERROR_NONE_ENROLLED,
            BIOMETRIC_ERROR_NO_HARDWARE,
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BiometricError {}

    /**
     * Types of authenticators, defined at a level of granularity supported by
     * {@link BiometricManager} and {@link BiometricPrompt}.
     *
     * <p>Types may combined via bitwise OR into a single integer representing multiple
     * authenticators (e.g. <code>DEVICE_CREDENTIAL | BIOMETRIC_WEAK</code>).
     *
     * @see #canAuthenticate(int)
     * @see BiometricPrompt.Builder#setAllowedAuthenticators(int)
     */
    public interface Authenticators {
        /**
         * An {@link IntDef} representing valid combinations of authenticator types.
         * @hide
         */
        @IntDef(flag = true, value = {
                BIOMETRIC_STRONG,
                BIOMETRIC_WEAK,
                BIOMETRIC_CONVENIENCE,
                DEVICE_CREDENTIAL,
        })
        @interface Types {}

        /**
         * Empty set with no authenticators specified.
         *
         * <p>This constant is intended for use by {@link android.provider.DeviceConfig} to adjust
         * the reported strength of a biometric sensor. It is not a valid parameter for any of the
         * public {@link android.hardware.biometrics} APIs.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(WRITE_DEVICE_CONFIG)
        int EMPTY_SET = 0x0000;

        /**
         * Placeholder for the theoretical strongest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MAX_STRENGTH = 0x0001;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 3</strong> (formerly <strong>Strong</strong>), as defined
         * by the Android CDD.
         *
         * <p>This corresponds to {@link KeyProperties#AUTH_BIOMETRIC_STRONG} during key generation.
         *
         * @see KeyGenParameterSpec.Builder#setUserAuthenticationParameters(int, int)
         */
        int BIOMETRIC_STRONG = 0x000F;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 2</strong> (formerly <strong>Weak</strong>), as defined by
         * the Android CDD.
         *
         * <p>Note that this is a superset of {@link #BIOMETRIC_STRONG} and is defined such that
         * {@code BIOMETRIC_STRONG | BIOMETRIC_WEAK == BIOMETRIC_WEAK}.
         */
        int BIOMETRIC_WEAK = 0x00FF;

        /**
         * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
         * requirements for <strong>Class 1</strong> (formerly <strong>Convenience</strong>), as
         * defined by the Android CDD.
         *
         * <p>This constant is intended for use by {@link android.provider.DeviceConfig} to adjust
         * the reported strength of a biometric sensor. It is not a valid parameter for any of the
         * public {@link android.hardware.biometrics} APIs.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(WRITE_DEVICE_CONFIG)
        int BIOMETRIC_CONVENIENCE = 0x0FFF;

        /**
         * Placeholder for the theoretical weakest biometric security tier.
         * @hide
         */
        int BIOMETRIC_MIN_STRENGTH = 0x7FFF;

        /**
         * The non-biometric credential used to secure the device (i.e., PIN, pattern, or password).
         * This should typically only be used in combination with a biometric auth type, such as
         * {@link #BIOMETRIC_WEAK}.
         *
         * <p>This corresponds to {@link KeyProperties#AUTH_DEVICE_CREDENTIAL} during key
         * generation.
         *
         * @see KeyGenParameterSpec.Builder#setUserAuthenticationParameters(int, int)
         */
        int DEVICE_CREDENTIAL = 1 << 15;
    }

    private final Context mContext;
    private final IAuthService mService;

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(Context context, IAuthService service) {
        mContext = context;
        mService = service;
    }

    /**
     * @return A list of {@link SensorProperties}
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public List<SensorProperties> getSensorProperties() {
        try {
            final List<SensorPropertiesInternal> internalProperties =
                    mService.getSensorProperties(mContext.getOpPackageName());
            final List<SensorProperties> properties = new ArrayList<>();
            for (SensorPropertiesInternal internalProp : internalProperties) {
                properties.add(SensorProperties.from(internalProp));
            }
            return properties;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves a test session for BiometricManager/BiometricPrompt.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public BiometricTestSession createTestSession(int sensorId) {
        try {
            return new BiometricTestSession(mContext,
                    mService.createTestSession(sensorId, mContext.getOpPackageName()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the package where BiometricPrompt's UI is implemented.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public String getUiPackage() {
        try {
            return mService.getUiPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determine if biometrics can be used. In other words, determine if
     * {@link BiometricPrompt} can be expected to be shown (hardware available, templates enrolled,
     * user-enabled). This is the equivalent of {@link #canAuthenticate(int)} with
     * {@link Authenticators#BIOMETRIC_WEAK}
     *
     * @return {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any strong
     *     biometrics enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are currently
     *     supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if a strong biometric can currently
     *     be used (enrolled and available).
     *
     * @deprecated See {@link #canAuthenticate(int)}.
     */
    @Deprecated
    @RequiresPermission(USE_BIOMETRIC)
    public @BiometricError int canAuthenticate() {
        return canAuthenticate(Authenticators.BIOMETRIC_WEAK);
    }

    /**
     * Determine if any of the provided authenticators can be used. In other words, determine if
     * {@link BiometricPrompt} can be expected to be shown (hardware available, templates enrolled,
     * user-enabled).
     *
     * For biometric authenticators, determine if the device can currently authenticate with at
     * least the requested strength. For example, invoking this API with
     * {@link Authenticators#BIOMETRIC_WEAK} on a device that currently only has
     * {@link Authenticators#BIOMETRIC_STRONG} enrolled will return {@link #BIOMETRIC_SUCCESS}.
     *
     * Invoking this API with {@link Authenticators#DEVICE_CREDENTIAL} can be used to determine
     * if the user has a PIN/Pattern/Password set up.
     *
     * @param authenticators bit field consisting of constants defined in {@link Authenticators}.
     *                       If multiple authenticators are queried, a logical OR will be applied.
     *                       For example, if {@link Authenticators#DEVICE_CREDENTIAL} |
     *                       {@link Authenticators#BIOMETRIC_STRONG} is queried and only
     *                       {@link Authenticators#DEVICE_CREDENTIAL} is set up, this API will
     *                       return {@link #BIOMETRIC_SUCCESS}
     *
     * @return {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any of the
     *     requested authenticators enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are
     *     currently supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if one of the requested
     *     authenticators can currently be used (enrolled and available).
     */
    @RequiresPermission(USE_BIOMETRIC)
    public @BiometricError int canAuthenticate(@Authenticators.Types int authenticators) {
        return canAuthenticate(mContext.getUserId(), authenticators);
    }

    /**
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public @BiometricError int canAuthenticate(int userId,
            @Authenticators.Types int authenticators) {
        if (mService != null) {
            try {
                final String opPackageName = mContext.getOpPackageName();
                return mService.canAuthenticate(opPackageName, userId, authenticators);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "hasEnrolledBiometrics(): Service not connected");
            return BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }

    /**
     * @hide
     * @param userId
     * @return
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public boolean hasEnrolledBiometrics(int userId) {
        if (mService != null) {
            try {
                return mService.hasEnrolledBiometrics(userId, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception in hasEnrolledBiometrics(): " + e);
                return false;
            }
        } else {
            return false;
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
     * Requests all {@link Authenticators.Types#BIOMETRIC_STRONG} sensors to have their
     * authenticatorId invalidated for the specified user. This happens when enrollments have been
     * added on devices with multiple biometric sensors.
     *
     * @param userId userId that the authenticatorId should be invalidated for
     * @param fromSensorId sensor that triggered the invalidation request
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void invalidateAuthenticatorIds(int userId, int fromSensorId,
            @NonNull IInvalidationCallback callback) {
        if (mService != null) {
            try {
                mService.invalidateAuthenticatorIds(userId, fromSensorId, callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get a list of AuthenticatorIDs for biometric authenticators which have 1) enrolled templates,
     * and 2) meet the requirements for integrating with Keystore. The AuthenticatorIDs are known
     * in Keystore land as SIDs, and are used during key generation.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public long[] getAuthenticatorIds() {
        if (mService != null) {
            try {
                return mService.getAuthenticatorIds();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "getAuthenticatorIds(): Service not connected");
            return new long[0];
        }
    }

}

