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

package com.android.server.locksettings.recoverablekeystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;

import android.security.recoverablekeystore.KeyEntryRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.AEADBadTagException;

/**
 * Class with {@link RecoverableKeyStoreLoader} API implementation and internal methods to interact
 * with {@code LockSettingsService}.
 *
 * @hide
 */
public class RecoverableKeyStoreManager {
    private static final String TAG = "RecoverableKeyStoreMgr";

    private static final int ERROR_INSECURE_USER = 1;
    private static final int ERROR_KEYSTORE_INTERNAL_ERROR = 2;
    private static final int ERROR_DATABASE_ERROR = 3;

    private static RecoverableKeyStoreManager mInstance;

    private final Context mContext;
    private final RecoverableKeyStoreDb mDatabase;
    private final RecoverySessionStorage mRecoverySessionStorage;
    private final ExecutorService mExecutorService;
    private final RecoverySnapshotListenersStorage mListenersStorage;
    private final RecoverableKeyGenerator mRecoverableKeyGenerator;
    private final RecoverySnapshotStorage mSnapshotStorage;

    /**
     * Returns a new or existing instance.
     *
     * @hide
     */
    public static synchronized RecoverableKeyStoreManager getInstance(Context mContext) {
        if (mInstance == null) {
            RecoverableKeyStoreDb db = RecoverableKeyStoreDb.newInstance(mContext);
            mInstance = new RecoverableKeyStoreManager(
                    mContext.getApplicationContext(),
                    db,
                    new RecoverySessionStorage(),
                    Executors.newSingleThreadExecutor(),
                    new RecoverySnapshotStorage(),
                    new RecoverySnapshotListenersStorage());
        }
        return mInstance;
    }

    @VisibleForTesting
    RecoverableKeyStoreManager(
            Context context,
            RecoverableKeyStoreDb recoverableKeyStoreDb,
            RecoverySessionStorage recoverySessionStorage,
            ExecutorService executorService,
            RecoverySnapshotStorage snapshotStorage,
            RecoverySnapshotListenersStorage listenersStorage) {
        mContext = context;
        mDatabase = recoverableKeyStoreDb;
        mRecoverySessionStorage = recoverySessionStorage;
        mExecutorService = executorService;
        mListenersStorage = listenersStorage;
        mSnapshotStorage = snapshotStorage;
        try {
            mRecoverableKeyGenerator = RecoverableKeyGenerator.newInstance(mDatabase);
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all AOSP implementations must support AES.
            throw new RuntimeException(e);
        }
    }

