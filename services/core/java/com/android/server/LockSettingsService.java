/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE;
import static android.Manifest.permission.READ_CONTACTS;
import static android.content.Context.KEYGUARD_SERVICE;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PasswordMetrics;
import android.app.backup.BackupManager;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.provider.Settings.SettingNotFoundException;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LockSettingsStorage.CredentialHash;

import libcore.util.HexEncoding;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the lock pattern/password data and related settings for each user. Used by
 * LockPatternUtils. Needs to be a service because Settings app also needs to be able to save
 * lockscreen information for secondary users.
 *
 * @hide
 */
public class LockSettingsService extends ILockSettings.Stub {
    private static final String TAG = "LockSettingsService";
    private static final String PERMISSION = ACCESS_KEYGUARD_SECURE_STORAGE;
    private static final int FBE_ENCRYPTED_NOTIFICATION = 0;
    private static final boolean DEBUG = false;

    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final String SEPARATE_PROFILE_CHALLENGE_KEY = "lockscreen.profilechallenge";

    private final Object mSeparateChallengeLock = new Object();

    private final Injector mInjector;
    private final Context mContext;
    private final Handler mHandler;
    @VisibleForTesting
    protected final LockSettingsStorage mStorage;
    private final LockSettingsStrongAuth mStrongAuth;
    private final SynchronizedStrongAuthTracker mStrongAuthTracker;

    private final LockPatternUtils mLockPatternUtils;
    private final NotificationManager mNotificationManager;
    private final UserManager mUserManager;
    private final IActivityManager mActivityManager;

    private final KeyStore mKeyStore;

    private boolean mFirstCallToVold;
    protected IGateKeeperService mGateKeeperService;
    /**
     * The UIDs that are used for system credential storage in keystore.
     */
    private static final int[] SYSTEM_CREDENTIAL_UIDS = {
            Process.WIFI_UID, Process.VPN_UID,
            Process.ROOT_UID, Process.SYSTEM_UID };

    // This class manages life cycle events for encrypted users on File Based Encryption (FBE)
    // devices. The most basic of these is to show/hide notifications about missing features until
    // the user unlocks the account and credential-encrypted storage is available.
    public static final class Lifecycle extends SystemService {
        private LockSettingsService mLockSettingsService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            AndroidKeyStoreProvider.install();
            mLockSettingsService = new LockSettingsService(getContext());
            publishBinderService("lock_settings", mLockSettingsService);
        }

