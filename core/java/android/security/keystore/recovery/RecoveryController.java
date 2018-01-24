/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.security.keystore.recovery;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;

import com.android.internal.widget.ILockSettings;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An assistant for generating {@link javax.crypto.SecretKey} instances that can be recovered by
 * other Android devices belonging to the user. The exported keychain is protected by the user's
 * lock screen.
 *
 * <p>The RecoveryController must be paired with a recovery agent. The recovery agent is responsible
 * for transporting the keychain to remote trusted hardware. This hardware must prevent brute force
 * attempts against the user's lock screen by limiting the number of allowed guesses (to, e.g., 10).
 * After  that number of incorrect guesses, the trusted hardware no longer allows access to the
 * key chain.
 *
 * <p>For now only the recovery agent itself is able to create keys, so it is expected that the
 * recovery agent is itself the system app.
 *
 * <p>A recovery agent requires the privileged permission
 * {@code android.Manifest.permission#RECOVER_KEYSTORE}.
 *
 * @hide
 */
@SystemApi
public class RecoveryController {
    private static final String TAG = "RecoveryController";

    /** Key has been successfully synced. */
    public static final int RECOVERY_STATUS_SYNCED = 0;
    /** Waiting for recovery agent to sync the key. */
    public static final int RECOVERY_STATUS_SYNC_IN_PROGRESS = 1;
    /** Recovery account is not available. */
    public static final int RECOVERY_STATUS_MISSING_ACCOUNT = 2;
    /** Key cannot be synced. */
    public static final int RECOVERY_STATUS_PERMANENT_FAILURE = 3;

    /**
     * Failed because no snapshot is yet pending to be synced for the user.
     *
     * @hide
     */
    public static final int ERROR_NO_SNAPSHOT_PENDING = 21;

    /**
     * Failed due to an error internal to the recovery service. This is unexpected and indicates
     * either a problem with the logic in the service, or a problem with a dependency of the
     * service (such as AndroidKeyStore).
     *
     * @hide
     */
    public static final int ERROR_SERVICE_INTERNAL_ERROR = 22;

    /**
     * Failed because the user does not have a lock screen set.
     *
     * @hide
     */
    public static final int ERROR_INSECURE_USER = 23;

    /**
     * Error thrown when attempting to use a recovery session that has since been closed.
     *
     * @hide
     */
    public static final int ERROR_SESSION_EXPIRED = 24;

    /**
     * Failed because the provided certificate was not a valid X509 certificate.
     *
     * @hide
     */
    public static final int ERROR_BAD_CERTIFICATE_FORMAT = 25;

    /**
     * Error thrown if decryption failed. This might be because the tag is wrong, the key is wrong,
     * the data has become corrupted, the data has been tampered with, etc.
     *
     * @hide
     */
    public static final int ERROR_DECRYPTION_FAILED = 26;


    private final ILockSettings mBinder;

    private RecoveryController(ILockSettings binder) {
        mBinder = binder;
    }

    /**
     * Internal method used by {@code RecoverySession}.
     *
     * @hide
     */
    ILockSettings getBinder() {
        return mBinder;
    }

    /**
     * Gets a new instance of the class.
     */
    public static RecoveryController getInstance(Context context) {
        ILockSettings lockSettings =
                ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        return new RecoveryController(lockSettings);
    }

    /**
     * Initializes key recovery service for the calling application. RecoveryController
     * randomly chooses one of the keys from the list and keeps it to use for future key export
     * operations. Collection of all keys in the list must be signed by the provided {@code
     * rootCertificateAlias}, which must also be present in the list of root certificates
     * preinstalled on the device. The random selection allows RecoveryController to select
     * which of a set of remote recovery service devices will be used.
     *
     * <p>In addition, RecoveryController enforces a delay of three months between
     * consecutive initialization attempts, to limit the ability of an attacker to often switch
     * remote recovery devices and significantly increase number of recovery attempts.
     *
     * @param rootCertificateAlias alias of a root certificate preinstalled on the device
     * @param signedPublicKeyList binary blob a list of X509 certificates and signature
     * @throws CertificateException if the {@code signedPublicKeyList} is in a bad format.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList)
            throws CertificateException, InternalRecoveryServiceException {
        try {
            mBinder.initRecoveryService(rootCertificateAlias, signedPublicKeyList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_BAD_CERTIFICATE_FORMAT) {
                throw new CertificateException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns data necessary to store all recoverable keys. Key material is
     * encrypted with user secret and recovery public key.
     *
     * @return Data necessary to recover keystore.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public @NonNull KeyChainSnapshot getRecoveryData()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getRecoveryData(/*account=*/ new byte[]{});
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_NO_SNAPSHOT_PENDING) {
                return null;
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Sets a listener which notifies recovery agent that new recovery snapshot is available. {@link
     * #getRecoveryData} can be used to get the snapshot. Note that every recovery agent can have at
     * most one registered listener at any time.
     *
     * @param intent triggered when new snapshot is available. Unregisters listener if the value is
     *     {@code null}.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setSnapshotCreatedPendingIntent(intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Server parameters used to generate new recovery key blobs. This value will be included in
     * {@code KeyChainSnapshot.getEncryptedRecoveryKeyBlob()}. The same value must be included
     * in vaultParams {@link #startRecoverySession}
     *
     * @param serverParams included in recovery key blob.
     * @see #getRecoveryData
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setServerParams(byte[] serverParams) throws InternalRecoveryServiceException {
        try {
            mBinder.setServerParams(serverParams);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Gets aliases of recoverable keys for the application.
     *
     * @param packageName which recoverable keys' aliases will be returned.
     *
     * @return {@code List} of all aliases.
     */
    public List<String> getAliases(@Nullable String packageName)
            throws InternalRecoveryServiceException {
        try {
            // TODO: update aidl
            Map<String, Integer> allStatuses = mBinder.getRecoveryStatus(packageName);
            return new ArrayList<>(allStatuses.keySet());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Updates recovery status for given key. It is used to notify keystore that key was
     * successfully stored on the server or there were an error. Application can check this value
     * using {@code getRecoveyStatus}.
     *
     * @param packageName Application whose recoverable key's status are to be updated.
     * @param alias Application-specific key alias.
     * @param status Status specific to recovery agent.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @RequiresPermission(android.Manifest.permission.RECOVER_KEYSTORE)
    public void setRecoveryStatus(
            @NonNull String packageName, String alias, int status)
            throws NameNotFoundException, InternalRecoveryServiceException {
        try {
            // TODO: update aidl
            String[] aliases = alias == null ? null : new String[]{alias};
            mBinder.setRecoveryStatus(packageName, aliases, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns recovery status for Application's KeyStore key.
     * Negative status values are reserved for recovery agent specific codes. List of common codes:
     *
     * <ul>
     *   <li>{@link #RECOVERY_STATUS_SYNCED}
     *   <li>{@link #RECOVERY_STATUS_SYNC_IN_PROGRESS}
     *   <li>{@link #RECOVERY_STATUS_MISSING_ACCOUNT}
     *   <li>{@link #RECOVERY_STATUS_PERMANENT_FAILURE}
     * </ul>
     *
     * @param packageName Application whose recoverable key status is returned.
     * @param alias Application-specific key alias.
     * @return Recovery status.
     * @see #setRecoveryStatus
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public int getRecoveryStatus(String packageName, String alias)
            throws InternalRecoveryServiceException {
        try {
            // TODO: update aidl
            Map<String, Integer> allStatuses = mBinder.getRecoveryStatus(packageName);
            Integer status = allStatuses.get(alias);
            if (status == null) {
                return RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE;
            } else {
                return status;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Specifies a set of secret types used for end-to-end keystore encryption. Knowing all of them
     * is necessary to recover data.
     *
     * @param secretTypes {@link KeyChainProtectionParams#TYPE_LOCKSCREEN} or {@link
     *     KeyChainProtectionParams#TYPE_CUSTOM_PASSWORD}
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyChainProtectionParams.UserSecretType int[] secretTypes)
            throws InternalRecoveryServiceException {
        try {
            mBinder.setRecoverySecretTypes(secretTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Defines a set of secret types used for end-to-end keystore encryption. Knowing all of them is
     * necessary to generate KeyChainSnapshot.
     *
     * @return list of recovery secret types
     * @see KeyChainSnapshot
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public @NonNull @KeyChainProtectionParams.UserSecretType int[] getRecoverySecretTypes()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Returns a list of recovery secret types, necessary to create a pending recovery snapshot.
     * When user enters a secret of a pending type {@link #recoverySecretAvailable} should be
     * called.
     *
     * @return list of recovery secret types
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    @NonNull
    public @KeyChainProtectionParams.UserSecretType int[] getPendingRecoverySecretTypes()
            throws InternalRecoveryServiceException {
        try {
            return mBinder.getPendingRecoverySecretTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Method notifies KeyStore that a user-generated secret is available. This method generates a
     * symmetric session key which a trusted remote device can use to return a recovery key. Caller
     * should use {@link KeyChainProtectionParams#clearSecret} to override the secret value in
     * memory.
     *
     * @param recoverySecret user generated secret together with parameters necessary to regenerate
     *     it on a new device.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void recoverySecretAvailable(@NonNull KeyChainProtectionParams recoverySecret)
            throws InternalRecoveryServiceException {
        try {
            mBinder.recoverySecretAvailable(recoverySecret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Generates a AES256/GCM/NoPADDING key called {@code alias} and loads it into the recoverable
     * key store. Returns the raw material of the key.
     *
     * @param alias The key alias.
     * @param account The account associated with the key
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     * @throws LockScreenRequiredException if the user has not set a lock screen. This is required
     *     to generate recoverable keys, as the snapshots are encrypted using a key derived from the
     *     lock screen.
     */
    public byte[] generateAndStoreKey(@NonNull String alias, byte[] account)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        try {
            // TODO: add account
            return mBinder.generateAndStoreKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ERROR_INSECURE_USER) {
                throw new LockScreenRequiredException(e.getMessage());
            }
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    /**
     * Removes a key called {@code alias} from the recoverable key store.
     *
     * @param alias The key alias.
     * @throws InternalRecoveryServiceException if an unexpected error occurred in the recovery
     *     service.
     */
    public void removeKey(@NonNull String alias) throws InternalRecoveryServiceException {
        try {
            mBinder.removeKey(alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw wrapUnexpectedServiceSpecificException(e);
        }
    }

    InternalRecoveryServiceException wrapUnexpectedServiceSpecificException(
            ServiceSpecificException e) {
        if (e.errorCode == ERROR_SERVICE_INTERNAL_ERROR) {
            return new InternalRecoveryServiceException(e.getMessage());
        }

        // Should never happen. If it does, it's a bug, and we need to update how the method that
        // called this throws its exceptions.
        return new InternalRecoveryServiceException("Unexpected error code for method: "
                + e.errorCode, e);
    }
}