    public void initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        // TODO: open /system/etc/security/... cert file, and check the signature on the public keys
        PublicKey publicKey;
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            // TODO: Randomly choose a key from the list -- right now we just use the whole input
            X509EncodedKeySpec pkSpec = new X509EncodedKeySpec(signedPublicKeyList);
            publicKey = kf.generatePublic(pkSpec);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            throw new RemoteException("Invalid public key for the recovery service");
        }
        mDatabase.setRecoveryServicePublicKey(userId, Binder.getCallingUid(), publicKey);
    }

    /**
     * Gets all data necessary to recover application keys on new device.
     *
     * @return recovery data
     * @hide
     */
    public @NonNull KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();

        KeyStoreRecoveryData snapshot = mSnapshotStorage.get(UserHandle.getCallingUserId());
        if (snapshot == null) {
            throw new ServiceSpecificException(RecoverableKeyStoreLoader.NO_SNAPSHOT_PENDING_ERROR);
        }
        return snapshot;
    }

    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        final int recoveryAgentUid = Binder.getCallingUid();
        mListenersStorage.setSnapshotListener(recoveryAgentUid, intent);
    }

    /**
     * Gets recovery snapshot versions for all accounts. Note that snapshot may have 0 application
     * keys, but it still needs to be synced, if previous versions were not empty.
     *
     * @return Map from Recovery agent account to snapshot version.
     */
    public @NonNull Map<byte[], Integer> getRecoverySnapshotVersions(int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void setServerParameters(long serverParameters, int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        mDatabase.setServerParameters(userId, Binder.getCallingUid(), serverParameters);
    }

    /**
     * Updates recovery status for the application given its {@code packageName}.
     *
     * @param packageName which recoverable key statuses will be returned
     * @param aliases - KeyStore aliases or {@code null} for all aliases of the app
     * @param status - new status
     */
    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        int uid = Binder.getCallingUid();
        if (packageName != null) {
            // TODO: get uid for package name, when many apps are supported.
        }
        if (aliases == null) {
            // Get all keys for the app.
            Map<String, Integer> allKeys = mDatabase.getStatusForAllKeys(uid);
            aliases = new String[allKeys.size()];
            allKeys.keySet().toArray(aliases);
        }
        for (String alias: aliases) {
            mDatabase.setRecoveryStatus(uid, alias, status);
        }
    }

    /**
     * Gets recovery status for caller or other application {@code packageName}.
     * @param packageName which recoverable keys statuses will be returned.
     *
     * @return {@code Map} from KeyStore alias to recovery status.
     */
    public @NonNull Map<String, Integer> getRecoveryStatus(@Nullable String packageName, int userId)
            throws RemoteException {
        // Any application should be able to check status for its own keys.
        // If caller is a recovery agent it can check statuses for other packages, but
        // only for recoverable keys it manages.
        checkRecoverKeyStorePermission();
        return mDatabase.getStatusForAllKeys(Binder.getCallingUid());
    }

    /**
     * Sets recovery secrets list used by all recovery agents for given {@code userId}
     *
     * @hide
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] secretTypes, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        mDatabase.setRecoverySecretTypes(UserHandle.getCallingUserId(), Binder.getCallingUid(),
            secretTypes);
    }

    /**
     * Gets secret types necessary to create Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        return mDatabase.getRecoverySecretTypes(UserHandle.getCallingUserId(),
            Binder.getCallingUid());
    }

    /**
     * Gets secret types RecoverableKeyStoreLoaders is waiting for to create new Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public @NonNull int[] getPendingRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void recoverySecretAvailable(
            @NonNull KeyStoreRecoveryMetadata recoverySecret, int userId) throws RemoteException {
        final int callingUid = Binder.getCallingUid(); // Recovery agent uid.
        if (recoverySecret.getLockScreenUiFormat() == KeyStoreRecoveryMetadata.TYPE_LOCKSCREEN) {
            throw new SecurityException(
                    "Caller " + callingUid + "is not allowed to set lock screen secret");
        }
        checkRecoverKeyStorePermission();
        // TODO: add hook from LockSettingsService to set lock screen secret.
        throw new UnsupportedOperationException();
    }

    /**
     * Initializes recovery session.
     *
     * @param sessionId A unique ID to identify the recovery session.
     * @param verifierPublicKey X509-encoded public key.
     * @param vaultParams Additional params associated with vault.
     * @param vaultChallenge Challenge issued by vault service.
     * @param secrets Lock-screen hashes. For now only a single secret is supported.
     * @return Encrypted bytes of recovery claim. This can then be issued to the vault service.
     *
     * @hide
     */
    public @NonNull byte[] startRecoverySession(
            @NonNull String sessionId,
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyStoreRecoveryMetadata> secrets,
            int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();

        if (secrets.size() != 1) {
            // TODO: support multiple secrets
            throw new RemoteException("Only a single KeyStoreRecoveryMetadata is supported");
        }

        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] kfHash = secrets.get(0).getSecret();
        mRecoverySessionStorage.add(
                userId,
                new RecoverySessionStorage.Entry(sessionId, kfHash, keyClaimant, vaultParams));

        try {
            byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(kfHash);
            PublicKey publicKey = KeySyncUtils.deserializePublicKey(verifierPublicKey);
            return KeySyncUtils.encryptRecoveryClaim(
                    publicKey,
                    vaultParams,
                    vaultChallenge,
                    thmKfHash,
                    keyClaimant);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen: all the algorithms used are required by AOSP implementations.
            throw new RemoteException(
                    "Missing required algorithm",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        } catch (InvalidKeySpecException | InvalidKeyException e) {
            throw new RemoteException(
                    "Not a valid X509 key",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        }
    }

    /**
     * Invoked by a recovery agent after a successful recovery claim is sent to the remote vault
     * service.
     *
     * @param sessionId The session ID used to generate the claim. See
     *     {@link #startRecoverySession(String, byte[], byte[], byte[], List, int)}.
     * @param encryptedRecoveryKey The encrypted recovery key blob returned by the remote vault
     *     service.
     * @param applicationKeys The encrypted key blobs returned by the remote vault service. These
     *     were wrapped with the recovery key.
     * @param uid The uid of the recovery agent.
     * @return Map from alias to raw key material.
     * @throws RemoteException if an error occurred recovering the keys.
     */
    public Map<String, byte[]> recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] encryptedRecoveryKey,
            @NonNull List<KeyEntryRecoveryData> applicationKeys,
            int uid)
            throws RemoteException {
        checkRecoverKeyStorePermission();

        RecoverySessionStorage.Entry sessionEntry = mRecoverySessionStorage.get(uid, sessionId);
        if (sessionEntry == null) {
            throw new RemoteException(String.format(Locale.US,
                    "User %d does not have pending session '%s'", uid, sessionId));
        }

        try {
            byte[] recoveryKey = decryptRecoveryKey(sessionEntry, encryptedRecoveryKey);
            return recoverApplicationKeys(recoveryKey, applicationKeys);
        } finally {
            sessionEntry.destroy();
            mRecoverySessionStorage.remove(uid);
        }
    }

    /**
     * Generates a key named {@code alias} in the recoverable store for the calling uid. Then
     * returns the raw key material.
     *
     * <p>TODO: Once AndroidKeyStore has added move api, do not return raw bytes.
     *
     * @hide
     */
    public byte[] generateAndStoreKey(@NonNull String alias) throws RemoteException {
        int uid = Binder.getCallingUid();
        int userId = Binder.getCallingUserHandle().getIdentifier();

        PlatformEncryptionKey encryptionKey;

        try {
            PlatformKeyManager platformKeyManager = PlatformKeyManager.getInstance(
                    mContext, mDatabase, userId);
            encryptionKey = platformKeyManager.getEncryptKey();
        } catch (NoSuchAlgorithmException e) {
            // Impossible: all algorithms must be supported by AOSP
            throw new RuntimeException(e);
        } catch (KeyStoreException | UnrecoverableKeyException e) {
            throw new ServiceSpecificException(ERROR_KEYSTORE_INTERNAL_ERROR, e.getMessage());
        } catch (InsecureUserException e) {
            throw new ServiceSpecificException(ERROR_INSECURE_USER, e.getMessage());
        }

        try {
            return mRecoverableKeyGenerator.generateAndStoreKey(encryptionKey, userId, uid, alias);
        } catch (KeyStoreException | InvalidKeyException e) {
            throw new ServiceSpecificException(ERROR_KEYSTORE_INTERNAL_ERROR, e.getMessage());
        } catch (RecoverableKeyStorageException e) {
            throw new ServiceSpecificException(ERROR_DATABASE_ERROR, e.getMessage());
        }
    }

    private byte[] decryptRecoveryKey(
            RecoverySessionStorage.Entry sessionEntry, byte[] encryptedClaimResponse)
            throws RemoteException {
        try {
            byte[] locallyEncryptedKey = KeySyncUtils.decryptRecoveryClaimResponse(
                    sessionEntry.getKeyClaimant(),
                    sessionEntry.getVaultParams(),
                    encryptedClaimResponse);
            return KeySyncUtils.decryptRecoveryKey(sessionEntry.getLskfHash(), locallyEncryptedKey);
        } catch (InvalidKeyException | AEADBadTagException e) {
            throw new RemoteException(
                    "Failed to decrypt recovery key",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen: all the algorithms used are required by AOSP implementations
            throw new RemoteException(
                    "Missing required algorithm",
                    e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
        }
    }

    /**
     * Uses {@code recoveryKey} to decrypt {@code applicationKeys}.
     *
     * @return Map from alias to raw key material.
     * @throws RemoteException if an error occurred decrypting the keys.
     */
    private Map<String, byte[]> recoverApplicationKeys(
            @NonNull byte[] recoveryKey,
            @NonNull List<KeyEntryRecoveryData> applicationKeys) throws RemoteException {
        HashMap<String, byte[]> keyMaterialByAlias = new HashMap<>();
        for (KeyEntryRecoveryData applicationKey : applicationKeys) {
            String alias = new String(applicationKey.getAlias(), StandardCharsets.UTF_8);
            byte[] encryptedKeyMaterial = applicationKey.getEncryptedKeyMaterial();

            try {
                byte[] keyMaterial =
                        KeySyncUtils.decryptApplicationKey(recoveryKey, encryptedKeyMaterial);
                keyMaterialByAlias.put(alias, keyMaterial);
            } catch (NoSuchAlgorithmException e) {
                // Should never happen: all the algorithms used are required by AOSP implementations
                throw new RemoteException(
                        "Missing required algorithm",
                        e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
            } catch (InvalidKeyException | AEADBadTagException e) {
                throw new RemoteException(
                        "Failed to recover key with alias '" + alias + "'",
                        e,
                    /*enableSuppression=*/ true,
                    /*writeableStackTrace=*/ true);
            }
        }
        return keyMaterialByAlias;
    }

    /**
     * This function can only be used inside LockSettingsService.
     *
     * @param storedHashType from {@code CredentialHash}
     * @param credential - unencrypted String. Password length should be at most 16 symbols {@code
     *     mPasswordMaxLength}
     * @param userId for user who just unlocked the device.
     * @hide
     */
    public void lockScreenSecretAvailable(
            int storedHashType, @NonNull String credential, int userId) {
        // So as not to block the critical path unlocking the phone, defer to another thread.
        try {
            mExecutorService.execute(KeySyncTask.newInstance(
                    mContext,
                    mDatabase,
                    mSnapshotStorage,
                    mListenersStorage,
                    userId,
                    storedHashType,
                    credential));
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Should never happen - algorithm unavailable for KeySync", e);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key store error encountered during recoverable key sync", e);
        } catch (InsecureUserException e) {
            Log.wtf(TAG, "Impossible - insecure user, but user just entered lock screen", e);
        }
    }

    /** This function can only be used inside LockSettingsService. */
    public void lockScreenSecretChanged(
            @KeyStoreRecoveryMetadata.LockScreenUiFormat int type,
            @Nullable String credential,
            int userId) {
        throw new UnsupportedOperationException();
    }

    private void checkRecoverKeyStorePermission() {
        mContext.enforceCallingOrSelfPermission(
                RecoverableKeyStoreLoader.PERMISSION_RECOVER_KEYSTORE,
                "Caller " + Binder.getCallingUid() + " doesn't have RecoverKeyStore permission.");
    }
}
