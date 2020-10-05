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

package com.android.server.pm.permission;

import static android.Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISALLOWED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISCOURAGED;
import static android.content.pm.PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_ONE_TIME;
import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_ROLE_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE;
import static android.content.pm.PackageManager.MASK_PERMISSION_FLAGS_ALL;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.permission.PermissionManager.KILL_APP_REASON_GIDS_CHANGED;
import static android.permission.PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED;

import static com.android.server.pm.ApexManager.MATCH_ACTIVE_PACKAGE;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_PERMISSIONS;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationPackageManager;
import android.app.IActivityManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PermissionGroupInfoFlags;
import android.content.pm.PackageManager.PermissionInfoFlags;
import android.content.pm.PackageManager.PermissionWhitelistFlags;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.permission.IOnPermissionsChangeListener;
import android.permission.IPermissionManager;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.permission.PermissionManagerInternal;
import android.permission.PermissionManagerInternal.CheckPermissionDelegate;
import android.permission.PermissionManagerInternal.OnRuntimePermissionStateChangedListener;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.ApexManager;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal.DefaultBrowserProvider;
import com.android.server.pm.permission.PermissionManagerServiceInternal.DefaultDialerProvider;
import com.android.server.pm.permission.PermissionManagerServiceInternal.DefaultHomeProvider;
import com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.SoftRestrictedPermissionPolicy;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Manages all permissions and handles permissions related tasks.
 */
public class PermissionManagerService extends IPermissionManager.Stub {
    private static final String TAG = "PackageManager";

    /** Permission grant: not grant the permission. */
    private static final int GRANT_DENIED = 1;
    /** Permission grant: grant the permission as an install permission. */
    private static final int GRANT_INSTALL = 2;
    /** Permission grant: grant the permission as a runtime one. */
    private static final int GRANT_RUNTIME = 3;

    private static final long BACKUP_TIMEOUT_MILLIS = SECONDS.toMillis(60);

    /** Cap the size of permission trees that 3rd party apps can define; in characters of text */
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;
    /** Empty array to avoid allocations */
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * When these flags are set, the system should not automatically modify the permission grant
     * state.
     */
    private static final int BLOCKING_PERMISSION_FLAGS = FLAG_PERMISSION_SYSTEM_FIXED
            | FLAG_PERMISSION_POLICY_FIXED
            | FLAG_PERMISSION_GRANTED_BY_DEFAULT;

    /** Permission flags set by the user */
    private static final int USER_PERMISSION_FLAGS = FLAG_PERMISSION_USER_SET
            | FLAG_PERMISSION_USER_FIXED;

    /** If the permission of the value is granted, so is the key */
    private static final Map<String, String> FULLER_PERMISSION_MAP = new HashMap<>();

