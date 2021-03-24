/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.security.maintenance.IKeystoreMaintenance;
import android.system.keystore2.Domain;
import android.system.keystore2.ResponseCode;
import android.util.Log;

/**
 * @hide This is the client side for IKeystoreUserManager AIDL.
 * It shall only be used by the LockSettingsService.
 */
public class AndroidKeyStoreMaintenance {
    private static final String TAG = "AndroidKeyStoreMaintenance";

    public static final int SYSTEM_ERROR = ResponseCode.SYSTEM_ERROR;

    private static IKeystoreMaintenance getService() {
        return IKeystoreMaintenance.Stub.asInterface(
                ServiceManager.checkService("android.security.maintenance"));
    }

    /**
     * Informs Keystore 2.0 about adding a user
     *
     * @param userId - Android user id of the user being added
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserAdded(@NonNull int userId) {
        if (!android.security.keystore2.AndroidKeyStoreProvider.isInstalled()) return 0;
        try {
            getService().onUserAdded(userId);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserAdded failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 about removing a usergit mer
     *
     * @param userId - Android user id of the user being removed
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserRemoved(int userId) {
        if (!android.security.keystore2.AndroidKeyStoreProvider.isInstalled()) return 0;
        try {
            getService().onUserRemoved(userId);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserRemoved failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 about changing user's password
     *
     * @param userId   - Android user id of the user
     * @param password - a secret derived from the synthetic password provided by the
     *                 LockSettingService
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserPasswordChanged(int userId, @Nullable byte[] password) {
        if (!android.security.keystore2.AndroidKeyStoreProvider.isInstalled()) return 0;
        try {
            getService().onUserPasswordChanged(userId, password);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserPasswordChanged failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 that an app was uninstalled and the corresponding namspace is to
     * be cleared.
     */
    public static int clearNamespace(@Domain int domain, long namespace) {
        if (!android.security.keystore2.AndroidKeyStoreProvider.isInstalled()) return 0;
        try {
            getService().clearNamespace(domain, namespace);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "clearNamespace failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Queries user state from Keystore 2.0.
     *
     * @param userId - Android user id of the user.
     * @return UserState enum variant as integer if successful or an error
     */
    public static int getState(int userId) {
        try {
            return getService().getState(userId);
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "getState failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 that an off body event was detected.
     */
    public static void onDeviceOffBody() {
        if (!android.security.keystore2.AndroidKeyStoreProvider.isInstalled()) return;
        try {
            getService().onDeviceOffBody();
        } catch (Exception e) {
            // TODO This fails open. This is not a regression with respect to keystore1 but it
            //      should get fixed.
            Log.e(TAG, "Error while reporting device off body event.", e);
        }
    }
}