        @Override
        public void onStartUser(int userHandle) {
            mLockSettingsService.onStartUser(userHandle);
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mLockSettingsService.onUnlockUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            mLockSettingsService.onCleanupUser(userHandle);
        }
    }

    @VisibleForTesting
    protected static class SynchronizedStrongAuthTracker
            extends LockPatternUtils.StrongAuthTracker {
        public SynchronizedStrongAuthTracker(Context context) {
            super(context);
        }

        @Override
        protected void handleStrongAuthRequiredChanged(int strongAuthFlags, int userId) {
            synchronized (this) {
                super.handleStrongAuthRequiredChanged(strongAuthFlags, userId);
            }
        }

        @Override
        public int getStrongAuthForUser(int userId) {
            synchronized (this) {
                return super.getStrongAuthForUser(userId);
            }
        }

        void register(LockSettingsStrongAuth strongAuth) {
            strongAuth.registerStrongAuthTracker(this.mStub);
        }
    }

    /**
     * Tie managed profile to primary profile if it is in unified mode and not tied before.
     *
     * @param managedUserId Managed profile user Id
     * @param managedUserPassword Managed profile original password (when it has separated lock).
     *            NULL when it does not have a separated lock before.
     */
    public void tieManagedProfileLockIfNecessary(int managedUserId, String managedUserPassword) {
        if (DEBUG) Slog.v(TAG, "Check child profile lock for user: " + managedUserId);
        // Only for managed profile
        if (!mUserManager.getUserInfo(managedUserId).isManagedProfile()) {
            return;
        }
        // Do not tie managed profile when work challenge is enabled
        if (mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
            return;
        }
        // Do not tie managed profile to parent when it's done already
        if (mStorage.hasChildProfileLock(managedUserId)) {
            return;
        }
        // Do not tie it to parent when parent does not have a screen lock
        final int parentId = mUserManager.getProfileParent(managedUserId).id;
        if (!mStorage.hasPassword(parentId) && !mStorage.hasPattern(parentId)) {
            if (DEBUG) Slog.v(TAG, "Parent does not have a screen lock");
            return;
        }
        // Do not tie when the parent has no SID (but does have a screen lock).
        // This can only happen during an upgrade path where SID is yet to be
        // generated when the user unlocks for the first time.
        try {
            if (getGateKeeperService().getSecureUserId(parentId) == 0) {
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to talk to GateKeeper service", e);
            return;
        }
        if (DEBUG) Slog.v(TAG, "Tie managed profile to parent now!");
        byte[] randomLockSeed = new byte[] {};
        try {
            randomLockSeed = SecureRandom.getInstance("SHA1PRNG").generateSeed(40);
            String newPassword = String.valueOf(HexEncoding.encode(randomLockSeed));
            setLockCredentialInternal(newPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    managedUserPassword, managedUserId);
            // We store a private credential for the managed user that's unlocked by the primary
            // account holder's credential. As such, the user will never be prompted to enter this
            // password directly, so we always store a password.
            setLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                    DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC, managedUserId);
            tieProfileLockToParent(managedUserId, newPassword);
        } catch (NoSuchAlgorithmException | RemoteException e) {
            Slog.e(TAG, "Fail to tie managed profile", e);
            // Nothing client can do to fix this issue, so we do not throw exception out
        }
    }

    static class Injector {

        protected Context mContext;

        public Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public Handler getHandler() {
            return new Handler();
        }

        public LockSettingsStorage getStorage() {
            final LockSettingsStorage storage = new LockSettingsStorage(mContext);
            storage.setDatabaseOnCreateCallback(new LockSettingsStorage.Callback() {
                @Override
                public void initialize(SQLiteDatabase db) {
                    // Get the lockscreen default from a system property, if available
                    boolean lockScreenDisable = SystemProperties.getBoolean(
                            "ro.lockscreen.disable.default", false);
                    if (lockScreenDisable) {
                        storage.writeKeyValue(db, LockPatternUtils.DISABLE_LOCKSCREEN_KEY, "1", 0);
                    }
                }
            });
            return storage;
        }

        public LockSettingsStrongAuth getStrongAuth() {
            return new LockSettingsStrongAuth(mContext);
        }

        public SynchronizedStrongAuthTracker getStrongAuthTracker() {
            return new SynchronizedStrongAuthTracker(mContext);
        }

        public IActivityManager getActivityManager() {
            return ActivityManager.getService();
        }

        public LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(mContext);
        }

        public NotificationManager getNotificationManager() {
            return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        public UserManager getUserManager() {
            return (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        }

        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        public IStorageManager getStorageManager() {
            final IBinder service = ServiceManager.getService("mount");
            if (service != null) {
                return IStorageManager.Stub.asInterface(service);
            }
            return null;
        }
    }

    public LockSettingsService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    protected LockSettingsService(Injector injector) {
        mInjector = injector;
        mContext = injector.getContext();
        mKeyStore = injector.getKeyStore();
        mHandler = injector.getHandler();
        mStrongAuth = injector.getStrongAuth();
        mActivityManager = injector.getActivityManager();

        mLockPatternUtils = injector.getLockPatternUtils();
        mFirstCallToVold = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        injector.getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter,
                null, null);

        mStorage = injector.getStorage();
        mNotificationManager = injector.getNotificationManager();
        mUserManager = injector.getUserManager();
        mStrongAuthTracker = injector.getStrongAuthTracker();
        mStrongAuthTracker.register(mStrongAuth);
    }

    /**
     * If the account is credential-encrypted, show notification requesting the user to unlock the
     * device.
     */
    private void maybeShowEncryptionNotificationForUser(@UserIdInt int userId) {
        final UserInfo user = mUserManager.getUserInfo(userId);
        if (!user.isManagedProfile()) {
            // When the user is locked, we communicate it loud-and-clear
            // on the lockscreen; we only show a notification below for
            // locked managed profiles.
            return;
        }

        final UserHandle userHandle = user.getUserHandle();
        final boolean isSecure = mStorage.hasPassword(userId) || mStorage.hasPattern(userId);
        if (isSecure && !mUserManager.isUserUnlockingOrUnlocked(userHandle)) {
            UserInfo parent = mUserManager.getProfileParent(userId);
            if (parent != null &&
                    mUserManager.isUserUnlockingOrUnlocked(parent.getUserHandle()) &&
                    !mUserManager.isQuietModeEnabled(userHandle)) {
                // Only show notifications for managed profiles once their parent
                // user is unlocked.
                showEncryptionNotificationForProfile(userHandle);
            }
        }
    }

    private void showEncryptionNotificationForProfile(UserHandle user) {
        Resources r = mContext.getResources();
        CharSequence title = r.getText(
                com.android.internal.R.string.user_encrypted_title);
        CharSequence message = r.getText(
                com.android.internal.R.string.profile_encrypted_message);
        CharSequence detail = r.getText(
                com.android.internal.R.string.profile_encrypted_detail);

        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(KEYGUARD_SERVICE);
        final Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null,
                user.getIdentifier());
        if (unlockIntent == null) {
            return;
        }
        unlockIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent intent = PendingIntent.getActivity(mContext, 0, unlockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        showEncryptionNotification(user, title, message, detail, intent);
    }

    private void showEncryptionNotification(UserHandle user, CharSequence title,
            CharSequence message, CharSequence detail, PendingIntent intent) {
        if (DEBUG) Slog.v(TAG, "showing encryption notification, user: " + user.getIdentifier());

        // Suppress all notifications on non-FBE devices for now
        if (!StorageManager.isFileEncryptedNativeOrEmulated()) return;

        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(com.android.internal.R.drawable.ic_user_secure)
                .setWhen(0)
                .setOngoing(true)
                .setTicker(title)
                .setDefaults(0) // please be quiet
                .setPriority(Notification.PRIORITY_MAX)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(message)
                .setSubText(detail)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(intent)
                .build();
        mNotificationManager.notifyAsUser(null, FBE_ENCRYPTED_NOTIFICATION, notification, user);
    }

    private void hideEncryptionNotification(UserHandle userHandle) {
        if (DEBUG) Slog.v(TAG, "hide encryption notification, user: " + userHandle.getIdentifier());
        mNotificationManager.cancelAsUser(null, FBE_ENCRYPTED_NOTIFICATION, userHandle);
    }

    public void onCleanupUser(int userId) {
        hideEncryptionNotification(new UserHandle(userId));
    }

    public void onStartUser(final int userId) {
        maybeShowEncryptionNotificationForUser(userId);
    }

    public void onUnlockUser(final int userId) {
        // Hide notification first, as tie managed profile lock takes time
        hideEncryptionNotification(new UserHandle(userId));

        if (mUserManager.getUserInfo(userId).isManagedProfile()) {
            // As tieManagedProfileLockIfNecessary() may try to unlock user, we should not do it
            // in onUnlockUser() synchronously, otherwise it may cause a deadlock
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    tieManagedProfileLockIfNecessary(userId, null);
                }
            });
        }

        // Now we have unlocked the parent user we should show notifications
        // about any profiles that exist.
        List<UserInfo> profiles = mUserManager.getProfiles(userId);
        for (int i = 0; i < profiles.size(); i++) {
            UserInfo profile = profiles.get(i);
            final boolean isSecure =
                    mStorage.hasPassword(profile.id) || mStorage.hasPattern(profile.id);
            if (isSecure && profile.isManagedProfile()) {
                UserHandle userHandle = profile.getUserHandle();
                if (!mUserManager.isUserUnlockingOrUnlocked(userHandle) &&
                        !mUserManager.isQuietModeEnabled(userHandle)) {
                    showEncryptionNotificationForProfile(userHandle);
                }
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                // Notify keystore that a new user was added.
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (userHandle > UserHandle.USER_SYSTEM) {
                    removeUser(userHandle, /* unknownUser= */ true);
                }
                final KeyStore ks = KeyStore.getInstance();
                final UserInfo parentInfo = mUserManager.getProfileParent(userHandle);
                final int parentHandle = parentInfo != null ? parentInfo.id : -1;
                ks.onUserAdded(userHandle, parentHandle);
            } else if (Intent.ACTION_USER_STARTING.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mStorage.prefetchUser(userHandle);
            } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (userHandle > 0) {
                    removeUser(userHandle, /* unknownUser= */ false);
                }
            }
        }
    };

    @Override // binder interface
    public void systemReady() {
        migrateOldData();
        try {
            getGateKeeperService();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failure retrieving IGateKeeperService", e);
        }
        // TODO: maybe skip this for split system user mode.
        mStorage.prefetchUser(UserHandle.USER_SYSTEM);
    }

    private void migrateOldData() {
        try {
            // These Settings moved before multi-user was enabled, so we only have to do it for the
            // root user.
            if (getString("migrated", null, 0) == null) {
                final ContentResolver cr = mContext.getContentResolver();
                for (String validSetting : VALID_SETTINGS) {
                    String value = Settings.Secure.getString(cr, validSetting);
                    if (value != null) {
                        setString(validSetting, value, 0);
                    }
                }
                // No need to move the password / pattern files. They're already in the right place.
                setString("migrated", "true", 0);
                Slog.i(TAG, "Migrated lock settings to new location");
            }

            // These Settings changed after multi-user was enabled, hence need to be moved per user.
            if (getString("migrated_user_specific", null, 0) == null) {
                final ContentResolver cr = mContext.getContentResolver();
                List<UserInfo> users = mUserManager.getUsers();
                for (int user = 0; user < users.size(); user++) {
                    // Migrate owner info
                    final int userId = users.get(user).id;
                    final String OWNER_INFO = Secure.LOCK_SCREEN_OWNER_INFO;
                    String ownerInfo = Settings.Secure.getStringForUser(cr, OWNER_INFO, userId);
                    if (!TextUtils.isEmpty(ownerInfo)) {
                        setString(OWNER_INFO, ownerInfo, userId);
                        Settings.Secure.putStringForUser(cr, OWNER_INFO, "", userId);
                    }

                    // Migrate owner info enabled. Note there was a bug where older platforms only
                    // stored this value if the checkbox was toggled at least once. The code detects
                    // this case by handling the exception.
                    final String OWNER_INFO_ENABLED = Secure.LOCK_SCREEN_OWNER_INFO_ENABLED;
                    boolean enabled;
                    try {
                        int ivalue = Settings.Secure.getIntForUser(cr, OWNER_INFO_ENABLED, userId);
                        enabled = ivalue != 0;
                        setLong(OWNER_INFO_ENABLED, enabled ? 1 : 0, userId);
                    } catch (SettingNotFoundException e) {
                        // Setting was never stored. Store it if the string is not empty.
                        if (!TextUtils.isEmpty(ownerInfo)) {
                            setLong(OWNER_INFO_ENABLED, 1, userId);
                        }
                    }
                    Settings.Secure.putIntForUser(cr, OWNER_INFO_ENABLED, 0, userId);
                }
                // No need to move the password / pattern files. They're already in the right place.
                setString("migrated_user_specific", "true", 0);
                Slog.i(TAG, "Migrated per-user lock settings to new location");
            }

            // Migrates biometric weak such that the fallback mechanism becomes the primary.
            if (getString("migrated_biometric_weak", null, 0) == null) {
                List<UserInfo> users = mUserManager.getUsers();
                for (int i = 0; i < users.size(); i++) {
                    int userId = users.get(i).id;
                    long type = getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                    long alternateType = getLong(LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                    if (type == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK) {
                        setLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                                alternateType,
                                userId);
                    }
                    setLong(LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                }
                setString("migrated_biometric_weak", "true", 0);
                Slog.i(TAG, "Migrated biometric weak to use the fallback instead");
            }

            // Migrates lockscreen.disabled. Prior to M, the flag was ignored when more than one
            // user was present on the system, so if we're upgrading to M and there is more than one
            // user we disable the flag to remain consistent.
            if (getString("migrated_lockscreen_disabled", null, 0) == null) {
                final List<UserInfo> users = mUserManager.getUsers();
                final int userCount = users.size();
                int switchableUsers = 0;
                for (int i = 0; i < userCount; i++) {
                    if (users.get(i).supportsSwitchTo()) {
                        switchableUsers++;
                    }
                }

                if (switchableUsers > 1) {
                    for (int i = 0; i < userCount; i++) {
                        int id = users.get(i).id;

                        if (getBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id)) {
                            setBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id);
                        }
                    }
                }

                setString("migrated_lockscreen_disabled", "true", 0);
                Slog.i(TAG, "Migrated lockscreen disabled flag");
            }

            final List<UserInfo> users = mUserManager.getUsers();
            for (int i = 0; i < users.size(); i++) {
                final UserInfo userInfo = users.get(i);
                if (userInfo.isManagedProfile() && mStorage.hasChildProfileLock(userInfo.id)) {
                    // When managed profile has a unified lock, the password quality stored has 2
                    // possibilities only.
                    // 1). PASSWORD_QUALITY_UNSPECIFIED, which is upgraded from dp2, and we are
                    // going to set it back to PASSWORD_QUALITY_ALPHANUMERIC.
                    // 2). PASSWORD_QUALITY_ALPHANUMERIC, which is the actual password quality for
                    // unified lock.
                    final long quality = getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userInfo.id);
                    if (quality == DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                        // Only possible when it's upgraded from nyc dp3
                        Slog.i(TAG, "Migrated tied profile lock type");
                        setLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                                DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC, userInfo.id);
                    } else if (quality != DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC) {
                        // It should not happen
                        Slog.e(TAG, "Invalid tied profile lock type: " + quality);
                    }
                }
                try {
                    final String alias = LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userInfo.id;
                    java.security.KeyStore keyStore =
                            java.security.KeyStore.getInstance("AndroidKeyStore");
                    keyStore.load(null);
                    if (keyStore.containsAlias(alias)) {
                        keyStore.deleteEntry(alias);
                    }
                } catch (KeyStoreException | NoSuchAlgorithmException |
                        CertificateException | IOException e) {
                    Slog.e(TAG, "Unable to remove tied profile key", e);
                }
            }

            boolean isWatch = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WATCH);
            // Wear used to set DISABLE_LOCKSCREEN to 'true', but because Wear now allows accounts
            // and device management the lockscreen must be re-enabled now for users that upgrade.
            if (isWatch && getString("migrated_wear_lockscreen_disabled", null, 0) == null) {
                final int userCount = users.size();
                for (int i = 0; i < userCount; i++) {
                    int id = users.get(i).id;
                    setBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id);
                }
                setString("migrated_wear_lockscreen_disabled", "true", 0);
                Slog.i(TAG, "Migrated lockscreen_disabled for Wear devices");
            }
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to migrate old data", re);
        }
    }

    private final void checkWritePermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsRead");
    }

    private final void checkReadPermission(String requestedKey, int userId) {
        final int callingUid = Binder.getCallingUid();

        for (int i = 0; i < READ_CONTACTS_PROTECTED_SETTINGS.length; i++) {
            String key = READ_CONTACTS_PROTECTED_SETTINGS[i];
            if (key.equals(requestedKey) && mContext.checkCallingOrSelfPermission(READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("uid=" + callingUid
                        + " needs permission " + READ_CONTACTS + " to read "
                        + requestedKey + " for user " + userId);
            }
        }

        for (int i = 0; i < READ_PASSWORD_PROTECTED_SETTINGS.length; i++) {
            String key = READ_PASSWORD_PROTECTED_SETTINGS[i];
            if (key.equals(requestedKey) && mContext.checkCallingOrSelfPermission(PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("uid=" + callingUid
                        + " needs permission " + PERMISSION + " to read "
                        + requestedKey + " for user " + userId);
            }
        }
    }

    @Override
    public boolean getSeparateProfileChallengeEnabled(int userId) throws RemoteException {
        checkReadPermission(SEPARATE_PROFILE_CHALLENGE_KEY, userId);
        synchronized (mSeparateChallengeLock) {
            return getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        }
    }

    @Override
    public void setSeparateProfileChallengeEnabled(int userId, boolean enabled,
            String managedUserPassword) throws RemoteException {
        checkWritePermission(userId);
        synchronized (mSeparateChallengeLock) {
            setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, enabled, userId);
            if (enabled) {
                mStorage.removeChildProfileLock(userId);
                removeKeystoreProfileKey(userId);
            } else {
                tieManagedProfileLockIfNecessary(userId, managedUserPassword);
            }
        }
    }

    @Override
    public void setBoolean(String key, boolean value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value ? "1" : "0");
    }

    @Override
    public void setLong(String key, long value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, Long.toString(value));
    }

    @Override
    public void setString(String key, String value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value);
    }

    private void setStringUnchecked(String key, int userId, String value) {
        mStorage.writeKeyValue(key, value, userId);
        if (ArrayUtils.contains(SETTINGS_TO_BACKUP, key)) {
            BackupManager.dataChanged("com.android.providers.settings");
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ?
                defaultValue : (value.equals("1") || value.equals("true"));
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);
        return getStringUnchecked(key, defaultValue, userId);
    }

    public String getStringUnchecked(String key, String defaultValue, int userId) {
        if (Settings.Secure.LOCK_PATTERN_ENABLED.equals(key)) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mLockPatternUtils.isLockPatternEnabled(userId) ? "1" : "0";
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        if (LockPatternUtils.LEGACY_LOCK_PATTERN_ENABLED.equals(key)) {
            key = Settings.Secure.LOCK_PATTERN_ENABLED;
        }

        return mStorage.readKeyValue(key, defaultValue, userId);
    }

    @Override
    public boolean havePassword(int userId) throws RemoteException {
        // Do we need a permissions check here?
        return mStorage.hasPassword(userId);
    }

    @Override
    public boolean havePattern(int userId) throws RemoteException {
        // Do we need a permissions check here?
        return mStorage.hasPattern(userId);
    }

    private void setKeystorePassword(String password, int userHandle) {
        final KeyStore ks = KeyStore.getInstance();
        ks.onUserPasswordChanged(userHandle, password);
    }

    private void unlockKeystore(String password, int userHandle) {
        if (DEBUG) Slog.v(TAG, "Unlock keystore for user: " + userHandle);
        final KeyStore ks = KeyStore.getInstance();
        ks.unlock(userHandle, password);
    }

    @VisibleForTesting
    protected String getDecryptedPasswordForTiedProfile(int userId)
            throws KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException,
            CertificateException, IOException {
        if (DEBUG) Slog.v(TAG, "Get child profile decrytped key");
        byte[] storedData = mStorage.readChildProfileLock(userId);
        if (storedData == null) {
            throw new FileNotFoundException("Child profile lock file not found");
        }
        byte[] iv = Arrays.copyOfRange(storedData, 0, PROFILE_KEY_IV_SIZE);
        byte[] encryptedPassword = Arrays.copyOfRange(storedData, PROFILE_KEY_IV_SIZE,
                storedData.length);
        byte[] decryptionResult;
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey decryptionKey = (SecretKey) keyStore.getKey(
                LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + userId, null);

        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);

        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(128, iv));
        decryptionResult = cipher.doFinal(encryptedPassword);
        return new String(decryptionResult, StandardCharsets.UTF_8);
    }

    private void unlockChildProfile(int profileHandle) throws RemoteException {
        try {
            doVerifyCredential(getDecryptedPasswordForTiedProfile(profileHandle),
                    LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    false, 0 /* no challenge */, profileHandle, null /* progressCallback */);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            if (e instanceof FileNotFoundException) {
                Slog.i(TAG, "Child profile key not found");
            } else {
                Slog.e(TAG, "Failed to decrypt child profile key", e);
            }
        }
    }

    private void unlockUser(int userId, byte[] token, byte[] secret) {
        // TODO: make this method fully async so we can update UI with progress strings
        final CountDownLatch latch = new CountDownLatch(1);
        final IProgressListener listener = new IProgressListener.Stub() {
            @Override
            public void onStarted(int id, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser started");
            }

            @Override
            public void onProgress(int id, int progress, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser progress " + progress);
            }

            @Override
            public void onFinished(int id, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser finished");
                latch.countDown();
            }
        };

        try {
            mActivityManager.unlockUser(userId, token, secret, listener);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!mUserManager.getUserInfo(userId).isManagedProfile()) {
                final List<UserInfo> profiles = mUserManager.getProfiles(userId);
                for (UserInfo pi : profiles) {
                    // Unlock managed profile with unified lock
                    if (pi.isManagedProfile()
                            && !mLockPatternUtils.isSeparateProfileChallengeEnabled(pi.id)
                            && mStorage.hasChildProfileLock(pi.id)) {
                        unlockChildProfile(pi.id);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to unlock child profile", e);
        }
    }

    private byte[] getCurrentHandle(int userId) {
        CredentialHash credential = mStorage.readCredentialHash(userId);

        // sanity check
        if (credential.type != LockPatternUtils.CREDENTIAL_TYPE_NONE && credential.hash == null) {
            Slog.e(TAG, "Stored handle type [" + credential.type + "] but no handle available");
        }
        return credential.hash;
    }

    private void onUserLockChanged(int userId) throws RemoteException {
        if (mUserManager.getUserInfo(userId).isManagedProfile()) {
            return;
        }
        final boolean isSecure = mStorage.hasPassword(userId) || mStorage.hasPattern(userId);
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        final int size = profiles.size();
        for (int i = 0; i < size; i++) {
            final UserInfo profile = profiles.get(i);
            if (profile.isManagedProfile()) {
                final int managedUserId = profile.id;
                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
                    continue;
                }
                if (isSecure) {
                    tieManagedProfileLockIfNecessary(managedUserId, null);
                } else {
                    clearUserKeyProtection(managedUserId);
                    getGateKeeperService().clearSecureUserId(managedUserId);
                    mStorage.writeCredentialHash(CredentialHash.createEmptyHash(), managedUserId);
                    setKeystorePassword(null, managedUserId);
                    fixateNewestUserKeyAuth(managedUserId);
                    mStorage.removeChildProfileLock(managedUserId);
                    removeKeystoreProfileKey(managedUserId);
                }
            }
        }
    }

    private boolean isManagedProfileWithUnifiedLock(int userId) {
        return mUserManager.getUserInfo(userId).isManagedProfile()
                && !mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    private boolean isManagedProfileWithSeparatedLock(int userId) {
        return mUserManager.getUserInfo(userId).isManagedProfile()
                && mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    // This method should be called by LockPatternUtil only, all internal methods in this class
    // should call setLockCredentialInternal.
    @Override
    public void setLockCredential(String credential, int type, String savedCredential, int userId)
            throws RemoteException {
        checkWritePermission(userId);
        synchronized (mSeparateChallengeLock) {
            setLockCredentialInternal(credential, type, savedCredential, userId);
            setSeparateProfileChallengeEnabled(userId, true, null);
            notifyPasswordChanged(userId);
        }
    }

    private void setLockCredentialInternal(String credential, int credentialType,
            String savedCredential, int userId) throws RemoteException {
        byte[] currentHandle = getCurrentHandle(userId);
        if (credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            if (credential != null) {
                Slog.wtf(TAG, "CredentialType is none, but credential is non-null.");
            }
            clearUserKeyProtection(userId);
            getGateKeeperService().clearSecureUserId(userId);
            mStorage.writeCredentialHash(CredentialHash.createEmptyHash(), userId);
            setKeystorePassword(null, userId);
            fixateNewestUserKeyAuth(userId);
            onUserLockChanged(userId);
            notifyActivePasswordMetricsAvailable(null, userId);
            return;
        }
        if (credential == null) {
            throw new RemoteException("Null credential with mismatched credential type");
        }
        if (isManagedProfileWithUnifiedLock(userId)) {
            // get credential from keystore when managed profile has unified lock
            try {
                savedCredential = getDecryptedPasswordForTiedProfile(userId);
            } catch (FileNotFoundException e) {
                Slog.i(TAG, "Child profile key not found");
            } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                    | NoSuchAlgorithmException | NoSuchPaddingException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | CertificateException | IOException e) {
                Slog.e(TAG, "Failed to decrypt child profile key", e);
            }
        } else {
            if (currentHandle == null) {
                if (savedCredential != null) {
                    Slog.w(TAG, "Saved credential provided, but none stored");
                }
                savedCredential = null;
            }
        }

        byte[] enrolledHandle = enrollCredential(currentHandle, savedCredential, credential,
                userId);
        if (enrolledHandle != null) {
            CredentialHash willStore = CredentialHash.create(enrolledHandle, credentialType);
            mStorage.writeCredentialHash(willStore, userId);
            // Refresh the auth token
            setUserKeyProtection(userId, credential,
                    doVerifyCredential(credential, credentialType, true, 0, userId,
                            null /* progressCallback */));
            fixateNewestUserKeyAuth(userId);
            onUserLockChanged(userId);
        } else {
            throw new RemoteException("Failed to enroll " +
                    (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ? "password"
                            : "pattern"));
        }
    }

    @VisibleForTesting
    protected void tieProfileLockToParent(int userId, String password) {
        if (DEBUG) Slog.v(TAG, "tieProfileLockToParent for user: " + userId);
        byte[] randomLockSeed = password.getBytes(StandardCharsets.UTF_8);
        byte[] encryptionResult;
        byte[] iv;
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            try {
                keyStore.setEntry(
                        LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                keyStore.setEntry(
                        LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setUserAuthenticationRequired(true)
                                .setUserAuthenticationValidityDurationSeconds(30)
                                .build());
                // Key imported, obtain a reference to it.
                SecretKey keyStoreEncryptionKey = (SecretKey) keyStore.getKey(
                        LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId, null);
                Cipher cipher = Cipher.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                                + KeyProperties.ENCRYPTION_PADDING_NONE);
                cipher.init(Cipher.ENCRYPT_MODE, keyStoreEncryptionKey);
                encryptionResult = cipher.doFinal(randomLockSeed);
                iv = cipher.getIV();
            } finally {
                // The original key can now be discarded.
                keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId);
            }
        } catch (CertificateException | UnrecoverableKeyException
                | IOException | BadPaddingException | IllegalBlockSizeException | KeyStoreException
                | NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to encrypt key", e);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (iv.length != PROFILE_KEY_IV_SIZE) {
                throw new RuntimeException("Invalid iv length: " + iv.length);
            }
            outputStream.write(iv);
            outputStream.write(encryptionResult);
        } catch (IOException e) {
            throw new RuntimeException("Failed to concatenate byte arrays", e);
        }
        mStorage.writeChildProfileLock(userId, outputStream.toByteArray());
    }

    private byte[] enrollCredential(byte[] enrolledHandle,
            String enrolledCredential, String toEnroll, int userId)
            throws RemoteException {
        checkWritePermission(userId);
        byte[] enrolledCredentialBytes = enrolledCredential == null
                ? null
                : enrolledCredential.getBytes();
        byte[] toEnrollBytes = toEnroll == null
                ? null
                : toEnroll.getBytes();
        GateKeeperResponse response = getGateKeeperService().enroll(userId, enrolledHandle,
                enrolledCredentialBytes, toEnrollBytes);

        if (response == null) {
            return null;
        }

        byte[] hash = response.getPayload();
        if (hash != null) {
            setKeystorePassword(toEnroll, userId);
        } else {
            // Should not happen
            Slog.e(TAG, "Throttled while enrolling a password");
        }
        return hash;
    }

    private void setUserKeyProtection(int userId, String credential, VerifyCredentialResponse vcr)
            throws RemoteException {
        if (vcr == null) {
            throw new RemoteException("Null response verifying a credential we just set");
        }
        if (vcr.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            throw new RemoteException("Non-OK response verifying a credential we just set: "
                    + vcr.getResponseCode());
        }
        byte[] token = vcr.getPayload();
        if (token == null) {
            throw new RemoteException("Empty payload verifying a credential we just set");
        }
        addUserKeyAuth(userId, token, secretFromCredential(credential));
    }

    private void clearUserKeyProtection(int userId) throws RemoteException {
        addUserKeyAuth(userId, null, null);
    }

    private static byte[] secretFromCredential(String credential) throws RemoteException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            // Personalize the hash
            byte[] personalization = "Android FBE credential hash"
                    .getBytes(StandardCharsets.UTF_8);
            // Pad it to the block size of the hash function
            personalization = Arrays.copyOf(personalization, 128);
            digest.update(personalization);
            digest.update(credential.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException for SHA-512");
        }
    }

    private void addUserKeyAuth(int userId, byte[] token, byte[] secret)
            throws RemoteException {
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        final IStorageManager storageManager = mInjector.getStorageManager();
        final long callingId = Binder.clearCallingIdentity();
        try {
            storageManager.addUserKeyAuth(userId, userInfo.serialNumber, token, secret);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void fixateNewestUserKeyAuth(int userId)
            throws RemoteException {
        final IStorageManager storageManager = mInjector.getStorageManager();
        final long callingId = Binder.clearCallingIdentity();
        try {
            storageManager.fixateNewestUserKeyAuth(userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void resetKeyStore(int userId) throws RemoteException {
        checkWritePermission(userId);
        if (DEBUG) Slog.v(TAG, "Reset keystore for user: " + userId);
        int managedUserId = -1;
        String managedUserDecryptedPassword = null;
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        for (UserInfo pi : profiles) {
            // Unlock managed profile with unified lock
            if (pi.isManagedProfile()
                    && !mLockPatternUtils.isSeparateProfileChallengeEnabled(pi.id)
                    && mStorage.hasChildProfileLock(pi.id)) {
                try {
                    if (managedUserId == -1) {
                        managedUserDecryptedPassword = getDecryptedPasswordForTiedProfile(pi.id);
                        managedUserId = pi.id;
                    } else {
                        // Should not happen
                        Slog.e(TAG, "More than one managed profile, uid1:" + managedUserId
                                + ", uid2:" + pi.id);
                    }
                } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                        | NoSuchAlgorithmException | NoSuchPaddingException
                        | InvalidAlgorithmParameterException | IllegalBlockSizeException
                        | BadPaddingException | CertificateException | IOException e) {
                    Slog.e(TAG, "Failed to decrypt child profile key", e);
                }
            }
        }
        try {
            // Clear all the users credentials could have been installed in for this user.
            for (int profileId : mUserManager.getProfileIdsWithDisabled(userId)) {
                for (int uid : SYSTEM_CREDENTIAL_UIDS) {
                    mKeyStore.clearUid(UserHandle.getUid(profileId, uid));
                }
            }
        } finally {
            if (managedUserId != -1 && managedUserDecryptedPassword != null) {
                if (DEBUG) Slog.v(TAG, "Restore tied profile lock");
                tieProfileLockToParent(managedUserId, managedUserDecryptedPassword);
            }
        }
    }

    @Override
    public VerifyCredentialResponse checkCredential(String credential, int type, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, false, 0, userId, progressCallback);
    }

    @Override
    public VerifyCredentialResponse verifyCredential(String credential, int type, long challenge,
            int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, true, challenge, userId,
                null /* progressCallback */);
    }

    /**
     * Verify user credential and unlock the user. Fix pattern bug by deprecating the old base zero
     * format.
     */
    private VerifyCredentialResponse doVerifyCredential(String credential, int credentialType,
            boolean hasChallenge, long challenge, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if (TextUtils.isEmpty(credential)) {
            throw new IllegalArgumentException("Credential can't be null or empty");
        }

        CredentialHash storedHash = mStorage.readCredentialHash(userId);
        if (storedHash.type != credentialType) {
            Slog.wtf(TAG, "doVerifyCredential type mismatch with stored credential??"
                    + " stored: " + storedHash.type + " passed in: " + credentialType);
            return VerifyCredentialResponse.ERROR;
        }

        boolean shouldReEnrollBaseZero = storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                && storedHash.isBaseZeroPattern;

        String credentialToVerify;
        if (shouldReEnrollBaseZero) {
            credentialToVerify = LockPatternUtils.patternStringToBaseZero(credential);
        } else {
            credentialToVerify = credential;
        }

        VerifyCredentialResponse response = verifyCredential(userId, storedHash, credentialToVerify,
                hasChallenge, challenge, progressCallback);

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);
            if (shouldReEnrollBaseZero) {
                setLockCredentialInternal(credential, storedHash.type, credentialToVerify, userId);
            }
        }

        return response;
    }

    @Override
    public VerifyCredentialResponse verifyTiedProfileChallenge(String credential, int type,
            long challenge, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        if (!isManagedProfileWithUnifiedLock(userId)) {
            throw new RemoteException("User id must be managed profile with unified lock");
        }
        final int parentProfileId = mUserManager.getProfileParent(userId).id;
        // Unlock parent by using parent's challenge
        final VerifyCredentialResponse parentResponse = doVerifyCredential(
                credential,
                type,
                true /* hasChallenge */,
                challenge,
                parentProfileId,
                null /* progressCallback */);
        if (parentResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            // Failed, just return parent's response
            return parentResponse;
        }

        try {
            // Unlock work profile, and work profile with unified lock must use password only
            return doVerifyCredential(getDecryptedPasswordForTiedProfile(userId),
                    LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    true,
                    challenge,
                    userId, null /* progressCallback */);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            Slog.e(TAG, "Failed to decrypt child profile key", e);
            throw new RemoteException("Unable to get tied profile token");
        }
    }

    /**
     * Lowest-level credential verification routine that talks to GateKeeper. If verification
     * passes, unlock the corresponding user and keystore. Also handles the migration from legacy
     * hash to GK.
     */
    private VerifyCredentialResponse verifyCredential(int userId, CredentialHash storedHash,
            String credential, boolean hasChallenge, long challenge,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if ((storedHash == null || storedHash.hash.length == 0) && TextUtils.isEmpty(credential)) {
            // don't need to pass empty credentials to GateKeeper
            return VerifyCredentialResponse.OK;
        }

        if (TextUtils.isEmpty(credential)) {
            return VerifyCredentialResponse.ERROR;
        }

        // We're potentially going to be doing a bunch of disk I/O below as part
        // of unlocking the user, so yell if calling from the main thread.
        StrictMode.noteDiskRead();

        if (storedHash.version == CredentialHash.VERSION_LEGACY) {
            final byte[] hash;
            if (storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
                hash = LockPatternUtils.patternToHash(LockPatternUtils.stringToPattern(credential));
            } else {
                hash = mLockPatternUtils.passwordToHash(credential, userId);
            }
            if (Arrays.equals(hash, storedHash.hash)) {
                if (storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
                    unlockKeystore(LockPatternUtils.patternStringToBaseZero(credential), userId);
                } else {
                    unlockKeystore(credential, userId);
                }
                // Users with legacy credentials don't have credential-backed
                // FBE keys, so just pass through a fake token/secret
                Slog.i(TAG, "Unlocking user with fake token: " + userId);
                final byte[] fakeToken = String.valueOf(userId).getBytes();
                unlockUser(userId, fakeToken, fakeToken);

                // migrate credential to GateKeeper
                setLockCredentialInternal(credential, storedHash.type, null, userId);
                if (!hasChallenge) {
                    notifyActivePasswordMetricsAvailable(credential, userId);
                    return VerifyCredentialResponse.OK;
                }
                // Fall through to get the auth token. Technically this should never happen,
                // as a user that had a legacy credential would have to unlock their device
                // before getting to a flow with a challenge, but supporting for consistency.
            } else {
                return VerifyCredentialResponse.ERROR;
            }
        }

        VerifyCredentialResponse response;
        boolean shouldReEnroll = false;
        GateKeeperResponse gateKeeperResponse = getGateKeeperService()
                .verifyChallenge(userId, challenge, storedHash.hash, credential.getBytes());
        int responseCode = gateKeeperResponse.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            response = new VerifyCredentialResponse(gateKeeperResponse.getTimeout());
        } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            byte[] token = gateKeeperResponse.getPayload();
            if (token == null) {
                // something's wrong if there's no payload with a challenge
                Slog.e(TAG, "verifyChallenge response had no associated payload");
                response = VerifyCredentialResponse.ERROR;
            } else {
                shouldReEnroll = gateKeeperResponse.getShouldReEnroll();
                response = new VerifyCredentialResponse(token);
            }
        } else {
            response = VerifyCredentialResponse.ERROR;
        }

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {

            // credential has matched

            if (progressCallback != null) {
                progressCallback.onCredentialVerified();
            }
            notifyActivePasswordMetricsAvailable(credential, userId);
            unlockKeystore(credential, userId);

            Slog.i(TAG, "Unlocking user " + userId +
                    " with token length " + response.getPayload().length);
            unlockUser(userId, response.getPayload(), secretFromCredential(credential));

            if (isManagedProfileWithSeparatedLock(userId)) {
                TrustManager trustManager =
                        (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
                trustManager.setDeviceLockedForUser(userId, false);
            }
            if (shouldReEnroll) {
                setLockCredentialInternal(credential, storedHash.type, credential, userId);
            }
        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }

        return response;
    }

    private void notifyActivePasswordMetricsAvailable(String password, @UserIdInt int userId) {
        final PasswordMetrics metrics;
        if (password == null) {
            metrics = new PasswordMetrics();
        } else {
            metrics = PasswordMetrics.computeForPassword(password);
            metrics.quality = mLockPatternUtils.getKeyguardStoredPasswordQuality(userId);
        }

        // Asynchronous to avoid dead lock
        mHandler.post(() -> {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.setActivePasswordState(metrics, userId);
        });
    }

    /**
     * Call after {@link #notifyActivePasswordMetricsAvailable} so metrics are updated before
     * reporting the password changed.
     */
    private void notifyPasswordChanged(@UserIdInt int userId) {
        // Same handler as notifyActivePasswordMetricsAvailable to ensure correct ordering
        mHandler.post(() -> {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.reportPasswordChanged(userId);
        });
    }

    @Override
    public boolean checkVoldPassword(int userId) throws RemoteException {
        if (!mFirstCallToVold) {
            return false;
        }
        mFirstCallToVold = false;

        checkPasswordReadPermission(userId);

        // There's no guarantee that this will safely connect, but if it fails
        // we will simply show the lock screen when we shouldn't, so relatively
        // benign. There is an outside chance something nasty would happen if
        // this service restarted before vold stales out the password in this
        // case. The nastiness is limited to not showing the lock screen when
        // we should, within the first minute of decrypting the phone if this
        // service can't connect to vold, it restarts, and then the new instance
        // does successfully connect.
        final IStorageManager service = mInjector.getStorageManager();
        String password;
        long identity = Binder.clearCallingIdentity();
        try {
            password = service.getPassword();
            service.clearPassword();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (password == null) {
            return false;
        }

        try {
            if (mLockPatternUtils.isLockPatternEnabled(userId)) {
                if (checkCredential(password, LockPatternUtils.CREDENTIAL_TYPE_PATTERN, userId,
                        null /* progressCallback */)
                                .getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        try {
            if (mLockPatternUtils.isLockPasswordEnabled(userId)) {
                if (checkCredential(password, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, userId,
                        null /* progressCallback */)
                                .getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    private void removeUser(int userId, boolean unknownUser) {
        mStorage.removeUser(userId);
        mStrongAuth.removeUser(userId);

        final KeyStore ks = KeyStore.getInstance();
        ks.onUserRemoved(userId);

        try {
            final IGateKeeperService gk = getGateKeeperService();
            if (gk != null) {
                gk.clearSecureUserId(userId);
            }
        } catch (RemoteException ex) {
            Slog.w(TAG, "unable to clear GK secure user id");
        }
        if (unknownUser || mUserManager.getUserInfo(userId).isManagedProfile()) {
            removeKeystoreProfileKey(userId);
        }
    }

    private void removeKeystoreProfileKey(int targetUserId) {
        if (DEBUG) Slog.v(TAG, "Remove keystore profile key for user: " + targetUserId);
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + targetUserId);
            keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + targetUserId);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException
                | IOException e) {
            // We have tried our best to remove all keys
            Slog.e(TAG, "Unable to remove keystore profile key for user:" + targetUserId, e);
        }
    }

    @Override
    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(UserHandle.USER_ALL);
        mStrongAuth.registerStrongAuthTracker(tracker);
    }

    @Override
    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(UserHandle.USER_ALL);
        mStrongAuth.unregisterStrongAuthTracker(tracker);
    }

    @Override
    public void requireStrongAuth(int strongAuthReason, int userId) {
        checkWritePermission(userId);
        mStrongAuth.requireStrongAuth(strongAuthReason, userId);
    }

    @Override
    public void userPresent(int userId) {
        checkWritePermission(userId);
        mStrongAuth.reportUnlock(userId);
    }

    @Override
    public int getStrongAuthForUser(int userId) {
        checkPasswordReadPermission(userId);
        return mStrongAuthTracker.getStrongAuthForUser(userId);
    }

    private boolean isCallerShell() {
        final int callingUid = Binder.getCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    private void enforceShell() {
        if (!isCallerShell()) {
            throw new SecurityException("Caller must be shell");
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver)
            throws RemoteException {
        enforceShell();
        final long origId = Binder.clearCallingIdentity();
        try {
            (new LockSettingsShellCommand(mContext, new LockPatternUtils(mContext))).exec(
                    this, in, out, err, args, callback, resultReceiver);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private static final String[] VALID_SETTINGS = new String[] {
            LockPatternUtils.LOCKOUT_PERMANENT_KEY,
            LockPatternUtils.LOCKOUT_ATTEMPT_DEADLINE,
            LockPatternUtils.PATTERN_EVER_CHOSEN_KEY,
            LockPatternUtils.PASSWORD_TYPE_KEY,
            LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
            LockPatternUtils.LOCK_PASSWORD_SALT_KEY,
            LockPatternUtils.DISABLE_LOCKSCREEN_KEY,
            LockPatternUtils.LOCKSCREEN_OPTIONS,
            LockPatternUtils.LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK,
            LockPatternUtils.BIOMETRIC_WEAK_EVER_CHOSEN_KEY,
            LockPatternUtils.LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS,
            LockPatternUtils.PASSWORD_HISTORY_KEY,
            Secure.LOCK_PATTERN_ENABLED,
            Secure.LOCK_BIOMETRIC_WEAK_FLAGS,
            Secure.LOCK_PATTERN_VISIBLE,
            Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED
    };

    // Reading these settings needs the contacts permission
    private static final String[] READ_CONTACTS_PROTECTED_SETTINGS = new String[] {
            Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
            Secure.LOCK_SCREEN_OWNER_INFO
    };

    // Reading these settings needs the same permission as checking the password
    private static final String[] READ_PASSWORD_PROTECTED_SETTINGS = new String[] {
            LockPatternUtils.LOCK_PASSWORD_SALT_KEY,
            LockPatternUtils.PASSWORD_HISTORY_KEY,
            LockPatternUtils.PASSWORD_TYPE_KEY,
            SEPARATE_PROFILE_CHALLENGE_KEY
    };

    private static final String[] SETTINGS_TO_BACKUP = new String[] {
            Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
            Secure.LOCK_SCREEN_OWNER_INFO
    };

    private class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            mGateKeeperService = null;
        }
    }

    private synchronized IGateKeeperService getGateKeeperService()
            throws RemoteException {
        if (mGateKeeperService != null) {
            return mGateKeeperService;
        }

        final IBinder service = ServiceManager.getService(Context.GATEKEEPER_SERVICE);
        if (service != null) {
            service.linkToDeath(new GateKeeperDiedRecipient(), 0);
            mGateKeeperService = IGateKeeperService.Stub.asInterface(service);
            return mGateKeeperService;
        }

        Slog.e(TAG, "Unable to acquire GateKeeperService");
        return null;
    }
}