    static {
        FULLER_PERMISSION_MAP.put(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        FULLER_PERMISSION_MAP.put(Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    /** Lock to protect internal data access */
    private final Object mLock;

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    @NonNull
    private final DevicePermissionState mState;

    /** Permission controller: User space permission management */
    private PermissionControllerManager mPermissionControllerManager;

    /** Map of OneTimePermissionUserManagers keyed by userId */
    private final SparseArray<OneTimePermissionUserManager> mOneTimePermissionUserManagers =
            new SparseArray<>();

    /** Default permission policy to provide proper behaviour out-of-the-box */
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;

    /** App ops manager */
    private final AppOpsManager mAppOpsManager;

    /**
     * Built-in permissions. Read from system configuration files. Mapping is from
     * UID to permission name.
     */
    private final SparseArray<ArraySet<String>> mSystemPermissions;

    /** Built-in group IDs given to all packages. Read from system configuration files. */
    @NonNull
    private final int[] mGlobalGids;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Context mContext;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final IPlatformCompat mPlatformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

    /** Internal storage for permissions and related settings */
    @GuardedBy("mLock")
    private final PermissionSettings mSettings;

    /** Injector that can be used to facilitate testing. */
    private final Injector mInjector;

    @GuardedBy("mLock")
    private ArraySet<String> mPrivappPermissionsViolations;

    @GuardedBy("mLock")
    private boolean mSystemReady;

    @GuardedBy("mLock")
    private PermissionPolicyInternal mPermissionPolicyInternal;

    /**
     * For each foreground/background permission the mapping:
     * Background permission -> foreground permissions
     */
    @GuardedBy("mLock")
    private ArrayMap<String, List<String>> mBackgroundPermissions;

    /**
     * A permission backup might contain apps that are not installed. In this case we delay the
     * restoration until the app is installed.
     *
     * <p>This array ({@code userId -> noDelayedBackupLeft}) is {@code true} for all the users where
     * there is <u>no more</u> delayed backup left.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mHasNoDelayedPermBackup = new SparseBooleanArray();

    /** Listeners for permission state (granting and flags) changes */
    @GuardedBy("mLock")
    final private ArrayList<OnRuntimePermissionStateChangedListener>
            mRuntimePermissionStateChangedListeners = new ArrayList<>();

    @GuardedBy("mLock")
    private CheckPermissionDelegate mCheckPermissionDelegate;

    @GuardedBy("mLock")
    private final OnPermissionChangeListeners mOnPermissionChangeListeners;

    @GuardedBy("mLock")
    private DefaultBrowserProvider mDefaultBrowserProvider;

    @GuardedBy("mLock")
    private DefaultDialerProvider mDefaultDialerProvider;

    @GuardedBy("mLock")
    private DefaultHomeProvider mDefaultHomeProvider;

    // TODO: Take a look at the methods defined in the callback.
    // The callback was initially created to support the split between permission
    // manager and the package manager. However, it's started to be used for other
    // purposes. It may make sense to keep as an abstraction, but, the methods
    // necessary to be overridden may be different than what was initially needed
    // for the split.
    private PermissionCallback mDefaultPermissionCallback = new PermissionCallback() {
        @Override
        public void onGidsChanged(int appId, int userId) {
            mHandler.post(() -> killUid(appId, userId, KILL_APP_REASON_GIDS_CHANGED));
        }
        @Override
        public void onPermissionGranted(int uid, int userId) {
            mOnPermissionChangeListeners.onPermissionsChanged(uid);

            // Not critical; if this is lost, the application has to request again.
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onInstallPermissionGranted() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionRevoked(int uid, int userId, String reason) {
            mOnPermissionChangeListeners.onPermissionsChanged(uid);

            // Critical; after this call the application should never have the permission
            mPackageManagerInt.writeSettings(false);
            final int appId = UserHandle.getAppId(uid);
            if (reason == null) {
                mHandler.post(() -> killUid(appId, userId, KILL_APP_REASON_PERMISSIONS_REVOKED));
            } else {
                mHandler.post(() -> killUid(appId, userId, reason));
            }
        }
        @Override
        public void onInstallPermissionRevoked() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionUpdated(int[] userIds, boolean sync) {
            mPackageManagerInt.writePermissionSettings(userIds, !sync);
        }
        @Override
        public void onInstallPermissionUpdated() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionRemoved() {
            mPackageManagerInt.writeSettings(false);
        }
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
            onPermissionUpdated(updatedUserIds, sync);
            for (int i = 0; i < updatedUserIds.length; i++) {
                int userUid = UserHandle.getUid(updatedUserIds[i], UserHandle.getAppId(uid));
                mOnPermissionChangeListeners.onPermissionsChanged(userUid);
            }
        }
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
            onInstallPermissionUpdated();
            mOnPermissionChangeListeners.onPermissionsChanged(uid);
        }
    };

    PermissionManagerService(Context context,
            @NonNull Object externalLock) {
        this(context, externalLock, new Injector(context));
    }

    @VisibleForTesting
    PermissionManagerService(Context context, @NonNull Object externalLock,
            @NonNull Injector injector) {
        mInjector = injector;
        // The package info cache is the cache for package and permission information.
        mInjector.invalidatePackageInfoCache();
        mInjector.disablePermissionCache();
        mInjector.disablePackageNamePermissionCache();

        mContext = context;
        mLock = externalLock;
        mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        mUserManagerInt = LocalServices.getService(UserManagerInternal.class);
        mSettings = new PermissionSettings(mLock);
        mState = new DevicePermissionState(mLock);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        Watchdog.getInstance().addThread(mHandler);

        mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(
                context, mHandlerThread.getLooper());
        SystemConfig systemConfig = SystemConfig.getInstance();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mGlobalGids = systemConfig.getGlobalGids();
        mOnPermissionChangeListeners = new OnPermissionChangeListeners(FgThread.get().getLooper());

        // propagate permission configuration
        final ArrayMap<String, SystemConfig.PermissionEntry> permConfig =
                SystemConfig.getInstance().getPermissions();
        synchronized (mLock) {
            for (int i=0; i<permConfig.size(); i++) {
                final SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                BasePermission bp = mSettings.getPermissionLocked(perm.name);
                if (bp == null) {
                    bp = new BasePermission(perm.name, "android", BasePermission.TYPE_BUILTIN);
                    mSettings.putPermissionLocked(perm.name, bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }
        }

        PermissionManagerServiceInternalImpl localService =
                new PermissionManagerServiceInternalImpl();
        LocalServices.addService(PermissionManagerServiceInternal.class, localService);
        LocalServices.addService(PermissionManagerInternal.class, localService);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        mContext.getSystemService(PermissionControllerManager.class).dump(fd, args);
    }

    /**
     * Creates and returns an initialized, internal service for use by other components.
     * <p>
     * The object returned is identical to the one returned by the LocalServices class using:
     * {@code LocalServices.getService(PermissionManagerServiceInternal.class);}
     * <p>
     * NOTE: The external lock is temporary and should be removed. This needs to be a
     * lock created by the permission manager itself.
     */
    public static PermissionManagerServiceInternal create(Context context,
            @NonNull Object externalLock) {
        final PermissionManagerServiceInternal permMgrInt =
                LocalServices.getService(PermissionManagerServiceInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        PermissionManagerService permissionService =
                (PermissionManagerService) ServiceManager.getService("permissionmgr");
        if (permissionService == null) {
            permissionService =
                    new PermissionManagerService(context, externalLock);
            ServiceManager.addService("permissionmgr", permissionService);
        }
        return LocalServices.getService(PermissionManagerServiceInternal.class);
    }

    /**
     * This method should typically only be used when granting or revoking
     * permissions, since the app may immediately restart after this call.
     * <p>
     * If you're doing surgery on app code/data, use {@link PackageFreezer} to
     * guard your work against the app being relaunched.
     */
    public static void killUid(int appId, int userId, String reason) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killUidForPermissionChange(appId, userId, reason);
                } catch (RemoteException e) {
                    /* ignore - same process */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    BasePermission getPermission(String permName) {
        synchronized (mLock) {
            return mSettings.getPermissionLocked(permName);
        }
    }

    @Override
    public String[] getAppOpPermissionPackages(String permName) {
        return getAppOpPermissionPackagesInternal(permName, getCallingUid());
    }

    private String[] getAppOpPermissionPackagesInternal(String permName, int callingUid) {
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            final ArraySet<String> pkgs = mSettings.mAppOpPermissionPackages.get(permName);
            if (pkgs == null) {
                return null;
            }
            return pkgs.toArray(new String[pkgs.size()]);
        }
    }

    @Override
    @NonNull
    public ParceledListSlice<PermissionGroupInfo> getAllPermissionGroups(
            @PermissionGroupInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        synchronized (mLock) {
            final int n = mSettings.mPermissionGroups.size();
            final ArrayList<PermissionGroupInfo> out = new ArrayList<>(n);
            for (ParsedPermissionGroup pg : mSettings.mPermissionGroups.values()) {
                out.add(PackageInfoUtils.generatePermissionGroupInfo(pg, flags));
            }
            return new ParceledListSlice<>(out);
        }
    }


    @Override
    @Nullable
    public PermissionGroupInfo getPermissionGroupInfo(String groupName,
            @PermissionGroupInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            return PackageInfoUtils.generatePermissionGroupInfo(
                    mSettings.mPermissionGroups.get(groupName), flags);
        }
    }


    @Override
    @Nullable
    public PermissionInfo getPermissionInfo(String permName, String packageName,
            @PermissionInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return null;
            }
            final int adjustedProtectionLevel = adjustPermissionProtectionFlagsLocked(
                    bp.getProtectionLevel(), pkg, callingUid);
            return bp.generatePermissionInfo(adjustedProtectionLevel, flags);
        }
    }

    @Override
    @Nullable
    public ParceledListSlice<PermissionInfo> queryPermissionsByGroup(String groupName,
            @PermissionInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (mLock) {
            if (groupName != null && !mSettings.mPermissionGroups.containsKey(groupName)) {
                return null;
            }
            final ArrayList<PermissionInfo> out = new ArrayList<PermissionInfo>(10);
            for (BasePermission bp : mSettings.mPermissions.values()) {
                final PermissionInfo pi = bp.generatePermissionInfo(groupName, flags);
                if (pi != null) {
                    out.add(pi);
                }
            }
            return new ParceledListSlice<>(out);
        }
    }

    @Override
    public boolean addPermission(PermissionInfo info, boolean async) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant apps can't add permissions");
        }
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        final BasePermission tree = mSettings.enforcePermissionTree(info.name, callingUid);
        final boolean added;
        final boolean changed;
        synchronized (mLock) {
            BasePermission bp = mSettings.getPermissionLocked(info.name);
            added = bp == null;
            int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
            if (added) {
                enforcePermissionCapLocked(info, tree);
                bp = new BasePermission(info.name, tree.getSourcePackageName(),
                        BasePermission.TYPE_DYNAMIC);
            } else if (!bp.isDynamic()) {
                throw new SecurityException("Not allowed to modify non-dynamic permission "
                        + info.name);
            }
            changed = bp.addToTree(fixedLevel, info, tree);
            if (added) {
                mSettings.putPermissionLocked(info.name, bp);
            }
        }
        if (changed) {
            mPackageManagerInt.writeSettings(async);
        }
        return added;
    }

    @Override
    public void removePermission(String permName) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        final BasePermission tree = mSettings.enforcePermissionTree(permName, callingUid);
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return;
            }
            if (bp.isDynamic()) {
                // TODO: switch this back to SecurityException
                Slog.wtf(TAG, "Not allowed to modify non-dynamic permission "
                        + permName);
            }
            mSettings.removePermissionLocked(permName);
            mPackageManagerInt.writeSettings(false);
        }
    }

    @Override
    public int getPermissionFlags(String permName, String packageName, int userId) {
        final int callingUid = getCallingUid();
        return getPermissionFlagsInternal(permName, packageName, callingUid, userId);
    }

    private int getPermissionFlagsInternal(
            String permName, String packageName, int callingUid, int userId) {
        if (!mUserManagerInt.exists(userId)) {
            return 0;
        }

        enforceGrantRevokeGetRuntimePermissionPermissions("getPermissionFlags");
        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                false, // checkShell
                false, // requirePermissionWhenSameUser
                "getPermissionFlags");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return 0;
        }
        synchronized (mLock) {
            if (mSettings.getPermissionLocked(permName) == null) {
                return 0;
            }
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            return 0;
        }
        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
            return 0;
        }
        return uidState.getPermissionFlags(permName);
    }

    @Override
    public void updatePermissionFlags(String permName, String packageName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int userId) {
        final int callingUid = getCallingUid();
        boolean overridePolicy = false;

        if (callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID) {
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                if ((flagMask & FLAG_PERMISSION_POLICY_FIXED) != 0) {
                    if (checkAdjustPolicyFlagPermission) {
                        mContext.enforceCallingOrSelfPermission(
                                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                                "Need " + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY
                                        + " to change policy flags");
                    } else if (mPackageManagerInt.getUidTargetSdkVersion(callingUid)
                            >= Build.VERSION_CODES.Q) {
                        throw new IllegalArgumentException(
                                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY + " needs "
                                        + " to be checked for packages targeting "
                                        + Build.VERSION_CODES.Q + " or later when changing policy "
                                        + "flags");
                    }
                    overridePolicy = true;
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        updatePermissionFlagsInternal(
                permName, packageName, flagMask, flagValues, callingUid, userId,
                overridePolicy, mDefaultPermissionCallback);
    }

    private void updatePermissionFlagsInternal(String permName, String packageName, int flagMask,
            int flagValues, int callingUid, int userId, boolean overridePolicy,
            PermissionCallback callback) {
        if (ApplicationPackageManager.DEBUG_TRACE_PERMISSION_UPDATES
                && ApplicationPackageManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(TAG, "System is updating flags for " + packageName + " "
                            + permName + " for user " + userId  + " "
                            + DebugUtils.flagsToString(
                                    PackageManager.class, "FLAG_PERMISSION_", flagMask)
                            + " := "
                            + DebugUtils.flagsToString(
                                    PackageManager.class, "FLAG_PERMISSION_", flagValues)
                            + " on behalf of uid " + callingUid
                            + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }

        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
                "updatePermissionFlags");

        if ((flagMask & FLAG_PERMISSION_POLICY_FIXED) != 0 && !overridePolicy) {
            throw new SecurityException("updatePermissionFlags requires "
                    + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);
        }

        // Only the system can change these flags and nothing else.
        if (callingUid != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagMask &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        final BasePermission bp;
        synchronized (mLock) {
            bp = mSettings.getPermissionLocked(permName);
        }
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }

        if (bp.isInstallerExemptIgnored()) {
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
        }

        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
            return;
        }

        final boolean hadState = uidState.getPermissionState(permName) != null;
        if (!hadState) {
            boolean isRequested = false;
            // Fast path, the current package has requested the permission.
            if (pkg.getRequestedPermissions().contains(permName)) {
                isRequested = true;
            }
            if (!isRequested) {
                // Slow path, go through all shared user packages.
                String[] sharedUserPackageNames =
                        mPackageManagerInt.getSharedUserPackagesForPackage(packageName, userId);
                for (String sharedUserPackageName : sharedUserPackageNames) {
                    AndroidPackage sharedUserPkg = mPackageManagerInt.getPackage(
                            sharedUserPackageName);
                    if (sharedUserPkg != null
                            && sharedUserPkg.getRequestedPermissions().contains(permName)) {
                        isRequested = true;
                        break;
                    }
                }
            }
            if (!isRequested) {
                Log.e(TAG, "Permission " + permName + " isn't requested by package " + packageName);
                return;
            }
        }
        final boolean permissionUpdated =
                uidState.updatePermissionFlags(bp, flagMask, flagValues);
        if (permissionUpdated && bp.isRuntime()) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }
        if (permissionUpdated && callback != null) {
            // Install and runtime permissions are stored in different places,
            // so figure out what permission changed and persist the change.
            if (!bp.isRuntime()) {
                int userUid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.getUid()));
                callback.onInstallPermissionUpdatedNotifyListener(userUid);
            } else {
                callback.onPermissionUpdatedNotifyListener(new int[]{userId}, false, pkg.getUid());
            }
        }
    }

    /**
     * Update the permission flags for all packages and runtime permissions of a user in order
     * to allow device or profile owner to remove POLICY_FIXED.
     */
    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues,
            final int userId) {
        final int callingUid = getCallingUid();
        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions(
                "updatePermissionFlagsForAllApps");
        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
                "updatePermissionFlagsForAllApps");

        // Only the system can change system fixed flags.
        final int effectiveFlagMask = (callingUid != Process.SYSTEM_UID)
                ? flagMask : flagMask & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
        final int effectiveFlagValues = (callingUid != Process.SYSTEM_UID)
                ? flagValues : flagValues & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;

        final boolean[] changed = new boolean[1];
        mPackageManagerInt.forEachPackage(pkg -> {
            final UidPermissionState uidState = getUidState(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return;
            }
            changed[0] |= uidState.updatePermissionFlagsForAllPermissions(
                    effectiveFlagMask, effectiveFlagValues);
            mOnPermissionChangeListeners.onPermissionsChanged(pkg.getUid());
        });

        if (changed[0]) {
            mPackageManagerInt.writePermissionSettings(new int[] { userId }, true);
        }
    }

    @Override
    public int checkPermission(String permName, String pkgName, @UserIdInt int userId) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (permName == null || pkgName == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }
        if (checkPermissionDelegate == null) {
            return checkPermissionImpl(permName, pkgName, userId);
        }
        return checkPermissionDelegate.checkPermission(permName, pkgName, userId,
                this::checkPermissionImpl);
    }

    private int checkPermissionImpl(String permName, String pkgName, int userId) {
        final AndroidPackage pkg = mPackageManagerInt.getPackage(pkgName);
        if (pkg == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkPermissionInternal(pkg, true, permName, userId);
    }

    private int checkPermissionInternal(@NonNull AndroidPackage pkg, boolean isPackageExplicit,
            @NonNull String permissionName, @UserIdInt int userId) {
        final int callingUid = getCallingUid();
        if (isPackageExplicit || pkg.getSharedUserId() == null) {
            if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                return PackageManager.PERMISSION_DENIED;
            }
        } else {
            if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        final int uid = UserHandle.getUid(userId, pkg.getUid());
        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                    + userId);
            return PackageManager.PERMISSION_DENIED;
        }

        if (checkSinglePermissionInternal(uid, uidState, permissionName)) {
            return PackageManager.PERMISSION_GRANTED;
        }

        final String fullerPermissionName = FULLER_PERMISSION_MAP.get(permissionName);
        if (fullerPermissionName != null
                && checkSinglePermissionInternal(uid, uidState, fullerPermissionName)) {
            return PackageManager.PERMISSION_GRANTED;
        }

        return PackageManager.PERMISSION_DENIED;
    }

    private boolean checkSinglePermissionInternal(int uid,
            @NonNull UidPermissionState uidState, @NonNull String permissionName) {
        if (!uidState.isPermissionGranted(permissionName)) {
            return false;
        }

        if (mPackageManagerInt.getInstantAppPackageName(uid) != null) {
            return mSettings.isPermissionInstant(permissionName);
        }

        return true;
    }

    @Override
    public int checkUidPermission(String permName, int uid) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (permName == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        final int userId = UserHandle.getUserId(uid);
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }
        if (checkPermissionDelegate == null)  {
            return checkUidPermissionImpl(permName, uid);
        }
        return checkPermissionDelegate.checkUidPermission(permName, uid,
                this::checkUidPermissionImpl);
    }

    private int checkUidPermissionImpl(String permName, int uid) {
        final AndroidPackage pkg = mPackageManagerInt.getPackage(uid);
        return checkUidPermissionInternal(pkg, uid, permName);
    }

    /**
     * Checks whether or not the given package has been granted the specified
     * permission. If the given package is {@code null}, we instead check the
     * system permissions for the given UID.
     *
     * @see SystemConfig#getSystemPermissions()
     */
    private int checkUidPermissionInternal(@Nullable AndroidPackage pkg, int uid,
            @NonNull String permissionName) {
        if (pkg != null) {
            final int userId = UserHandle.getUserId(uid);
            return checkPermissionInternal(pkg, false, permissionName, userId);
        }

        if (checkSingleUidPermissionInternal(uid, permissionName)) {
            return PackageManager.PERMISSION_GRANTED;
        }

        final String fullerPermissionName = FULLER_PERMISSION_MAP.get(permissionName);
        if (fullerPermissionName != null
                && checkSingleUidPermissionInternal(uid, fullerPermissionName)) {
            return PackageManager.PERMISSION_GRANTED;
        }

        return PackageManager.PERMISSION_DENIED;
    }

    private boolean checkSingleUidPermissionInternal(int uid, @NonNull String permissionName) {
        synchronized (mLock) {
            ArraySet<String> permissions = mSystemPermissions.get(uid);
            return permissions != null && permissions.contains(permissionName);
        }
    }

    @Override
    public int checkDeviceIdentifierAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        // If the check is being requested by an app then only allow the app to query its own
        // access status.
        int callingUid = mInjector.getCallingUid();
        int callingPid = mInjector.getCallingPid();
        if (UserHandle.getAppId(callingUid) >= Process.FIRST_APPLICATION_UID && (callingUid != uid
                || callingPid != pid)) {
            String response = String.format(
                    "Calling uid %d, pid %d cannot check device identifier access for package %s "
                            + "(uid=%d, pid=%d)",
                    callingUid, callingPid, packageName, uid, pid);
            Log.w(TAG, response);
            throw new SecurityException(response);
        }
        // Allow system and root access to the device identifiers.
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // Allow access to packages that have the READ_PRIVILEGED_PHONE_STATE permission.
        if (mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, pid,
                uid) == PackageManager.PERMISSION_GRANTED) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the calling package is not null then perform the appop and device / profile owner
        // check.
        if (packageName != null) {
            // Allow access to a package that has been granted the READ_DEVICE_IDENTIFIERS appop.
            long token = mInjector.clearCallingIdentity();
            AppOpsManager appOpsManager = (AppOpsManager) mInjector.getSystemService(
                    Context.APP_OPS_SERVICE);
            try {
                if (appOpsManager.noteOpNoThrow(AppOpsManager.OPSTR_READ_DEVICE_IDENTIFIERS, uid,
                        packageName, callingFeatureId, message) == AppOpsManager.MODE_ALLOWED) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            } finally {
                mInjector.restoreCallingIdentity(token);
            }
            // Check if the calling packages meets the device / profile owner requirements for
            // identifier access.
            DevicePolicyManager devicePolicyManager =
                    (DevicePolicyManager) mInjector.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (devicePolicyManager != null && devicePolicyManager.hasDeviceIdentifierAccess(
                    packageName, pid, uid)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS,
                "addOnPermissionsChangeListener");

        synchronized (mLock) {
            mOnPermissionChangeListeners.addListenerLocked(listener);
        }
    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        if (mPackageManagerInt.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        synchronized (mLock) {
            mOnPermissionChangeListeners.removeListenerLocked(listener);
        }
    }

    @Override
    @Nullable public List<String> getWhitelistedRestrictedPermissions(@NonNull String packageName,
            @PermissionWhitelistFlags int flags, @UserIdInt int userId) {
        Objects.requireNonNull(packageName);
        Preconditions.checkFlagsArgument(flags,
                PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                        | PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                        | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER
                        | PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE);
        Preconditions.checkArgumentNonNegative(userId, null);

        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    "getWhitelistedRestrictedPermissions for user " + userId);
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return null;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, UserHandle.getCallingUserId())) {
            return null;
        }
        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if ((flags & (PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                | PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE)) != 0 && !isCallerPrivileged) {
            throw new SecurityException("Querying system or role allowlist requires "
                    + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
        }

        if ((flags & (PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER)) != 0) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw new SecurityException("Querying upgrade or installer allowlist"
                        + " requires being installer on record or "
                        + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final UidPermissionState uidState = getUidState(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
                return null;
            }

            int queryFlags = 0;
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM) != 0) {
                queryFlags |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
            }
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE) != 0) {
                queryFlags |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
            }
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER) != 0) {
                queryFlags |=  FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
            }
            if ((flags & PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE) != 0) {
                queryFlags |=  FLAG_PERMISSION_RESTRICTION_ROLE_EXEMPT;
            }

            ArrayList<String> whitelistedPermissions = null;

            final int permissionCount = ArrayUtils.size(pkg.getRequestedPermissions());
            for (int i = 0; i < permissionCount; i++) {
                final String permissionName = pkg.getRequestedPermissions().get(i);
                final int currentFlags =
                        uidState.getPermissionFlags(permissionName);
                if ((currentFlags & queryFlags) != 0) {
                    if (whitelistedPermissions == null) {
                        whitelistedPermissions = new ArrayList<>();
                    }
                    whitelistedPermissions.add(permissionName);
                }
            }

            return whitelistedPermissions;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean addWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        // Other argument checks are done in get/setWhitelistedRestrictedPermissions
        Objects.requireNonNull(permName);

        if (!checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permName)) {
            return false;
        }

        List<String> permissions =
                getWhitelistedRestrictedPermissions(packageName, flags, userId);
        if (permissions == null) {
            permissions = new ArrayList<>(1);
        }
        if (permissions.indexOf(permName) < 0) {
            permissions.add(permName);
            return setWhitelistedRestrictedPermissionsInternal(packageName, permissions,
                    flags, userId);
        }
        return false;
    }

    private boolean checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(
            @NonNull String permName) {
        synchronized (mLock) {
            final BasePermission bp = mSettings.getPermissionLocked(permName);
            if (bp == null) {
                Slog.w(TAG, "No such permissions: " + permName);
                return false;
            }
            if (bp.isHardOrSoftRestricted() && bp.isImmutablyRestricted()
                    && mContext.checkCallingOrSelfPermission(
                    Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Cannot modify whitelisting of an immutably "
                        + "restricted permission: " + permName);
            }
            return true;
        }
    }

    @Override
    public boolean removeWhitelistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        // Other argument checks are done in get/setWhitelistedRestrictedPermissions
        Objects.requireNonNull(permName);

        if (!checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permName)) {
            return false;
        }

        final List<String> permissions =
                getWhitelistedRestrictedPermissions(packageName, flags, userId);
        if (permissions != null && permissions.remove(permName)) {
            return setWhitelistedRestrictedPermissionsInternal(packageName, permissions,
                    flags, userId);
        }
        return false;
    }

    private boolean setWhitelistedRestrictedPermissionsInternal(@NonNull String packageName,
            @Nullable List<String> permissions, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        Objects.requireNonNull(packageName);
        Preconditions.checkFlagsArgument(flags,
                PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                        | PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                        | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER
                        | PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE);
        Preconditions.checkArgument(Integer.bitCount(flags) == 1);
        Preconditions.checkArgumentNonNegative(userId, null);

        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    "setWhitelistedRestrictedPermissions for user " + userId);
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, UserHandle.getCallingUserId())) {
            return false;
        }

        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if ((flags & (PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                | PackageManager.FLAG_PERMISSION_ALLOWLIST_ROLE)) != 0
                && !isCallerPrivileged) {
            throw new SecurityException("Modifying system or role allowlist requires "
                    + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
        }

        if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE) != 0) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw new SecurityException("Modifying upgrade allowlist requires"
                        + " being installer on record or "
                        + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
            }
            final List<String> whitelistedPermissions =
                    getWhitelistedRestrictedPermissions(pkg.getPackageName(), flags, userId);
            if (permissions == null || permissions.isEmpty()) {
                if (whitelistedPermissions == null || whitelistedPermissions.isEmpty()) {
                    return true;
                }
            } else {
                // Only the system can add and remove while the installer can only remove.
                final int permissionCount = permissions.size();
                for (int i = 0; i < permissionCount; i++) {
                    if ((whitelistedPermissions == null
                            || !whitelistedPermissions.contains(permissions.get(i)))
                            && !isCallerPrivileged) {
                        throw new SecurityException("Adding to upgrade whitelist requires"
                                + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
                    }
                }
            }

            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER) != 0) {
                if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                    throw new SecurityException("Modifying installer whitelist requires"
                            + " being installer on record or "
                            + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
                }
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            setWhitelistedRestrictedPermissionsForUsers(pkg, new int[]{ userId }, permissions,
                    Process.myUid(), flags, mDefaultPermissionCallback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return true;
    }

    @Override
    public boolean setAutoRevokeWhitelisted(
            @NonNull String packageName, boolean whitelisted, int userId) {
        Objects.requireNonNull(packageName);

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final int callingUid = Binder.getCallingUid();
        final int packageUid = UserHandle.getUid(userId, pkg.getUid());

        if (!checkAutoRevokeAccess(pkg, callingUid)) {
            return false;
        }

        if (mAppOpsManager
                .checkOpNoThrow(AppOpsManager.OP_AUTO_REVOKE_MANAGED_BY_INSTALLER,
                        packageUid, packageName)
                != MODE_ALLOWED) {
            // Whitelist user set - don't override
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            mAppOpsManager.setMode(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    packageUid, packageName,
                    whitelisted ? MODE_IGNORED : MODE_ALLOWED);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    private boolean checkAutoRevokeAccess(AndroidPackage pkg, int callingUid) {
        if (pkg == null) {
            return false;
        }

        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS)
                == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
            throw new SecurityException("Caller must either hold "
                    + Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS
                    + " or be the installer on record");
        }
        return true;
    }

    @Override
    public boolean isAutoRevokeWhitelisted(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final int callingUid = Binder.getCallingUid();
        final int packageUid = UserHandle.getUid(userId, pkg.getUid());

        if (!checkAutoRevokeAccess(pkg, callingUid)) {
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, packageUid, packageName)
                    == MODE_IGNORED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void grantRuntimePermission(String packageName, String permName, final int userId) {
        final int callingUid = Binder.getCallingUid();
        final boolean overridePolicy =
                checkUidPermission(ADJUST_RUNTIME_PERMISSIONS_POLICY, callingUid)
                        == PackageManager.PERMISSION_GRANTED;

        grantRuntimePermissionInternal(permName, packageName, overridePolicy,
                callingUid, userId, mDefaultPermissionCallback);
    }

    // TODO swap permission name and package name
    private void grantRuntimePermissionInternal(String permName, String packageName,
            boolean overridePolicy, int callingUid, final int userId, PermissionCallback callback) {
        if (ApplicationPackageManager.DEBUG_TRACE_GRANTS
                && ApplicationPackageManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(TAG, "System is granting " + packageName + " "
                    + permName + " for user " + userId + " on behalf of uid " + callingUid
                    + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "grantRuntimePermission");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
                "grantRuntimePermission");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final PackageSetting ps = (PackageSetting) mPackageManagerInt.getPackageSetting(
                packageName);
        if (pkg == null || ps == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        final BasePermission bp;
        synchronized (mLock) {
            bp = mSettings.getPermissionLocked(permName);
        }
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                    + userId);
            return;
        }

        bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg, uidState);

        // If a permission review is required for legacy apps we represent
        // their permissions as always granted runtime ones since we need
        // to keep the review required permission flag per user while an
        // install permission's state is shared across all users.
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M
                && bp.isRuntime()) {
            return;
        }

        final int uid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.getUid()));

        final int flags = uidState.getPermissionFlags(permName);
        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
            Log.e(TAG, "Cannot grant system fixed permission "
                    + permName + " for package " + packageName);
            return;
        }
        if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
            Log.e(TAG, "Cannot grant policy fixed permission "
                    + permName + " for package " + packageName);
            return;
        }

        if (bp.isHardRestricted()
                && (flags & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) == 0) {
            Log.e(TAG, "Cannot grant hard restricted non-exempt permission "
                    + permName + " for package " + packageName);
            return;
        }

        if (bp.isSoftRestricted() && !SoftRestrictedPermissionPolicy.forPermission(mContext,
                pkg.toAppInfoWithoutState(), pkg, UserHandle.of(userId), permName)
                .mayGrantPermission()) {
            Log.e(TAG, "Cannot grant soft restricted permission " + permName + " for package "
                    + packageName);
            return;
        }

        if (bp.isDevelopment()) {
            // Development permissions must be handled specially, since they are not
            // normal runtime permissions.  For now they apply to all users.
            // TODO(zhanghai): We are breaking the behavior above by making all permission state
            //  per-user. It isn't documented behavior and relatively rarely used anyway.
            if (uidState.grantPermission(bp)) {
                if (callback != null) {
                    callback.onInstallPermissionGranted();
                }
            }
            return;
        }

        if (ps.getInstantApp(userId) && !bp.isInstant()) {
            throw new SecurityException("Cannot grant non-ephemeral permission"
                    + permName + " for package " + packageName);
        }

        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M) {
            Slog.w(TAG, "Cannot grant runtime permission to a legacy app");
            return;
        }

        if (!uidState.grantPermission(bp)) {
            return;
        }

        if (bp.hasGids()) {
            if (callback != null) {
                callback.onGidsChanged(UserHandle.getAppId(pkg.getUid()), userId);
            }
        }

        if (bp.isRuntime()) {
            logPermission(MetricsEvent.ACTION_PERMISSION_GRANTED, permName, packageName);
        }

        if (callback != null) {
            callback.onPermissionGranted(uid, userId);
        }

        if (bp.isRuntime()) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }

        // Only need to do this if user is initialized. Otherwise it's a new user
        // and there are no processes running as the user yet and there's no need
        // to make an expensive call to remount processes for the changed permissions.
        if (READ_EXTERNAL_STORAGE.equals(permName)
                || WRITE_EXTERNAL_STORAGE.equals(permName)) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (mUserManagerInt.isUserInitialized(userId)) {
                    StorageManagerInternal storageManagerInternal = LocalServices.getService(
                            StorageManagerInternal.class);
                    storageManagerInternal.onExternalStoragePolicyChanged(uid, packageName);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, int userId,
            String reason) {
        final int callingUid = Binder.getCallingUid();
        final boolean overridePolicy =
                checkUidPermission(ADJUST_RUNTIME_PERMISSIONS_POLICY, callingUid)
                        == PackageManager.PERMISSION_GRANTED;

        revokeRuntimePermissionInternal(permName, packageName, overridePolicy, callingUid, userId,
                reason, mDefaultPermissionCallback);
    }

    // TODO swap permission name and package name
    private void revokeRuntimePermissionInternal(String permName, String packageName,
            boolean overridePolicy, int callingUid, final int userId, String reason,
            PermissionCallback callback) {
        if (ApplicationPackageManager.DEBUG_TRACE_PERMISSION_UPDATES
                && ApplicationPackageManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(TAG, "System is revoking " + packageName + " "
                            + permName + " for user " + userId + " on behalf of uid " + callingUid
                            + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                false, // requirePermissionWhenSameUser
                "revokeRuntimePermission");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        final BasePermission bp = mSettings.getPermissionLocked(permName);
        if (bp == null) {
            throw new IllegalArgumentException("Unknown permission: " + permName);
        }

        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                    + userId);
            return;
        }

        bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg, uidState);

        // If a permission review is required for legacy apps we represent
        // their permissions as always granted runtime ones since we need
        // to keep the review required permission flag per user while an
        // install permission's state is shared across all users.
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M
                && bp.isRuntime()) {
            return;
        }

        final int flags = uidState.getPermissionFlags(permName);
        // Only the system may revoke SYSTEM_FIXED permissions.
        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                && UserHandle.getCallingAppId() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-System UID cannot revoke system fixed permission "
                    + permName + " for package " + packageName);
        }
        if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
            throw new SecurityException("Cannot revoke policy fixed permission "
                    + permName + " for package " + packageName);
        }

        if (bp.isDevelopment()) {
            // Development permissions must be handled specially, since they are not
            // normal runtime permissions.  For now they apply to all users.
            // TODO(zhanghai): We are breaking the behavior above by making all permission state
            //  per-user. It isn't documented behavior and relatively rarely used anyway.
            if (uidState.revokePermission(bp)) {
                if (callback != null) {
                    mDefaultPermissionCallback.onInstallPermissionRevoked();
                }
            }
            return;
        }

        if (!uidState.revokePermission(bp)) {
            return;
        }

        if (bp.isRuntime()) {
            logPermission(MetricsEvent.ACTION_PERMISSION_REVOKED, permName, packageName);
        }

        if (callback != null) {
            callback.onPermissionRevoked(UserHandle.getUid(userId,
                    UserHandle.getAppId(pkg.getUid())), userId, reason);
        }

        if (bp.isRuntime()) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }
    }

    @Override
    public void resetRuntimePermissions() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "resetRuntimePermissions");
        }

        updateAllPermissions(
                StorageManager.UUID_PRIVATE_INTERNAL, false, mDefaultPermissionCallback);
        for (final int userId : UserManagerService.getInstance().getUserIds()) {
            mPackageManagerInt.forEachPackage(
                    (AndroidPackage pkg) -> resetRuntimePermissionsInternal(pkg, userId));
        }
    }

    /**
     * Reverts user permission state changes (permissions and flags).
     *
     * @param pkg The package for which to reset.
     * @param userId The device user for which to do a reset.
     */
    @GuardedBy("mLock")
    private void resetRuntimePermissionsInternal(final AndroidPackage pkg,
            final int userId) {
        final String packageName = pkg.getPackageName();

        // These are flags that can change base on user actions.
        final int userSettableMask = FLAG_PERMISSION_USER_SET
                | FLAG_PERMISSION_USER_FIXED
                | FLAG_PERMISSION_REVOKED_COMPAT
                | FLAG_PERMISSION_REVIEW_REQUIRED
                | FLAG_PERMISSION_ONE_TIME;

        final int policyOrSystemFlags = FLAG_PERMISSION_SYSTEM_FIXED
                | FLAG_PERMISSION_POLICY_FIXED;

        // Delay and combine non-async permission callbacks
        final int permissionCount = ArrayUtils.size(pkg.getRequestedPermissions());
        final boolean[] permissionRemoved = new boolean[1];
        final ArraySet<Long> revokedPermissions = new ArraySet<>();
        final IntArray syncUpdatedUsers = new IntArray(permissionCount);
        final IntArray asyncUpdatedUsers = new IntArray(permissionCount);

        PermissionCallback delayingPermCallback = new PermissionCallback() {
            public void onGidsChanged(int appId, int userId) {
                mDefaultPermissionCallback.onGidsChanged(appId, userId);
            }

            public void onPermissionChanged() {
                mDefaultPermissionCallback.onPermissionChanged();
            }

            public void onPermissionGranted(int uid, int userId) {
                mDefaultPermissionCallback.onPermissionGranted(uid, userId);
            }

            public void onInstallPermissionGranted() {
                mDefaultPermissionCallback.onInstallPermissionGranted();
            }

            public void onPermissionRevoked(int uid, int userId, String reason) {
                revokedPermissions.add(IntPair.of(uid, userId));

                syncUpdatedUsers.add(userId);
            }

            public void onInstallPermissionRevoked() {
                mDefaultPermissionCallback.onInstallPermissionRevoked();
            }

            public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
                for (int userId : updatedUserIds) {
                    if (sync) {
                        syncUpdatedUsers.add(userId);
                        asyncUpdatedUsers.remove(userId);
                    } else {
                        // Don't override sync=true by sync=false
                        if (syncUpdatedUsers.indexOf(userId) == -1) {
                            asyncUpdatedUsers.add(userId);
                        }
                    }
                }
            }

            public void onPermissionRemoved() {
                permissionRemoved[0] = true;
            }

            public void onInstallPermissionUpdated() {
                mDefaultPermissionCallback.onInstallPermissionUpdated();
            }

            public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds,
                    boolean sync, int uid) {
                onPermissionUpdated(updatedUserIds, sync);
                mOnPermissionChangeListeners.onPermissionsChanged(uid);
            }

            public void onInstallPermissionUpdatedNotifyListener(int uid) {
                mDefaultPermissionCallback.onInstallPermissionUpdatedNotifyListener(uid);
            }
        };

        for (int i = 0; i < permissionCount; i++) {
            final String permName = pkg.getRequestedPermissions().get(i);
            final BasePermission bp;
            synchronized (mLock) {
                bp = mSettings.getPermissionLocked(permName);
            }
            if (bp == null) {
                continue;
            }

            if (bp.isRemoved()) {
                continue;
            }

            // If shared user we just reset the state to which only this app contributed.
            final String[] pkgNames = mPackageManagerInt.getSharedUserPackagesForPackage(
                    pkg.getPackageName(), userId);
            if (pkgNames.length > 0) {
                boolean used = false;
                for (String sharedPkgName : pkgNames) {
                    final AndroidPackage sharedPkg =
                            mPackageManagerInt.getPackage(sharedPkgName);
                    if (sharedPkg != null && !sharedPkg.getPackageName().equals(packageName)
                            && sharedPkg.getRequestedPermissions().contains(permName)) {
                        used = true;
                        break;
                    }
                }
                if (used) {
                    continue;
                }
            }

            final int oldFlags =
                    getPermissionFlagsInternal(permName, packageName, Process.SYSTEM_UID, userId);

            // Always clear the user settable flags.
            // If permission review is enabled and this is a legacy app, mark the
            // permission as requiring a review as this is the initial state.
            final int uid = mPackageManagerInt.getPackageUid(packageName, 0, userId);
            final int targetSdk = mPackageManagerInt.getUidTargetSdkVersion(uid);
            final int flags = (targetSdk < Build.VERSION_CODES.M && bp.isRuntime())
                    ? FLAG_PERMISSION_REVIEW_REQUIRED | FLAG_PERMISSION_REVOKED_COMPAT
                    : 0;

            updatePermissionFlagsInternal(
                    permName, packageName, userSettableMask, flags, Process.SYSTEM_UID, userId,
                    false, delayingPermCallback);

            // Below is only runtime permission handling.
            if (!bp.isRuntime()) {
                continue;
            }

            // Never clobber system or policy.
            if ((oldFlags & policyOrSystemFlags) != 0) {
                continue;
            }

            // If this permission was granted by default or role, make sure it is.
            if ((oldFlags & FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0
                    || (oldFlags & FLAG_PERMISSION_GRANTED_BY_ROLE) != 0) {
                // PermissionPolicyService will handle the app op for runtime permissions later.
                grantRuntimePermissionInternal(permName, packageName, false,
                        Process.SYSTEM_UID, userId, delayingPermCallback);
            // If permission review is enabled the permissions for a legacy apps
            // are represented as constantly granted runtime ones, so don't revoke.
            } else if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                // Otherwise, reset the permission.
                revokeRuntimePermissionInternal(permName, packageName, false, Process.SYSTEM_UID,
                        userId, null, delayingPermCallback);
            }
        }

        // Execute delayed callbacks
        if (permissionRemoved[0]) {
            mDefaultPermissionCallback.onPermissionRemoved();
        }

        // Slight variation on the code in mPermissionCallback.onPermissionRevoked() as we cannot
        // kill uid while holding mPackages-lock
        if (!revokedPermissions.isEmpty()) {
            int numRevokedPermissions = revokedPermissions.size();
            for (int i = 0; i < numRevokedPermissions; i++) {
                int revocationUID = IntPair.first(revokedPermissions.valueAt(i));
                int revocationUserId = IntPair.second(revokedPermissions.valueAt(i));

                mOnPermissionChangeListeners.onPermissionsChanged(revocationUID);

                // Kill app later as we are holding mPackages
                mHandler.post(() -> killUid(UserHandle.getAppId(revocationUID), revocationUserId,
                        KILL_APP_REASON_PERMISSIONS_REVOKED));
            }
        }

        mPackageManagerInt.writePermissionSettings(syncUpdatedUsers.toArray(), false);
        mPackageManagerInt.writePermissionSettings(asyncUpdatedUsers.toArray(), true);
    }

    @Override
    public String getDefaultBrowser(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        DefaultBrowserProvider provider;
        synchronized (mLock) {
            provider = mDefaultBrowserProvider;
        }
        return provider != null ? provider.getDefaultBrowser(userId) : null;
    }

    @Override
    public boolean setDefaultBrowser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        return setDefaultBrowserInternal(packageName, false, true, userId);
    }

    private boolean setDefaultBrowserInternal(String packageName, boolean async,
            boolean doGrant, int userId) {
        if (userId == UserHandle.USER_ALL) {
            return false;
        }
        DefaultBrowserProvider provider;
        synchronized (mLock) {
            provider = mDefaultBrowserProvider;
        }
        if (provider == null) {
            return false;
        }
        if (async) {
            provider.setDefaultBrowserAsync(packageName, userId);
        } else {
            if (!provider.setDefaultBrowser(packageName, userId)) {
                return false;
            }
        }
        if (doGrant && packageName != null) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultBrowser(packageName,
                    userId);
        }
        return true;
    }

    @Override
    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils
                .enforceSystemOrPhoneCaller("grantPermissionsToEnabledCarrierApps", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledCarrierApps(packageNames, userId));
    }

    @Override
    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantDefaultPermissionsToEnabledImsServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledImsServices(packageNames, userId));
    }

    @Override
    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantDefaultPermissionsToEnabledTelephonyDataServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledTelephonyDataServices(packageNames, userId));
    }

    @Override
    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "revokeDefaultPermissionsFromDisabledTelephonyDataServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .revokeDefaultPermissionsFromDisabledTelephonyDataServices(packageNames, userId));
    }

    @Override
    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils
                .enforceSystemOrPhoneCaller("grantDefaultPermissionsToActiveLuiApp", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToActiveLuiApp(packageName, userId));
    }

    @Override
    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils
                .enforceSystemOrPhoneCaller("revokeDefaultPermissionsFromLuiApps", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .revokeDefaultPermissionsFromLuiApps(packageNames, userId));
    }

    @Override
    public void setPermissionEnforced(String permName, boolean enforced) {
        // TODO: Now that we no longer change GID for storage, this should to away.
        mContext.enforceCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "setPermissionEnforced");
        if (READ_EXTERNAL_STORAGE.equals(permName)) {
            mPackageManagerInt.setReadExternalStorageEnforced(enforced);
            // kill any non-foreground processes so we restart them and
            // grant/revoke the GID.
            final IActivityManager am = ActivityManager.getService();
            if (am != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    am.killProcessesBelowForeground("setPermissionEnforcement");
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else {
            throw new IllegalArgumentException("No selective enforcement for " + permName);
        }
    }

    /** @deprecated */
    @Override
    @Deprecated
    public boolean isPermissionEnforced(String permName) {
        // allow instant applications
        return true;
    }

    /**
     * This change makes it so that apps are told to show rationale for asking for background
     * location access every time they request.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long BACKGROUND_RATIONALE_CHANGE_ID = 147316723L;

    @Override
    public boolean shouldShowRequestPermissionRationale(String permName,
            String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "canShowRequestPermissionRationale for user " + userId);
        }

        final int uid =
                mPackageManagerInt.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING, userId);
        if (UserHandle.getAppId(callingUid) != UserHandle.getAppId(uid)) {
            return false;
        }

        if (checkPermission(permName, packageName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final int flags;

        final long identity = Binder.clearCallingIdentity();
        try {
            flags = getPermissionFlagsInternal(permName, packageName, callingUid, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        final int fixedFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                | PackageManager.FLAG_PERMISSION_USER_FIXED;

        if ((flags & fixedFlags) != 0) {
            return false;
        }

        BasePermission permission = getPermission(permName);
        if (permission == null) {
            return false;
        }
        if (permission.isHardRestricted()
                && (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) == 0) {
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            if (permName.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    && mPlatformCompat.isChangeEnabledByPackageName(BACKGROUND_RATIONALE_CHANGE_ID,
                    packageName, userId)) {
                return true;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to check if compatibility change is enabled.", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return (flags & PackageManager.FLAG_PERMISSION_USER_SET) != 0;
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permName, String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "isPermissionRevokedByPolicy for user " + userId);
        }

        if (checkPermission(permName, packageName, userId) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(packageName, callingUid, userId)) {
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final int flags = getPermissionFlagsInternal(permName, packageName, callingUid, userId);
            return (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get the state of the runtime permissions as xml file.
     *
     * <p>Can not be called on main thread.
     *
     * @param user The user the data should be extracted for
     *
     * @return The state as a xml file
     */
    private @Nullable byte[] backupRuntimePermissions(@NonNull UserHandle user) {
        CompletableFuture<byte[]> backup = new CompletableFuture<>();
        mPermissionControllerManager.getRuntimePermissionBackup(user, mContext.getMainExecutor(),
                backup::complete);

        try {
            return backup.get(BACKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException  | TimeoutException e) {
            Slog.e(TAG, "Cannot create permission backup for " + user, e);
            return null;
        }
    }

    /**
     * Restore a permission state previously backed up via {@link #backupRuntimePermissions}.
     *
     * <p>If not all state can be restored, the un-appliable state will be delayed and can be
     * applied via {@link #restoreDelayedRuntimePermissions}.
     *
     * @param backup The state as an xml file
     * @param user The user the data should be restored for
     */
    private void restoreRuntimePermissions(@NonNull byte[] backup, @NonNull UserHandle user) {
        synchronized (mLock) {
            mHasNoDelayedPermBackup.delete(user.getIdentifier());
            mPermissionControllerManager.stageAndApplyRuntimePermissionsBackup(backup, user);
        }
    }

    /**
     * Try to apply permission backup that was previously not applied.
     *
     * <p>Can not be called on main thread.
     *
     * @param packageName The package that is newly installed
     * @param user The user the package is installed for
     *
     * @see #restoreRuntimePermissions
     */
    private void restoreDelayedRuntimePermissions(@NonNull String packageName,
            @NonNull UserHandle user) {
        synchronized (mLock) {
            if (mHasNoDelayedPermBackup.get(user.getIdentifier(), false)) {
                return;
            }

            mPermissionControllerManager.applyStagedRuntimePermissionBackup(packageName, user,
                    mContext.getMainExecutor(), (hasMoreBackup) -> {
                        if (hasMoreBackup) {
                            return;
                        }

                        synchronized (mLock) {
                            mHasNoDelayedPermBackup.put(user.getIdentifier(), true);
                        }
                    });
        }
    }

    private void addOnRuntimePermissionStateChangedListener(@NonNull
            OnRuntimePermissionStateChangedListener listener) {
        synchronized (mLock) {
            mRuntimePermissionStateChangedListeners.add(listener);
        }
    }

    private void removeOnRuntimePermissionStateChangedListener(@NonNull
            OnRuntimePermissionStateChangedListener listener) {
        synchronized (mLock) {
            mRuntimePermissionStateChangedListeners.remove(listener);
        }
    }

    private void notifyRuntimePermissionStateChanged(@NonNull String packageName,
            @UserIdInt int userId) {
        FgThread.getHandler().sendMessage(PooledLambda.obtainMessage
                (PermissionManagerService::doNotifyRuntimePermissionStateChanged,
                        PermissionManagerService.this, packageName, userId));
    }

    private void doNotifyRuntimePermissionStateChanged(@NonNull String packageName,
            @UserIdInt int userId) {
        final ArrayList<OnRuntimePermissionStateChangedListener> listeners;
        synchronized (mLock) {
            if (mRuntimePermissionStateChangedListeners.isEmpty()) {
                return;
            }
            listeners = new ArrayList<>(mRuntimePermissionStateChangedListeners);
        }
        final int listenerCount = listeners.size();
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onRuntimePermissionStateChanged(packageName, userId);
        }
    }

    private int adjustPermissionProtectionFlagsLocked(int protectionLevel,
            @Nullable AndroidPackage pkg, int uid) {
        // Signature permission flags area always reported
        final int protectionLevelMasked = protectionLevel
                & (PermissionInfo.PROTECTION_NORMAL
                | PermissionInfo.PROTECTION_DANGEROUS
                | PermissionInfo.PROTECTION_SIGNATURE);
        if (protectionLevelMasked == PermissionInfo.PROTECTION_SIGNATURE) {
            return protectionLevel;
        }
        // System sees all flags.
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID
                || appId == Process.SHELL_UID) {
            return protectionLevel;
        }
        if (pkg == null) {
            return protectionLevel;
        }
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.O) {
            return protectionLevelMasked;
        }
        // Apps that target O see flags for all protection levels.
        return protectionLevel;
    }

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     * @param allPackageNames All package names
     * @param permissionCallback Callback for permission changed
     */
    private void revokeRuntimePermissionsIfGroupChanged(
            @NonNull AndroidPackage newPackage,
            @NonNull AndroidPackage oldPackage,
            @NonNull ArrayList<String> allPackageNames,
            @NonNull PermissionCallback permissionCallback) {
        final int numOldPackagePermissions = ArrayUtils.size(oldPackage.getPermissions());
        final ArrayMap<String, String> oldPermissionNameToGroupName
                = new ArrayMap<>(numOldPackagePermissions);

        for (int i = 0; i < numOldPackagePermissions; i++) {
            final ParsedPermission permission = oldPackage.getPermissions().get(i);

            if (permission.getParsedPermissionGroup() != null) {
                oldPermissionNameToGroupName.put(permission.getName(),
                        permission.getParsedPermissionGroup().getName());
            }
        }

        final int callingUid = Binder.getCallingUid();
        final int numNewPackagePermissions = ArrayUtils.size(newPackage.getPermissions());
        for (int newPermissionNum = 0; newPermissionNum < numNewPackagePermissions;
                newPermissionNum++) {
            final ParsedPermission newPermission =
                    newPackage.getPermissions().get(newPermissionNum);
            final int newProtection = newPermission.getProtection();

            if ((newProtection & PermissionInfo.PROTECTION_DANGEROUS) != 0) {
                final String permissionName = newPermission.getName();
                final String newPermissionGroupName =
                        newPermission.getParsedPermissionGroup() == null
                                ? null : newPermission.getParsedPermissionGroup().getName();
                final String oldPermissionGroupName = oldPermissionNameToGroupName.get(
                        permissionName);

                if (newPermissionGroupName != null
                        && !newPermissionGroupName.equals(oldPermissionGroupName)) {
                    final int[] userIds = mUserManagerInt.getUserIds();
                    final int numUserIds = userIds.length;
                    for (int userIdNum = 0; userIdNum < numUserIds; userIdNum++) {
                        final int userId = userIds[userIdNum];

                        final int numPackages = allPackageNames.size();
                        for (int packageNum = 0; packageNum < numPackages; packageNum++) {
                            final String packageName = allPackageNames.get(packageNum);
                            final int permissionState = checkPermission(permissionName, packageName,
                                    userId);
                            if (permissionState == PackageManager.PERMISSION_GRANTED) {
                                EventLog.writeEvent(0x534e4554, "72710897",
                                        newPackage.getUid(),
                                        "Revoking permission " + permissionName +
                                        " from package " + packageName +
                                        " as the group changed from " + oldPermissionGroupName +
                                        " to " + newPermissionGroupName);

                                try {
                                    revokeRuntimePermissionInternal(permissionName, packageName,
                                            false, callingUid, userId, null, permissionCallback);
                                } catch (IllegalArgumentException e) {
                                    Slog.e(TAG, "Could not revoke " + permissionName + " from "
                                            + packageName, e);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void addAllPermissions(AndroidPackage pkg, boolean chatty) {
        final int N = ArrayUtils.size(pkg.getPermissions());
        for (int i=0; i<N; i++) {
            ParsedPermission p = pkg.getPermissions().get(i);

            // Assume by default that we did not install this permission into the system.
            p.setFlags(p.getFlags() & ~PermissionInfo.FLAG_INSTALLED);

            synchronized (PermissionManagerService.this.mLock) {
                // Now that permission groups have a special meaning, we ignore permission
                // groups for legacy apps to prevent unexpected behavior. In particular,
                // permissions for one app being granted to someone just because they happen
                // to be in a group defined by another app (before this had no implications).
                if (pkg.getTargetSdkVersion() > Build.VERSION_CODES.LOLLIPOP_MR1) {
                    p.setParsedPermissionGroup(mSettings.mPermissionGroups.get(p.getGroup()));
                    // Warn for a permission in an unknown group.
                    if (DEBUG_PERMISSIONS
                            && p.getGroup() != null && p.getParsedPermissionGroup() == null) {
                        Slog.i(TAG, "Permission " + p.getName() + " from package "
                                + p.getPackageName() + " in an unknown group " + p.getGroup());
                    }
                }

                if (p.isTree()) {
                    final BasePermission bp = BasePermission.createOrUpdate(
                            mPackageManagerInt,
                            mSettings.getPermissionTreeLocked(p.getName()), p, pkg,
                            mSettings.getAllPermissionTreesLocked(), chatty);
                    mSettings.putPermissionTreeLocked(p.getName(), bp);
                } else {
                    final BasePermission bp = BasePermission.createOrUpdate(
                            mPackageManagerInt,
                            mSettings.getPermissionLocked(p.getName()),
                            p, pkg, mSettings.getAllPermissionTreesLocked(), chatty);
                    mSettings.putPermissionLocked(p.getName(), bp);
                }
            }
        }
    }

    private void addAllPermissionGroups(AndroidPackage pkg, boolean chatty) {
        final int N = ArrayUtils.size(pkg.getPermissionGroups());
        StringBuilder r = null;
        for (int i=0; i<N; i++) {
            final ParsedPermissionGroup pg = pkg.getPermissionGroups().get(i);
            final ParsedPermissionGroup cur = mSettings.mPermissionGroups.get(pg.getName());
            final String curPackageName = (cur == null) ? null : cur.getPackageName();
            final boolean isPackageUpdate = pg.getPackageName().equals(curPackageName);
            if (cur == null || isPackageUpdate) {
                mSettings.mPermissionGroups.put(pg.getName(), pg);
                if (chatty && DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    if (isPackageUpdate) {
                        r.append("UPD:");
                    }
                    r.append(pg.getName());
                }
            } else {
                Slog.w(TAG, "Permission group " + pg.getName() + " from package "
                        + pg.getPackageName() + " ignored: original from "
                        + cur.getPackageName());
                if (chatty && DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append("DUP:");
                    r.append(pg.getName());
                }
            }
        }
        if (r != null && DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "  Permission Groups: " + r);
        }

    }

    private void removeAllPermissions(AndroidPackage pkg, boolean chatty) {
        synchronized (mLock) {
            int N = ArrayUtils.size(pkg.getPermissions());
            StringBuilder r = null;
            for (int i=0; i<N; i++) {
                ParsedPermission p = pkg.getPermissions().get(i);
                BasePermission bp = mSettings.mPermissions.get(p.getName());
                if (bp == null) {
                    bp = mSettings.mPermissionTrees.get(p.getName());
                }
                if (bp != null && bp.isPermission(p)) {
                    bp.setPermission(null);
                    if (DEBUG_REMOVE && chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.getName());
                    }
                }
                if (p.isAppOp()) {
                    ArraySet<String> appOpPkgs =
                            mSettings.mAppOpPermissionPackages.get(p.getName());
                    if (appOpPkgs != null) {
                        appOpPkgs.remove(pkg.getPackageName());
                    }
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.getRequestedPermissions().size();
            r = null;
            for (int i=0; i<N; i++) {
                String perm = pkg.getRequestedPermissions().get(i);
                if (mSettings.isPermissionAppOp(perm)) {
                    ArraySet<String> appOpPkgs = mSettings.mAppOpPermissionPackages.get(perm);
                    if (appOpPkgs != null) {
                        appOpPkgs.remove(pkg.getPackageName());
                        if (appOpPkgs.isEmpty()) {
                            mSettings.mAppOpPermissionPackages.remove(perm);
                        }
                    }
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }
        }
    }

    private void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            mState.removeUserState(userId);
        }
    }

    @NonNull
    private Set<String> getGrantedPermissions(@NonNull String packageName,
            @UserIdInt int userId) {
        final PackageSetting ps = mPackageManagerInt.getPackageSetting(packageName);
        if (ps == null) {
            return Collections.emptySet();
        }
        final UidPermissionState uidState = getUidState(ps, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
            return Collections.emptySet();
        }
        if (!ps.getInstantApp(userId)) {
            return uidState.getGrantedPermissions();
        } else {
            // Install permission state is shared among all users, but instant app state is
            // per-user, so we can only filter it here unless we make install permission state
            // per-user as well.
            final Set<String> instantPermissions = new ArraySet<>(uidState.getGrantedPermissions());
            instantPermissions.removeIf(permissionName -> {
                BasePermission permission = mSettings.getPermission(permissionName);
                if (permission == null) {
                    return true;
                }
                if (!permission.isInstant()) {
                    EventLog.writeEvent(0x534e4554, "140256621", UserHandle.getUid(userId,
                            ps.getAppId()), permissionName);
                    return true;
                }
                return false;
            });
            return instantPermissions;
        }
    }

    @NonNull
    private int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId) {
        BasePermission permission = mSettings.getPermission(permissionName);
        if (permission == null) {
            return EmptyArray.INT;
        }
        return permission.computeGids(userId);
    }

    /**
     * Restore the permission state for a package.
     *
     * <ul>
     *     <li>During boot the state gets restored from the disk</li>
     *     <li>During app update the state gets restored from the last version of the app</li>
     * </ul>
     *
     * <p>This restores the permission state for all users.
     *
     * @param pkg the package the permissions belong to
     * @param replace if the package is getting replaced (this might change the requested
     *                permissions of this package)
     * @param packageOfInterest If this is the name of {@code pkg} add extra logging
     * @param callback Result call back
     */
    private void restorePermissionState(@NonNull AndroidPackage pkg, boolean replace,
            @Nullable String packageOfInterest, @Nullable PermissionCallback callback) {
        // IMPORTANT: There are two types of permissions: install and runtime.
        // Install time permissions are granted when the app is installed to
        // all device users and users added in the future. Runtime permissions
        // are granted at runtime explicitly to specific users. Normal and signature
        // protected permissions are install time permissions. Dangerous permissions
        // are install permissions if the app's target SDK is Lollipop MR1 or older,
        // otherwise they are runtime permissions. This function does not manage
        // runtime permissions except for the case an app targeting Lollipop MR1
        // being upgraded to target a newer SDK, in which case dangerous permissions
        // are transformed from install time to runtime ones.

        final PackageSetting ps = (PackageSetting) mPackageManagerInt.getPackageSetting(
                pkg.getPackageName());
        if (ps == null) {
            return;
        }

        final int[] userIds = getAllUserIds();

        boolean runtimePermissionsRevoked = false;
        int[] updatedUserIds = EMPTY_INT_ARRAY;

        for (final int userId : userIds) {
            final UserPermissionState userState = mState.getOrCreateUserState(userId);
            final UidPermissionState uidState = userState.getOrCreateUidState(ps.getAppId());

            if (uidState.isMissing()) {
                Collection<String> requestedPermissions;
                int targetSdkVersion;
                if (!ps.isSharedUser()) {
                    requestedPermissions = pkg.getRequestedPermissions();
                    targetSdkVersion = pkg.getTargetSdkVersion();
                } else {
                    requestedPermissions = new ArraySet<>();
                    targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
                    List<AndroidPackage> packages = ps.getSharedUser().getPackages();
                    int packagesSize = packages.size();
                    for (int i = 0; i < packagesSize; i++) {
                        AndroidPackage sharedUserPackage = packages.get(i);
                        requestedPermissions.addAll(sharedUserPackage.getRequestedPermissions());
                        targetSdkVersion = Math.min(targetSdkVersion,
                                sharedUserPackage.getTargetSdkVersion());
                    }
                }

                for (String permissionName : requestedPermissions) {
                    BasePermission permission = mSettings.getPermission(permissionName);
                    if (permission == null) {
                        continue;
                    }
                    if (Objects.equals(permission.getSourcePackageName(), PLATFORM_PACKAGE_NAME)
                            && permission.isRuntime() && !permission.isRemoved()) {
                        if (permission.isHardOrSoftRestricted()
                                || permission.isImmutablyRestricted()) {
                            uidState.updatePermissionFlags(permission,
                                    FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT,
                                    FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT);
                        }
                        if (targetSdkVersion < Build.VERSION_CODES.M) {
                            uidState.updatePermissionFlags(permission,
                                    PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                                            | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT,
                                    PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                                            | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT);
                            uidState.grantPermission(permission);
                        }
                    }
                }

                uidState.setMissing(false);
                updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
            }

            UidPermissionState origState = uidState;

            boolean changedInstallPermission = false;

            if (replace) {
                userState.setInstallPermissionsFixed(ps.name, false);
                if (!ps.isSharedUser()) {
                    origState = new UidPermissionState(uidState);
                    uidState.reset();
                } else {
                    // We need to know only about runtime permission changes since the
                    // calling code always writes the install permissions state but
                    // the runtime ones are written only if changed. The only cases of
                    // changed runtime permissions here are promotion of an install to
                    // runtime and revocation of a runtime from a shared user.
                    synchronized (mLock) {
                        if (revokeUnusedSharedUserPermissionsLocked(
                                ps.getSharedUser().getPackages(), uidState)) {
                            updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                            runtimePermissionsRevoked = true;
                        }
                    }
                }
            }

            ArraySet<String> newImplicitPermissions = new ArraySet<>();
            final String friendlyName = pkg.getPackageName() + "(" + pkg.getUid() + ")";

            final int N = pkg.getRequestedPermissions().size();
            for (int i = 0; i < N; i++) {
                final String permName = pkg.getRequestedPermissions().get(i);
                final BasePermission bp = mSettings.getPermission(permName);
                final boolean appSupportsRuntimePermissions =
                        pkg.getTargetSdkVersion() >= Build.VERSION_CODES.M;
                String upgradedActivityRecognitionPermission = null;

                if (DEBUG_INSTALL && bp != null) {
                    Log.i(TAG, "Package " + friendlyName
                            + " checking " + permName + ": " + bp);
                }

                if (bp == null || getSourcePackageSetting(bp) == null) {
                    if (packageOfInterest == null || packageOfInterest.equals(
                            pkg.getPackageName())) {
                        if (DEBUG_PERMISSIONS) {
                            Slog.i(TAG, "Unknown permission " + permName
                                    + " in package " + friendlyName);
                        }
                    }
                    continue;
                }

                // Cache newImplicitPermissions before modifing permissionsState as for the shared
                // uids the original and new state are the same object
                if (!origState.hasPermissionState(permName)
                        && (pkg.getImplicitPermissions().contains(permName)
                                || (permName.equals(Manifest.permission.ACTIVITY_RECOGNITION)))) {
                    if (pkg.getImplicitPermissions().contains(permName)) {
                        // If permName is an implicit permission, try to auto-grant
                        newImplicitPermissions.add(permName);

                        if (DEBUG_PERMISSIONS) {
                            Slog.i(TAG, permName + " is newly added for " + friendlyName);
                        }
                    } else {
                        // Special case for Activity Recognition permission. Even if AR permission
                        // is not an implicit permission we want to add it to the list (try to
                        // auto-grant it) if the app was installed on a device before AR permission
                        // was split, regardless of if the app now requests the new AR permission
                        // or has updated its target SDK and AR is no longer implicit to it.
                        // This is a compatibility workaround for apps when AR permission was
                        // split in Q.
                        final List<SplitPermissionInfoParcelable> permissionList =
                                getSplitPermissions();
                        int numSplitPerms = permissionList.size();
                        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
                            SplitPermissionInfoParcelable sp = permissionList.get(splitPermNum);
                            String splitPermName = sp.getSplitPermission();
                            if (sp.getNewPermissions().contains(permName)
                                    && origState.isPermissionGranted(splitPermName)) {
                                upgradedActivityRecognitionPermission = splitPermName;
                                newImplicitPermissions.add(permName);

                                if (DEBUG_PERMISSIONS) {
                                    Slog.i(TAG, permName + " is newly added for "
                                            + friendlyName);
                                }
                                break;
                            }
                        }
                    }
                }

                // TODO(b/140256621): The package instant app method has been removed
                //  as part of work in b/135203078, so this has been commented out in the meantime
                // Limit ephemeral apps to ephemeral allowed permissions.
    //            if (/*pkg.isInstantApp()*/ false && !bp.isInstant()) {
    //                if (DEBUG_PERMISSIONS) {
    //                    Log.i(TAG, "Denying non-ephemeral permission " + bp.getName()
    //                            + " for package " + pkg.getPackageName());
    //                }
    //                continue;
    //            }

                if (bp.isRuntimeOnly() && !appSupportsRuntimePermissions) {
                    if (DEBUG_PERMISSIONS) {
                        Log.i(TAG, "Denying runtime-only permission " + bp.getName()
                                + " for package " + friendlyName);
                    }
                    continue;
                }

                final String perm = bp.getName();
                boolean allowedSig = false;
                int grant = GRANT_DENIED;

                // Keep track of app op permissions.
                if (bp.isAppOp()) {
                    mSettings.addAppOpPackage(perm, pkg.getPackageName());
                }

                if (bp.isNormal()) {
                    // For all apps normal permissions are install time ones.
                    grant = GRANT_INSTALL;
                } else if (bp.isRuntime()) {
                    // For modern apps keep runtime permissions unchanged.
                    grant = GRANT_RUNTIME;
                } else if (bp.isSignature()) {
                    // For all apps signature permissions are install time ones.
                    allowedSig = shouldGrantSignaturePermission(perm, pkg, ps, bp, origState);
                    if (allowedSig) {
                        grant = GRANT_INSTALL;
                    }
                }

                if (grant == GRANT_INSTALL && !allowedSig && !origState.isPermissionGranted(perm)) {
                    // If this is an existing, non-system package, then
                    // we can't add any new permissions to it. Runtime
                    // permissions can be added any time - they are dynamic.
                    if (!ps.isSystem() && userState.areInstallPermissionsFixed(ps.name)) {
                        // Except...  if this is a permission that was added
                        // to the platform (note: need to only do this when
                        // updating the platform).
                        if (!isNewPlatformPermissionForPackage(perm, pkg)) {
                            grant = GRANT_DENIED;
                        }
                    }
                }

                if (DEBUG_PERMISSIONS) {
                    Slog.i(TAG, "Considering granting permission " + perm + " to package "
                            + pkg.getPackageName());
                }

                synchronized (mLock) {
                    if (grant != GRANT_DENIED) {
                        switch (grant) {
                            case GRANT_INSTALL: {
                                // Grant an install permission.
                                if (uidState.grantPermission(bp)) {
                                    changedInstallPermission = true;
                                }
                            } break;

                            case GRANT_RUNTIME: {
                                boolean hardRestricted = bp.isHardRestricted();
                                boolean softRestricted = bp.isSoftRestricted();

                                // If permission policy is not ready we don't deal with restricted
                                // permissions as the policy may whitelist some permissions. Once
                                // the policy is initialized we would re-evaluate permissions.
                                final boolean permissionPolicyInitialized =
                                        mPermissionPolicyInternal != null
                                                && mPermissionPolicyInternal.isInitialized(userId);

                                PermissionState origPermState = origState.getPermissionState(perm);
                                int flags = origPermState != null ? origPermState.getFlags() : 0;

                                boolean wasChanged = false;

                                boolean restrictionExempt =
                                        (origState.getPermissionFlags(bp.name)
                                                & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                                boolean restrictionApplied = (origState.getPermissionFlags(
                                        bp.name) & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;

                                if (appSupportsRuntimePermissions) {
                                    // If hard restricted we don't allow holding it
                                    if (permissionPolicyInitialized && hardRestricted) {
                                        if (!restrictionExempt) {
                                            if (origPermState != null && origPermState.isGranted()
                                                    && uidState.revokePermission(bp)) {
                                                wasChanged = true;
                                            }
                                            if (!restrictionApplied) {
                                                flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                                wasChanged = true;
                                            }
                                        }
                                    // If soft restricted we allow holding in a restricted form
                                    } else if (permissionPolicyInitialized && softRestricted) {
                                        // Regardless if granted set the restriction flag as it
                                        // may affect app treatment based on this permission.
                                        if (!restrictionExempt && !restrictionApplied) {
                                            flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                            wasChanged = true;
                                        }
                                    }

                                    // Remove review flag as it is not necessary anymore
                                    if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVIEW_REQUIRED;
                                        wasChanged = true;
                                    }

                                    if ((flags & FLAG_PERMISSION_REVOKED_COMPAT) != 0) {
                                        flags &= ~FLAG_PERMISSION_REVOKED_COMPAT;
                                        wasChanged = true;
                                    // Hard restricted permissions cannot be held.
                                    } else if (!permissionPolicyInitialized
                                            || (!hardRestricted || restrictionExempt)) {
                                        if ((origPermState != null && origPermState.isGranted())
                                                || upgradedActivityRecognitionPermission != null) {
                                            if (!uidState.grantPermission(bp)) {
                                                wasChanged = true;
                                            }
                                        }
                                    }
                                } else {
                                    if (origPermState == null) {
                                        // New permission
                                        if (PLATFORM_PACKAGE_NAME.equals(
                                                bp.getSourcePackageName())) {
                                            if (!bp.isRemoved()) {
                                                flags |= FLAG_PERMISSION_REVIEW_REQUIRED
                                                        | FLAG_PERMISSION_REVOKED_COMPAT;
                                                wasChanged = true;
                                            }
                                        }
                                    }

                                    if (!uidState.isPermissionGranted(bp.name)
                                            && uidState.grantPermission(bp)) {
                                        wasChanged = true;
                                    }

                                    // If legacy app always grant the permission but if restricted
                                    // and not exempt take a note a restriction should be applied.
                                    if (permissionPolicyInitialized
                                            && (hardRestricted || softRestricted)
                                                    && !restrictionExempt && !restrictionApplied) {
                                        flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                        wasChanged = true;
                                    }
                                }

                                // If unrestricted or restriction exempt, don't apply restriction.
                                if (permissionPolicyInitialized) {
                                    if (!(hardRestricted || softRestricted) || restrictionExempt) {
                                        if (restrictionApplied) {
                                            flags &= ~FLAG_PERMISSION_APPLY_RESTRICTION;
                                            // Dropping restriction on a legacy app implies a review
                                            if (!appSupportsRuntimePermissions) {
                                                flags |= FLAG_PERMISSION_REVIEW_REQUIRED;
                                            }
                                            wasChanged = true;
                                        }
                                    }
                                }

                                if (wasChanged) {
                                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                                }

                                uidState.updatePermissionFlags(bp, MASK_PERMISSION_FLAGS_ALL,
                                        flags);
                            } break;

                            default: {
                                if (packageOfInterest == null
                                        || packageOfInterest.equals(pkg.getPackageName())) {
                                    if (DEBUG_PERMISSIONS) {
                                        Slog.i(TAG, "Not granting permission " + perm
                                                + " to package " + friendlyName
                                                + " because it was previously installed without");
                                    }
                                }
                            } break;
                        }
                    } else {
                        if (DEBUG_PERMISSIONS) {
                            boolean wasGranted = uidState.isPermissionGranted(bp.name);
                            if (wasGranted || bp.isAppOp()) {
                                Slog.i(TAG, (wasGranted ? "Un-granting" : "Not granting")
                                        + " permission " + perm
                                        + " from package " + friendlyName
                                        + " (protectionLevel=" + bp.getProtectionLevel()
                                        + " flags=0x"
                                        + Integer.toHexString(PackageInfoUtils.appInfoFlags(pkg,
                                                ps))
                                        + ")");
                            }
                        }
                        if (uidState.removePermissionState(bp.name)) {
                            changedInstallPermission = true;
                        }
                    }
                }
            }

            if ((changedInstallPermission || replace)
                    && !userState.areInstallPermissionsFixed(ps.name)
                    && !ps.isSystem() || ps.getPkgState().isUpdatedSystemApp()) {
                // This is the first that we have heard about this package, so the
                // permissions we have now selected are fixed until explicitly
                // changed.
                userState.setInstallPermissionsFixed(ps.name, true);
            }

            synchronized (mLock) {
                updatedUserIds = revokePermissionsNoLongerImplicitLocked(uidState, pkg,
                        userId, updatedUserIds);
                updatedUserIds = setInitialGrantForNewImplicitPermissionsLocked(origState,
                        uidState, pkg, newImplicitPermissions, userId, updatedUserIds);
            }
        }

        updatedUserIds = checkIfLegacyStorageOpsNeedToBeUpdated(pkg, replace, userIds,
                updatedUserIds);

        // TODO: Kill UIDs whose GIDs or runtime permissions changed. This might be more important
        //  for shared users.
        // Persist the runtime permissions state for users with changes. If permissions
        // were revoked because no app in the shared user declares them we have to
        // write synchronously to avoid losing runtime permissions state.
        if (callback != null) {
            callback.onPermissionUpdated(updatedUserIds, runtimePermissionsRevoked);
        }

        for (int userId : updatedUserIds) {
            notifyRuntimePermissionStateChanged(pkg.getPackageName(), userId);
        }
    }

    /**
     * Returns all relevant user ids.  This list include the current set of created user ids as well
     * as pre-created user ids.
     * @return user ids for created users and pre-created users
     */
    private int[] getAllUserIds() {
        return UserManagerService.getInstance().getUserIdsIncludingPreCreated();
    }

    /**
     * Revoke permissions that are not implicit anymore and that have
     * {@link PackageManager#FLAG_PERMISSION_REVOKE_WHEN_REQUESTED} set.
     *
     * @param ps The state of the permissions of the package
     * @param pkg The package that is currently looked at
     * @param userIds All user IDs in the system, must be passed in because this method is locked
     * @param updatedUserIds a list of user ids that needs to be amended if the permission state
     *                       for a user is changed.
     *
     * @return The updated value of the {@code updatedUserIds} parameter
     */
    private @NonNull int[] revokePermissionsNoLongerImplicitLocked(@NonNull UidPermissionState ps,
            @NonNull AndroidPackage pkg, int userId, @NonNull int[] updatedUserIds) {
        String pkgName = pkg.getPackageName();
        boolean supportsRuntimePermissions = pkg.getTargetSdkVersion()
                >= Build.VERSION_CODES.M;

        for (String permission : ps.getGrantedPermissions()) {
            if (!pkg.getImplicitPermissions().contains(permission)) {
                BasePermission bp = mSettings.getPermissionLocked(permission);
                if (bp.isRuntime()) {
                    int flags = ps.getPermissionFlags(permission);

                    if ((flags & FLAG_PERMISSION_REVOKE_WHEN_REQUESTED) != 0) {

                        int flagsToRemove = FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;

                        if ((flags & BLOCKING_PERMISSION_FLAGS) == 0
                                && supportsRuntimePermissions) {
                            if (ps.revokePermission(bp)) {
                                if (DEBUG_PERMISSIONS) {
                                    Slog.i(TAG, "Revoking runtime permission "
                                            + permission + " for " + pkgName
                                            + " as it is now requested");
                                }
                            }

                            flagsToRemove |= USER_PERMISSION_FLAGS;
                        }

                        ps.updatePermissionFlags(bp, flagsToRemove, 0);
                        updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                    }
                }
            }
        }

        return updatedUserIds;
    }

    /**
     * {@code newPerm} is newly added; Inherit the state from {@code sourcePerms}.
     *
     * <p>A single new permission can be split off from several source permissions. In this case
     * the most leniant state is inherited.
     *
     * <p>Warning: This does not handle foreground / background permissions
     *
     * @param sourcePerms The permissions to inherit from
     * @param newPerm The permission to inherit to
     * @param ps The permission state of the package
     * @param pkg The package requesting the permissions
     */
    private void inheritPermissionStateToNewImplicitPermissionLocked(
            @NonNull ArraySet<String> sourcePerms, @NonNull String newPerm,
            @NonNull UidPermissionState ps, @NonNull AndroidPackage pkg) {
        String pkgName = pkg.getPackageName();
        boolean isGranted = false;
        int flags = 0;

        int numSourcePerm = sourcePerms.size();
        for (int i = 0; i < numSourcePerm; i++) {
            String sourcePerm = sourcePerms.valueAt(i);
            if (ps.isPermissionGranted(sourcePerm)) {
                if (!isGranted) {
                    flags = 0;
                }

                isGranted = true;
                flags |= ps.getPermissionFlags(sourcePerm);
            } else {
                if (!isGranted) {
                    flags |= ps.getPermissionFlags(sourcePerm);
                }
            }
        }

        if (isGranted) {
            if (DEBUG_PERMISSIONS) {
                Slog.i(TAG, newPerm + " inherits runtime perm grant from " + sourcePerms
                        + " for " + pkgName);
            }

            ps.grantPermission(mSettings.getPermissionLocked(newPerm));
        }

        // Add permission flags
        ps.updatePermissionFlags(mSettings.getPermission(newPerm), flags, flags);
    }

    /**
     * When the app has requested legacy storage we might need to update
     * {@link android.app.AppOpsManager#OP_LEGACY_STORAGE}. Hence force an update in
     * {@link com.android.server.policy.PermissionPolicyService#synchronizePackagePermissionsAndAppOpsForUser(Context, String, int)}
     *
     * @param pkg The package for which the permissions are updated
     * @param replace If the app is being replaced
     * @param userIds All user IDs in the system, must be passed in because this method is locked
     * @param updatedUserIds The ids of the users that already changed.
     *
     * @return The ids of the users that are changed
     */
    private @NonNull int[] checkIfLegacyStorageOpsNeedToBeUpdated(@NonNull AndroidPackage pkg,
            boolean replace, @NonNull int[] userIds, @NonNull int[] updatedUserIds) {
        if (replace && pkg.isRequestLegacyExternalStorage() && (
                pkg.getRequestedPermissions().contains(READ_EXTERNAL_STORAGE)
                        || pkg.getRequestedPermissions().contains(WRITE_EXTERNAL_STORAGE))) {
            return userIds.clone();
        }

        return updatedUserIds;
    }

    /**
     * Set the state of a implicit permission that is seen for the first time.
     *
     * @param origPs The permission state of the package before the split
     * @param ps The new permission state
     * @param pkg The package the permission belongs to
     * @param userId The user ID
     * @param updatedUserIds List of users for which the permission state has already been changed
     *
     * @return  List of users for which the permission state has been changed
     */
    private @NonNull int[] setInitialGrantForNewImplicitPermissionsLocked(
            @NonNull UidPermissionState origPs, @NonNull UidPermissionState ps,
            @NonNull AndroidPackage pkg, @NonNull ArraySet<String> newImplicitPermissions,
            @UserIdInt int userId, @NonNull int[] updatedUserIds) {
        String pkgName = pkg.getPackageName();
        ArrayMap<String, ArraySet<String>> newToSplitPerms = new ArrayMap<>();

        final List<SplitPermissionInfoParcelable> permissionList = getSplitPermissions();
        int numSplitPerms = permissionList.size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            SplitPermissionInfoParcelable spi = permissionList.get(splitPermNum);

            List<String> newPerms = spi.getNewPermissions();
            int numNewPerms = newPerms.size();
            for (int newPermNum = 0; newPermNum < numNewPerms; newPermNum++) {
                String newPerm = newPerms.get(newPermNum);

                ArraySet<String> splitPerms = newToSplitPerms.get(newPerm);
                if (splitPerms == null) {
                    splitPerms = new ArraySet<>();
                    newToSplitPerms.put(newPerm, splitPerms);
                }

                splitPerms.add(spi.getSplitPermission());
            }
        }

        int numNewImplicitPerms = newImplicitPermissions.size();
        for (int newImplicitPermNum = 0; newImplicitPermNum < numNewImplicitPerms;
                newImplicitPermNum++) {
            String newPerm = newImplicitPermissions.valueAt(newImplicitPermNum);
            ArraySet<String> sourcePerms = newToSplitPerms.get(newPerm);

            if (sourcePerms != null) {
                BasePermission bp = mSettings.getPermissionLocked(newPerm);
                if (bp.isRuntime()) {

                    if (!newPerm.equals(Manifest.permission.ACTIVITY_RECOGNITION)) {
                        ps.updatePermissionFlags(bp,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED);
                    }
                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);

                    if (!origPs.hasPermissionState(sourcePerms)) {
                        boolean inheritsFromInstallPerm = false;
                        for (int sourcePermNum = 0; sourcePermNum < sourcePerms.size();
                                sourcePermNum++) {
                            final String sourcePerm = sourcePerms.valueAt(sourcePermNum);
                            BasePermission sourceBp = mSettings.getPermissionLocked(sourcePerm);
                            if (!sourceBp.isRuntime()) {
                                inheritsFromInstallPerm = true;
                                break;
                            }
                        }

                        if (!inheritsFromInstallPerm) {
                            // Both permissions are new so nothing to inherit.
                            if (DEBUG_PERMISSIONS) {
                                Slog.i(TAG, newPerm + " does not inherit from " + sourcePerms
                                        + " for " + pkgName + " as split permission is also new");
                            }
                            continue;
                        }
                    }

                    // Inherit from new install or existing runtime permissions
                    inheritPermissionStateToNewImplicitPermissionLocked(sourcePerms, newPerm, ps,
                            pkg);
                }
            }
        }

        return updatedUserIds;
    }

    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        return PermissionManager.splitPermissionInfoListToParcelableList(
                SystemConfig.getInstance().getSplitPermissions());
    }

    private OneTimePermissionUserManager getOneTimePermissionUserManager(@UserIdInt int userId) {
        OneTimePermissionUserManager oneTimePermissionUserManager;
        synchronized (mLock) {
            oneTimePermissionUserManager =
                    mOneTimePermissionUserManagers.get(userId);
            if (oneTimePermissionUserManager != null) {
                return oneTimePermissionUserManager;
            }
            oneTimePermissionUserManager = new OneTimePermissionUserManager(
                    mContext.createContextAsUser(UserHandle.of(userId), /*flags*/ 0));
            mOneTimePermissionUserManagers.put(userId, oneTimePermissionUserManager);
        }
        oneTimePermissionUserManager.registerUninstallListener();
        return oneTimePermissionUserManager;
    }

    @Override
    public void startOneTimePermissionSession(String packageName, @UserIdInt int userId,
            long timeoutMillis, int importanceToResetTimer, int importanceToKeepSessionAlive) {
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS,
                "Must hold " + Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS
                        + " to register permissions as one time.");
        Objects.requireNonNull(packageName);

        long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).startPackageOneTimeSession(packageName,
                    timeoutMillis, importanceToResetTimer, importanceToKeepSessionAlive);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void stopOneTimePermissionSession(String packageName, @UserIdInt int userId) {
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS,
                "Must hold " + Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS
                        + " to remove permissions as one time.");
        Objects.requireNonNull(packageName);

        long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).stopPackageOneTimeSession(packageName);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getAutoRevokeExemptionRequestedPackages(int userId) {
        return getPackagesWithAutoRevokePolicy(AUTO_REVOKE_DISCOURAGED, userId);
    }

    @Override
    public List<String> getAutoRevokeExemptionGrantedPackages(int userId) {
        return getPackagesWithAutoRevokePolicy(AUTO_REVOKE_DISALLOWED, userId);
    }

    @NonNull
    private List<String> getPackagesWithAutoRevokePolicy(int autoRevokePolicy, int userId) {
        mContext.enforceCallingPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                "Must hold " + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);

        List<String> result = new ArrayList<>();
        mPackageManagerInt.forEachInstalledPackage(pkg -> {
            if (pkg.getAutoRevokePermissions() == autoRevokePolicy) {
                result.add(pkg.getPackageName());
            }
        }, userId);
        return result;
    }

    private boolean isNewPlatformPermissionForPackage(String perm, AndroidPackage pkg) {
        boolean allowed = false;
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (npi.name.equals(perm)
                    && pkg.getTargetSdkVersion() < npi.sdkVersion) {
                allowed = true;
                Log.i(TAG, "Auto-granting " + perm + " to old pkg "
                        + pkg.getPackageName());
                break;
            }
        }
        return allowed;
    }

    /**
     * Determines whether a package is whitelisted for a particular privapp permission.
     *
     * <p>Does NOT check whether the package is a privapp, just whether it's whitelisted.
     *
     * <p>This handles parent/child apps.
     */
    private boolean hasPrivappWhitelistEntry(String perm, AndroidPackage pkg) {
        ArraySet<String> wlPermissions;
        if (pkg.isVendor()) {
            wlPermissions =
                    SystemConfig.getInstance().getVendorPrivAppPermissions(pkg.getPackageName());
        } else if (pkg.isProduct()) {
            wlPermissions =
                    SystemConfig.getInstance().getProductPrivAppPermissions(pkg.getPackageName());
        } else if (pkg.isSystemExt()) {
            wlPermissions =
                    SystemConfig.getInstance().getSystemExtPrivAppPermissions(
                            pkg.getPackageName());
        } else {
            wlPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg.getPackageName());
        }

        return wlPermissions != null && wlPermissions.contains(perm);
    }

    private boolean shouldGrantSignaturePermission(String perm, AndroidPackage pkg,
            PackageSetting pkgSetting, BasePermission bp, UidPermissionState origPermissions) {
        boolean oemPermission = bp.isOEM();
        boolean vendorPrivilegedPermission = bp.isVendorPrivileged();
        boolean privilegedPermission = bp.isPrivileged() || bp.isVendorPrivileged();
        boolean privappPermissionsDisable =
                RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE;
        boolean platformPermission = PLATFORM_PACKAGE_NAME.equals(bp.getSourcePackageName());
        boolean platformPackage = PLATFORM_PACKAGE_NAME.equals(pkg.getPackageName());
        if (!privappPermissionsDisable && privilegedPermission && pkg.isPrivileged()
                && !platformPackage && platformPermission) {
            if (!hasPrivappWhitelistEntry(perm, pkg)) {
                // Only enforce whitelist this on boot
                if (!mSystemReady
                        // Updated system apps do not need to be whitelisted
                        && !pkgSetting.getPkgState().isUpdatedSystemApp()) {
                    ApexManager apexMgr = ApexManager.getInstance();
                    String apexContainingPkg = apexMgr.getActiveApexPackageNameContainingPackage(
                            pkg);

                    // Apps that are in updated apexs' do not need to be whitelisted
                    if (apexContainingPkg == null || apexMgr.isFactory(
                            apexMgr.getPackageInfo(apexContainingPkg, MATCH_ACTIVE_PACKAGE))) {
                        // it's only a reportable violation if the permission isn't explicitly
                        // denied
                        ArraySet<String> deniedPermissions = null;
                        if (pkg.isVendor()) {
                            deniedPermissions = SystemConfig.getInstance()
                                    .getVendorPrivAppDenyPermissions(pkg.getPackageName());
                        } else if (pkg.isProduct()) {
                            deniedPermissions = SystemConfig.getInstance()
                                    .getProductPrivAppDenyPermissions(pkg.getPackageName());
                        } else if (pkg.isSystemExt()) {
                            deniedPermissions = SystemConfig.getInstance()
                                    .getSystemExtPrivAppDenyPermissions(pkg.getPackageName());
                        } else {
                            deniedPermissions = SystemConfig.getInstance()
                                    .getPrivAppDenyPermissions(pkg.getPackageName());
                        }
                        final boolean permissionViolation =
                                deniedPermissions == null || !deniedPermissions.contains(perm);
                        if (permissionViolation) {
                            Slog.w(TAG, "Privileged permission " + perm + " for package "
                                    + pkg.getPackageName() + " (" + pkg.getPath()
                                    + ") not in privapp-permissions whitelist");

                            if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                                if (mPrivappPermissionsViolations == null) {
                                    mPrivappPermissionsViolations = new ArraySet<>();
                                }
                                mPrivappPermissionsViolations.add(
                                        pkg.getPackageName() + " (" + pkg.getPath() + "): "
                                                + perm);
                            }
                        } else {
                            return false;
                        }
                    }
                }
                if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    return false;
                }
            }
        }
        // expect single system package
        String systemPackageName = ArrayUtils.firstOrNull(mPackageManagerInt.getKnownPackageNames(
                PackageManagerInternal.PACKAGE_SYSTEM, UserHandle.USER_SYSTEM));
        final AndroidPackage systemPackage =
                mPackageManagerInt.getPackage(systemPackageName);

        // check if the package is allow to use this signature permission.  A package is allowed to
        // use a signature permission if:
        //     - it has the same set of signing certificates as the source package
        //     - or its signing certificate was rotated from the source package's certificate
        //     - or its signing certificate is a previous signing certificate of the defining
        //       package, and the defining package still trusts the old certificate for permissions
        //     - or it shares the above relationships with the system package
        final PackageParser.SigningDetails sourceSigningDetails =
                getSourcePackageSigningDetails(bp);
        boolean allowed =
                pkg.getSigningDetails().hasAncestorOrSelf(sourceSigningDetails)
                || sourceSigningDetails.checkCapability(
                        pkg.getSigningDetails(),
                        PackageParser.SigningDetails.CertCapabilities.PERMISSION)
                || pkg.getSigningDetails().hasAncestorOrSelf(systemPackage.getSigningDetails())
                || systemPackage.getSigningDetails().checkCapability(
                        pkg.getSigningDetails(),
                        PackageParser.SigningDetails.CertCapabilities.PERMISSION);
        if (!allowed && (privilegedPermission || oemPermission)) {
            if (pkg.isSystem()) {
                // For updated system applications, a privileged/oem permission
                // is granted only if it had been defined by the original application.
                if (pkgSetting.getPkgState().isUpdatedSystemApp()) {
                    final PackageSetting disabledPs = mPackageManagerInt
                            .getDisabledSystemPackage(pkg.getPackageName());
                    final AndroidPackage disabledPkg = disabledPs == null ? null : disabledPs.pkg;
                    if (disabledPkg != null && isPackageRequestingPermission(disabledPkg, perm)
                            && ((privilegedPermission && disabledPkg.isPrivileged())
                                    || (oemPermission && canGrantOemPermission(disabledPkg,
                                            perm)))) {
                        allowed = true;
                    }
                } else {
                    allowed = (privilegedPermission && pkg.isPrivileged())
                            || (oemPermission && canGrantOemPermission(pkg, perm));
                }
                // In any case, don't grant a privileged permission to privileged vendor apps, if
                // the permission's protectionLevel does not have the extra 'vendorPrivileged'
                // flag.
                if (allowed && privilegedPermission &&
                        !vendorPrivilegedPermission && pkg.isVendor()) {
                   Slog.w(TAG, "Permission " + perm + " cannot be granted to privileged vendor apk "
                           + pkg.getPackageName()
                           + " because it isn't a 'vendorPrivileged' permission.");
                   allowed = false;
                }
            }
        }
        if (!allowed) {
            if (!allowed
                    && bp.isPre23()
                    && pkg.getTargetSdkVersion() < Build.VERSION_CODES.M) {
                // If this was a previously normal/dangerous permission that got moved
                // to a system permission as part of the runtime permission redesign, then
                // we still want to blindly grant it to old apps.
                allowed = true;
            }
            // TODO (moltmann): The installer now shares the platforms signature. Hence it does not
            //                  need a separate flag anymore. Hence we need to check which
            //                  permissions are needed by the permission controller
            if (!allowed && bp.isInstaller()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM),
                    pkg.getPackageName()) || ArrayUtils.contains(
                            mPackageManagerInt.getKnownPackageNames(
                                    PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER,
                    UserHandle.USER_SYSTEM), pkg.getPackageName())) {
                // If this permission is to be granted to the system installer and
                // this app is an installer, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isVerifier()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_VERIFIER, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // If this permission is to be granted to the system verifier and
                // this app is a verifier, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isPreInstalled()
                    && pkg.isSystem()) {
                // Any pre-installed system app is allowed to get this permission.
                allowed = true;
            }
            if (!allowed && bp.isDevelopment()) {
                // For development permissions, a development permission
                // is granted only if it was already granted.
                allowed = origPermissions.isPermissionGranted(perm);
            }
            if (!allowed && bp.isSetup()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // If this permission is to be granted to the system setup wizard and
                // this app is a setup wizard, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isSystemTextClassifier()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_SYSTEM_TEXT_CLASSIFIER,
                    UserHandle.USER_SYSTEM), pkg.getPackageName())) {
                // Special permissions for the system default text classifier.
                allowed = true;
            }
            if (!allowed && bp.isConfigurator()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_CONFIGURATOR,
                    UserHandle.USER_SYSTEM), pkg.getPackageName())) {
                // Special permissions for the device configurator.
                allowed = true;
            }
            if (!allowed && bp.isWellbeing()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_WELLBEING, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // Special permission granted only to the OEM specified wellbeing app
                allowed = true;
            }
            if (!allowed && bp.isDocumenter()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_DOCUMENTER, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // If this permission is to be granted to the documenter and
                // this app is the documenter, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isIncidentReportApprover()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_INCIDENT_REPORT_APPROVER,
                    UserHandle.USER_SYSTEM), pkg.getPackageName())) {
                // If this permission is to be granted to the incident report approver and
                // this app is the incident report approver, then it gets the permission.
                allowed = true;
            }
            if (!allowed && bp.isAppPredictor()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_APP_PREDICTOR, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // Special permissions for the system app predictor.
                allowed = true;
            }
            if (!allowed && bp.isCompanion()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_COMPANION, UserHandle.USER_SYSTEM),
                    pkg.getPackageName())) {
                // Special permissions for the system companion device manager.
                allowed = true;
            }
            if (!allowed && bp.isRetailDemo()
                    && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                            PackageManagerInternal.PACKAGE_RETAIL_DEMO, UserHandle.USER_SYSTEM),
                    pkg.getPackageName()) && isProfileOwner(pkg.getUid())) {
                // Special permission granted only to the OEM specified retail demo app
                allowed = true;
            }
        }
        return allowed;
    }

    @NonNull
    private PackageParser.SigningDetails getSourcePackageSigningDetails(
            @NonNull BasePermission bp) {
        final PackageSetting ps = getSourcePackageSetting(bp);
        if (ps == null) {
            return PackageParser.SigningDetails.UNKNOWN;
        }
        return ps.getSigningDetails();
    }

    @Nullable
    private PackageSetting getSourcePackageSetting(@NonNull BasePermission bp) {
        final String sourcePackageName = bp.getSourcePackageName();
        return mPackageManagerInt.getPackageSetting(sourcePackageName);
    }

    private static boolean isProfileOwner(int uid) {
        DevicePolicyManagerInternal dpmInternal =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        if (dpmInternal != null) {
            return dpmInternal
                    .isActiveAdminWithPolicy(uid, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }
        return false;
    }

    private static boolean canGrantOemPermission(AndroidPackage pkg, String permission) {
        if (!pkg.isOem()) {
            return false;
        }
        // all oem permissions must explicitly be granted or denied
        final Boolean granted =
                SystemConfig.getInstance().getOemPermissions(pkg.getPackageName()).get(permission);
        if (granted == null) {
            throw new IllegalStateException("OEM permission" + permission + " requested by package "
                    + pkg.getPackageName() + " must be explicitly declared granted or not");
        }
        return Boolean.TRUE == granted;
    }

    private boolean isPermissionsReviewRequired(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        // Permission review applies only to apps not supporting the new permission model.
        if (pkg.getTargetSdkVersion() >= Build.VERSION_CODES.M) {
            return false;
        }

        // Legacy apps have the permission and get user consent on launch.
        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                    + userId);
            return false;
        }
        return uidState.isPermissionReviewRequired();
    }

    private boolean isPackageRequestingPermission(AndroidPackage pkg, String permission) {
        final int permCount = pkg.getRequestedPermissions().size();
        for (int j = 0; j < permCount; j++) {
            String requestedPermission = pkg.getRequestedPermissions().get(j);
            if (permission.equals(requestedPermission)) {
                return true;
            }
        }
        return false;
    }

    private void grantRequestedRuntimePermissions(AndroidPackage pkg, int[] userIds,
            String[] grantedPermissions, int callingUid, PermissionCallback callback) {
        for (int userId : userIds) {
            grantRequestedRuntimePermissionsForUser(pkg, userId, grantedPermissions, callingUid,
                    callback);
        }
    }

    private void grantRequestedRuntimePermissionsForUser(AndroidPackage pkg, int userId,
            String[] grantedPermissions, int callingUid, PermissionCallback callback) {
        final UidPermissionState uidState = getUidState(pkg, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                    + userId);
            return;
        }

        final int immutableFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED;

        final int compatFlags = PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;

        final boolean supportsRuntimePermissions = pkg.getTargetSdkVersion()
                >= Build.VERSION_CODES.M;

        final boolean instantApp = mPackageManagerInt.isInstantApp(pkg.getPackageName(), userId);

        for (String permission : pkg.getRequestedPermissions()) {
            final BasePermission bp;
            synchronized (mLock) {
                bp = mSettings.getPermissionLocked(permission);
            }
            if (bp != null && (bp.isRuntime() || bp.isDevelopment())
                    && (!instantApp || bp.isInstant())
                    && (supportsRuntimePermissions || !bp.isRuntimeOnly())
                    && (grantedPermissions == null
                           || ArrayUtils.contains(grantedPermissions, permission))) {
                final int flags = uidState.getPermissionFlags(permission);
                if (supportsRuntimePermissions) {
                    // Installer cannot change immutable permissions.
                    if ((flags & immutableFlags) == 0) {
                        grantRuntimePermissionInternal(permission, pkg.getPackageName(), false,
                                callingUid, userId, callback);
                    }
                } else {
                    // In permission review mode we clear the review flag and the revoked compat
                    // flag when we are asked to install the app with all permissions granted.
                    if ((flags & compatFlags) != 0) {
                        updatePermissionFlagsInternal(permission, pkg.getPackageName(), compatFlags,
                                0, callingUid, userId, false, callback);
                    }
                }
            }
        }
    }

    private void setWhitelistedRestrictedPermissionsForUsers(@NonNull AndroidPackage pkg,
            @UserIdInt int[] userIds, @Nullable List<String> permissions, int callingUid,
            @PermissionWhitelistFlags int whitelistFlags, PermissionCallback callback) {
        SparseArray<ArraySet<String>> oldGrantedRestrictedPermissions = new SparseArray<>();
        boolean updatePermissions = false;
        final int permissionCount = pkg.getRequestedPermissions().size();

        for (int i = 0; i < userIds.length; i++) {
            int userId = userIds[i];
            final UidPermissionState uidState = getUidState(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                continue;
            }

            for (int j = 0; j < permissionCount; j++) {
                final String permissionName = pkg.getRequestedPermissions().get(j);

                final BasePermission bp = mSettings.getPermissionLocked(permissionName);

                if (bp == null || !bp.isHardOrSoftRestricted()) {
                    continue;
                }

                if (uidState.isPermissionGranted(permissionName)) {
                    if (oldGrantedRestrictedPermissions.get(userId) == null) {
                        oldGrantedRestrictedPermissions.put(userId, new ArraySet<>());
                    }
                    oldGrantedRestrictedPermissions.get(userId).add(permissionName);
                }

                final int oldFlags = uidState.getPermissionFlags(permissionName);

                int newFlags = oldFlags;
                int mask = 0;
                int whitelistFlagsCopy = whitelistFlags;
                while (whitelistFlagsCopy != 0) {
                    final int flag = 1 << Integer.numberOfTrailingZeros(whitelistFlagsCopy);
                    whitelistFlagsCopy &= ~flag;
                    switch (flag) {
                        case FLAG_PERMISSION_WHITELIST_SYSTEM: {
                            mask |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                            if (permissions != null && permissions.contains(permissionName)) {
                                newFlags |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                            } else {
                                newFlags &= ~FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                            }
                        }
                        break;
                        case FLAG_PERMISSION_WHITELIST_UPGRADE: {
                            mask |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                            if (permissions != null && permissions.contains(permissionName)) {
                                newFlags |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                            } else {
                                newFlags &= ~FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                            }
                        }
                        break;
                        case FLAG_PERMISSION_WHITELIST_INSTALLER: {
                            mask |= FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                            if (permissions != null && permissions.contains(permissionName)) {
                                newFlags |= FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                            } else {
                                newFlags &= ~FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                            }
                        }
                        break;
                        case FLAG_PERMISSION_ALLOWLIST_ROLE: {
                            mask |= FLAG_PERMISSION_RESTRICTION_ROLE_EXEMPT;
                            if (permissions != null && permissions.contains(permissionName)) {
                                newFlags |= FLAG_PERMISSION_RESTRICTION_ROLE_EXEMPT;
                            } else {
                                newFlags &= ~FLAG_PERMISSION_RESTRICTION_ROLE_EXEMPT;
                            }
                        }
                        break;
                    }
                }

                if (oldFlags == newFlags) {
                    continue;
                }

                updatePermissions = true;

                final boolean wasWhitelisted = (oldFlags
                        & (PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT)) != 0;
                final boolean isWhitelisted = (newFlags
                        & (PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT)) != 0;

                // If the permission is policy fixed as granted but it is no longer
                // on any of the whitelists we need to clear the policy fixed flag
                // as whitelisting trumps policy i.e. policy cannot grant a non
                // grantable permission.
                if ((oldFlags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                    final boolean isGranted = uidState.isPermissionGranted(permissionName);
                    if (!isWhitelisted && isGranted) {
                        mask |= PackageManager.FLAG_PERMISSION_POLICY_FIXED;
                        newFlags &= ~PackageManager.FLAG_PERMISSION_POLICY_FIXED;
                    }
                }

                // If we are whitelisting an app that does not support runtime permissions
                // we need to make sure it goes through the permission review UI at launch.
                if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M
                        && !wasWhitelisted && isWhitelisted) {
                    mask |= PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                    newFlags |= PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                }

                updatePermissionFlagsInternal(permissionName, pkg.getPackageName(), mask, newFlags,
                        callingUid, userId, false, null /*callback*/);
            }
        }

        if (updatePermissions) {
            // Update permission of this app to take into account the new whitelist state.
            restorePermissionState(pkg, false, pkg.getPackageName(), callback);

            // If this resulted in losing a permission we need to kill the app.
            for (int i = 0; i < userIds.length; i++) {
                int userId = userIds[i];
                ArraySet<String> oldPermsForUser = oldGrantedRestrictedPermissions.get(userId);
                if (oldPermsForUser == null) {
                    continue;
                }

                final int oldGrantedCount = oldPermsForUser.size();
                for (int j = 0; j < oldGrantedCount; j++) {
                    final String permission = oldPermsForUser.valueAt(j);
                    // Sometimes we create a new permission state instance during update.
                    final UidPermissionState newUidState = getUidState(pkg, userId);
                    if (newUidState == null) {
                        Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName()
                                + " and user " + userId);
                        continue;
                    }
                    if (!newUidState.isPermissionGranted(permission)) {
                        callback.onPermissionRevoked(pkg.getUid(), userId, null);
                        break;
                    }
                }
            }
        }
    }

    @UserIdInt
    private int revokeSharedUserPermissionsForDeletedPackage(@NonNull PackageSetting deletedPs,
            @UserIdInt int userId) {
        if ((deletedPs == null) || (deletedPs.pkg == null)) {
            Slog.i(TAG, "Trying to update info for null package. Just ignoring");
            return UserHandle.USER_NULL;
        }

        SharedUserSetting sus = deletedPs.getSharedUser();

        // No sharedUserId
        if (sus == null) {
            return UserHandle.USER_NULL;
        }

        int affectedUserId = UserHandle.USER_NULL;
        // Update permissions
        for (String eachPerm : deletedPs.pkg.getRequestedPermissions()) {
            BasePermission bp = mSettings.getPermission(eachPerm);
            if (bp == null) {
                continue;
            }

            // Check if another package in the shared user needs the permission.
            boolean used = false;
            final List<AndroidPackage> pkgs = sus.getPackages();
            if (pkgs != null) {
                for (AndroidPackage pkg : pkgs) {
                    if (pkg != null
                            && !pkg.getPackageName().equals(deletedPs.pkg.getPackageName())
                            && pkg.getRequestedPermissions().contains(eachPerm)) {
                        used = true;
                        break;
                    }
                }
            }
            if (used) {
                continue;
            }

            UidPermissionState uidState = getUidState(deletedPs.pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + deletedPs.pkg.getPackageName()
                        + " and user " + userId);
                continue;
            }

            PackageSetting disabledPs = mPackageManagerInt.getDisabledSystemPackage(
                    deletedPs.pkg.getPackageName());

            // If the package is shadowing is a disabled system package,
            // do not drop permissions that the shadowed package requests.
            if (disabledPs != null) {
                boolean reqByDisabledSysPkg = false;
                for (String permission : disabledPs.pkg.getRequestedPermissions()) {
                    if (permission.equals(eachPerm)) {
                        reqByDisabledSysPkg = true;
                        break;
                    }
                }
                if (reqByDisabledSysPkg) {
                    continue;
                }
            }

            // TODO(zhanghai): Why are we only killing the UID when GIDs changed, instead of any
            //  permission change?
            if (uidState.removePermissionState(bp.name) && bp.hasGids()) {
                affectedUserId = userId;
            }
        }

        return affectedUserId;
    }

    @GuardedBy("mLock")
    private boolean revokeUnusedSharedUserPermissionsLocked(
            List<AndroidPackage> pkgList, UidPermissionState uidState) {
        // Collect all used permissions in the UID
        final ArraySet<String> usedPermissions = new ArraySet<>();
        if (pkgList == null || pkgList.size() == 0) {
            return false;
        }
        for (AndroidPackage pkg : pkgList) {
            if (pkg.getRequestedPermissions().isEmpty()) {
                continue;
            }
            final int requestedPermCount = pkg.getRequestedPermissions().size();
            for (int j = 0; j < requestedPermCount; j++) {
                String permission = pkg.getRequestedPermissions().get(j);
                BasePermission bp = mSettings.getPermissionLocked(permission);
                if (bp != null) {
                    usedPermissions.add(permission);
                }
            }
        }

        boolean runtimePermissionChanged = false;

        // Prune permissions
        final List<PermissionState> permissionStates = uidState.getPermissionStates();
        final int permissionStatesSize = permissionStates.size();
        for (int i = permissionStatesSize - 1; i >= 0; i--) {
            PermissionState permissionState = permissionStates.get(i);
            if (!usedPermissions.contains(permissionState.getName())) {
                BasePermission bp = mSettings.getPermissionLocked(permissionState.getName());
                if (bp != null) {
                    if (uidState.removePermissionState(bp.name) && permissionState.isRuntime()) {
                        runtimePermissionChanged = true;
                    }
                }
            }
        }

        return runtimePermissionChanged;
    }

    /**
     * Update permissions when a package changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param packageName The package that is updated
     * @param pkg The package that is updated, or {@code null} if package is deleted
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    private void updatePermissions(@NonNull String packageName, @Nullable AndroidPackage pkg,
            @NonNull PermissionCallback callback) {
        // If the package is being deleted, update the permissions of all the apps
        final int flags =
                (pkg == null ? UPDATE_PERMISSIONS_ALL | UPDATE_PERMISSIONS_REPLACE_PKG
                        : UPDATE_PERMISSIONS_REPLACE_PKG);
        updatePermissions(
                packageName, pkg, getVolumeUuidForPackage(pkg), flags, callback);
    }

    /**
     * Update all permissions for all apps.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param volumeUuid The volume of the packages to be updated, {@code null} for all volumes
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    private void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdated,
            @NonNull PermissionCallback callback) {
        PackageManager.corkPackageInfoCache();  // Prevent invalidation storm
        try {
            final int flags = UPDATE_PERMISSIONS_ALL |
                    (sdkUpdated
                            ? UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL
                            : 0);
            updatePermissions(null, null, volumeUuid, flags, callback);
        } finally {
            PackageManager.uncorkPackageInfoCache();
        }
    }

    /**
     * Cache background->foreground permission mapping.
     *
     * <p>This is only run once.
     */
    private void cacheBackgroundToForegoundPermissionMapping() {
        synchronized (mLock) {
            if (mBackgroundPermissions == null) {
                // Cache background -> foreground permission mapping.
                // Only system declares background permissions, hence mapping does never change.
                mBackgroundPermissions = new ArrayMap<>();
                for (BasePermission bp : mSettings.getAllPermissionsLocked()) {
                    if (bp.perm != null && bp.perm.getBackgroundPermission() != null) {
                        String fgPerm = bp.name;
                        String bgPerm = bp.perm.getBackgroundPermission();

                        List<String> fgPerms = mBackgroundPermissions.get(bgPerm);
                        if (fgPerms == null) {
                            fgPerms = new ArrayList<>();
                            mBackgroundPermissions.put(bgPerm, fgPerms);
                        }

                        fgPerms.add(fgPerm);
                    }
                }
            }
        }
    }

    /**
     * Update all packages on the volume, <u>beside</u> the changing package. If the changing
     * package is set too, all packages are updated.
     */
    private static final int UPDATE_PERMISSIONS_ALL = 1 << 0;
    /** The changing package is replaced. Requires the changing package to be set */
    private static final int UPDATE_PERMISSIONS_REPLACE_PKG = 1 << 1;
    /**
     * Schedule all packages <u>beside</u> the changing package for replacement. Requires
     * UPDATE_PERMISSIONS_ALL to be set
     */
    private static final int UPDATE_PERMISSIONS_REPLACE_ALL = 1 << 2;

    @IntDef(flag = true, prefix = { "UPDATE_PERMISSIONS_" }, value = {
            UPDATE_PERMISSIONS_ALL, UPDATE_PERMISSIONS_REPLACE_PKG,
            UPDATE_PERMISSIONS_REPLACE_ALL })
    @Retention(RetentionPolicy.SOURCE)
    private @interface UpdatePermissionFlags {}

    /**
     * Update permissions when packages changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * <p>Meaning of combination of package parameters:
     * <table>
     *     <tr><th></th><th>changingPkgName != null</th><th>changingPkgName == null</th></tr>
     *     <tr><th>changingPkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>changingPkg == null</th><td>package is deleted</td><td>all packages are
     *                                                                    updated</td></tr>
     * </table>
     *
     * @param changingPkgName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param changingPkg The package that is updated, or {@code null} if all packages should be
     *                    updated or package is deleted
     * @param replaceVolumeUuid The volume of the packages to be updated are on, {@code null} for
     *                          all volumes
     * @param flags Control permission for which apps should be updated
     * @param callback Callback to call after permission changes
     */
    private void updatePermissions(final @Nullable String changingPkgName,
            final @Nullable AndroidPackage changingPkg,
            final @Nullable String replaceVolumeUuid,
            @UpdatePermissionFlags int flags,
            final @Nullable PermissionCallback callback) {
        // TODO: Most of the methods exposing BasePermission internals [source package name,
        // etc..] shouldn't be needed. Instead, when we've parsed a permission that doesn't
        // have package settings, we should make note of it elsewhere [map between
        // source package name and BasePermission] and cycle through that here. Then we
        // define a single method on BasePermission that takes a PackageSetting, changing
        // package name and a package.
        // NOTE: With this approach, we also don't need to tree trees differently than
        // normal permissions. Today, we need two separate loops because these BasePermission
        // objects are stored separately.
        // Make sure there are no dangling permission trees.
        boolean permissionTreesSourcePackageChanged = updatePermissionTreeSourcePackage(
                changingPkgName, changingPkg);
        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        boolean permissionSourcePackageChanged = updatePermissionSourcePackage(changingPkgName,
                changingPkg, callback);

        if (permissionTreesSourcePackageChanged | permissionSourcePackageChanged) {
            // Permission ownership has changed. This e.g. changes which packages can get signature
            // permissions
            Slog.i(TAG, "Permission ownership changed. Updating all permissions.");
            flags |= UPDATE_PERMISSIONS_ALL;
        }

        cacheBackgroundToForegoundPermissionMapping();

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "restorePermissionState");
        // Now update the permissions for all packages.
        if ((flags & UPDATE_PERMISSIONS_ALL) != 0) {
            final boolean replaceAll = ((flags & UPDATE_PERMISSIONS_REPLACE_ALL) != 0);
            mPackageManagerInt.forEachPackage((AndroidPackage pkg) -> {
                if (pkg == changingPkg) {
                    return;
                }
                // Only replace for packages on requested volume
                final String volumeUuid = getVolumeUuidForPackage(pkg);
                final boolean replace = replaceAll && Objects.equals(replaceVolumeUuid, volumeUuid);
                restorePermissionState(pkg, replace, changingPkgName, callback);
            });
        }

        if (changingPkg != null) {
            // Only replace for packages on requested volume
            final String volumeUuid = getVolumeUuidForPackage(changingPkg);
            final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_PKG) != 0)
                    && Objects.equals(replaceVolumeUuid, volumeUuid);
            restorePermissionState(changingPkg, replace, changingPkgName, callback);
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    /**
     * Update which app declares a permission.
     *
     * <p>Possible parameter combinations
     * <table>
     *     <tr><th></th><th>packageName != null</th><th>packageName == null</th></tr>
     *     <tr><th>pkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>pkg == null</th><td>package is deleted</td><td>all packages are updated</td></tr>
     * </table>
     *
     * @param packageName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param pkg The package that is updated, or {@code null} if all packages should be updated or
     *            package is deleted
     *
     * @return {@code true} if a permission source package might have changed
     */
    private boolean updatePermissionSourcePackage(@Nullable String packageName,
            @Nullable AndroidPackage pkg,
            final @Nullable PermissionCallback callback) {
        // Always need update if packageName is null
        if (packageName == null) {
            return true;
        }

        boolean changed = false;
        Set<BasePermission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<BasePermission> it = mSettings.mPermissions.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (bp.isDynamic()) {
                    bp.updateDynamicPermission(mSettings.mPermissionTrees.values());
                }
                if (!packageName.equals(bp.getSourcePackageName())) {
                    // Not checking sourcePackageSetting because it can be null when
                    // the permission source package is the target package and the target package is
                    // being uninstalled,
                    continue;
                }
                // The target package is the source of the current permission
                // Set to changed for either install or uninstall
                changed = true;
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>(mSettings.mPermissions.size());
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final BasePermission bp : needsUpdate) {
                // If the target package is being uninstalled, we need to revoke this permission
                // From all other packages
                if (pkg == null || !hasPermission(pkg, bp.getName())) {
                    Slog.i(TAG, "Removing permission " + bp.getName()
                            + " that used to be declared by " + bp.getSourcePackageName());
                    if (bp.isRuntime()) {
                        final int[] userIds = mUserManagerInt.getUserIds();
                        final int numUserIds = userIds.length;
                        for (int userIdNum = 0; userIdNum < numUserIds; userIdNum++) {
                            final int userId = userIds[userIdNum];
                            mPackageManagerInt.forEachPackage((AndroidPackage p) ->
                                    revokePermissionFromPackageForUser(p.getPackageName(),
                                            bp.getName(), true, userId, callback));
                        }
                    } else {
                        mPackageManagerInt.forEachPackage(p -> {
                            final int[] userIds = mUserManagerInt.getUserIds();
                            for (final int userId : userIds) {
                                final UidPermissionState uidState = getUidState(p, userId);
                                if (uidState == null) {
                                    Slog.e(TAG, "Missing permissions state for "
                                            + p.getPackageName() + " and user " + userId);
                                    return;
                                }
                                uidState.removePermissionState(bp.name);
                            }
                        });
                    }
                    mSettings.removePermissionLocked(bp.getName());
                    continue;
                }
                final AndroidPackage sourcePkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                final PackageSetting sourcePs =
                        (PackageSetting) mPackageManagerInt.getPackageSetting(
                                bp.getSourcePackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePs != null) {
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission: " + bp.getName()
                            + " from package " + bp.getSourcePackageName());
                    mSettings.removePermissionLocked(bp.getName());
                }
            }
        }
        return changed;
    }

    /**
     * Revoke a runtime permission from a package for a given user ID.
     */
    private void revokePermissionFromPackageForUser(@NonNull String pName,
            @NonNull String permissionName, boolean overridePolicy, int userId,
            @Nullable PermissionCallback callback) {
        final ApplicationInfo appInfo =
                mPackageManagerInt.getApplicationInfo(pName, 0,
                        Process.SYSTEM_UID, UserHandle.USER_SYSTEM);
        if (appInfo != null
                && appInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            return;
        }

        if (checkPermissionImpl(permissionName, pName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                revokeRuntimePermissionInternal(
                        permissionName,
                        pName,
                        overridePolicy,
                        Process.SYSTEM_UID,
                        userId,
                        null, callback);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG,
                        "Failed to revoke "
                                + permissionName
                                + " from "
                                + pName,
                        e);
            }
        }
    }
    /**
     * Update which app owns a permission trees.
     *
     * <p>Possible parameter combinations
     * <table>
     *     <tr><th></th><th>packageName != null</th><th>packageName == null</th></tr>
     *     <tr><th>pkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>pkg == null</th><td>package is deleted</td><td>all packages are updated</td></tr>
     * </table>
     *
     * @param packageName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param pkg The package that is updated, or {@code null} if all packages should be updated or
     *            package is deleted
     *
     * @return {@code true} if a permission tree ownership might have changed
     */
    private boolean updatePermissionTreeSourcePackage(@Nullable String packageName,
            @Nullable AndroidPackage pkg) {
        // Always need update if packageName is null
        if (packageName == null) {
            return true;
        }
        boolean changed = false;

        Set<BasePermission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<BasePermission> it = mSettings.mPermissionTrees.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (!packageName.equals(bp.getSourcePackageName())) {
                    // Not checking sourcePackageSetting because it can be null when
                    // the permission source package is the target package and the target package is
                    // being uninstalled,
                    continue;
                }
                // The target package is the source of the current permission tree
                // Set to changed for either install or uninstall
                changed = true;
                if (pkg == null || !hasPermission(pkg, bp.getName())) {
                    Slog.i(TAG, "Removing permission tree " + bp.getName()
                            + " that used to be declared by " + bp.getSourcePackageName());
                    it.remove();
                }
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>(mSettings.mPermissionTrees.size());
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final BasePermission bp : needsUpdate) {
                final AndroidPackage sourcePkg =
                        mPackageManagerInt.getPackage(bp.getSourcePackageName());
                final PackageSetting sourcePs =
                        (PackageSetting) mPackageManagerInt.getPackageSetting(
                                bp.getSourcePackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePs != null) {
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission tree: " + bp.getName()
                            + " from package " + bp.getSourcePackageName());
                    mSettings.removePermissionLocked(bp.getName());
                }
            }
        }
        return changed;
    }

    private void enforceGrantRevokeRuntimePermissionPermissions(String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
        }
    }

    private void enforceGrantRevokeGetRuntimePermissionPermissions(@NonNull String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.GET_RUNTIME_PERMISSIONS);
        }
    }

    /**
     * Checks if the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the userid is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(mUserManagerInt,
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (hasCrossUserPermission(
                callingUid, callingUserId, userId, requireFullPermission,
                requirePermissionWhenSameUser)) {
            return;
        }
        String errorMessage = buildInvalidCrossUserPermissionMessage(
                callingUid, userId, message, requireFullPermission);
        Slog.w(TAG, errorMessage);
        throw new SecurityException(errorMessage);
    }

    /**
     * Checks if the request is from the system or an app that has the appropriate cross-user
     * permissions defined as follows:
     * <ul>
     * <li>INTERACT_ACROSS_USERS_FULL if {@code requireFullPermission} is true.</li>
     * <li>INTERACT_ACROSS_USERS if the given {@userId} is in a different profile group
     * to the caller.</li>
     * <li>Otherwise, INTERACT_ACROSS_PROFILES if the given {@userId} is in the same profile group
     * as the caller.</li>
     * </ul>
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction(mUserManagerInt,
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (hasCrossUserPermission(callingUid, callingUserId, userId, requireFullPermission,
                /*requirePermissionWhenSameUser= */ false)) {
            return;
        }
        final boolean isSameProfileGroup = isSameProfileGroup(callingUserId, userId);
        if (isSameProfileGroup && PermissionChecker.checkPermissionForPreflight(
                mContext,
                android.Manifest.permission.INTERACT_ACROSS_PROFILES,
                PermissionChecker.PID_UNKNOWN,
                callingUid,
                mPackageManagerInt.getPackage(callingUid).getPackageName())
                == PermissionChecker.PERMISSION_GRANTED) {
            return;
        }
        String errorMessage = buildInvalidCrossUserOrProfilePermissionMessage(
                callingUid, userId, message, requireFullPermission, isSameProfileGroup);
        Slog.w(TAG, errorMessage);
        throw new SecurityException(errorMessage);
    }

    private boolean hasCrossUserPermission(
            int callingUid, int callingUserId, int userId, boolean requireFullPermission,
            boolean requirePermissionWhenSameUser) {
        if (!requirePermissionWhenSameUser && userId == callingUserId) {
            return true;
        }
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        if (requireFullPermission) {
            return hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        }
        return hasPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                || hasPermission(Manifest.permission.INTERACT_ACROSS_USERS);
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return UserManagerService.getInstance().isSameProfileGroup(callerUserId, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String buildInvalidCrossUserPermissionMessage(int callingUid,
            @UserIdInt int userId, String message, boolean requireFullPermission) {
        StringBuilder builder = new StringBuilder();
        if (message != null) {
            builder.append(message);
            builder.append(": ");
        }
        builder.append("UID ");
        builder.append(callingUid);
        builder.append(" requires ");
        builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        if (!requireFullPermission) {
            builder.append(" or ");
            builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS);
        }
        builder.append(" to access user ");
        builder.append(userId);
        builder.append(".");
        return builder.toString();
    }

    private static String buildInvalidCrossUserOrProfilePermissionMessage(int callingUid,
            @UserIdInt int userId, String message, boolean requireFullPermission,
            boolean isSameProfileGroup) {
        StringBuilder builder = new StringBuilder();
        if (message != null) {
            builder.append(message);
            builder.append(": ");
        }
        builder.append("UID ");
        builder.append(callingUid);
        builder.append(" requires ");
        builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        if (!requireFullPermission) {
            builder.append(" or ");
            builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS);
            if (isSameProfileGroup) {
                builder.append(" or ");
                builder.append(android.Manifest.permission.INTERACT_ACROSS_PROFILES);
            }
        }
        builder.append(" to access user ");
        builder.append(".");
        return builder.toString();
    }

    @GuardedBy({"mSettings.mLock", "mLock"})
    private int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : mSettings.mPermissions.values()) {
            size += tree.calculateFootprint(perm);
        }
        return size;
    }

    @GuardedBy({"mSettings.mLock", "mLock"})
    private void enforcePermissionCapLocked(PermissionInfo info, BasePermission tree) {
        // We calculate the max size of permissions defined by this uid and throw
        // if that plus the size of 'info' would exceed our stated maximum.
        if (tree.getUid() != Process.SYSTEM_UID) {
            final int curTreeSize = calculateCurrentPermissionFootprintLocked(tree);
            if (curTreeSize + info.calculateFootprint() > MAX_PERMISSION_TREE_FOOTPRINT) {
                throw new SecurityException("Permission tree size cap exceeded");
            }
        }
    }

    private void systemReady() {
        mSystemReady = true;
        if (mPrivappPermissionsViolations != null) {
            throw new IllegalStateException("Signature|privileged permissions not in "
                    + "privapp-permissions whitelist: " + mPrivappPermissionsViolations);
        }

        mPermissionControllerManager = mContext.getSystemService(PermissionControllerManager.class);
        mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);

        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            if (mPackageManagerInt.isPermissionUpgradeNeeded(userId)) {
                grantPermissionsUserIds = ArrayUtils.appendInt(
                        grantPermissionsUserIds, userId);
            }
        }
        // If we upgraded grant all default permissions before kicking off.
        for (int userId : grantPermissionsUserIds) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissions(userId);
        }
        if (grantPermissionsUserIds == EMPTY_INT_ARRAY) {
            // If we did not grant default permissions, we preload from this the
            // default permission exceptions lazily to ensure we don't hit the
            // disk on a new user creation.
            mDefaultPermissionGrantPolicy.scheduleReadDefaultPermissionExceptions();
        }
    }

    private static String getVolumeUuidForPackage(AndroidPackage pkg) {
        if (pkg == null) {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
        if (pkg.isExternalStorage()) {
            if (TextUtils.isEmpty(pkg.getVolumeUuid())) {
                return StorageManager.UUID_PRIMARY_PHYSICAL;
            } else {
                return pkg.getVolumeUuid();
            }
        } else {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
    }

    private static boolean hasPermission(AndroidPackage pkg, String permName) {
        if (pkg.getPermissions().isEmpty()) {
            return false;
        }

        for (int i = pkg.getPermissions().size() - 1; i >= 0; i--) {
            if (pkg.getPermissions().get(i).getName().equals(permName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log that a permission request was granted/revoked.
     *
     * @param action the action performed
     * @param name name of the permission
     * @param packageName package permission is for
     */
    private void logPermission(int action, @NonNull String name, @NonNull String packageName) {
        final LogMaker log = new LogMaker(action);
        log.setPackageName(packageName);
        log.addTaggedData(MetricsEvent.FIELD_PERMISSION, name);

        mMetricsLogger.write(log);
    }

    /**
     * Get the mapping of background permissions to their foreground permissions.
     *
     * <p>Only initialized in the system server.
     *
     * @return the map &lt;bg permission -> list&lt;fg perm&gt;&gt;
     */
    public @Nullable ArrayMap<String, List<String>> getBackgroundPermissions() {
        return mBackgroundPermissions;
    }

    @Nullable
    private UidPermissionState getUidState(@NonNull PackageSetting ps,
            @UserIdInt int userId) {
        return getUidState(ps.getAppId(), userId);
    }

    @Nullable
    private UidPermissionState getUidState(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        return getUidState(pkg.getUid(), userId);
    }

    @Nullable
    private UidPermissionState getUidState(int appId, @UserIdInt int userId) {
        synchronized (mLock) {
            final UserPermissionState userState = mState.getUserState(userId);
            if (userState == null) {
                return null;
            }
            return userState.getUidState(appId);
        }
    }

    private void removeAppIdState(@AppIdInt int appId) {
        synchronized (mLock) {
            final int[] userIds = mState.getUserIds();
            for (final int userId : userIds) {
                final UserPermissionState userState = mState.getUserState(userId);
                userState.removeUidState(appId);
            }
        }
    }

    private void readStateFromPackageSettings() {
        final int[] userIds = getAllUserIds();
        mPackageManagerInt.forEachPackageSetting(ps -> {
            final int appId = ps.getAppId();
            final LegacyPermissionState legacyState = ps.getLegacyPermissionState();

            synchronized (mLock) {
                for (final int userId : userIds) {
                    final UserPermissionState userState = mState.getOrCreateUserState(userId);

                    userState.setInstallPermissionsFixed(ps.name, ps.areInstallPermissionsFixed());
                    final UidPermissionState uidState = userState.getOrCreateUidState(appId);
                    uidState.reset();
                    uidState.setMissing(legacyState.isMissing(userId));
                    readStateFromPermissionStates(uidState,
                            legacyState.getInstallPermissionStates());
                    readStateFromPermissionStates(uidState,
                            legacyState.getRuntimePermissionStates(userId));
                }
            }
        });
    }

    private void readStateFromPermissionStates(@NonNull UidPermissionState uidState,
            @NonNull Collection<LegacyPermissionState.PermissionState> permissionStates) {
        for (final LegacyPermissionState.PermissionState permissionState : permissionStates) {
            uidState.putPermissionState(permissionState.getPermission(),
                    permissionState.isGranted(), permissionState.getFlags());
        }
    }

    private void writeStateToPackageSettings() {
        final int[] userIds = mState.getUserIds();
        mPackageManagerInt.forEachPackageSetting(ps -> {
            ps.setInstallPermissionsFixed(false);
            final LegacyPermissionState legacyState = ps.getLegacyPermissionState();
            legacyState.reset();
            final int appId = ps.getAppId();

            synchronized (mLock) {
                for (final int userId : userIds) {
                    final UserPermissionState userState = mState.getUserState(userId);
                    if (userState == null) {
                        Slog.e(TAG, "Missing user state for " + userId);
                        continue;
                    }

                    if (userState.areInstallPermissionsFixed(ps.name)) {
                        ps.setInstallPermissionsFixed(true);
                    }

                    final UidPermissionState uidState = userState.getUidState(appId);
                    if (uidState == null) {
                        Slog.e(TAG, "Missing permission state for " + ps.name + " and user "
                                + userId);
                        continue;
                    }

                    legacyState.setMissing(uidState.isMissing(), userId);
                    final List<PermissionState> permissionStates = uidState.getPermissionStates();
                    final int permissionStatesSize = permissionStates.size();
                    for (int i = 0; i < permissionStatesSize; i++) {
                        final PermissionState permissionState = permissionStates.get(i);

                        final LegacyPermissionState.PermissionState legacyPermissionState =
                                new LegacyPermissionState.PermissionState(
                                        permissionState.getPermission(),
                                        permissionState.isGranted(), permissionState.getFlags());
                        if (permissionState.isRuntime()) {
                            legacyState.putRuntimePermissionState(legacyPermissionState,
                                    userId);
                        } else {
                            legacyState.putInstallPermissionState(legacyPermissionState);
                        }
                    }
                }
            }
        });
    }

    @NonNull
    private LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId) {
        final LegacyPermissionState legacyState = new LegacyPermissionState();
        final int[] userIds = mState.getUserIds();
        for (final int userId : userIds) {
            final UidPermissionState uidState = getUidState(appId, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for app ID " + appId + " and user ID "
                        + userId);
                continue;
            }

            final List<PermissionState> permissionStates = uidState.getPermissionStates();
            final int permissionStatesSize = permissionStates.size();
            for (int i = 0; i < permissionStatesSize; i++) {
                final PermissionState permissionState = permissionStates.get(i);

                final LegacyPermissionState.PermissionState legacyPermissionState =
                        new LegacyPermissionState.PermissionState(permissionState.getPermission(),
                                permissionState.isGranted(), permissionState.getFlags());
                if (permissionState.isRuntime()) {
                    legacyState.putRuntimePermissionState(legacyPermissionState, userId);
                } else if (userId == UserHandle.USER_SYSTEM) {
                    legacyState.putInstallPermissionState(legacyPermissionState);
                }
            }
        }
        return legacyState;
    }

    @NonNull
    private int[] getGidsForUid(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final int userId = UserHandle.getUserId(uid);
        final UidPermissionState uidState = getUidState(appId, userId);
        if (uidState == null) {
            Slog.e(TAG, "Missing permissions state for app ID " + appId + " and user ID " + userId);
            return EMPTY_INT_ARRAY;
        }
        return uidState.computeGids(mGlobalGids, userId);
    }

    private class PermissionManagerServiceInternalImpl extends PermissionManagerServiceInternal {
        @Override
        public void systemReady() {
            PermissionManagerService.this.systemReady();
        }
        @Override
        public boolean isPermissionsReviewRequired(@NonNull AndroidPackage pkg,
                @UserIdInt int userId) {
            return PermissionManagerService.this.isPermissionsReviewRequired(pkg, userId);
        }

        @Override
        public void revokeRuntimePermissionsIfGroupChanged(
                @NonNull AndroidPackage newPackage,
                @NonNull AndroidPackage oldPackage,
                @NonNull ArrayList<String> allPackageNames) {
            PermissionManagerService.this.revokeRuntimePermissionsIfGroupChanged(newPackage,
                    oldPackage, allPackageNames, mDefaultPermissionCallback);
        }
        @Override
        public void addAllPermissions(AndroidPackage pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissions(pkg, chatty);
        }
        @Override
        public void addAllPermissionGroups(AndroidPackage pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissionGroups(pkg, chatty);
        }
        @Override
        public void removeAllPermissions(AndroidPackage pkg, boolean chatty) {
            PermissionManagerService.this.removeAllPermissions(pkg, chatty);
        }
        @Override
        public void readStateFromPackageSettingsTEMP() {
            PermissionManagerService.this.readStateFromPackageSettings();
        }
        @Override
        public void writeStateToPackageSettingsTEMP() {
            PermissionManagerService.this.writeStateToPackageSettings();
        }
        @Override
        public void onUserRemoved(@UserIdInt int userId) {
            PermissionManagerService.this.onUserRemoved(userId);
        }
        @Override
        public void removeAppIdStateTEMP(@AppIdInt int appId) {
            PermissionManagerService.this.removeAppIdState(appId);
        }
        @Override
        @UserIdInt
        public int revokeSharedUserPermissionsForDeletedPackageTEMP(
                @NonNull PackageSetting deletedPs, @UserIdInt int userId) {
            return PermissionManagerService.this.revokeSharedUserPermissionsForDeletedPackage(
                    deletedPs, userId);
        }
        @NonNull
        @Override
        public Set<String> getGrantedPermissions(@NonNull String packageName,
                @UserIdInt int userId) {
            return PermissionManagerService.this.getGrantedPermissions(packageName, userId);
        }
        @NonNull
        @Override
        public int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId) {
            return PermissionManagerService.this.getPermissionGids(permissionName, userId);
        }
        @Override
        public void grantRequestedRuntimePermissions(AndroidPackage pkg, int[] userIds,
                String[] grantedPermissions, int callingUid) {
            PermissionManagerService.this.grantRequestedRuntimePermissions(
                    pkg, userIds, grantedPermissions, callingUid, mDefaultPermissionCallback);
        }
        @Override
        public void setWhitelistedRestrictedPermissions(@NonNull AndroidPackage pkg,
                @NonNull int[] userIds, @Nullable List<String> permissions, int callingUid,
                @PackageManager.PermissionWhitelistFlags int flags) {
            setWhitelistedRestrictedPermissionsForUsers(pkg, userIds, permissions,
                    callingUid, flags, mDefaultPermissionCallback);
        }
        @Override
        public void setWhitelistedRestrictedPermissions(String packageName,
                List<String> permissions, int flags, int userId) {
            PermissionManagerService.this.setWhitelistedRestrictedPermissionsInternal(
                    packageName, permissions, flags, userId);
        }
        @Override
        public void setAutoRevokeWhitelisted(
                @NonNull String packageName, boolean whitelisted, int userId) {
            PermissionManagerService.this.setAutoRevokeWhitelisted(
                    packageName, whitelisted, userId);
        }
        @Override
        public void updatePermissions(@NonNull String packageName, @Nullable AndroidPackage pkg) {
            PermissionManagerService.this
                    .updatePermissions(packageName, pkg, mDefaultPermissionCallback);
        }
        @Override
        public void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdated) {
            PermissionManagerService.this
                    .updateAllPermissions(volumeUuid, sdkUpdated, mDefaultPermissionCallback);
        }
        @Override
        public void resetRuntimePermissions(AndroidPackage pkg, int userId) {
            PermissionManagerService.this.resetRuntimePermissionsInternal(pkg, userId);
        }
        @Override
        public void resetAllRuntimePermissions(final int userId) {
            mPackageManagerInt.forEachPackage(
                    (AndroidPackage pkg) -> resetRuntimePermissionsInternal(pkg, userId));
        }
        @Override
        public String[] getAppOpPermissionPackages(String permName, int callingUid) {
            return PermissionManagerService.this
                    .getAppOpPermissionPackagesInternal(permName, callingUid);
        }
        @Override
        public void enforceCrossUserPermission(int callingUid, int userId,
                boolean requireFullPermission, boolean checkShell, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, false, message);
        }
        @Override
        public void enforceCrossUserPermission(int callingUid, int userId,
                boolean requireFullPermission, boolean checkShell,
                boolean requirePermissionWhenSameUser, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, requirePermissionWhenSameUser, message);
        }

        @Override
        public void enforceCrossUserOrProfilePermission(int callingUid, int userId,
                boolean requireFullPermission, boolean checkShell, String message) {
            PermissionManagerService.this.enforceCrossUserOrProfilePermission(
                    callingUid,
                    userId,
                    requireFullPermission,
                    checkShell,
                    message);
        }

        @Override
        public void enforceGrantRevokeRuntimePermissionPermissions(String message) {
            PermissionManagerService.this.enforceGrantRevokeRuntimePermissionPermissions(message);
        }
        @Override
        public PermissionSettings getPermissionSettings() {
            return mSettings;
        }
        @Override
        public BasePermission getPermissionTEMP(String permName) {
            synchronized (PermissionManagerService.this.mLock) {
                return mSettings.getPermissionLocked(permName);
            }
        }

        @Override
        public @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtection(
                @PermissionInfo.Protection int protection) {
            ArrayList<PermissionInfo> matchingPermissions = new ArrayList<>();

            synchronized (mLock) {
                int numTotalPermissions = mSettings.mPermissions.size();

                for (int i = 0; i < numTotalPermissions; i++) {
                    BasePermission bp = mSettings.mPermissions.valueAt(i);

                    if (bp.perm != null && bp.perm.getProtection() == protection) {
                        matchingPermissions.add(
                                PackageInfoUtils.generatePermissionInfo(bp.perm, 0));
                    }
                }
            }

            return matchingPermissions;
        }

        @Override
        public @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtectionFlags(
                @PermissionInfo.ProtectionFlags int protectionFlags) {
            ArrayList<PermissionInfo> matchingPermissions = new ArrayList<>();

            synchronized (mLock) {
                int numTotalPermissions = mSettings.mPermissions.size();

                for (int i = 0; i < numTotalPermissions; i++) {
                    BasePermission bp = mSettings.mPermissions.valueAt(i);

                    if (bp.perm != null && (bp.perm.getProtectionFlags() & protectionFlags)
                            == protectionFlags) {
                        matchingPermissions.add(
                                PackageInfoUtils.generatePermissionInfo(bp.perm, 0));
                    }
                }
            }

            return matchingPermissions;
        }

        @Override
        public @Nullable byte[] backupRuntimePermissions(@NonNull UserHandle user) {
            return PermissionManagerService.this.backupRuntimePermissions(user);
        }

        @Override
        public void restoreRuntimePermissions(@NonNull byte[] backup, @NonNull UserHandle user) {
            PermissionManagerService.this.restoreRuntimePermissions(backup, user);
        }

        @Override
        public void restoreDelayedRuntimePermissions(@NonNull String packageName,
                @NonNull UserHandle user) {
            PermissionManagerService.this.restoreDelayedRuntimePermissions(packageName, user);
        }

        @Override
        public void addOnRuntimePermissionStateChangedListener(
                OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.addOnRuntimePermissionStateChangedListener(
                    listener);
        }

        @Override
        public void removeOnRuntimePermissionStateChangedListener(
                OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.removeOnRuntimePermissionStateChangedListener(
                    listener);
        }

        @Override
        public CheckPermissionDelegate getCheckPermissionDelegate() {
            synchronized (mLock) {
                return mCheckPermissionDelegate;
            }
        }

        @Override
        public void setCheckPermissionDelegate(CheckPermissionDelegate delegate) {
            synchronized (mLock) {
                if (delegate != null || mCheckPermissionDelegate != null) {
                    PackageManager.invalidatePackageInfoCache();
                }
                mCheckPermissionDelegate = delegate;
            }
        }

        @Override
        public void setDefaultBrowserProvider(@NonNull DefaultBrowserProvider provider) {
            synchronized (mLock) {
                mDefaultBrowserProvider = provider;
            }
        }

        @Override
        public void setDefaultBrowser(String packageName, boolean async, boolean doGrant,
                int userId) {
            setDefaultBrowserInternal(packageName, async, doGrant, userId);
        }

        @Override
        public void setDefaultDialerProvider(@NonNull DefaultDialerProvider provider) {
            synchronized (mLock) {
                mDefaultDialerProvider = provider;
            }
        }

        @Override
        public void setDefaultHomeProvider(@NonNull DefaultHomeProvider provider) {
            synchronized (mLock) {
                mDefaultHomeProvider = provider;
            }
        }

        @Override
        public void setDefaultHome(String packageName, int userId, Consumer<Boolean> callback) {
            if (userId == UserHandle.USER_ALL) {
                return;
            }
            DefaultHomeProvider provider;
            synchronized (mLock) {
                provider = mDefaultHomeProvider;
            }
            if (provider == null) {
                return;
            }
            provider.setDefaultHomeAsync(packageName, userId, callback);
        }

        @Override
        public void setDialerAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setDialerAppPackagesProvider(provider);
        }

        @Override
        public void setLocationExtraPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setLocationExtraPackagesProvider(provider);
        }

        @Override
        public void setLocationPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setLocationPackagesProvider(provider);
        }

        @Override
        public void setSimCallManagerPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSimCallManagerPackagesProvider(provider);
        }

        @Override
        public void setSmsAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSmsAppPackagesProvider(provider);
        }

        @Override
        public void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSyncAdapterPackagesProvider(provider);
        }

        @Override
        public void setUseOpenWifiAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setUseOpenWifiAppPackagesProvider(provider);
        }

        @Override
        public void setVoiceInteractionPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setVoiceInteractionPackagesProvider(provider);
        }

        @Override
        public String getDefaultBrowser(int userId) {
            DefaultBrowserProvider provider;
            synchronized (mLock) {
                provider = mDefaultBrowserProvider;
            }
            return provider != null ? provider.getDefaultBrowser(userId) : null;
        }

        @Override
        public String getDefaultDialer(int userId) {
            DefaultDialerProvider provider;
            synchronized (mLock) {
                provider = mDefaultDialerProvider;
            }
            return provider != null ? provider.getDefaultDialer(userId) : null;
        }

        @Override
        public String getDefaultHome(int userId) {
            DefaultHomeProvider provider;
            synchronized (mLock) {
                provider = mDefaultHomeProvider;
            }
            return provider != null ? provider.getDefaultHome(userId) : null;
        }

        @Override
        public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultSimCallManager(
                    packageName, userId);
        }

        @Override
        public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultUseOpenWifiApp(
                    packageName, userId);
        }

        @Override
        public void grantDefaultPermissionsToDefaultBrowser(String packageName, int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultBrowser(packageName,
                    userId);
        }

        @Override
        public void onNewUserCreated(int userId) {
            // NOTE: This adds UPDATE_PERMISSIONS_REPLACE_PKG
            PermissionManagerService.this.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL,
                    true, mDefaultPermissionCallback);
            mDefaultPermissionGrantPolicy.grantDefaultPermissions(userId);
        }

        @Override
        public void retainHardAndSoftRestrictedPermissions(@NonNull List<String> permissions) {
            synchronized (mLock) {
                Iterator<String> iterator = permissions.iterator();
                while (iterator.hasNext()) {
                    String permission = iterator.next();
                    BasePermission basePermission = mSettings.mPermissions.get(permission);
                    if (basePermission == null || !basePermission.isHardOrSoftRestricted()) {
                        iterator.remove();
                    }
                }
            }
        }

        @NonNull
        public LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId) {
            return PermissionManagerService.this.getLegacyPermissionState(appId);
        }

        @NonNull
        public int[] getGidsForUid(int uid) {
            return PermissionManagerService.this.getGidsForUid(uid);
        }
    }

    private static final class OnPermissionChangeListeners extends Handler {
        private static final int MSG_ON_PERMISSIONS_CHANGED = 1;

        private final RemoteCallbackList<IOnPermissionsChangeListener> mPermissionListeners =
                new RemoteCallbackList<>();

        OnPermissionChangeListeners(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_PERMISSIONS_CHANGED: {
                    final int uid = msg.arg1;
                    handleOnPermissionsChanged(uid);
                } break;
            }
        }

        public void addListenerLocked(IOnPermissionsChangeListener listener) {
            mPermissionListeners.register(listener);

        }

        public void removeListenerLocked(IOnPermissionsChangeListener listener) {
            mPermissionListeners.unregister(listener);
        }

        public void onPermissionsChanged(int uid) {
            if (mPermissionListeners.getRegisteredCallbackCount() > 0) {
                obtainMessage(MSG_ON_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
            }
        }

        private void handleOnPermissionsChanged(int uid) {
            final int count = mPermissionListeners.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    IOnPermissionsChangeListener callback = mPermissionListeners
                            .getBroadcastItem(i);
                    try {
                        callback.onPermissionsChanged(uid);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Permission listener is dead", e);
                    }
                }
            } finally {
                mPermissionListeners.finishBroadcast();
            }
        }
    }

    /**
     * Allows injection of services and method responses to facilitate testing.
     *
     * <p>Test classes can create a mock of this class and pass it to the PermissionManagerService
     * constructor to control behavior of services and external methods during execution.
     * @hide
     */
    @VisibleForTesting
    public static class Injector {
        private final Context mContext;

        /**
         * Public constructor that accepts a {@code context} within which to operate.
         */
        public Injector(@NonNull Context context) {
            mContext = context;
        }

        /**
         * Returns the UID of the calling package.
         */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /**
         * Returns the process ID of the calling package.
         */
        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        /**
         * Invalidates the package info cache.
         */
        public void invalidatePackageInfoCache() {
            PackageManager.invalidatePackageInfoCache();
        }

        /**
         * Disables the permission cache.
         */
        public void disablePermissionCache() {
            PermissionManager.disablePermissionCache();
        }

        /**
         * Disables the package name permission cache.
         */
        public void disablePackageNamePermissionCache() {
            PermissionManager.disablePackageNamePermissionCache();
        }

        /**
         * Checks if the package running under the specified {@code pid} and {@code uid} has been
         * granted the provided {@code permission}.
         *
         * @return {@link PackageManager#PERMISSION_GRANTED} if the package has been granted the
         * permission, {@link PackageManager#PERMISSION_DENIED} otherwise
         */
        public int checkPermission(@NonNull String permission, int pid, int uid) {
            return mContext.checkPermission(permission, pid, uid);
        }

        /**
         * Clears the calling identity to allow subsequent calls to be treated as coming from this
         * package.
         *
         * @return a token that can be used to restore the calling identity
         */
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        /**
         * Restores the calling identity to that of the calling package based on the provided
         * {@code token}.
         */
        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        /**
         * Returns the system service with the provided {@code name}.
         */
        public Object getSystemService(@NonNull String name) {
            return mContext.getSystemService(name);
        }
    }
}
