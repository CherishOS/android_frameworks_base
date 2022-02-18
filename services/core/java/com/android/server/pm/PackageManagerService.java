/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.pm;

import static android.Manifest.permission.MANAGE_DEVICE_ADMINS;
import static android.Manifest.permission.SET_HARMFUL_APP_WARNINGS;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.RESTRICTION_NONE;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationPackageManager;
import android.app.IActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.SecurityLog;
import android.app.backup.IBackupManager;
import android.app.role.RoleManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.Checksum;
import android.content.pm.ComponentInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.FallbackCategoryProvider;
import android.content.pm.FeatureInfo;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageChangeObserver;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageLoadingProgressCallback;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstantAppRequest;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageChangeEvent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ComponentEnabledSetting;
import android.content.pm.PackageManager.ComponentType;
import android.content.pm.PackageManager.LegacyPackageDeleteObserver;
import android.content.pm.PackageManager.ModuleInfoFlags;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageManager.PropertyLocation;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackagePartitions;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.TestUtilityService;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.IArtManager;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.PackageLite;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.os.incremental.PerUidReadTimeouts;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeRecord;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.PackageWatchdog;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.compat.CompatChange;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Settings.VersionInfo;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.ArtUtils;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.ViewCompiler;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerService;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackageApi;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUtils;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.mutate.PackageStateMutator;
import com.android.server.pm.pkg.mutate.PackageStateWrite;
import com.android.server.pm.pkg.mutate.PackageUserStateWrite;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.resolution.ComponentResolver;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.supplementalprocess.SupplementalProcessManagerLocal;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.utils.Watchable;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedSparseBooleanArray;
import com.android.server.utils.WatchedSparseIntArray;
import com.android.server.utils.Watcher;

import dalvik.system.VMRuntime;

import libcore.util.HexEncoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Keep track of all those APKs everywhere.
 * <p>
 * Internally there are three important locks:
 * <ul>
 * <li>{@link #mLock} is used to guard all in-memory parsed package details
 * and other related state. It is a fine-grained lock that should only be held
 * momentarily, as it's one of the most contended locks in the system.
 * <li>{@link #mInstallLock} is used to guard all {@code installd} access, whose
 * operations typically involve heavy lifting of application data on disk. Since
 * {@code installd} is single-threaded, and it's operations can often be slow,
 * this lock should never be acquired while already holding {@link #mLock}.
 * Conversely, it's safe to acquire {@link #mLock} momentarily while already
 * holding {@link #mInstallLock}.
 * <li>{@link #mSnapshotLock} is used to guard access to two snapshot fields: the snapshot
 * itself and the snapshot invalidation flag.  This lock should never be acquired while
 * already holding {@link #mLock}. Conversely, it's safe to acquire {@link #mLock}
 * momentarily while already holding {@link #mSnapshotLock}.
 * </ul>
 * Many internal methods rely on the caller to hold the appropriate locks, and
 * this contract is expressed through method name suffixes:
 * <ul>
 * <li>fooLI(): the caller must hold {@link #mInstallLock}
 * <li>fooLIF(): the caller must hold {@link #mInstallLock} and the package
 * being modified must be frozen
 * <li>fooLPr(): the caller must hold {@link #mLock} for reading
 * <li>fooLPw(): the caller must hold {@link #mLock} for writing
 * </ul>
 * {@link #mSnapshotLock} is taken in exactly one place - {@code snapshotComputer()}.  It
 * should not be taken anywhere else or used for any other purpose.
 * <p>
 * Because this class is very central to the platform's security; please run all
 * CTS and unit tests whenever making modifications:
 *
 * <pre>
 * $ runtest -c android.content.pm.PackageManagerTests frameworks-core
 * $ cts-tradefed run commandAndExit cts -m CtsAppSecurityHostTestCases
 * </pre>
 */
public class PackageManagerService extends IPackageManager.Stub
        implements PackageSender, TestUtilityService {
    static final String TAG = "PackageManager";
    public static final boolean DEBUG_SETTINGS = false;
    static final boolean DEBUG_PREFERRED = false;
    static final boolean DEBUG_UPGRADE = false;
    static final boolean DEBUG_DOMAIN_VERIFICATION = false;
    static final boolean DEBUG_BACKUP = false;
    public static final boolean DEBUG_INSTALL = false;
    public static final boolean DEBUG_REMOVE = false;
    static final boolean DEBUG_PACKAGE_INFO = false;
    static final boolean DEBUG_INTENT_MATCHING = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    static final boolean DEBUG_VERIFY = false;
    public static final boolean DEBUG_PERMISSIONS = false;
    public static final boolean DEBUG_COMPRESSION = Build.IS_DEBUGGABLE;
    public static final boolean TRACE_SNAPSHOTS = false;
    private static final boolean DEBUG_PER_UID_READ_TIMEOUTS = false;

    // Debug output for dexopting. This is shared between PackageManagerService, OtaDexoptService
    // and PackageDexOptimizer. All these classes have their own flag to allow switching a single
    // user, but by default initialize to this.
    public static final boolean DEBUG_DEXOPT = false;

    static final boolean DEBUG_ABI_SELECTION = false;
    public static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;

    static final boolean HIDE_EPHEMERAL_APIS = false;

    static final String PRECOMPILE_LAYOUTS = "pm.precompile_layouts";

    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int LOG_UID = Process.LOG_UID;
    private static final int NFC_UID = Process.NFC_UID;
    private static final int BLUETOOTH_UID = Process.BLUETOOTH_UID;
    private static final int SHELL_UID = Process.SHELL_UID;
    private static final int SE_UID = Process.SE_UID;
    private static final int NETWORKSTACK_UID = Process.NETWORK_STACK_UID;
    private static final int UWB_UID = Process.UWB_UID;

    static final int SCAN_NO_DEX = 1 << 0;
    static final int SCAN_UPDATE_SIGNATURE = 1 << 1;
    static final int SCAN_NEW_INSTALL = 1 << 2;
    static final int SCAN_UPDATE_TIME = 1 << 3;
    static final int SCAN_BOOTING = 1 << 4;
    static final int SCAN_REQUIRE_KNOWN = 1 << 7;
    static final int SCAN_MOVE = 1 << 8;
    static final int SCAN_INITIAL = 1 << 9;
    static final int SCAN_DONT_KILL_APP = 1 << 10;
    static final int SCAN_IGNORE_FROZEN = 1 << 11;
    static final int SCAN_FIRST_BOOT_OR_UPGRADE = 1 << 12;
    static final int SCAN_AS_INSTANT_APP = 1 << 13;
    static final int SCAN_AS_FULL_APP = 1 << 14;
    static final int SCAN_AS_VIRTUAL_PRELOAD = 1 << 15;
    static final int SCAN_AS_SYSTEM = 1 << 16;
    static final int SCAN_AS_PRIVILEGED = 1 << 17;
    static final int SCAN_AS_OEM = 1 << 18;
    static final int SCAN_AS_VENDOR = 1 << 19;
    static final int SCAN_AS_PRODUCT = 1 << 20;
    static final int SCAN_AS_SYSTEM_EXT = 1 << 21;
    static final int SCAN_AS_ODM = 1 << 22;
    static final int SCAN_AS_APK_IN_APEX = 1 << 23;

    @IntDef(flag = true, prefix = { "SCAN_" }, value = {
            SCAN_NO_DEX,
            SCAN_UPDATE_SIGNATURE,
            SCAN_NEW_INSTALL,
            SCAN_UPDATE_TIME,
            SCAN_BOOTING,
            SCAN_REQUIRE_KNOWN,
            SCAN_MOVE,
            SCAN_INITIAL,
            SCAN_DONT_KILL_APP,
            SCAN_IGNORE_FROZEN,
            SCAN_FIRST_BOOT_OR_UPGRADE,
            SCAN_AS_INSTANT_APP,
            SCAN_AS_FULL_APP,
            SCAN_AS_VIRTUAL_PRELOAD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanFlags {}

    /**
     * Used as the result code of the {@link #getPackageStartability}.
     */
    @IntDef(value = {
        PACKAGE_STARTABILITY_OK,
        PACKAGE_STARTABILITY_NOT_FOUND,
        PACKAGE_STARTABILITY_NOT_SYSTEM,
        PACKAGE_STARTABILITY_FROZEN,
        PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageStartability {}

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is allowed to start.
     */
    public static final int PACKAGE_STARTABILITY_OK = 0;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's not found
     * (could be due to that package is invisible to the given user).
     */
    public static final int PACKAGE_STARTABILITY_NOT_FOUND = 1;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's not a system app
     * and the system is running in safe mode.
     */
    public static final int PACKAGE_STARTABILITY_NOT_SYSTEM = 2;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's currently frozen.
     */
    public static final int PACKAGE_STARTABILITY_FROZEN = 3;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it doesn't support
     * direct boot.
     */
    public static final int PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED = 4;

    private static final String STATIC_SHARED_LIB_DELIMITER = "_";
    /** Extension of the compressed packages */
    public final static String COMPRESSED_EXTENSION = ".gz";
    /** Suffix of stub packages on the system partition */
    public final static String STUB_SUFFIX = "-Stub";

    static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * Timeout (in milliseconds) after which the watchdog should declare that
     * our handler thread is wedged.  The usual default for such things is one
     * minute but we sometimes do very lengthy I/O operations on this thread,
     * such as installing multi-gigabyte applications, so ours needs to be longer.
     */
    static final long WATCHDOG_TIMEOUT = 1000*60*10;     // ten minutes

    /**
     * Wall-clock timeout (in milliseconds) after which we *require* that an fstrim
     * be run on this device.  We use the value in the Settings.Global.MANDATORY_FSTRIM_INTERVAL
     * settings entry if available, otherwise we use the hardcoded default.  If it's been
     * more than this long since the last fstrim, we force one during the boot sequence.
     *
     * This backstops other fstrim scheduling:  if the device is alive at midnight+idle,
     * one gets run at the next available charging+idle time.  This final mandatory
     * no-fstrim check kicks in only of the other scheduling criteria is never met.
     */
    private static final long DEFAULT_MANDATORY_FSTRIM_INTERVAL = 3 * DateUtils.DAY_IN_MILLIS;

    /**
     * Default IncFs timeouts. Maximum values in IncFs is 1hr.
     *
     * <p>If flag value is empty, the default value will be assigned.
     *
     * Flag type: {@code String}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_INCFS_DEFAULT_TIMEOUTS = "incfs_default_timeouts";

    /**
     * Known digesters with optional timeouts.
     *
     * Flag type: {@code String}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_KNOWN_DIGESTERS_LIST = "known_digesters_list";

    /**
     * The default response for package verification timeout.
     *
     * This can be either PackageManager.VERIFICATION_ALLOW or
     * PackageManager.VERIFICATION_REJECT.
     */
    static final int DEFAULT_VERIFICATION_RESPONSE = PackageManager.VERIFICATION_ALLOW;

    /**
     * Adding an installer package name to a package that does not have one set requires the
     * INSTALL_PACKAGES permission.
     *
     * If the caller targets R, this will throw a SecurityException. Otherwise the request will
     * fail silently. In both cases, and regardless of whether this change is enabled, the
     * installer package will remain unchanged.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long THROW_EXCEPTION_ON_REQUIRE_INSTALL_PACKAGES_TO_ADD_INSTALLER_PACKAGE =
            150857253;

    public static final String PLATFORM_PACKAGE_NAME = "android";

    static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    static final String PACKAGE_SCHEME = "package";

    private static final String COMPANION_PACKAGE_NAME = "com.android.companiondevicemanager";

    // Compilation reasons.
    public static final int REASON_FIRST_BOOT = 0;
    public static final int REASON_BOOT_AFTER_OTA = 1;
    public static final int REASON_POST_BOOT = 2;
    public static final int REASON_INSTALL = 3;
    public static final int REASON_INSTALL_FAST = 4;
    public static final int REASON_INSTALL_BULK = 5;
    public static final int REASON_INSTALL_BULK_SECONDARY = 6;
    public static final int REASON_INSTALL_BULK_DOWNGRADED = 7;
    public static final int REASON_INSTALL_BULK_SECONDARY_DOWNGRADED = 8;
    public static final int REASON_BACKGROUND_DEXOPT = 9;
    public static final int REASON_AB_OTA = 10;
    public static final int REASON_INACTIVE_PACKAGE_DOWNGRADE = 11;
    public static final int REASON_CMDLINE = 12;
    public static final int REASON_SHARED = 13;

    public static final int REASON_LAST = REASON_SHARED;

    static final String RANDOM_DIR_PREFIX = "~~";
    static final char RANDOM_CODEPATH_PREFIX = '-';

    final Handler mHandler;

    final ProcessLoggingHandler mProcessLoggingHandler;

    private final boolean mEnableFreeCacheV2;

    private final int mSdkVersion;
    final Context mContext;
    final boolean mFactoryTest;
    private final boolean mOnlyCore;
    final DisplayMetrics mMetrics;
    private final int mDefParseFlags;
    private final String[] mSeparateProcesses;
    private final boolean mIsUpgrade;
    private final boolean mIsPreNUpgrade;
    private final boolean mIsPreNMR1Upgrade;
    private final boolean mIsPreQUpgrade;

    // Used for privilege escalation. MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    @GuardedBy("mInstallLock")
    final Installer mInstaller;

    /** Directory where installed applications are stored */
    private final File mAppInstallDir;

    // ----------------------------------------------------------------

    // Lock for state used when installing and doing other long running
    // operations.  Methods that must be called with this lock held have
    // the suffix "LI".
    final Object mInstallLock;

    // ----------------------------------------------------------------

    // Lock for global state used when modifying package state or settings.
    // Methods that must be called with this lock held have
    // the suffix "Locked". Some methods may use the legacy suffix "LP"
    final PackageManagerTracedLock mLock;

    // Ensures order of overlay updates until data storage can be moved to overlay code
    private final PackageManagerTracedLock mOverlayPathsLock = new PackageManagerTracedLock();

    // Lock alias for doing package state mutation
    private final PackageManagerTracedLock mPackageStateWriteLock;

    private final PackageStateMutator mPackageStateMutator = new PackageStateMutator(
            this::getPackageSettingForMutation,
            this::getDisabledPackageSettingForMutation);

    // Keys are String (package name), values are Package.
    @Watched
    @GuardedBy("mLock")
    final WatchedArrayMap<String, AndroidPackage> mPackages = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, AndroidPackage>> mPackagesSnapshot =
            new SnapshotCache.Auto(mPackages, mPackages, "PackageManagerService.mPackages");

    // Keys are isolated uids and values are the uid of the application
    // that created the isolated process.
    @Watched
    @GuardedBy("mLock")
    final WatchedSparseIntArray mIsolatedOwners = new WatchedSparseIntArray();
    private final SnapshotCache<WatchedSparseIntArray> mIsolatedOwnersSnapshot =
            new SnapshotCache.Auto(mIsolatedOwners, mIsolatedOwners,
                                   "PackageManagerService.mIsolatedOwners");

    /**
     * Tracks existing packages prior to receiving an OTA. Keys are package name.
     * Only non-null during an OTA, and even then it is nulled again once systemReady().
     */
    private @Nullable ArraySet<String> mExistingPackages = null;

    /**
     * List of code paths that need to be released when the system becomes ready.
     * <p>
     * NOTE: We have to delay releasing cblocks for no other reason than we cannot
     * retrieve the setting {@link Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL}. When
     * we no longer need to read that setting, cblock release can occur in the
     * constructor.
     *
     * @see Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL
     * @see #systemReady()
     */
    @Nullable List<File> mReleaseOnSystemReady;

    /**
     * Whether or not system app permissions should be promoted from install to runtime.
     */
    boolean mPromoteSystemApps;

    private final PackageManagerInternal mPmInternal;
    private final TestUtilityService mTestUtilityService;

    @Watched
    @GuardedBy("mLock")
    final Settings mSettings;

    /**
     * Map of package names to frozen counts that are currently "frozen",
     * which means active surgery is being done on the code/data for that
     * package. The platform will refuse to launch frozen packages to avoid
     * race conditions.
     *
     * @see PackageFreezer
     */
    @GuardedBy("mLock")
    final WatchedArrayMap<String, Integer> mFrozenPackages = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, Integer>> mFrozenPackagesSnapshot =
            new SnapshotCache.Auto(mFrozenPackages, mFrozenPackages,
                    "PackageManagerService.mFrozenPackages");

    final ProtectedPackages mProtectedPackages;

    @GuardedBy("mLoadedVolumes")
    final ArraySet<String> mLoadedVolumes = new ArraySet<>();

    private boolean mFirstBoot;

    final boolean mIsEngBuild;
    private final boolean mIsUserDebugBuild;
    private final String mIncrementalVersion;

    PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;

    @GuardedBy("mAvailableFeatures")
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;

    @Watched
    final InstantAppRegistry mInstantAppRegistry;

    @NonNull
    final ChangedPackagesTracker mChangedPackagesTracker;

    @NonNull
    private final PackageObserverHelper mPackageObserverHelper = new PackageObserverHelper();

    private final ModuleInfoProvider mModuleInfoProvider;

    final ApexManager mApexManager;

    final PackageManagerServiceInjector mInjector;

    /**
     * The list of all system partitions that may contain packages in ascending order of
     * specificity (the more generic, the earlier in the list a partition appears).
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static final List<ScanPartition> SYSTEM_PARTITIONS = Collections.unmodifiableList(
            PackagePartitions.getOrderedPartitions(ScanPartition::new));

    private @NonNull final OverlayConfig mOverlayConfig;

    @GuardedBy("itself")
    final ArrayList<IPackageChangeObserver> mPackageChangeObservers =
        new ArrayList<>();

    // Cached parsed flag value. Invalidated on each flag change.
    PerUidReadTimeouts[] mPerUidReadTimeoutsCache;

    private static final PerUidReadTimeouts[] EMPTY_PER_UID_READ_TIMEOUTS_ARRAY = {};

    private static class DefaultSystemWrapper implements
            PackageManagerServiceInjector.SystemWrapper {

        @Override
        public void disablePackageCaches() {
            // disable all package caches that shouldn't apply within system server
            PackageManager.disableApplicationInfoCache();
            PackageManager.disablePackageInfoCache();
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
            ApplicationPackageManager.disableGetPackagesForUidCache();
            ApplicationPackageManager.invalidateHasSystemFeatureCache();
            PackageManager.corkPackageInfoCache();
        }

        @Override
        public void enablePackageCaches() {
            PackageManager.uncorkPackageInfoCache();
        }
    }

    @Watched
    final AppsFilter mAppsFilter;

    final PackageParser2.Callback mPackageParserCallback;

    // Currently known shared libraries.
    @Watched
    private final SharedLibrariesImpl mSharedLibraries;

    // Mapping from instrumentation class names to info about them.
    @Watched
    private final WatchedArrayMap<ComponentName, ParsedInstrumentation> mInstrumentation =
            new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<ComponentName, ParsedInstrumentation>>
            mInstrumentationSnapshot =
            new SnapshotCache.Auto<>(mInstrumentation, mInstrumentation,
                                     "PackageManagerService.mInstrumentation");

    // Packages whose data we have transfered into another package, thus
    // should no longer exist.
    final ArraySet<String> mTransferredPackages = new ArraySet<>();

    // Broadcast actions that are only available to the system.
    @GuardedBy("mProtectedBroadcasts")
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<>();

    /**
     * List of packages waiting for verification.
     * Handler thread only!
     */
    final SparseArray<PackageVerificationState> mPendingVerification = new SparseArray<>();

    /**
     * List of packages waiting for rollback to be enabled.
     * Handler thread only!
     */
    final SparseArray<VerificationParams> mPendingEnableRollback = new SparseArray<>();

    final PackageInstallerService mInstallerService;

    final ArtManagerService mArtManagerService;

    final PackageDexOptimizer mPackageDexOptimizer;
    final BackgroundDexOptService mBackgroundDexOptService;
    // DexManager handles the usage of dex files (e.g. secondary files, whether or not a package
    // is used by other apps).
    private final DexManager mDexManager;

    final ViewCompiler mViewCompiler;

    private final AtomicInteger mNextMoveId = new AtomicInteger();
    final MovePackageHelper.MoveCallbacks mMoveCallbacks;

    /**
     * Token for keys in mPendingVerification.
     * Handler thread only!
     */
    int mPendingVerificationToken = 0;

    /**
     * Token for keys in mPendingEnableRollback.
     * Handler thread only!
     */
    int mPendingEnableRollbackToken = 0;

    @Watched(manual = true)
    private volatile boolean mSystemReady;
    @Watched(manual = true)
    private volatile boolean mSafeMode;
    @Watched
    private final WatchedSparseBooleanArray mWebInstantAppsDisabled =
            new WatchedSparseBooleanArray();

    @Watched(manual = true)
    private ApplicationInfo mAndroidApplication;
    @Watched(manual = true)
    private final ActivityInfo mResolveActivity = new ActivityInfo();
    private final ResolveInfo mResolveInfo = new ResolveInfo();
    @Watched(manual = true)
    ComponentName mResolveComponentName;
    private AndroidPackage mPlatformPackage;
    ComponentName mCustomResolverComponentName;

    private boolean mResolverReplaced = false;

    @NonNull
    final DomainVerificationManagerInternal mDomainVerificationManager;

    /** The service connection to the ephemeral resolver */
    final InstantAppResolverConnection mInstantAppResolverConnection;
    /** Component used to show resolver settings for Instant Apps */
    final ComponentName mInstantAppResolverSettingsComponent;

    /** Activity used to install instant applications */
    @Watched(manual = true)
    ActivityInfo mInstantAppInstallerActivity;
    @Watched(manual = true)
    private final ResolveInfo mInstantAppInstallerInfo = new ResolveInfo();

    private final Map<String, Pair<PackageInstalledInfo, IPackageInstallObserver2>>
            mNoKillInstallObservers = Collections.synchronizedMap(new HashMap<>());

    private final Map<String, Pair<PackageInstalledInfo, IPackageInstallObserver2>>
            mPendingKillInstallObservers = Collections.synchronizedMap(new HashMap<>());

    // Internal interface for permission manager
    final PermissionManagerServiceInternal mPermissionManager;

    @Watched
    final ComponentResolver mComponentResolver;

    // Set of packages names to keep cached, even if they are uninstalled for all users
    @GuardedBy("mKeepUninstalledPackages")
    @NonNull
    private final ArraySet<String> mKeepUninstalledPackages = new ArraySet<>();

    // Cached reference to IDevicePolicyManager.
    private IDevicePolicyManager mDevicePolicyManager = null;

    private File mCacheDir;

    private Future<?> mPrepareAppDataFuture;

    final IncrementalManager mIncrementalManager;

    private final DefaultAppProvider mDefaultAppProvider;

    private final LegacyPermissionManagerInternal mLegacyPermissionManager;

    private final PackageProperty mPackageProperty = new PackageProperty();

    final PendingPackageBroadcasts mPendingBroadcasts;

    static final int SEND_PENDING_BROADCAST = 1;
    static final int INIT_COPY = 5;
    static final int POST_INSTALL = 9;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_PACKAGE_RESTRICTIONS = 14;
    static final int PACKAGE_VERIFIED = 15;
    static final int CHECK_PENDING_VERIFICATION = 16;
    // public static final int UNUSED = 17;
    // public static final int UNUSED = 18;
    static final int WRITE_PACKAGE_LIST = 19;
    static final int INSTANT_APP_RESOLUTION_PHASE_TWO = 20;
    static final int ENABLE_ROLLBACK_STATUS = 21;
    static final int ENABLE_ROLLBACK_TIMEOUT = 22;
    static final int DEFERRED_NO_KILL_POST_DELETE = 23;
    static final int DEFERRED_NO_KILL_INSTALL_OBSERVER = 24;
    static final int INTEGRITY_VERIFICATION_COMPLETE = 25;
    static final int CHECK_PENDING_INTEGRITY_VERIFICATION = 26;
    static final int DOMAIN_VERIFICATION = 27;
    static final int PRUNE_UNUSED_STATIC_SHARED_LIBRARIES = 28;
    static final int DEFERRED_PENDING_KILL_INSTALL_OBSERVER = 29;

    static final int DEFERRED_NO_KILL_POST_DELETE_DELAY_MS = 3 * 1000;
    private static final int DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS = 500;
    private static final int DEFERRED_PENDING_KILL_INSTALL_OBSERVER_DELAY_MS = 1000;

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    private static final long BROADCAST_DELAY_DURING_STARTUP = 10 * 1000L; // 10 seconds (in millis)
    private static final long BROADCAST_DELAY = 1 * 1000L; // 1 second (in millis)

    private static final long PRUNE_UNUSED_SHARED_LIBRARIES_DELAY =
            TimeUnit.MINUTES.toMillis(3); // 3 minutes

    // When the service constructor finished plus a delay (used for broadcast delay computation)
    private long mServiceStartWithDelay;

    private static final long FREE_STORAGE_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
            TimeUnit.HOURS.toMillis(2); /* two hours */
    static final long DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
            TimeUnit.DAYS.toMillis(7); /* 7 days */

    final UserManagerService mUserManager;

    final UserNeedsBadgingCache mUserNeedsBadging;

    // Stores a list of users whose package restrictions file needs to be updated
    final ArraySet<Integer> mDirtyUsers = new ArraySet<>();

    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<>();
    int mNextInstallToken = 1;  // nonzero; will be wrapped back to 1 when ++ overflows

    final @Nullable String mRequiredVerifierPackage;
    final @NonNull String mRequiredInstallerPackage;
    final @NonNull String mRequiredUninstallerPackage;
    final @NonNull String mRequiredPermissionControllerPackage;
    final @Nullable String mSetupWizardPackage;
    final @Nullable String mStorageManagerPackage;
    final @Nullable String mDefaultTextClassifierPackage;
    final @Nullable String mSystemTextClassifierPackageName;
    final @Nullable String mConfiguratorPackage;
    final @Nullable String mAppPredictionServicePackage;
    final @Nullable String mIncidentReportApproverPackage;
    final @Nullable String mServicesExtensionPackageName;
    final @Nullable String mSharedSystemSharedLibraryPackageName;
    final @Nullable String mRetailDemoPackage;
    final @Nullable String mOverlayConfigSignaturePackage;
    final @Nullable String mRecentsPackage;
    final @Nullable String mAmbientContextDetectionPackage;
    private final @NonNull String mRequiredSupplementalProcessPackage;

    @GuardedBy("mLock")
    private final PackageUsage mPackageUsage = new PackageUsage();
    final CompilerStats mCompilerStats = new CompilerStats();

    private final DomainVerificationConnection mDomainVerificationConnection;

    private final BroadcastHelper mBroadcastHelper;
    private final RemovePackageHelper mRemovePackageHelper;
    private final DeletePackageHelper mDeletePackageHelper;
    private final InitAndSystemPackageHelper mInitAndSystemPackageHelper;
    private final AppDataHelper mAppDataHelper;
    private final InstallPackageHelper mInstallPackageHelper;
    private final PreferredActivityHelper mPreferredActivityHelper;
    private final ResolveIntentHelper mResolveIntentHelper;
    private final DexOptHelper mDexOptHelper;
    private final SuspendPackageHelper mSuspendPackageHelper;

    /**
     * Invalidate the package info cache, which includes updating the cached computer.
     * @hide
     */
    public static void invalidatePackageInfoCache() {
        PackageManager.invalidatePackageInfoCache();
        onChanged();
    }

    private final Watcher mWatcher = new Watcher() {
            @Override
                       public void onChange(@Nullable Watchable what) {
                PackageManagerService.onChange(what);
            }
        };

    /**
     * A Snapshot is a subset of PackageManagerService state.  A snapshot is either live
     * or snapped.  Live snapshots directly reference PackageManagerService attributes.
     * Snapped snapshots contain deep copies of the attributes.
     */
    class Snapshot {
        public static final int LIVE = 1;
        public static final int SNAPPED = 2;

        public final Settings settings;
        public final WatchedSparseIntArray isolatedOwners;
        public final WatchedArrayMap<String, AndroidPackage> packages;
        public final WatchedArrayMap<ComponentName, ParsedInstrumentation> instrumentation;
        public final WatchedSparseBooleanArray webInstantAppsDisabled;
        public final ComponentName resolveComponentName;
        public final ActivityInfo resolveActivity;
        public final ActivityInfo instantAppInstallerActivity;
        public final ResolveInfo instantAppInstallerInfo;
        public final InstantAppRegistry instantAppRegistry;
        public final ApplicationInfo androidApplication;
        public final String appPredictionServicePackage;
        public final AppsFilter appsFilter;
        public final ComponentResolverApi componentResolver;
        public final PackageManagerService service;
        public final WatchedArrayMap<String, Integer> frozenPackages;
        public final SharedLibrariesRead sharedLibraries;

        Snapshot(int type) {
            if (type == Snapshot.SNAPPED) {
                settings = mSettings.snapshot();
                isolatedOwners = mIsolatedOwnersSnapshot.snapshot();
                packages = mPackagesSnapshot.snapshot();
                instrumentation = mInstrumentationSnapshot.snapshot();
                resolveComponentName = mResolveComponentName == null
                        ? null : mResolveComponentName.clone();
                resolveActivity = new ActivityInfo(mResolveActivity);
                instantAppInstallerActivity =
                        (mInstantAppInstallerActivity == null)
                        ? null
                        : new ActivityInfo(mInstantAppInstallerActivity);
                instantAppInstallerInfo = new ResolveInfo(mInstantAppInstallerInfo);
                webInstantAppsDisabled = mWebInstantAppsDisabled.snapshot();
                instantAppRegistry = mInstantAppRegistry.snapshot();
                androidApplication =
                        (mAndroidApplication == null)
                        ? null
                        : new ApplicationInfo(mAndroidApplication);
                appPredictionServicePackage = mAppPredictionServicePackage;
                appsFilter = mAppsFilter.snapshot();
                componentResolver = mComponentResolver.snapshot();
                frozenPackages = mFrozenPackagesSnapshot.snapshot();
                sharedLibraries = mSharedLibraries.snapshot();
            } else if (type == Snapshot.LIVE) {
                settings = mSettings;
                isolatedOwners = mIsolatedOwners;
                packages = mPackages;
                instrumentation = mInstrumentation;
                resolveComponentName = mResolveComponentName;
                resolveActivity = mResolveActivity;
                instantAppInstallerActivity = mInstantAppInstallerActivity;
                instantAppInstallerInfo = mInstantAppInstallerInfo;
                webInstantAppsDisabled = mWebInstantAppsDisabled;
                instantAppRegistry = mInstantAppRegistry;
                androidApplication = mAndroidApplication;
                appPredictionServicePackage = mAppPredictionServicePackage;
                appsFilter = mAppsFilter;
                componentResolver = mComponentResolver;
                frozenPackages = mFrozenPackages;
                sharedLibraries = mSharedLibraries;
            } else {
                throw new IllegalArgumentException();
            }
            service = PackageManagerService.this;
        }
    }

    // Compute read-only functions, based on live data.  This attribute may be modified multiple
    // times during the PackageManagerService constructor but it should not be modified thereafter.
    private ComputerLocked mLiveComputer;

    // A lock-free cache for frequently called functions.
    private volatile Computer mSnapshotComputer;

    // A trampoline that directs callers to either the live or snapshot computer.
    private final ComputerTracker mComputer = new ComputerTracker(this);

    // If true, the snapshot is invalid (stale).  The attribute is static since it may be
    // set from outside classes.  The attribute may be set to true anywhere, although it
    // should only be set true while holding mLock.  However, the attribute id guaranteed
    // to be set false only while mLock and mSnapshotLock are both held.
    private static final AtomicBoolean sSnapshotInvalid = new AtomicBoolean(true);

    static final ThreadLocal<ThreadComputer> sThreadComputer =
            ThreadLocal.withInitial(ThreadComputer::new);

    /**
     * This lock is used to make reads from {@link #sSnapshotInvalid} and
     * {@link #mSnapshotComputer} atomic inside {@code snapshotComputer()}.  This lock is
     * not meant to be used outside that method.  This lock must be taken before
     * {@link #mLock} is taken.
     */
    private final Object mSnapshotLock = new Object();

    /**
     * The snapshot statistics.  These are collected to track performance and to identify
     * situations in which the snapshots are misbehaving.
     */
    @Nullable
    private final SnapshotStatistics mSnapshotStatistics;

    /**
     * Return the cached computer.  The method will rebuild the cached computer if necessary.
     * The live computer will be returned if snapshots are disabled.
     */
    public Computer snapshotComputer() {
        if (Thread.holdsLock(mLock)) {
            // If the current thread holds mLock then it may have modified state but not
            // yet invalidated the snapshot.  Always give the thread the live computer.
            return mLiveComputer;
        }
        synchronized (mSnapshotLock) {
            // This synchronization block serializes access to the snapshot computer and
            // to the code that samples mSnapshotInvalid.
            Computer c = mSnapshotComputer;
            if (sSnapshotInvalid.getAndSet(false) || (c == null)) {
                // The snapshot is invalid if it is marked as invalid or if it is null.  If it
                // is null, then it is currently being rebuilt by rebuildSnapshot().
                synchronized (mLock) {
                    // Rebuild the snapshot if it is invalid.  Note that the snapshot might be
                    // invalidated as it is rebuilt.  However, the snapshot is still
                    // self-consistent (the lock is being held) and is current as of the time
                    // this function is entered.
                    rebuildSnapshot();

                    // Guaranteed to be non-null.  mSnapshotComputer is only be set to null
                    // temporarily in rebuildSnapshot(), which is guarded by mLock().  Since
                    // the mLock is held in this block and since rebuildSnapshot() is
                    // complete, the attribute can not now be null.
                    c = mSnapshotComputer;
                }
            }
            c.use();
            return c;
        }
    }

    /**
     * Rebuild the cached computer.  mSnapshotComputer is temporarily set to null to block other
     * threads from using the invalid computer until it is rebuilt.
     */
    @GuardedBy({ "mLock", "mSnapshotLock"})
    private void rebuildSnapshot() {
        final long now = SystemClock.currentTimeMicro();
        final int hits = mSnapshotComputer == null ? -1 : mSnapshotComputer.getUsed();
        mSnapshotComputer = null;
        final Snapshot args = new Snapshot(Snapshot.SNAPPED);
        mSnapshotComputer = new ComputerEngine(args);
        final long done = SystemClock.currentTimeMicro();

        if (mSnapshotStatistics != null) {
            mSnapshotStatistics.rebuild(now, done, hits);
        }
    }

    /**
     * Create a live computer
     */
    private ComputerLocked createLiveComputer() {
        return new ComputerLocked(new Snapshot(Snapshot.LIVE));
    }

    /**
     * This method is called when the state of PackageManagerService changes so as to
     * invalidate the current snapshot.
     * @param what The {@link Watchable} that reported the change
     * @hide
     */
    public static void onChange(@Nullable Watchable what) {
        if (TRACE_SNAPSHOTS) {
            Log.i(TAG, "snapshot: onChange(" + what + ")");
        }
        sSnapshotInvalid.set(true);
    }

    /**
     * Report a locally-detected change to observers.  The <what> parameter is left null,
     * but it signifies that the change was detected by PackageManagerService itself.
     */
    static void onChanged() {
        onChange(null);
    }

    @Override
    public void notifyPackagesReplacedReceived(String[] packages) {
        Computer computer = snapshotComputer();
        ArraySet<String> packagesToNotify = computer.getNotifyPackagesForReplacedReceived(packages);
        for (int index = 0; index < packagesToNotify.size(); index++) {
            notifyInstallObserver(packagesToNotify.valueAt(index), false /* killApp */);
        }
    }

    void notifyInstallObserver(String packageName, boolean killApp) {
        final Pair<PackageInstalledInfo, IPackageInstallObserver2> pair =
                killApp ? mPendingKillInstallObservers.remove(packageName)
                        : mNoKillInstallObservers.remove(packageName);

        if (pair != null) {
            notifyInstallObserver(pair.first, pair.second);
        }
    }

    void notifyInstallObserver(PackageInstalledInfo info,
            IPackageInstallObserver2 installObserver) {
        if (installObserver != null) {
            try {
                Bundle extras = extrasForInstallResult(info);
                installObserver.onPackageInstalled(info.mName, info.mReturnCode,
                        info.mReturnMsg, extras);
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
    }

    void scheduleDeferredNoKillInstallObserver(PackageInstalledInfo info,
            IPackageInstallObserver2 observer) {
        String packageName = info.mPkg.getPackageName();
        mNoKillInstallObservers.put(packageName, Pair.create(info, observer));
        Message message = mHandler.obtainMessage(DEFERRED_NO_KILL_INSTALL_OBSERVER, packageName);
        mHandler.sendMessageDelayed(message, DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS);
    }

    void scheduleDeferredNoKillPostDelete(InstallArgs args) {
        Message message = mHandler.obtainMessage(DEFERRED_NO_KILL_POST_DELETE, args);
        mHandler.sendMessageDelayed(message, DEFERRED_NO_KILL_POST_DELETE_DELAY_MS);
    }

    void schedulePruneUnusedStaticSharedLibraries(boolean delay) {
        mHandler.removeMessages(PRUNE_UNUSED_STATIC_SHARED_LIBRARIES);
        mHandler.sendEmptyMessageDelayed(PRUNE_UNUSED_STATIC_SHARED_LIBRARIES,
                delay ? getPruneUnusedSharedLibrariesDelay() : 0);
    }

    void scheduleDeferredPendingKillInstallObserver(PackageInstalledInfo info,
            IPackageInstallObserver2 observer) {
        final String packageName = info.mPkg.getPackageName();
        mPendingKillInstallObservers.put(packageName, Pair.create(info, observer));
        final Message message = mHandler.obtainMessage(DEFERRED_PENDING_KILL_INSTALL_OBSERVER,
                packageName);
        mHandler.sendMessageDelayed(message, DEFERRED_PENDING_KILL_INSTALL_OBSERVER_DELAY_MS);
    }

    private static long getPruneUnusedSharedLibrariesDelay() {
        return SystemProperties.getLong("debug.pm.prune_unused_shared_libraries_delay",
                PRUNE_UNUSED_SHARED_LIBRARIES_DELAY);
    }

    @Override
    public void requestPackageChecksums(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId) {
        requestChecksumsInternal(packageName, includeSplits, optional, required, trustedInstallers,
                onChecksumsReadyListener, userId, mInjector.getBackgroundExecutor(),
                mInjector.getBackgroundHandler());
    }

    /**
     * Requests checksums for the APK file.
     * See {@link PackageInstaller.Session#requestChecksums} for details.
     */
    public void requestFileChecksums(@NonNull File file,
            @NonNull String installerPackageName, @Checksum.TypeMask int optional,
            @Checksum.TypeMask int required, @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener)
            throws FileNotFoundException {
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        final Executor executor = mInjector.getBackgroundExecutor();
        final Handler handler = mInjector.getBackgroundHandler();
        final Certificate[] trustedCerts = (trustedInstallers != null) ? decodeCertificates(
                trustedInstallers) : null;

        final List<Pair<String, File>> filesToChecksum = new ArrayList<>(1);
        filesToChecksum.add(Pair.create(null, file));

        executor.execute(() -> {
            ApkChecksums.Injector injector = new ApkChecksums.Injector(
                    () -> mContext,
                    () -> handler,
                    () -> mInjector.getIncrementalManager(),
                    () -> mPmInternal);
            ApkChecksums.getChecksums(filesToChecksum, optional, required, installerPackageName,
                    trustedCerts, onChecksumsReadyListener, injector);
        });
    }

    private void requestChecksumsInternal(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
            @NonNull Executor executor, @NonNull Handler handler) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(onChecksumsReadyListener);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(handler);

        final ApplicationInfo applicationInfo = getApplicationInfoInternal(packageName, 0,
                Binder.getCallingUid(), userId);
        if (applicationInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }
        final InstallSourceInfo installSourceInfo = getInstallSourceInfo(packageName);
        final String installerPackageName =
                installSourceInfo != null ? installSourceInfo.getInitiatingPackageName() : null;

        List<Pair<String, File>> filesToChecksum = new ArrayList<>();

        // Adding base split.
        filesToChecksum.add(Pair.create(null, new File(applicationInfo.sourceDir)));

        // Adding other splits.
        if (includeSplits && applicationInfo.splitNames != null) {
            for (int i = 0, size = applicationInfo.splitNames.length; i < size; ++i) {
                filesToChecksum.add(Pair.create(applicationInfo.splitNames[i],
                        new File(applicationInfo.splitSourceDirs[i])));
            }
        }

        final Certificate[] trustedCerts = (trustedInstallers != null) ? decodeCertificates(
                trustedInstallers) : null;

        executor.execute(() -> {
            ApkChecksums.Injector injector = new ApkChecksums.Injector(
                    () -> mContext,
                    () -> handler,
                    () -> mInjector.getIncrementalManager(),
                    () -> mPmInternal);
            ApkChecksums.getChecksums(filesToChecksum, optional, required, installerPackageName,
                    trustedCerts, onChecksumsReadyListener, injector);
        });
    }

    private static @NonNull Certificate[] decodeCertificates(@NonNull List certs) {
        try {
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final Certificate[] result = new Certificate[certs.size()];
            for (int i = 0, size = certs.size(); i < size; ++i) {
                final InputStream is = new ByteArrayInputStream((byte[]) certs.get(i));
                final X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                result[i] = cert;
            }
            return result;
        } catch (CertificateException e) {
            throw ExceptionUtils.propagate(e);
        }
    }

    private static Bundle extrasForInstallResult(PackageInstalledInfo res) {
        Bundle extras = null;
        switch (res.mReturnCode) {
            case PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION: {
                extras = new Bundle();
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION,
                        res.mOrigPermission);
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE,
                        res.mOrigPackage);
                break;
            }
            case PackageManager.INSTALL_SUCCEEDED: {
                extras = new Bundle();
                extras.putBoolean(Intent.EXTRA_REPLACING,
                        res.mRemovedInfo != null && res.mRemovedInfo.mRemovedPackage != null);
                break;
            }
        }
        return extras;
    }

    void scheduleWriteSettings() {
        // We normally invalidate when we write settings, but in cases where we delay and
        // coalesce settings writes, this strategy would have us invalidate the cache too late.
        // Invalidating on schedule addresses this problem.
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }

    private void scheduleWritePackageListLocked(int userId) {
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_PACKAGE_LIST)) {
            Message msg = mHandler.obtainMessage(WRITE_PACKAGE_LIST);
            msg.arg1 = userId;
            mHandler.sendMessageDelayed(msg, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageRestrictions(UserHandle user) {
        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();
        scheduleWritePackageRestrictions(userId);
    }

    void scheduleWritePackageRestrictions(int userId) {
        invalidatePackageInfoCache();
        if (userId == UserHandle.USER_ALL) {
            synchronized (mDirtyUsers) {
                for (int aUserId : mUserManager.getUserIds()) {
                    mDirtyUsers.add(aUserId);
                }
            }
        } else {
            if (!mUserManager.exists(userId)) {
                return;
            }
            synchronized (mDirtyUsers) {
                mDirtyUsers.add(userId);
            }
        }
        if (!mHandler.hasMessages(WRITE_PACKAGE_RESTRICTIONS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_PACKAGE_RESTRICTIONS, WRITE_SETTINGS_DELAY);
        }
    }

    void writePendingRestrictions() {
        synchronized (mLock) {
            mHandler.removeMessages(WRITE_PACKAGE_RESTRICTIONS);
            synchronized (mDirtyUsers) {
                for (int userId : mDirtyUsers) {
                    mSettings.writePackageRestrictionsLPr(userId);
                }
                mDirtyUsers.clear();
            }
        }
    }

    void writeSettings() {
        synchronized (mLock) {
            mHandler.removeMessages(WRITE_SETTINGS);
            mHandler.removeMessages(WRITE_PACKAGE_RESTRICTIONS);
            writeSettingsLPrTEMP();
            synchronized (mDirtyUsers) {
                mDirtyUsers.clear();
            }
        }
    }

    void writePackageList(int userId) {
        synchronized (mLock) {
            mHandler.removeMessages(WRITE_PACKAGE_LIST);
            mSettings.writePackageListLPr(userId);
        }
    }

    public static PackageManagerService main(Context context, Installer installer,
            @NonNull DomainVerificationService domainVerificationService, boolean factoryTest,
            boolean onlyCore) {
        // Self-check for initial settings.
        PackageManagerServiceCompilerMapping.checkProperties();
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("create package manager");
        final PackageManagerTracedLock lock = new PackageManagerTracedLock();
        final Object installLock = new Object();
        HandlerThread backgroundThread = new HandlerThread("PackageManagerBg");
        backgroundThread.start();
        Handler backgroundHandler = new Handler(backgroundThread.getLooper());

        PackageManagerServiceInjector injector = new PackageManagerServiceInjector(
                context, lock, installer, installLock, new PackageAbiHelperImpl(),
                backgroundHandler,
                SYSTEM_PARTITIONS,
                (i, pm) -> new ComponentResolver(i.getUserManagerService(), pm.mUserNeedsBadging),
                (i, pm) -> PermissionManagerService.create(context,
                        i.getSystemConfig().getAvailableFeatures()),
                (i, pm) -> new UserManagerService(context, pm,
                        new UserDataPreparer(installer, installLock, context, onlyCore),
                        lock),
                (i, pm) -> new Settings(Environment.getDataDirectory(),
                        RuntimePermissionsPersistence.createInstance(),
                        i.getPermissionManagerServiceInternal(),
                        domainVerificationService, lock),
                (i, pm) -> AppsFilter.create(pm.mPmInternal, i),
                (i, pm) -> (PlatformCompat) ServiceManager.getService("platform_compat"),
                (i, pm) -> SystemConfig.getInstance(),
                (i, pm) -> new PackageDexOptimizer(i.getInstaller(), i.getInstallLock(),
                        i.getContext(), "*dexopt*"),
                (i, pm) -> new DexManager(i.getContext(), pm, i.getPackageDexOptimizer(),
                        i.getInstaller(), i.getInstallLock()),
                (i, pm) -> new ArtManagerService(i.getContext(), pm, i.getInstaller(),
                        i.getInstallLock()),
                (i, pm) -> ApexManager.getInstance(),
                (i, pm) -> new ViewCompiler(i.getInstallLock(), i.getInstaller()),
                (i, pm) -> (IncrementalManager)
                        i.getContext().getSystemService(Context.INCREMENTAL_SERVICE),
                (i, pm) -> new DefaultAppProvider(() -> context.getSystemService(RoleManager.class),
                        () -> LocalServices.getService(UserManagerInternal.class)),
                (i, pm) -> new DisplayMetrics(),
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, pm.mOnlyCore,
                        i.getDisplayMetrics(), pm.mCacheDir,
                        pm.mPackageParserCallback) /* scanningCachingPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, pm.mOnlyCore,
                        i.getDisplayMetrics(), null,
                        pm.mPackageParserCallback) /* scanningPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, false, i.getDisplayMetrics(),
                        null, pm.mPackageParserCallback) /* preparingPackageParserProducer */,
                // Prepare a supplier of package parser for the staging manager to parse apex file
                // during the staging installation.
                (i, pm) -> new PackageInstallerService(
                        i.getContext(), pm, i::getScanningPackageParser),
                (i, pm, cn) -> new InstantAppResolverConnection(
                        i.getContext(), cn, Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE),
                (i, pm) -> new ModuleInfoProvider(i.getContext(), pm),
                (i, pm) -> LegacyPermissionManagerService.create(i.getContext()),
                (i, pm) -> domainVerificationService,
                (i, pm) -> {
                    HandlerThread thread = new ServiceThread(TAG,
                            Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
                    thread.start();
                    return new PackageHandler(thread.getLooper(), pm);
                },
                new DefaultSystemWrapper(),
                LocalServices::getService,
                context::getSystemService,
                (i, pm) -> new BackgroundDexOptService(i.getContext(), i.getDexManager(), pm),
                (i, pm) -> IBackupManager.Stub.asInterface(ServiceManager.getService(
                        Context.BACKUP_SERVICE)),
                (i, pm) -> new SharedLibrariesImpl(pm, i));

        if (Build.VERSION.SDK_INT <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        PackageManagerService m = new PackageManagerService(injector, onlyCore, factoryTest,
                PackagePartitions.FINGERPRINT, Build.IS_ENG, Build.IS_USERDEBUG,
                Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL);
        t.traceEnd(); // "create package manager"

        final CompatChange.ChangeListener selinuxChangeListener = packageName -> {
            synchronized (m.mInstallLock) {
                final PackageStateInternal packageState = m.getPackageStateInternal(packageName);
                if (packageState == null) {
                    Slog.e(TAG, "Failed to find package setting " + packageName);
                    return;
                }
                AndroidPackage pkg = packageState.getPkg();
                SharedUserApi sharedUser = m.mComputer.getSharedUser(
                        packageState.getSharedUserAppId());
                String oldSeInfo = AndroidPackageUtils.getSeInfo(pkg, packageState);

                if (pkg == null) {
                    Slog.e(TAG, "Failed to find package " + packageName);
                    return;
                }
                final String newSeInfo = SELinuxMMAC.getSeInfo(pkg, sharedUser,
                        m.mInjector.getCompatibility());

                if (!newSeInfo.equals(oldSeInfo)) {
                    Slog.i(TAG, "Updating seInfo for package " + packageName + " from: "
                            + oldSeInfo + " to: " + newSeInfo);
                    m.commitPackageStateMutation(null, packageName,
                            state -> state.setOverrideSeInfo(newSeInfo));
                    m.mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
                }
            }
        };

        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_LATEST_CHANGES,
                selinuxChangeListener);
        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_R_CHANGES,
                selinuxChangeListener);

        m.installAllowlistedSystemPackages();
        ServiceManager.addService("package", m);
        final PackageManagerNative pmn = new PackageManagerNative(m);
        ServiceManager.addService("package_native", pmn);
        return m;
    }

    /** Install/uninstall system packages for all users based on their user-type, as applicable. */
    private void installAllowlistedSystemPackages() {
        if (mUserManager.installWhitelistedSystemPackages(isFirstBoot(), isDeviceUpgrading(),
                mExistingPackages)) {
            scheduleWritePackageRestrictions(UserHandle.USER_ALL);
            scheduleWriteSettings();
        }
    }

    // Link watchables to the class
    private void registerObservers(boolean verify) {
        // Null check to handle nullable test parameters
        if (mPackages != null) {
            mPackages.registerObserver(mWatcher);
        }
        if (mSharedLibraries != null) {
            mSharedLibraries.registerObserver(mWatcher);
        }
        if (mInstrumentation != null) {
            mInstrumentation.registerObserver(mWatcher);
        }
        if (mWebInstantAppsDisabled != null) {
            mWebInstantAppsDisabled.registerObserver(mWatcher);
        }
        if (mAppsFilter != null) {
            mAppsFilter.registerObserver(mWatcher);
        }
        if (mInstantAppRegistry != null) {
            mInstantAppRegistry.registerObserver(mWatcher);
        }
        if (mSettings != null) {
            mSettings.registerObserver(mWatcher);
        }
        if (mIsolatedOwners != null) {
            mIsolatedOwners.registerObserver(mWatcher);
        }
        if (mComponentResolver != null) {
            mComponentResolver.registerObserver(mWatcher);
        }
        if (mFrozenPackages != null) {
            mFrozenPackages.registerObserver(mWatcher);
        }
        if (verify) {
            // If neither "build" attribute is true then this may be a mockito test,
            // and verification can fail as a false positive.
            Watchable.verifyWatchedAttributes(this, mWatcher, !(mIsEngBuild || mIsUserDebugBuild));
        }
    }

    /**
     * A extremely minimal constructor designed to start up a PackageManagerService instance for
     * testing.
     *
     * It is assumed that all methods under test will mock the internal fields and thus
     * none of the initialization is needed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public PackageManagerService(@NonNull PackageManagerServiceInjector injector,
            @NonNull PackageManagerServiceTestParams testParams) {
        mInjector = injector;
        mInjector.bootstrap(this);
        mAppsFilter = injector.getAppsFilter();
        mComponentResolver = injector.getComponentResolver();
        mContext = injector.getContext();
        mInstaller = injector.getInstaller();
        mInstallLock = injector.getInstallLock();
        mLock = injector.getLock();
        mPackageStateWriteLock = mLock;
        mPermissionManager = injector.getPermissionManagerServiceInternal();
        mSettings = injector.getSettings();
        mUserManager = injector.getUserManagerService();
        mUserNeedsBadging = new UserNeedsBadgingCache(mUserManager);
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mHandler = injector.getHandler();
        mSharedLibraries = injector.getSharedLibrariesImpl();

        mApexManager = testParams.apexManager;
        mArtManagerService = testParams.artManagerService;
        mAvailableFeatures = testParams.availableFeatures;
        mBackgroundDexOptService = testParams.backgroundDexOptService;
        mDefParseFlags = testParams.defParseFlags;
        mDefaultAppProvider = testParams.defaultAppProvider;
        mLegacyPermissionManager = testParams.legacyPermissionManagerInternal;
        mDexManager = testParams.dexManager;
        mFactoryTest = testParams.factoryTest;
        mIncrementalManager = testParams.incrementalManager;
        mInstallerService = testParams.installerService;
        mInstantAppRegistry = testParams.instantAppRegistry;
        mChangedPackagesTracker = testParams.changedPackagesTracker;
        mInstantAppResolverConnection = testParams.instantAppResolverConnection;
        mInstantAppResolverSettingsComponent = testParams.instantAppResolverSettingsComponent;
        mIsPreNMR1Upgrade = testParams.isPreNmr1Upgrade;
        mIsPreNUpgrade = testParams.isPreNupgrade;
        mIsPreQUpgrade = testParams.isPreQupgrade;
        mIsUpgrade = testParams.isUpgrade;
        mMetrics = testParams.Metrics;
        mModuleInfoProvider = testParams.moduleInfoProvider;
        mMoveCallbacks = testParams.moveCallbacks;
        mOnlyCore = testParams.onlyCore;
        mOverlayConfig = testParams.overlayConfig;
        mPackageDexOptimizer = testParams.packageDexOptimizer;
        mPackageParserCallback = testParams.packageParserCallback;
        mPendingBroadcasts = testParams.pendingPackageBroadcasts;
        mPmInternal = testParams.pmInternal;
        mTestUtilityService = testParams.testUtilityService;
        mProcessLoggingHandler = testParams.processLoggingHandler;
        mProtectedPackages = testParams.protectedPackages;
        mSeparateProcesses = testParams.separateProcesses;
        mViewCompiler = testParams.viewCompiler;
        mRequiredVerifierPackage = testParams.requiredVerifierPackage;
        mRequiredInstallerPackage = testParams.requiredInstallerPackage;
        mRequiredUninstallerPackage = testParams.requiredUninstallerPackage;
        mRequiredPermissionControllerPackage = testParams.requiredPermissionControllerPackage;
        mSetupWizardPackage = testParams.setupWizardPackage;
        mStorageManagerPackage = testParams.storageManagerPackage;
        mDefaultTextClassifierPackage = testParams.defaultTextClassifierPackage;
        mSystemTextClassifierPackageName = testParams.systemTextClassifierPackage;
        mRetailDemoPackage = testParams.retailDemoPackage;
        mRecentsPackage = testParams.recentsPackage;
        mAmbientContextDetectionPackage = testParams.ambientContextDetectionPackage;
        mConfiguratorPackage = testParams.configuratorPackage;
        mAppPredictionServicePackage = testParams.appPredictionServicePackage;
        mIncidentReportApproverPackage = testParams.incidentReportApproverPackage;
        mServicesExtensionPackageName = testParams.servicesExtensionPackageName;
        mSharedSystemSharedLibraryPackageName = testParams.sharedSystemSharedLibraryPackageName;
        mOverlayConfigSignaturePackage = testParams.overlayConfigSignaturePackage;
        mResolveComponentName = testParams.resolveComponentName;
        mRequiredSupplementalProcessPackage = testParams.requiredSupplementalProcessPackage;

        mLiveComputer = createLiveComputer();
        mSnapshotComputer = null;
        mSnapshotStatistics = null;

        mPackages.putAll(testParams.packages);
        mEnableFreeCacheV2 = testParams.enableFreeCacheV2;
        mSdkVersion = testParams.sdkVersion;
        mAppInstallDir = testParams.appInstallDir;
        mIsEngBuild = testParams.isEngBuild;
        mIsUserDebugBuild = testParams.isUserDebugBuild;
        mIncrementalVersion = testParams.incrementalVersion;
        mDomainVerificationConnection = new DomainVerificationConnection(this);

        mBroadcastHelper = testParams.broadcastHelper;
        mAppDataHelper = testParams.appDataHelper;
        mInstallPackageHelper = testParams.installPackageHelper;
        mRemovePackageHelper = testParams.removePackageHelper;
        mInitAndSystemPackageHelper = testParams.initAndSystemPackageHelper;
        mDeletePackageHelper = testParams.deletePackageHelper;
        mPreferredActivityHelper = testParams.preferredActivityHelper;
        mResolveIntentHelper = testParams.resolveIntentHelper;
        mDexOptHelper = testParams.dexOptHelper;
        mSuspendPackageHelper = testParams.suspendPackageHelper;

        mSharedLibraries.setDeletePackageHelper(mDeletePackageHelper);

        registerObservers(false);
        invalidatePackageInfoCache();
    }

    public PackageManagerService(PackageManagerServiceInjector injector, boolean onlyCore,
            boolean factoryTest, final String buildFingerprint, final boolean isEngBuild,
            final boolean isUserDebugBuild, final int sdkVersion, final String incrementalVersion) {
        mIsEngBuild = isEngBuild;
        mIsUserDebugBuild = isUserDebugBuild;
        mSdkVersion = sdkVersion;
        mIncrementalVersion = incrementalVersion;
        mInjector = injector;
        mInjector.getSystemWrapper().disablePackageCaches();

        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        mPendingBroadcasts = new PendingPackageBroadcasts();

        mInjector.bootstrap(this);
        mLock = injector.getLock();
        mPackageStateWriteLock = mLock;
        mInstallLock = injector.getInstallLock();
        LockGuard.installLock(mLock, LockGuard.INDEX_PACKAGES);
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        mContext = injector.getContext();
        mFactoryTest = factoryTest;
        mOnlyCore = onlyCore;
        mMetrics = injector.getDisplayMetrics();
        mInstaller = injector.getInstaller();
        mEnableFreeCacheV2 = SystemProperties.getBoolean("fw.free_cache_v2", true);

        // Create sub-components that provide services / data. Order here is important.
        t.traceBegin("createSubComponents");

        // Expose private service for system components to use.
        mPmInternal = new PackageManagerInternalImpl();
        LocalServices.addService(TestUtilityService.class, this);
        mTestUtilityService = LocalServices.getService(TestUtilityService.class);
        LocalServices.addService(PackageManagerInternal.class, mPmInternal);
        mUserManager = injector.getUserManagerService();
        mUserNeedsBadging = new UserNeedsBadgingCache(mUserManager);
        mComponentResolver = injector.getComponentResolver();
        mPermissionManager = injector.getPermissionManagerServiceInternal();
        mSettings = injector.getSettings();
        mIncrementalManager = mInjector.getIncrementalManager();
        mDefaultAppProvider = mInjector.getDefaultAppProvider();
        mLegacyPermissionManager = mInjector.getLegacyPermissionManagerInternal();
        PlatformCompat platformCompat = mInjector.getCompatibility();
        mPackageParserCallback = new PackageParser2.Callback() {
            @Override
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                return platformCompat.isChangeEnabled(changeId, appInfo);
            }

            @Override
            public boolean hasFeature(String feature) {
                return PackageManagerService.this.hasSystemFeature(feature, 0);
            }
        };

        // CHECKSTYLE:ON IndentationCheck
        t.traceEnd();

        t.traceBegin("addSharedUsers");
        mSettings.addSharedUserLPw("android.uid.system", Process.SYSTEM_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.phone", RADIO_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.log", LOG_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.nfc", NFC_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.se", SE_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.networkstack", NETWORKSTACK_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.uwb", UWB_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        t.traceEnd();

        String separateProcesses = SystemProperties.get("debug.separate_processes");

        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = ParsingPackageUtils.PARSE_IGNORE_PROCESSES;
                mSeparateProcesses = null;
                Slog.w(TAG, "Running with debug.separate_processes: * (ALL)");
            } else {
                mDefParseFlags = 0;
                mSeparateProcesses = separateProcesses.split(",");
                Slog.w(TAG, "Running with debug.separate_processes: "
                        + separateProcesses);
            }
        } else {
            mDefParseFlags = 0;
            mSeparateProcesses = null;
        }

        mPackageDexOptimizer = injector.getPackageDexOptimizer();
        mDexManager = injector.getDexManager();
        mBackgroundDexOptService = injector.getBackgroundDexOptService();
        mArtManagerService = injector.getArtManagerService();
        mMoveCallbacks = new MovePackageHelper.MoveCallbacks(FgThread.get().getLooper());
        mViewCompiler = injector.getViewCompiler();
        mSharedLibraries = mInjector.getSharedLibrariesImpl();

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getMetrics(mMetrics);

        t.traceBegin("get system config");
        SystemConfig systemConfig = injector.getSystemConfig();
        mAvailableFeatures = systemConfig.getAvailableFeatures();
        t.traceEnd();

        mProtectedPackages = new ProtectedPackages(mContext);

        mApexManager = injector.getApexManager();
        mAppsFilter = mInjector.getAppsFilter();

        mInstantAppRegistry = new InstantAppRegistry(mContext, mPermissionManager,
                mInjector.getUserManagerInternal(), new DeletePackageHelper(this));

        mChangedPackagesTracker = new ChangedPackagesTracker();

        mAppInstallDir = new File(Environment.getDataDirectory(), "app");

        mDomainVerificationConnection = new DomainVerificationConnection(this);
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mDomainVerificationManager.setConnection(mDomainVerificationConnection);

        mBroadcastHelper = new BroadcastHelper(mInjector);
        mAppDataHelper = new AppDataHelper(this);
        mInstallPackageHelper = new InstallPackageHelper(this, mAppDataHelper);
        mRemovePackageHelper = new RemovePackageHelper(this, mAppDataHelper);
        mInitAndSystemPackageHelper = new InitAndSystemPackageHelper(this);
        mDeletePackageHelper = new DeletePackageHelper(this, mRemovePackageHelper,
                mAppDataHelper);
        mSharedLibraries.setDeletePackageHelper(mDeletePackageHelper);
        mPreferredActivityHelper = new PreferredActivityHelper(this);
        mResolveIntentHelper = new ResolveIntentHelper(mContext, mPreferredActivityHelper,
                injector.getCompatibility(), mUserManager, mDomainVerificationManager,
                mUserNeedsBadging, () -> mResolveInfo, () -> mInstantAppInstallerActivity);
        mDexOptHelper = new DexOptHelper(this);
        mSuspendPackageHelper = new SuspendPackageHelper(this, mInjector, mBroadcastHelper,
                mProtectedPackages);

        synchronized (mLock) {
            // Create the computer as soon as the state objects have been installed.  The
            // cached computer is the same as the live computer until the end of the
            // constructor, at which time the invalidation method updates it.
            mSnapshotStatistics = new SnapshotStatistics();
            sSnapshotInvalid.set(true);
            mLiveComputer = createLiveComputer();
            mSnapshotComputer = null;
            registerObservers(true);
        }

        Computer computer = mLiveComputer;
        // CHECKSTYLE:OFF IndentationCheck
        synchronized (mInstallLock) {
        // writer
        synchronized (mLock) {
            mHandler = injector.getHandler();
            mProcessLoggingHandler = new ProcessLoggingHandler();
            Watchdog.getInstance().addThread(mHandler, WATCHDOG_TIMEOUT);

            ArrayMap<String, SystemConfig.SharedLibraryEntry> libConfig
                    = systemConfig.getSharedLibraries();
            final int builtInLibCount = libConfig.size();
            for (int i = 0; i < builtInLibCount; i++) {
                mSharedLibraries.addBuiltInSharedLibraryLPw(libConfig.valueAt(i));
            }

            // Now that we have added all the libraries, iterate again to add dependency
            // information IFF their dependencies are added.
            long undefinedVersion = SharedLibraryInfo.VERSION_UNDEFINED;
            for (int i = 0; i < builtInLibCount; i++) {
                String name = libConfig.keyAt(i);
                SystemConfig.SharedLibraryEntry entry = libConfig.valueAt(i);
                final int dependencyCount = entry.dependencies.length;
                for (int j = 0; j < dependencyCount; j++) {
                    final SharedLibraryInfo dependency =
                        getSharedLibraryInfo(entry.dependencies[j], undefinedVersion);
                    if (dependency != null) {
                        getSharedLibraryInfo(name, undefinedVersion).addDependency(dependency);
                    }
                }
            }

            SELinuxMMAC.readInstallPolicy();

            t.traceBegin("loadFallbacks");
            FallbackCategoryProvider.loadFallbacks();
            t.traceEnd();

            t.traceBegin("read user settings");
            mFirstBoot = !mSettings.readLPw(mInjector.getUserManagerInternal().getUsers(
                    /* excludePartial= */ true,
                    /* excludeDying= */ false,
                    /* excludePreCreated= */ false));
            t.traceEnd();

            mPermissionManager.readLegacyPermissionsTEMP(mSettings.mPermissions);
            mPermissionManager.readLegacyPermissionStateTEMP();

            if (!mOnlyCore && mFirstBoot) {
                DexOptHelper.requestCopyPreoptedFiles();
            }

            String customResolverActivityName = Resources.getSystem().getString(
                    R.string.config_customResolverActivity);
            if (!TextUtils.isEmpty(customResolverActivityName)) {
                mCustomResolverComponentName = ComponentName.unflattenFromString(
                        customResolverActivityName);
            }

            long startTime = SystemClock.uptimeMillis();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);

            final String bootClassPath = System.getenv("BOOTCLASSPATH");
            final String systemServerClassPath = System.getenv("SYSTEMSERVERCLASSPATH");

            if (bootClassPath == null) {
                Slog.w(TAG, "No BOOTCLASSPATH found!");
            }

            if (systemServerClassPath == null) {
                Slog.w(TAG, "No SYSTEMSERVERCLASSPATH found!");
            }

            final VersionInfo ver = mSettings.getInternalVersion();
            mIsUpgrade =
                    !buildFingerprint.equals(ver.fingerprint);
            if (mIsUpgrade) {
                PackageManagerServiceUtils.logCriticalInfo(Log.INFO, "Upgrading from "
                        + ver.fingerprint + " to " + PackagePartitions.FINGERPRINT);
            }

            // when upgrading from pre-M, promote system app permissions from install to runtime
            mPromoteSystemApps =
                    mIsUpgrade && ver.sdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1;

            // When upgrading from pre-N, we need to handle package extraction like first boot,
            // as there is no profiling data available.
            mIsPreNUpgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.N;

            mIsPreNMR1Upgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.N_MR1;
            mIsPreQUpgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.Q;

            final WatchedArrayMap<String, PackageSetting> packageSettings =
                mSettings.getPackagesLocked();

            // Save the names of pre-existing packages prior to scanning, so we can determine
            // which system packages are completely new due to an upgrade.
            if (isDeviceUpgrading()) {
                mExistingPackages = new ArraySet<>(packageSettings.size());
                for (PackageSetting ps : packageSettings.values()) {
                    mExistingPackages.add(ps.getPackageName());
                }
            }

            mCacheDir = PackageManagerServiceUtils.preparePackageParserCache(
                    mIsEngBuild, mIsUserDebugBuild, mIncrementalVersion);

            final int[] userIds = mUserManager.getUserIds();
            mOverlayConfig = mInitAndSystemPackageHelper.initPackages(packageSettings,
                    userIds, startTime);

            // Resolve the storage manager.
            mStorageManagerPackage = getStorageManagerPackageName(computer);

            // Resolve protected action filters. Only the setup wizard is allowed to
            // have a high priority filter for these actions.
            mSetupWizardPackage = getSetupWizardPackageNameImpl(computer);
            mComponentResolver.fixProtectedFilterPriorities(mPmInternal.getSetupWizardPackageName());

            mDefaultTextClassifierPackage = getDefaultTextClassifierPackageName();
            mSystemTextClassifierPackageName = getSystemTextClassifierPackageName();
            mConfiguratorPackage = getDeviceConfiguratorPackageName();
            mAppPredictionServicePackage = getAppPredictionServicePackageName();
            mIncidentReportApproverPackage = getIncidentReportApproverPackageName();
            mRetailDemoPackage = getRetailDemoPackageName();
            mOverlayConfigSignaturePackage = getOverlayConfigSignaturePackageName();
            mRecentsPackage = getRecentsPackageName();
            mAmbientContextDetectionPackage = getAmbientContextDetectionPackageName();

            // Now that we know all of the shared libraries, update all clients to have
            // the correct library paths.
            mSharedLibraries.updateAllSharedLibrariesLPw(
                    null, null, Collections.unmodifiableMap(mPackages));

            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                // NOTE: We ignore potential failures here during a system scan (like
                // the rest of the commands above) because there's precious little we
                // can do about it. A settings error is reported, though.
                final List<String> changedAbiCodePath =
                        ScanPackageUtils.applyAdjustedAbiToSharedUser(
                                setting, null /*scannedPackage*/,
                                mInjector.getAbiHelper().getAdjustedAbiForSharedUser(
                                        setting.getPackageStates(), null /*scannedPackage*/));
                if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
                    for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                        final String codePathString = changedAbiCodePath.get(i);
                        try {
                            mInstaller.rmdex(codePathString,
                                    getDexCodeInstructionSet(getPreferredInstructionSet()));
                        } catch (InstallerException ignored) {
                        }
                    }
                }
                // Adjust seInfo to ensure apps which share a sharedUserId are placed in the same
                // SELinux domain.
                setting.fixSeInfoLocked();
                setting.updateProcesses();
            }

            // Now that we know all the packages we are keeping,
            // read and update their last usage times.
            mPackageUsage.read(packageSettings);
            mCompilerStats.read();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Slog.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis() - startTime) / 1000f)
                    + " seconds");

            // If the build fingerprint has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear.  This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow...  it would be nice to have some better way to handle
            // this situation.
            if (mIsUpgrade) {
                Slog.i(TAG, "Build fingerprint changed from " + ver.fingerprint + " to "
                        + PackagePartitions.FINGERPRINT
                        + "; regranting permissions for internal storage");
            }
            mPermissionManager.onStorageVolumeMounted(
                    StorageManager.UUID_PRIVATE_INTERNAL, mIsUpgrade);
            ver.sdkVersion = mSdkVersion;

            // If this is the first boot or an update from pre-M, and it is a normal
            // boot, then we need to initialize the default preferred apps across
            // all defined users.
            if (!mOnlyCore && (mPromoteSystemApps || mFirstBoot)) {
                for (UserInfo user : mInjector.getUserManagerInternal().getUsers(true)) {
                    mSettings.applyDefaultPreferredAppsLPw(user.id);
                }
            }

            mPrepareAppDataFuture = mAppDataHelper.fixAppsDataOnBoot();

            // If this is first boot after an OTA, and a normal boot, then
            // we need to clear code cache directories.
            // Note that we do *not* clear the application profiles. These remain valid
            // across OTAs and are used to drive profile verification (post OTA) and
            // profile compilation (without waiting to collect a fresh set of profiles).
            if (mIsUpgrade && !mOnlyCore) {
                Slog.i(TAG, "Build fingerprint changed; clearing code caches");
                for (int i = 0; i < packageSettings.size(); i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, ps.getVolumeUuid())) {
                        // No apps are running this early, so no need to freeze
                        mAppDataHelper.clearAppDataLIF(ps.getPkg(), UserHandle.USER_ALL,
                                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                        | Installer.FLAG_CLEAR_CODE_CACHE_ONLY
                                        | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES);
                    }
                }
                ver.fingerprint = PackagePartitions.FINGERPRINT;
            }

            // Legacy existing (installed before Q) non-system apps to hide
            // their icons in launcher.
            if (!mOnlyCore && mIsPreQUpgrade) {
                Slog.i(TAG, "Allowlisting all existing apps to hide their icons");
                int size = packageSettings.size();
                for (int i = 0; i < size; i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if ((ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        continue;
                    }
                    ps.disableComponentLPw(PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME,
                            UserHandle.USER_SYSTEM);
                }
            }

            // clear only after permissions and other defaults have been updated
            mPromoteSystemApps = false;

            // All the changes are done during package scanning.
            ver.databaseVersion = Settings.CURRENT_DATABASE_VERSION;

            // can downgrade to reader
            t.traceBegin("write settings");
            writeSettingsLPrTEMP();
            t.traceEnd();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());

            if (!mOnlyCore) {
                mRequiredVerifierPackage = getRequiredButNotReallyRequiredVerifierLPr(computer);
                mRequiredInstallerPackage = getRequiredInstallerLPr(computer);
                mRequiredUninstallerPackage = getRequiredUninstallerLPr(computer);
                ComponentName intentFilterVerifierComponent =
                        getIntentFilterVerifierComponentNameLPr(computer);
                ComponentName domainVerificationAgent =
                        getDomainVerificationAgentComponentNameLPr(computer);

                DomainVerificationProxy domainVerificationProxy = DomainVerificationProxy.makeProxy(
                        intentFilterVerifierComponent, domainVerificationAgent, mContext,
                        mDomainVerificationManager, mDomainVerificationManager.getCollector(),
                        mDomainVerificationConnection);

                mDomainVerificationManager.setProxy(domainVerificationProxy);

                mServicesExtensionPackageName = getRequiredServicesExtensionPackageLPr();
                mSharedSystemSharedLibraryPackageName = getRequiredSharedLibrary(
                        PackageManager.SYSTEM_SHARED_LIBRARY_SHARED,
                        SharedLibraryInfo.VERSION_UNDEFINED);
            } else {
                mRequiredVerifierPackage = null;
                mRequiredInstallerPackage = null;
                mRequiredUninstallerPackage = null;
                mServicesExtensionPackageName = null;
                mSharedSystemSharedLibraryPackageName = null;
            }

            // PermissionController hosts default permission granting and role management, so it's a
            // critical part of the core system.
            mRequiredPermissionControllerPackage = getRequiredPermissionControllerLPr(computer);

            mSettings.setPermissionControllerVersion(
                    getPackageInfo(mRequiredPermissionControllerPackage, 0,
                            UserHandle.USER_SYSTEM).getLongVersionCode());

            // Resolve the supplemental process
            mRequiredSupplementalProcessPackage = getRequiredSupplementalProcessPackageName();

            // Initialize InstantAppRegistry's Instant App list for all users.
            for (AndroidPackage pkg : mPackages.values()) {
                if (pkg.isSystem()) {
                    continue;
                }
                for (int userId : userIds) {
                    final PackageStateInternal ps = getPackageStateInternal(pkg.getPackageName());
                    if (ps == null || !ps.getUserStateOrDefault(userId).isInstantApp()
                            || !ps.getUserStateOrDefault(userId).isInstalled()) {
                        continue;
                    }
                    mInstantAppRegistry.addInstantApp(userId, ps.getAppId());
                }
            }

            mInstallerService = mInjector.getPackageInstallerService();
            final ComponentName instantAppResolverComponent = getInstantAppResolver();
            if (instantAppResolverComponent != null) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Set ephemeral resolver: " + instantAppResolverComponent);
                }
                mInstantAppResolverConnection =
                        mInjector.getInstantAppResolverConnection(instantAppResolverComponent);
                mInstantAppResolverSettingsComponent =
                        getInstantAppResolverSettingsLPr(computer,
                                instantAppResolverComponent);
            } else {
                mInstantAppResolverConnection = null;
                mInstantAppResolverSettingsComponent = null;
            }
            updateInstantAppInstallerLocked(null);

            // Read and update the usage of dex files.
            // Do this at the end of PM init so that all the packages have their
            // data directory reconciled.
            // At this point we know the code paths of the packages, so we can validate
            // the disk file and build the internal cache.
            // The usage file is expected to be small so loading and verifying it
            // should take a fairly small time compare to the other activities (e.g. package
            // scanning).
            final Map<Integer, List<PackageInfo>> userPackages = new HashMap<>();
            for (int userId : userIds) {
                userPackages.put(userId, getInstalledPackages(/*flags*/ 0, userId).getList());
            }
            mDexManager.load(userPackages);
            if (mIsUpgrade) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                        BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME,
                        SystemClock.uptimeMillis() - startTime);
            }

            // Rebild the live computer since some attributes have been rebuilt.
            mLiveComputer = createLiveComputer();

        } // synchronized (mLock)
        } // synchronized (mInstallLock)
        // CHECKSTYLE:ON IndentationCheck

        mModuleInfoProvider = mInjector.getModuleInfoProvider();
        mInjector.getSystemWrapper().enablePackageCaches();

        // Now after opening every single application zip, make sure they
        // are all flushed.  Not really needed, but keeps things nice and
        // tidy.
        t.traceBegin("GC");
        VMRuntime.getRuntime().requestConcurrentGC();
        t.traceEnd();

        // The initial scanning above does many calls into installd while
        // holding the mPackages lock, but we're mostly interested in yelling
        // once we have a booted system.
        mInstaller.setWarnIfHeld(mLock);

        ParsingPackageUtils.readConfigUseRoundIcon(mContext.getResources());

        mServiceStartWithDelay = SystemClock.uptimeMillis() + (60 * 1000L);

        Slog.i(TAG, "Fix for b/169414761 is applied");
    }

    @GuardedBy("mLock")
    void updateInstantAppInstallerLocked(String modifiedPackage) {
        // we're only interested in updating the installer appliction when 1) it's not
        // already set or 2) the modified package is the installer
        if (mInstantAppInstallerActivity != null
                && !mInstantAppInstallerActivity.getComponentName().getPackageName()
                        .equals(modifiedPackage)) {
            return;
        }
        setUpInstantAppInstallerActivityLP(getInstantAppInstallerLPr());
    }

    @Override
    public boolean isFirstBoot() {
        // allow instant applications
        return mFirstBoot;
    }

    @Override
    public boolean isOnlyCoreApps() {
        // allow instant applications
        return mOnlyCore;
    }

    @Override
    public boolean isDeviceUpgrading() {
        // allow instant applications
        // The system property allows testing ota flow when upgraded to the same image.
        return mIsUpgrade || SystemProperties.getBoolean(
                "persist.pm.mock-upgrade", false /* default */);
    }

    @Nullable
    private String getRequiredButNotReallyRequiredVerifierLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent,
                        PACKAGE_MIME_TYPE,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else if (matches.size() == 0) {
            Log.w(TAG, "There should probably be a verifier, but, none were found");
            return null;
        }
        throw new RuntimeException("There must be exactly one verifier; found " + matches);
    }

    @NonNull
    private String getRequiredSharedLibrary(@NonNull String name, int version) {
        SharedLibraryInfo libraryInfo = getSharedLibraryInfo(name, version);
        if (libraryInfo == null) {
            throw new IllegalStateException("Missing required shared library:" + name);
        }
        String packageName = libraryInfo.getPackageName();
        if (packageName == null) {
            throw new IllegalStateException("Expected a package for shared library " + name);
        }
        return packageName;
    }

    @NonNull
    private String getRequiredServicesExtensionPackageLPr() {
        String servicesExtensionPackage =
                ensureSystemPackageName(
                        mContext.getString(R.string.config_servicesExtensionPackage));
        if (TextUtils.isEmpty(servicesExtensionPackage)) {
            throw new RuntimeException(
                    "Required services extension package is missing, check "
                            + "config_servicesExtensionPackage.");
        }
        return servicesExtensionPackage;
    }

    private @NonNull String getRequiredInstallerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.parse("content://com.example/foo.apk"), PACKAGE_MIME_TYPE);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent,
                PACKAGE_MIME_TYPE,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The installer must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There must be exactly one installer; found " + matches);
        }
    }

    private @NonNull String getRequiredUninstallerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.fromParts(PACKAGE_SCHEME, "foo.bar", null));

        final ResolveInfo resolveInfo = mResolveIntentHelper.resolveIntentInternal(computer, intent,
                null, MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                0 /*privateResolveFlags*/, UserHandle.USER_SYSTEM, false, Binder.getCallingUid());
        if (resolveInfo == null ||
                mResolveActivity.name.equals(resolveInfo.getComponentInfo().name)) {
            throw new RuntimeException("There must be exactly one uninstaller; found "
                    + resolveInfo);
        }
        return resolveInfo.getComponentInfo().packageName;
    }

    private @NonNull String getRequiredPermissionControllerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_MANAGE_PERMISSIONS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The permissions manager must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There must be exactly one permissions manager; found "
                    + matches);
        }
    }

    @NonNull
    private ComponentName getIntentFilterVerifierComponentNameLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent,
                        PACKAGE_MIME_TYPE,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
                continue;
            }

            if (best == null || cur.priority > best.priority) {
                best = cur;
            }
        }

        if (best != null) {
            return best.getComponentInfo().getComponentName();
        }
        Slog.w(TAG, "Intent filter verifier not found");
        return null;
    }

    @Nullable
    private ComponentName getDomainVerificationAgentComponentNameLPr(@NonNull Computer computer) {
        Intent intent = new Intent(Intent.ACTION_DOMAINS_NEED_VERIFICATION);
        List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent, null,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Domain verification agent found but does not hold permission: "
                        + packageName);
                continue;
            }

            if (best == null || cur.priority > best.priority) {
                if (mComputer.isComponentEffectivelyEnabled(cur.getComponentInfo(),
                        UserHandle.USER_SYSTEM)) {
                    best = cur;
                } else {
                    Slog.w(TAG, "Domain verification agent found but not enabled");
                }
            }
        }

        if (best != null) {
            return best.getComponentInfo().getComponentName();
        }
        Slog.w(TAG, "Domain verification agent not found");
        return null;
    }

    @Override
    public @Nullable ComponentName getInstantAppResolverComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return getInstantAppResolver();
    }

    private @Nullable ComponentName getInstantAppResolver() {
        final String[] packageArray =
                mContext.getResources().getStringArray(R.array.config_ephemeralResolverPackage);
        if (packageArray.length == 0 && !Build.IS_DEBUGGABLE) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; empty package list");
            }
            return null;
        }

        final int callingUid = Binder.getCallingUid();
        final int resolveFlags =
                MATCH_DIRECT_BOOT_AWARE
                | MATCH_DIRECT_BOOT_UNAWARE
                | (!Build.IS_DEBUGGABLE ? MATCH_SYSTEM_ONLY : 0);
        final Intent resolverIntent = new Intent(Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE);
        List<ResolveInfo> resolvers = queryIntentServicesInternal(resolverIntent, null,
                resolveFlags, UserHandle.USER_SYSTEM, callingUid, false /*includeInstantApps*/);
        final int N = resolvers.size();
        if (N == 0) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; no matching intent filters");
            }
            return null;
        }

        final Set<String> possiblePackages = new ArraySet<>(Arrays.asList(packageArray));
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = resolvers.get(i);

            if (info.serviceInfo == null) {
                continue;
            }

            final String packageName = info.serviceInfo.packageName;
            if (!possiblePackages.contains(packageName) && !Build.IS_DEBUGGABLE) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Ephemeral resolver not in allowed package list;"
                            + " pkg: " + packageName + ", info:" + info);
                }
                continue;
            }

            if (DEBUG_INSTANT) {
                Slog.v(TAG, "Ephemeral resolver found;"
                        + " pkg: " + packageName + ", info:" + info);
            }
            return new ComponentName(packageName, info.serviceInfo.name);
        }
        if (DEBUG_INSTANT) {
            Slog.v(TAG, "Ephemeral resolver NOT found");
        }
        return null;
    }

    @GuardedBy("mLock")
    private @Nullable ActivityInfo getInstantAppInstallerLPr() {
        String[] orderedActions = mIsEngBuild
                ? new String[]{
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE + "_TEST",
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE}
                : new String[]{
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE};

        final int resolveFlags =
                MATCH_DIRECT_BOOT_AWARE
                        | MATCH_DIRECT_BOOT_UNAWARE
                        | Intent.FLAG_IGNORE_EPHEMERAL
                        | (mIsEngBuild ? 0 : MATCH_SYSTEM_ONLY);
        final Computer computer = snapshotComputer();
        final Intent intent = new Intent();
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);
        List<ResolveInfo> matches = null;
        for (String action : orderedActions) {
            intent.setAction(action);
            matches = computer.queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE,
                    resolveFlags, UserHandle.USER_SYSTEM);
            if (matches.isEmpty()) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Instant App installer not found with " + action);
                }
            } else {
                break;
            }
        }
        Iterator<ResolveInfo> iter = matches.iterator();
        while (iter.hasNext()) {
            final ResolveInfo rInfo = iter.next();
            if (checkPermission(
                    Manifest.permission.INSTALL_PACKAGES,
                    rInfo.activityInfo.packageName, 0) == PERMISSION_GRANTED || mIsEngBuild) {
                continue;
            }
            iter.remove();
        }
        if (matches.size() == 0) {
            return null;
        } else if (matches.size() == 1) {
            return (ActivityInfo) matches.get(0).getComponentInfo();
        } else {
            throw new RuntimeException(
                    "There must be at most one ephemeral installer; found " + matches);
        }
    }

    private @Nullable ComponentName getInstantAppResolverSettingsLPr(@NonNull Computer computer,
            @NonNull ComponentName resolver) {
        final Intent intent =  new Intent(Intent.ACTION_INSTANT_APP_RESOLVER_SETTINGS)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(resolver.getPackageName());
        final int resolveFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                resolveFlags, UserHandle.USER_SYSTEM);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(0).getComponentInfo().getComponentName();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Slog.wtf(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    /**
     * Returns whether or not a full application can see an instant application.
     * <p>
     * Currently, there are four cases in which this can occur:
     * <ol>
     * <li>The calling application is a "special" process. Special processes
     *     are those with a UID < {@link Process#FIRST_APPLICATION_UID}.</li>
     * <li>The calling application has the permission
     *     {@link android.Manifest.permission#ACCESS_INSTANT_APPS}.</li>
     * <li>The calling application is the default launcher on the
     *     system partition.</li>
     * <li>The calling application is the default app prediction service.</li>
     * </ol>
     */
    boolean canViewInstantApps(int callingUid, int userId) {
        return mComputer.canViewInstantApps(callingUid, userId);
    }

    private PackageInfo generatePackageInfo(@NonNull PackageStateInternal ps,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return mComputer.generatePackageInfo(ps, flags, userId);
    }

    @Override
    public void checkPackageStartable(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        if (!mUserManager.exists(userId)) {
            throw new SecurityException("User doesn't exist");
        }
        enforceCrossUserPermission(callingUid, userId, false, false, "checkPackageStartable");
        switch (getPackageStartability(packageName, callingUid, userId)) {
            case PACKAGE_STARTABILITY_NOT_FOUND:
                throw new SecurityException("Package " + packageName + " was not found!");
            case PACKAGE_STARTABILITY_NOT_SYSTEM:
                throw new SecurityException("Package " + packageName + " not a system app!");
            case PACKAGE_STARTABILITY_FROZEN:
                throw new SecurityException("Package " + packageName + " is currently frozen!");
            case PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED:
                throw new SecurityException("Package " + packageName + " is not encryption aware!");
            case PACKAGE_STARTABILITY_OK:
            default:
        }
    }

    private @PackageStartability int getPackageStartability(String packageName,
            int callingUid, int userId) {
        return mComputer.getPackageStartability(mSafeMode, packageName, callingUid, userId);
    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) {
        return mComputer.isPackageAvailable(packageName, userId);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return mComputer.getPackageInfo(packageName, flags, userId);
    }

    @Override
    public PackageInfo getPackageInfoVersioned(VersionedPackage versionedPackage,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return mComputer.getPackageInfoInternal(versionedPackage.getPackageName(),
                versionedPackage.getLongVersionCode(), flags, Binder.getCallingUid(), userId);
    }

    /**
     * Returns whether or not access to the application should be filtered.
     * <p>
     * Access may be limited based upon whether the calling or target applications
     * are instant applications.
     *
     * @see #canViewInstantApps(int, int)
     */
    private boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            @Nullable ComponentName component, @ComponentType int componentType, int userId) {
        return mComputer.shouldFilterApplication(ps, callingUid,
                component, componentType, userId);
    }

    /**
     * @see #shouldFilterApplication(PackageStateInternal, int, ComponentName, int, int)
     */
    boolean shouldFilterApplication(
            @Nullable PackageStateInternal ps, int callingUid, int userId) {
        return mComputer.shouldFilterApplication(
            ps, callingUid, userId);
    }

    /**
     * @see #shouldFilterApplication(PackageStateInternal, int, ComponentName, int, int)
     */
    private boolean shouldFilterApplication(@NonNull SharedUserSetting sus, int callingUid,
            int userId) {
        return mComputer.shouldFilterApplication(sus, callingUid, userId);
    }

    private boolean filterSharedLibPackage(@Nullable PackageStateInternal ps, int uid,
            int userId, @PackageManager.ComponentInfoFlagsBits long flags) {
        return mComputer.filterSharedLibPackage(ps, uid, userId, flags);
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        return mComputer.currentToCanonicalPackageNames(names);
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        return mComputer.canonicalToCurrentPackageNames(names);
    }

    @Override
    public int getPackageUid(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        return mComputer.getPackageUid(packageName, flags, userId);
    }

    int getPackageUidInternal(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId, int callingUid) {
        return mComputer.getPackageUidInternal(packageName, flags, userId, callingUid);
    }

    @Override
    public int[] getPackageGids(String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            int userId) {
        return mComputer.getPackageGids(packageName, flags, userId);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class)
                .getPermissionGroupInfo(groupName, flags);
    }

    private ApplicationInfo generateApplicationInfoFromSettings(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int filterCallingUid, int userId) {
        return mComputer.generateApplicationInfoFromSettings(packageName, flags, filterCallingUid,
                userId);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        return mComputer.getApplicationInfo(packageName, flags, userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out applications
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    private ApplicationInfo getApplicationInfoInternal(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags,
            int filterCallingUid, int userId) {
        return mComputer.getApplicationInfoInternal(packageName, flags,
                filterCallingUid, userId);
    }

    @Override
    public void deletePreloadsFileCache() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CLEAR_APP_CACHE,
                "deletePreloadsFileCache");
        File dir = Environment.getDataPreloadsFileCacheDirectory();
        Slog.i(TAG, "Deleting preloaded file cache " + dir);
        FileUtils.deleteContents(dir);
    }

    @Override
    public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize,
            final @StorageManager.AllocateFlags int flags, final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        mHandler.post(() -> {
            boolean success = false;
            try {
                freeStorage(volumeUuid, freeStorageSize, flags);
                success = true;
            } catch (IOException e) {
                Slog.w(TAG, e);
            }
            if (observer != null) {
                try {
                    observer.onRemoveCompleted(null, success);
                } catch (RemoteException e) {
                    Slog.w(TAG, e);
                }
            }
        });
    }

    @Override
    public void freeStorage(final String volumeUuid, final long freeStorageSize,
            final @StorageManager.AllocateFlags int flags, final IntentSender pi) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, TAG);
        mHandler.post(() -> {
            boolean success = false;
            try {
                freeStorage(volumeUuid, freeStorageSize, flags);
                success = true;
            } catch (IOException e) {
                Slog.w(TAG, e);
            }
            if (pi != null) {
                try {
                    pi.sendIntent(null, success ? 1 : 0, null, null, null);
                } catch (SendIntentException e) {
                    Slog.w(TAG, e);
                }
            }
        });
    }

    /**
     * Blocking call to clear all cached app data above quota.
     */
    public void freeAllAppCacheAboveQuota(String volumeUuid) throws IOException {
        synchronized (mInstallLock) {
            // To avoid refactoring Installer.freeCache() and InstalldNativeService.freeCache(),
            // Long.MAX_VALUE is passed as an argument which is used in neither of two methods
            // when FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES is set
            try {
                mInstaller.freeCache(volumeUuid, Long.MAX_VALUE, Installer.FLAG_FREE_CACHE_V2
                        | Installer.FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES);
            } catch (InstallerException ignored) {
            }
        }
        return;
    }

    /**
     * Blocking call to clear various types of cached data across the system
     * until the requested bytes are available.
     */
    public void freeStorage(String volumeUuid, long bytes,
            @StorageManager.AllocateFlags int flags) throws IOException {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        final File file = storage.findPathForUuid(volumeUuid);
        if (file.getUsableSpace() >= bytes) return;

        if (mEnableFreeCacheV2) {
            final boolean internalVolume = Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL,
                    volumeUuid);
            final boolean aggressive = (flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0;

            // 1. Pre-flight to determine if we have any chance to succeed
            // 2. Consider preloaded data (after 1w honeymoon, unless aggressive)
            if (internalVolume && (aggressive || SystemProperties
                    .getBoolean("persist.sys.preloads.file_cache_expired", false))) {
                deletePreloadsFileCache();
                if (file.getUsableSpace() >= bytes) return;
            }

            // 3. Consider parsed APK data (aggressive only)
            if (internalVolume && aggressive) {
                FileUtils.deleteContents(mCacheDir);
                if (file.getUsableSpace() >= bytes) return;
            }

            // 4. Consider cached app data (above quotas)
            synchronized (mInstallLock) {
                try {
                    mInstaller.freeCache(volumeUuid, bytes, Installer.FLAG_FREE_CACHE_V2);
                } catch (InstallerException ignored) {
                }
            }
            if (file.getUsableSpace() >= bytes) return;

            // 5. Consider shared libraries with refcount=0 and age>min cache period
            if (internalVolume && mSharedLibraries.pruneUnusedStaticSharedLibraries(bytes,
                    android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                            Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                            FREE_STORAGE_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD))) {
                return;
            }

            // 6. Consider dexopt output (aggressive only)
            // TODO: Implement

            // 7. Consider installed instant apps unused longer than min cache period
            if (internalVolume) {
                if (executeWithConsistentComputerReturning(computer ->
                        mInstantAppRegistry.pruneInstalledInstantApps(computer, bytes,
                                android.provider.Settings.Global.getLong(
                                        mContext.getContentResolver(),
                                        Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                                        InstantAppRegistry
                                                .DEFAULT_INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD)))
                ) {
                    return;
                }
            }

            // 8. Consider cached app data (below quotas)
            synchronized (mInstallLock) {
                try {
                    mInstaller.freeCache(volumeUuid, bytes,
                            Installer.FLAG_FREE_CACHE_V2 | Installer.FLAG_FREE_CACHE_V2_DEFY_QUOTA);
                } catch (InstallerException ignored) {
                }
            }
            if (file.getUsableSpace() >= bytes) return;

            // 9. Consider DropBox entries
            // TODO: Implement

            // 10. Consider instant meta-data (uninstalled apps) older that min cache period
            if (internalVolume) {
                if (executeWithConsistentComputerReturning(computer ->
                        mInstantAppRegistry.pruneUninstalledInstantApps(computer, bytes,
                                android.provider.Settings.Global.getLong(
                                        mContext.getContentResolver(),
                                        Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                                        InstantAppRegistry
                                                .DEFAULT_UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD)))
                ) {
                    return;
                }
            }

            // 11. Free storage service cache
            StorageManagerInternal smInternal =
                    mInjector.getLocalService(StorageManagerInternal.class);
            long freeBytesRequired = bytes - file.getUsableSpace();
            if (freeBytesRequired > 0) {
                smInternal.freeCache(volumeUuid, freeBytesRequired);
            }

            // 12. Clear temp install session files
            mInstallerService.freeStageDirs(volumeUuid);
        } else {
            synchronized (mInstallLock) {
                try {
                    mInstaller.freeCache(volumeUuid, bytes, 0);
                } catch (InstallerException ignored) {
                }
            }
        }
        if (file.getUsableSpace() >= bytes) return;

        throw new IOException("Failed to free " + bytes + " on storage device at " + file);
    }

    int freeCacheForInstallation(int recommendedInstallLocation, PackageLite pkgLite,
            String resolvedPath, String mPackageAbiOverride, int installFlags) {
        // TODO: focus freeing disk space on the target device
        final StorageManager storage = StorageManager.from(mContext);
        final long lowThreshold = storage.getStorageLowBytes(Environment.getDataDirectory());

        final long sizeBytes = PackageManagerServiceUtils.calculateInstalledSize(resolvedPath,
                mPackageAbiOverride);
        if (sizeBytes >= 0) {
            synchronized (mInstallLock) {
                try {
                    mInstaller.freeCache(null, sizeBytes + lowThreshold, 0);
                    PackageInfoLite pkgInfoLite = PackageManagerServiceUtils.getMinimalPackageInfo(
                            mContext, pkgLite, resolvedPath, installFlags,
                            mPackageAbiOverride);
                    // The cache free must have deleted the file we downloaded to install.
                    if (pkgInfoLite.recommendedInstallLocation
                            == InstallLocationUtils.RECOMMEND_FAILED_INVALID_URI) {
                        pkgInfoLite.recommendedInstallLocation =
                                InstallLocationUtils.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
                    }
                    return pkgInfoLite.recommendedInstallLocation;
                } catch (Installer.InstallerException e) {
                    Slog.w(TAG, "Failed to free cache", e);
                }
            }
        }
        return recommendedInstallLocation;
    }

    /**
     * Update given flags when being used to request {@link ResolveInfo}.
     * <p>Instant apps are resolved specially, depending upon context. Minimally,
     * {@code}flags{@code} must have the {@link PackageManager#MATCH_INSTANT}
     * flag set. However, this flag is only honoured in three circumstances:
     * <ul>
     * <li>when called from a system process</li>
     * <li>when the caller holds the permission {@code android.permission.ACCESS_INSTANT_APPS}</li>
     * <li>when resolution occurs to start an activity with a {@code android.intent.action.VIEW}
     * action and a {@code android.intent.category.BROWSABLE} category</li>
     * </ul>
     */
    long updateFlagsForResolve(long flags, int userId, int callingUid,
            boolean wantInstantApps, boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        return mComputer.updateFlagsForResolve(flags, userId, callingUid,
                wantInstantApps, isImplicitImageCaptureIntentAndNotSetByDpc);
    }

    @Override
    public int getTargetSdkVersion(@NonNull String packageName)  {
        return mComputer.getTargetSdkVersion(packageName);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        return mComputer.getActivityInfo(component, flags, userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out activities
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    private ActivityInfo getActivityInfoInternal(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int filterCallingUid, int userId) {
        return mComputer.getActivityInfoInternal(component, flags,
                filterCallingUid, userId);
    }

    @Override
    public boolean activitySupportsIntent(ComponentName component, Intent intent,
            String resolvedType) {
        return mComputer.activitySupportsIntent(mResolveComponentName, component, intent,
                resolvedType);
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        return mComputer.getReceiverInfo(component, flags, userId);
    }

    @Override
    public ParceledListSlice<SharedLibraryInfo> getSharedLibraries(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return mComputer.getSharedLibraries(packageName, flags, userId);
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @NonNull int userId) {
        return mComputer.getDeclaredSharedLibraries(packageName, flags, userId);
    }

    @Nullable
    List<VersionedPackage> getPackagesUsingSharedLibrary(
            SharedLibraryInfo libInfo, @PackageManager.PackageInfoFlagsBits long flags,
            int callingUid, int userId) {
        return mComputer.getPackagesUsingSharedLibrary(libInfo, flags, callingUid, userId);
    }

    @Nullable
    @Override
    public ServiceInfo getServiceInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        return mComputer.getServiceInfo(component, flags, userId);
    }

    @Nullable
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        return mComputer.getProviderInfo(component, flags, userId);
    }

    @Override
    public ModuleInfo getModuleInfo(String packageName, @ModuleInfoFlags int flags) {
        return mModuleInfoProvider.getModuleInfo(packageName, flags);
    }

    @Override
    public List<ModuleInfo> getInstalledModules(int flags) {
        return mModuleInfoProvider.getInstalledModules(flags);
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        return mComputer.getSystemSharedLibraryNames();
    }

    @Override
    public @NonNull String getServicesSystemSharedLibraryPackageName() {
        return mServicesExtensionPackageName;
    }

    @Override
    public @NonNull String getSharedSystemSharedLibraryPackageName() {
        return mSharedSystemSharedLibraryPackageName;
    }

    @GuardedBy("mLock")
    void updateSequenceNumberLP(PackageSetting pkgSetting, int[] userList) {
        mChangedPackagesTracker.updateSequenceNumber(pkgSetting.getPackageName(), userList);
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        if (!mUserManager.exists(userId)) {
            return null;
        }
        enforceCrossUserPermission(callingUid, userId, false, false, "getChangedPackages");
        final ChangedPackages changedPackages = mChangedPackagesTracker.getChangedPackages(
                sequenceNumber, userId);

        if (changedPackages != null) {
            final List<String> packageNames = changedPackages.getPackageNames();
            for (int index = packageNames.size() - 1; index >= 0; index--) {
                // Filter out the changes if the calling package should not be able to see it.
                final PackageSetting ps = mSettings.getPackageLPr(packageNames.get(index));
                if (shouldFilterApplication(ps, callingUid, userId)) {
                    packageNames.remove(index);
                }
            }
        }

        return changedPackages;
    }

    @Override
    public @NonNull ParceledListSlice<FeatureInfo> getSystemAvailableFeatures() {
        // allow instant applications
        ArrayList<FeatureInfo> res;
        synchronized (mAvailableFeatures) {
            res = new ArrayList<>(mAvailableFeatures.size() + 1);
            res.addAll(mAvailableFeatures.values());
        }
        final FeatureInfo fi = new FeatureInfo();
        fi.reqGlEsVersion = SystemProperties.getInt("ro.opengles.version",
                FeatureInfo.GL_ES_VERSION_UNDEFINED);
        res.add(fi);

        return new ParceledListSlice<>(res);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        // allow instant applications
        synchronized (mAvailableFeatures) {
            final FeatureInfo feat = mAvailableFeatures.get(name);
            if (feat == null) {
                return false;
            } else {
                return feat.version >= version;
            }
        }
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public int checkPermission(String permName, String pkgName, int userId) {
        return mPermissionManager.checkPermission(pkgName, permName, userId);
    }

    // NOTE: Can't remove without a major refactor. Keep around for now.
    @Override
    public int checkUidPermission(String permName, int uid) {
        return mComputer.checkUidPermission(permName, uid);
    }

    @Override
    public String getPermissionControllerPackageName() {
        final int callingUid = Binder.getCallingUid();
        if (mComputer.getPackageStateFiltered(mRequiredPermissionControllerPackage,
                callingUid, UserHandle.getUserId(callingUid)) != null) {
            return mRequiredPermissionControllerPackage;
        }

        throw new IllegalStateException("PermissionController is not found");
    }

    @Override
    public String getSupplementalProcessPackageName() {
        return mRequiredSupplementalProcessPackage;
    }

    String getPackageInstallerPackageName() {
        return mRequiredInstallerPackage;
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public boolean addPermission(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, false);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, true);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public void removePermission(String permName) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class).removePermission(permName);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public void grantRuntimePermission(String packageName, String permName, final int userId) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class)
                .grantRuntimePermission(packageName, permName, UserHandle.of(userId));
    }

    @Override
    public boolean isProtectedBroadcast(String actionName) {
        if (actionName != null) {
            // TODO: remove these terrible hacks
            if (actionName.startsWith("android.net.netmon.lingerExpired")
                    || actionName.startsWith("com.android.server.sip.SipWakeupTimer")
                    || actionName.startsWith("com.android.internal.telephony.data-reconnect")
                    || actionName.startsWith("android.net.netmon.launchCaptivePortalApp")) {
                return true;
            }
        }
         // allow instant applications
        synchronized (mProtectedBroadcasts) {
            return mProtectedBroadcasts.contains(actionName);
        }
    }

    @Override
    public int checkSignatures(@NonNull String pkg1, @NonNull String pkg2) {
        return mComputer.checkSignatures(pkg1, pkg2);
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        return mComputer.checkUidSignatures(uid1, uid2);
    }

    @Override
    public boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type) {
        return mComputer.hasSigningCertificate(packageName, certificate, type);
    }

    @Override
    public boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type) {
        return mComputer.hasUidSigningCertificate(uid, certificate, type);
    }

    @Override
    public List<String> getAllPackages() {
        return mComputer.getAllPackages();
    }

    /**
     * <em>IMPORTANT:</em> Not all packages returned by this method may be known
     * to the system. There are two conditions in which this may occur:
     * <ol>
     *   <li>The package is on adoptable storage and the device has been removed</li>
     *   <li>The package is being removed and the internal structures are partially updated</li>
     * </ol>
     * The second is an artifact of the current data structures and should be fixed. See
     * b/111075456 for one such instance.
     * This binder API is cached.  If the algorithm in this method changes,
     * or if the underlying objecs (as returned by getSettingLPr()) change
     * then the logic that invalidates the cache must be revisited.  See
     * calls to invalidateGetPackagesForUidCache() to locate the points at
     * which the cache is invalidated.
     */
    @Override
    public String[] getPackagesForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        enforceCrossUserOrProfilePermission(callingUid, userId,
                /* requireFullPermission */ false,
                /* checkShell */ false, "getPackagesForUid");
        return mComputer.getPackagesForUid(uid);
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        return mComputer.getNameForUid(uid);
    }

    @Nullable
    @Override
    public String[] getNamesForUids(@NonNull int[] uids) {
        return mComputer.getNamesForUids(uids);
    }

    @Override
    public int getUidForSharedUser(@NonNull String sharedUserName) {
        return mComputer.getUidForSharedUser(sharedUserName);
    }

    @Override
    public int getFlagsForUid(int uid) {
        return mComputer.getFlagsForUid(uid);
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        return mComputer.getPrivateFlagsForUid(uid);
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        return mComputer.isUidPrivileged(uid);
    }

    // NOTE: Can't remove due to unsupported app usage
    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        return mComputer.getAppOpPermissionPackages(permissionName);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return mResolveIntentHelper.resolveIntentInternal(snapshotComputer(), intent, resolvedType,
                flags, 0 /*privateResolveFlags*/, userId, false, Binder.getCallingUid());
    }

    @Override
    public ResolveInfo findPersistentPreferredActivity(Intent intent, int userId) {
        return mPreferredActivityHelper.findPersistentPreferredActivity(intent, userId);
    }

    @Override
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            IntentFilter filter, int match, ComponentName activity) {
        mPreferredActivityHelper.setLastChosenActivity(intent, resolvedType, flags,
                              new WatchedIntentFilter(filter), match, activity);
    }

    @Override
    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        return mPreferredActivityHelper.getLastChosenActivity(intent, resolvedType, flags);
    }

    private void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
            Intent origIntent, String resolvedType, String callingPackage,
            @Nullable String callingFeatureId, boolean isRequesterInstantApp,
            Bundle verificationBundle, int userId) {
        final Message msg = mHandler.obtainMessage(INSTANT_APP_RESOLUTION_PHASE_TWO,
                new InstantAppRequest(responseObj, origIntent, resolvedType,
                        callingPackage, callingFeatureId, isRequesterInstantApp, userId,
                        verificationBundle, false /*resolveForStart*/,
                        responseObj.hostDigestPrefixSecure, responseObj.token));
        mHandler.sendMessage(msg);
    }

    /**
     * From Android R, camera intents have to match system apps. The only exception to this is if
     * the DPC has set the camera persistent preferred activity. This case was introduced
     * because it is important that the DPC has the ability to set both system and non-system
     * camera persistent preferred activities.
     *
     * @return {@code true} if the intent is a camera intent and the persistent preferred
     * activity was not set by the DPC.
     */
    @GuardedBy("mLock")
    boolean isImplicitImageCaptureIntentAndNotSetByDpcLocked(Intent intent, int userId,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags) {
        return mComputer.isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId,
                resolvedType, flags);
    }

    @GuardedBy("mLock")
    ResolveInfo findPersistentPreferredActivityLP(Intent intent,
            String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, List<ResolveInfo> query, boolean debug,
            int userId) {
        return mComputer.findPersistentPreferredActivityLP(intent,
                resolvedType, flags, query, debug, userId);
    }

    // findPreferredActivityBody returns two items: a "things changed" flag and a
    // ResolveInfo, which is the preferred activity itself.
    static class FindPreferredActivityBodyResult {
        boolean mChanged;
        ResolveInfo mPreferredResolveInfo;
    }

    FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered) {
        return mComputer.findPreferredActivityInternal(
            intent, resolvedType, flags,
            query, always,
            removeMatches, debug, userId, queryMayBeFiltered);
    }

    /*
     * Returns if intent can be forwarded from the sourceUserId to the targetUserId
     */
    @Override
    public boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId) {
        return mComputer.canForwardTo(intent, resolvedType, sourceUserId, targetUserId);
    }

    private UserInfo getProfileParent(int userId) {
        return mComputer.getProfileParent(userId);
    }

    private List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
            String resolvedType, int userId) {
        return mComputer.getMatchingCrossProfileIntentFilters(intent,
                resolvedType, userId);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "queryIntentActivities");

            return new ParceledListSlice<>(snapshotComputer().queryIntentActivitiesInternal(intent,
                    resolvedType, flags, userId));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Returns the package name of the calling Uid if it's an instant app. If it isn't
     * instant, returns {@code null}.
     */
    String getInstantAppPackageName(int callingUid) {
        return mComputer.getInstantAppPackageName(callingUid);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentActivityOptionsInternal(
                snapshotComputer(), caller, specifics, specificTypes, intent, resolvedType, flags,
                userId));
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentReceiversInternal(
                snapshotComputer(), intent, resolvedType, flags, userId, Binder.getCallingUid()));
    }

    @Override
    public ResolveInfo resolveService(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return mResolveIntentHelper.resolveServiceInternal(snapshotComputer(), intent, resolvedType,
                flags, userId, callingUid);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentServices(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(queryIntentServicesInternal(
                intent, resolvedType, flags, userId, callingUid, false /*includeInstantApps*/));
    }

    @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid, boolean includeInstantApps) {
        return mComputer.queryIntentServicesInternal(intent,
                resolvedType, flags, userId, callingUid,
                includeInstantApps);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentContentProviders(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentContentProvidersInternal(
                snapshotComputer(), intent, resolvedType, flags, userId));
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackages(
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return mComputer.getInstalledPackages(flags, userId);
    }

    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        return mComputer.getPackagesHoldingPermissions(permissions, flags, userId);
    }

    @Override
    public ParceledListSlice<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(
                mComputer.getInstalledApplications(flags, userId, callingUid));
    }

    @Override
    public ParceledListSlice<InstantAppInfo> getInstantApps(int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }
        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                    "getEphemeralApplications");
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getEphemeralApplications");

        List<InstantAppInfo> instantApps = executeWithConsistentComputerReturning(computer ->
                mInstantAppRegistry.getInstantApps(computer, userId));
        if (instantApps != null) {
            return new ParceledListSlice<>(instantApps);
        }
        return null;
    }

    @Override
    public boolean isInstantApp(String packageName, int userId) {
        return mComputer.isInstantApp(packageName, userId);
    }

    private boolean isInstantAppInternal(String packageName, @UserIdInt int userId,
            int callingUid) {
        return mComputer.isInstantAppInternal(packageName, userId,
                callingUid);
    }

    @Override
    public byte[] getInstantAppCookie(String packageName, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return null;
        }
        PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null || packageState.getPkg() == null) {
            return null;
        }
        return mInstantAppRegistry.getInstantAppCookie(packageState.getPkg(), userId);
    }

    @Override
    public boolean setInstantAppCookie(String packageName, byte[] cookie, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return true;
        }

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                true /* checkShell */, "setInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return false;
        }

        PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null || packageState.getPkg() == null) {
            return false;
        }
        return mInstantAppRegistry.setInstantAppCookie(packageState.getPkg(), cookie,
                mContext.getPackageManager().getInstantAppCookieMaxBytes(), userId);
    }

    @Override
    public Bitmap getInstantAppIcon(String packageName, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }

        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                    "getInstantAppIcon");
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppIcon");

        return mInstantAppRegistry.getInstantAppIcon(packageName, userId);
    }

    boolean isCallerSameApp(String packageName, int uid) {
        return mComputer.isCallerSameApp(packageName, uid);
    }

    @Override
    public @NonNull ParceledListSlice<ApplicationInfo> getPersistentApplications(int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(mComputer.getPersistentApplications(mSafeMode, flags));
    }

    @Override
    public ProviderInfo resolveContentProvider(String name,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return mComputer.resolveContentProvider(name, flags, userId, Binder.getCallingUid());
    }

    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        mComputer.querySyncProviders(mSafeMode, outNames, outInfo);
    }

    @NonNull
    @Override
    public ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable  String processName,
            int uid, @PackageManager.ComponentInfoFlagsBits long flags,
            @Nullable String metaDataKey) {
        return mComputer.queryContentProviders(processName, uid, flags, metaDataKey);
    }

    @Nullable
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags) {
        return mComputer.getInstrumentationInfo(component, flags);
    }

    @NonNull
    @Override
    public ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags) {
        return mComputer.queryInstrumentation(targetPackage, flags);
    }

    public static void reportSettingsProblem(int priority, String msg) {
        logCriticalInfo(priority, msg);
    }

    // TODO:(b/135203078): Move to parsing
    static void renameStaticSharedLibraryPackage(ParsedPackage parsedPackage) {
        // Derive the new package synthetic package name
        parsedPackage.setPackageName(toStaticSharedLibraryPackageName(
                parsedPackage.getPackageName(), parsedPackage.getStaticSharedLibVersion()));
    }

    private static String toStaticSharedLibraryPackageName(
            String packageName, long libraryVersion) {
        return packageName + STATIC_SHARED_LIB_DELIMITER + libraryVersion;
    }

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        mComputer.enforceCrossUserPermission(callingUid, userId,
                requireFullPermission, checkShell, message);
    }

    /**
     * Checks if the request is from the system or an app that has the appropriate cross-user
     * permissions defined as follows:
     * <ul>
     * <li>INTERACT_ACROSS_USERS_FULL if {@code requireFullPermission} is true.</li>
     * <li>INTERACT_ACROSS_USERS if the given {@code userId} is in a different profile group
     * to the caller.</li>
     * <li>Otherwise, INTERACT_ACROSS_PROFILES if the given {@code userId} is in the same profile
     * group as the caller.</li>
     * </ul>
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        mComputer.enforceCrossUserOrProfilePermission(callingUid, userId,
                requireFullPermission, checkShell, message);
    }

    @Override
    public void performFstrimIfNeeded() {
        PackageManagerServiceUtils.enforceSystemOrRoot("Only the system can request fstrim");

        // Before everything else, see whether we need to fstrim.
        try {
            IStorageManager sm = InstallLocationUtils.getStorageManager();
            if (sm != null) {
                boolean doTrim = false;
                final long interval = android.provider.Settings.Global.getLong(
                        mContext.getContentResolver(),
                        android.provider.Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                        DEFAULT_MANDATORY_FSTRIM_INTERVAL);
                if (interval > 0) {
                    final long timeSinceLast = System.currentTimeMillis() - sm.lastMaintenance();
                    if (timeSinceLast > interval) {
                        doTrim = true;
                        Slog.w(TAG, "No disk maintenance in " + timeSinceLast
                                + "; running immediately");
                    }
                }
                if (doTrim) {
                    if (!isFirstBoot()) {
                        if (mDexOptHelper.isDexOptDialogShown()) {
                            try {
                                ActivityManager.getService().showBootMessage(
                                        mContext.getResources().getString(
                                                R.string.android_upgrading_fstrim), true);
                            } catch (RemoteException e) {
                            }
                        }
                    }
                    sm.runMaintenance();
                }
            } else {
                Slog.e(TAG, "storageManager service unavailable!");
            }
        } catch (RemoteException e) {
            // Can't happen; StorageManagerService is local
        }
    }

    @Override
    public void updatePackagesIfNeeded() {
        mDexOptHelper.performPackageDexOptUpgradeIfNeeded();
    }

    @Override
    public void notifyPackageUse(String packageName, int reason) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        boolean notify = executeWithConsistentComputerReturning(computer -> {
            if (getInstantAppPackageName(callingUid) != null) {
                return isCallerSameApp(packageName, callingUid);
            } else {
                return !isInstantAppInternal(packageName, callingUserId, Process.SYSTEM_UID);
            }
        });
        if (!notify) {
            return;
        }

        notifyPackageUseInternal(packageName, reason);
    }

    private void notifyPackageUseInternal(String packageName, int reason) {
        long time = System.currentTimeMillis();
        commitPackageStateMutation(null, packageName, packageState -> {
            packageState.setLastPackageUsageTime(reason, time);
        });
    }

    @Override
    public void notifyDexLoad(String loadingPackageName, Map<String, String> classLoaderContextMap,
            String loaderIsa) {
        int callingUid = Binder.getCallingUid();
        if (PLATFORM_PACKAGE_NAME.equals(loadingPackageName) && callingUid != Process.SYSTEM_UID) {
            Slog.w(TAG, "Non System Server process reporting dex loads as system server. uid="
                    + callingUid);
            // Do not record dex loads from processes pretending to be system server.
            // Only the system server should be assigned the package "android", so reject calls
            // that don't satisfy the constraint.
            //
            // notifyDexLoad is a PM API callable from the app process. So in theory, apps could
            // craft calls to this API and pretend to be system server. Doing so poses no particular
            // danger for dex load reporting or later dexopt, however it is a sensible check to do
            // in order to verify the expectations.
            return;
        }

        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(loadingPackageName, /*flags*/ 0, userId);
        if (ai == null) {
            Slog.w(TAG, "Loading a package that does not exist for the calling user. package="
                + loadingPackageName + ", user=" + userId);
            return;
        }
        mDexManager.notifyDexLoad(ai, classLoaderContextMap, loaderIsa, userId,
                Process.isIsolated(callingUid));
    }

    @Override
    public void registerDexModule(String packageName, String dexModulePath, boolean isSharedModule,
            IDexModuleRegisterCallback callback) {
        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(packageName, /*flags*/ 0, userId);
        DexManager.RegisterDexModuleResult result;
        if (ai == null) {
            Slog.w(TAG, "Registering a dex module for a package that does not exist for the" +
                     " calling user. package=" + packageName + ", user=" + userId);
            result = new DexManager.RegisterDexModuleResult(false, "Package not installed");
        } else {
            result = mDexManager.registerDexModule(ai, dexModulePath, isSharedModule, userId);
        }

        if (callback != null) {
            mHandler.post(() -> {
                try {
                    callback.onDexModuleRegistered(dexModulePath, result.success, result.message);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to callback after module registration " + dexModulePath, e);
                }
            });
        }
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    @Override
    public boolean performDexOptMode(String packageName,
            boolean checkProfiles, String targetCompilerFilter, boolean force,
            boolean bootComplete, String splitName) {
        return mDexOptHelper.performDexOptMode(packageName, checkProfiles, targetCompilerFilter,
                force, bootComplete, splitName);
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter on the
     * secondary dex files belonging to the given package.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    @Override
    public boolean performDexOptSecondary(String packageName, String compilerFilter,
            boolean force) {
        return mDexOptHelper.performDexOptSecondary(packageName, compilerFilter, force);
    }

    /**
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packageName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete the generated oat files.
     */
    @Override
    public void reconcileSecondaryDexFiles(String packageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        } else if (isInstantAppInternal(
                packageName, UserHandle.getCallingUserId(), Process.SYSTEM_UID)) {
            return;
        }
        mDexManager.reconcileSecondaryDexFiles(packageName);
    }

    /*package*/ DexManager getDexManager() {
        return mDexManager;
    }

    @NonNull
    List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting) {
        return mComputer.findSharedNonSystemLibraries(pkgSetting);
    }

    @Nullable
    SharedLibraryInfo getSharedLibraryInfo(String name, long version) {
        return mComputer.getSharedLibraryInfo(name, version);
    }

    public void shutdown() {
        mCompilerStats.writeNow();
        mDexManager.writePackageDexUsageNow();
        PackageWatchdog.getInstance(mContext).writeNow();

        synchronized (mLock) {
            mPackageUsage.writeNow(mSettings.getPackagesLocked());

            if (mHandler.hasMessages(WRITE_SETTINGS)) {
                mHandler.removeMessages(WRITE_SETTINGS);
                writeSettings();
            }
        }
    }

    @Override
    public void dumpProfiles(String packageName) {
        /* Only the shell, root, or the app user should be able to dump profiles. */
        final int callingUid = Binder.getCallingUid();
        final String[] callerPackageNames = getPackagesForUid(callingUid);
        if (callingUid != Process.SHELL_UID
                && callingUid != Process.ROOT_UID
                && !ArrayUtils.contains(callerPackageNames, packageName)) {
            throw new SecurityException("dumpProfiles");
        }

        AndroidPackage pkg = getPackage(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        synchronized (mInstallLock) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dump profiles");
            mArtManagerService.dumpProfiles(pkg);
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @Override
    public void forceDexOpt(String packageName) {
        mDexOptHelper.forceDexOpt(packageName);
    }

    int[] resolveUserIds(int userId) {
        return (userId == UserHandle.USER_ALL) ? mUserManager.getUserIds() : new int[] { userId };
    }

    @Override
    public Property getProperty(String propertyName, String packageName, String className) {
        Objects.requireNonNull(propertyName);
        Objects.requireNonNull(packageName);
        PackageStateInternal packageState = mComputer.getPackageStateFiltered(packageName,
                Binder.getCallingUid(), UserHandle.getCallingUserId());
        if (packageState == null) {
            return null;
        }
        return mPackageProperty.getProperty(propertyName, packageName, className);
    }

    @Override
    public ParceledListSlice<Property> queryProperty(
            String propertyName, @PropertyLocation int componentType) {
        Objects.requireNonNull(propertyName);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        final List<Property> result =
                mPackageProperty.queryProperty(propertyName, componentType, packageName -> {
                    final PackageStateInternal ps = getPackageStateInternal(packageName);
                    return shouldFilterApplication(ps, callingUid, callingUserId);
                });
        if (result == null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(result);
    }

    private void setUpInstantAppInstallerActivityLP(ActivityInfo installerActivity) {
        if (installerActivity == null) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Clear ephemeral installer activity");
            }
            mInstantAppInstallerActivity = null;
            onChanged();
            return;
        }

        if (DEBUG_INSTANT) {
            Slog.d(TAG, "Set ephemeral installer activity: "
                    + installerActivity.getComponentName());
        }
        // Set up information for ephemeral installer activity
        mInstantAppInstallerActivity = installerActivity;
        mInstantAppInstallerActivity.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
        mInstantAppInstallerActivity.exported = true;
        mInstantAppInstallerActivity.enabled = true;
        mInstantAppInstallerInfo.activityInfo = mInstantAppInstallerActivity;
        mInstantAppInstallerInfo.priority = 1;
        mInstantAppInstallerInfo.preferredOrder = 1;
        mInstantAppInstallerInfo.isDefault = true;
        mInstantAppInstallerInfo.match = IntentFilter.MATCH_CATEGORY_SCHEME_SPECIFIC_PART
                | IntentFilter.MATCH_ADJUSTMENT_NORMAL;
        onChanged();
    }

    void killApplication(String pkgName, @AppIdInt int appId, String reason) {
        killApplication(pkgName, appId, UserHandle.USER_ALL, reason);
    }

    void killApplication(String pkgName, @AppIdInt int appId,
            @UserIdInt int userId, String reason) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        final long token = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killApplication(pkgName, appId, userId, reason);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void sendPackageBroadcast(final String action, final String pkg, final Bundle extras,
            final int flags, final String targetPkg, final IIntentReceiver finishedReceiver,
            final int[] userIds, int[] instantUserIds,
            @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable Bundle bOptions) {
        mHandler.post(() -> mBroadcastHelper.sendPackageBroadcast(action, pkg, extras, flags,
                targetPkg, finishedReceiver, userIds, instantUserIds, broadcastAllowList,
                bOptions));
    }

    @Override
    public void notifyPackageAdded(String packageName, int uid) {
        mPackageObserverHelper.notifyAdded(packageName, uid);
    }

    @Override
    public void notifyPackageChanged(String packageName, int uid) {
        mPackageObserverHelper.notifyChanged(packageName, uid);
    }

    @Override
    public void notifyPackageRemoved(String packageName, int uid) {
        mPackageObserverHelper.notifyRemoved(packageName, uid);
    }

    void sendPackageAddedForUser(String packageName, @NonNull PackageStateInternal packageState,
            int userId, int dataLoaderType) {
        final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        final boolean isSystem = packageState.isSystem();
        final boolean isInstantApp = userState.isInstantApp();
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        sendPackageAddedForNewUsers(packageName, isSystem /*sendBootCompleted*/,
                false /*startReceiver*/, packageState.getAppId(), userIds, instantUserIds,
                dataLoaderType);

        // Send a session commit broadcast
        final PackageInstaller.SessionInfo info = new PackageInstaller.SessionInfo();
        info.installReason = userState.getInstallReason();
        info.appPackageName = packageName;
        sendSessionCommitBroadcast(info, userId);
    }

    @Override
    public void sendPackageAddedForNewUsers(String packageName, boolean sendBootCompleted,
            boolean includeStopped, @AppIdInt int appId, int[] userIds, int[] instantUserIds,
            int dataLoaderType) {
        if (ArrayUtils.isEmpty(userIds) && ArrayUtils.isEmpty(instantUserIds)) {
            return;
        }
        SparseArray<int[]> broadcastAllowList = mAppsFilter.getVisibilityAllowList(
                getPackageStateInternal(packageName, Process.SYSTEM_UID),
                userIds, getPackageStates());
        mHandler.post(() -> mBroadcastHelper.sendPackageAddedForNewUsers(
                packageName, appId, userIds, instantUserIds, dataLoaderType, broadcastAllowList));
        if (sendBootCompleted && !ArrayUtils.isEmpty(userIds)) {
            mHandler.post(() -> {
                        for (int userId : userIds) {
                            mBroadcastHelper.sendBootCompletedBroadcastToSystemApp(
                                    packageName, includeStopped, userId);
                        }
                    }
            );
        }
    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                true /* checkShell */, "setApplicationHiddenSetting for user " + userId);

        if (hidden && isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not hiding package " + packageName + ": has active device admin");
            return false;
        }

        // Do not allow "android" is being disabled
        if ("android".equals(packageName)) {
            Slog.w(TAG, "Cannot hide package: android");
            return false;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            final PackageStateInternal packageState =
                    mComputer.getPackageStateFiltered(packageName, callingUid, userId);
            if (packageState == null) {
                return false;
            }

            // Cannot hide static shared libs as they are considered
            // a part of the using app (emulating static linking). Also
            // static libs are installed always on internal storage.
            AndroidPackage pkg = packageState.getPkg();
            if (pkg != null) {
                // Cannot hide SDK libs as they are controlled by SDK manager.
                if (pkg.getSdkLibName() != null) {
                    Slog.w(TAG, "Cannot hide package: " + packageName
                            + " providing SDK library: "
                            + pkg.getSdkLibName());
                    return false;
                }
                // Cannot hide static shared libs as they are considered
                // a part of the using app (emulating static linking). Also
                // static libs are installed always on internal storage.
                if (pkg.getStaticSharedLibName() != null) {
                    Slog.w(TAG, "Cannot hide package: " + packageName
                            + " providing static shared library: "
                            + pkg.getStaticSharedLibName());
                    return false;
                }
            }
            // Only allow protected packages to hide themselves.
            if (hidden && !UserHandle.isSameApp(callingUid, packageState.getAppId())
                    && mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                Slog.w(TAG, "Not hiding protected package: " + packageName);
                return false;
            }

            if (packageState.getUserStateOrDefault(userId).isHidden() == hidden) {
                return false;
            }

            commitPackageStateMutation(null, packageName, packageState1 ->
                    packageState1.userState(userId).setHidden(hidden));

            final PackageStateInternal newPackageState = getPackageStateInternal(packageName);

            if (hidden) {
                killApplication(packageName, newPackageState.getAppId(), userId, "hiding pkg");
                sendApplicationHiddenForUser(packageName, newPackageState, userId);
            } else {
                sendPackageAddedForUser(packageName, newPackageState, userId, DataLoaderType.NONE);
            }

            scheduleWritePackageRestrictions(userId);
            return true;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        final PackageStateInternal stateRead = getPackageStateInternal(packageName);
        if (stateRead == null || !stateRead.isSystem() || stateRead.getPkg() == null) {
            return;
        }
        if (stateRead.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
            throw new SecurityException("Only system or phone callers can modify core apps");
        }

        commitPackageStateMutation(null, mutator -> {
            mutator.forPackage(packageName)
                    .setHiddenUntilInstalled(hidden);
            mutator.forDisabledSystemPackage(packageName)
                    .setHiddenUntilInstalled(hidden);
        });
    }

    @Override
    public boolean setSystemAppInstallState(String packageName, boolean installed, int userId) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        // The target app should always be in system
        if (packageState == null || !packageState.isSystem() || packageState.getPkg() == null) {
            return false;
        }
        if (packageState.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
            throw new SecurityException("Only system or phone callers can modify core apps");
        }
        // Check if the install state is the same
        if (packageState.getUserStateOrDefault(userId).isInstalled() == installed) {
            return false;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            if (installed) {
                // install the app from uninstalled state
                installExistingPackageAsUser(
                        packageName,
                        userId,
                        PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                        PackageManager.INSTALL_REASON_DEVICE_SETUP,
                        null);
                return true;
            }

            // uninstall the app from installed state
            deletePackageVersioned(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    new LegacyPackageDeleteObserver(null).getBinder(),
                    userId,
                    PackageManager.DELETE_SYSTEM_APP);
            return true;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void sendApplicationHiddenForUser(String packageName, PackageStateInternal packageState,
            int userId) {
        final PackageRemovedInfo info = new PackageRemovedInfo(this);
        info.mRemovedPackage = packageName;
        info.mInstallerPackageName = packageState.getInstallSource().installerPackageName;
        info.mRemovedUsers = new int[] {userId};
        info.mBroadcastUsers = new int[] {userId};
        info.mUid = UserHandle.getUid(userId, packageState.getAppId());
        info.sendPackageRemovedBroadcasts(true /*killApp*/, false /*removedBySystem*/);
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns
     * the hidden state of the package for the given user.
     */
    @Override
    public boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @UserIdInt int userId) {
        return mComputer.getApplicationHiddenSettingAsUser(packageName, userId);
    }

    /**
     * @hide
     */
    @Override
    public int installExistingPackageAsUser(String packageName, int userId, int installFlags,
            int installReason, List<String> whiteListedPermissions) {
        return mInstallPackageHelper.installExistingPackageAsUser(packageName, userId, installFlags,
                installReason, whiteListedPermissions, null);
    }

    boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = mUserManager.getUserRestrictions(userId);
        if (restrictions.getBoolean(restrictionKey, false)) {
            Log.w(TAG, "User is restricted: " + restrictionKey);
            return true;
        }
        return false;
    }

    @Override
    public String[] setDistractingPackageRestrictionsAsUser(String[] packageNames,
            int restrictionFlags, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                "setDistractingPackageRestrictionsAsUser");

        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != Process.SYSTEM_UID
                && UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid + " cannot call for user "
                    + userId);
        }
        Objects.requireNonNull(packageNames, "packageNames cannot be null");
        if (restrictionFlags != 0
                && !mSuspendPackageHelper.isSuspendAllowedForUser(userId, callingUid)) {
            Slog.w(TAG, "Cannot restrict packages due to restrictions on user " + userId);
            return packageNames;
        }

        final List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);
        final List<String> unactionedPackages = new ArrayList<>(packageNames.length);

        ArraySet<String> changesToCommit = new ArraySet<>();
        executeWithConsistentComputer(computer -> {
            final boolean[] canRestrict = (restrictionFlags != 0)
                    ? mSuspendPackageHelper.canSuspendPackageForUser(computer, packageNames, userId,
                    callingUid) : null;
            for (int i = 0; i < packageNames.length; i++) {
                final String packageName = packageNames[i];
                final PackageStateInternal packageState =
                        computer.getPackageStateInternal(packageName);
                if (packageState == null
                        || shouldFilterApplication(packageState, callingUid, userId)) {
                    Slog.w(TAG, "Could not find package setting for package: " + packageName
                            + ". Skipping...");
                    unactionedPackages.add(packageName);
                    continue;
                }
                if (canRestrict != null && !canRestrict[i]) {
                    unactionedPackages.add(packageName);
                    continue;
                }
                final int oldDistractionFlags = packageState.getUserStateOrDefault(userId)
                        .getDistractionFlags();
                if (restrictionFlags != oldDistractionFlags) {
                    changedPackagesList.add(packageName);
                    changedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
                    changesToCommit.add(packageName);
                }
            }
        });

        commitPackageStateMutation(null, mutator -> {
            final int size = changesToCommit.size();
            for (int index = 0; index < size; index++) {
                mutator.forPackage(changesToCommit.valueAt(index))
                        .userState(userId)
                        .setDistractionFlags(restrictionFlags);
            }
        });

        if (!changedPackagesList.isEmpty()) {
            final String[] changedPackages = changedPackagesList.toArray(
                    new String[changedPackagesList.size()]);
            mHandler.post(() -> mBroadcastHelper.sendDistractingPackagesChanged(
                    changedPackages, changedUids.toArray(), userId, restrictionFlags));
            scheduleWritePackageRestrictions(userId);
        }
        return unactionedPackages.toArray(new String[0]);
    }

    private void enforceCanSetPackagesSuspendedAsUser(String callingPackage, int callingUid,
            int userId, String callingMethod) {
        if (callingUid == Process.ROOT_UID
                // Need to compare app-id to allow system dialogs access on secondary users
                || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }

        final String ownerPackage = mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId);
        if (ownerPackage != null) {
            final int ownerUid = getPackageUid(ownerPackage, 0, userId);
            if (ownerUid == callingUid) {
                return;
            }
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                callingMethod);

        final int packageUid = getPackageUid(callingPackage, 0, userId);
        final boolean allowedPackageUid = packageUid == callingUid;
        // TODO(b/139383163): remove special casing for shell and enforce INTERACT_ACROSS_USERS_FULL
        final boolean allowedShell = callingUid == SHELL_UID
                && UserHandle.isSameApp(packageUid, callingUid);

        if (!allowedShell && !allowedPackageUid) {
            throw new SecurityException("Calling package " + callingPackage + " in user "
                    + userId + " does not belong to calling uid " + callingUid);
        }
    }

    @Override
    public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended,
            PersistableBundle appExtras, PersistableBundle launcherExtras,
            SuspendDialogInfo dialogInfo, String callingPackage, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCanSetPackagesSuspendedAsUser(callingPackage, callingUid, userId,
                "setPackagesSuspendedAsUser");
        return mSuspendPackageHelper.setPackagesSuspended(packageNames, suspended, appExtras,
                launcherExtras, dialogInfo, callingPackage, userId, callingUid);
    }

    @Override
    public Bundle getSuspendedPackageAppExtras(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getPackageUid(packageName, 0, userId) != callingUid) {
            throw new SecurityException("Calling package " + packageName
                    + " does not belong to calling uid " + callingUid);
        }
        return mSuspendPackageHelper.getSuspendedPackageAppExtras(
                packageName, userId, callingUid);
    }

    @Override
    public boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.isPackageSuspendedForUser(packageName, userId);
    }

    void unsuspendForSuspendingPackage(String suspendingPackage, int userId) {
        // TODO: This can be replaced by a special parameter to iterate all packages, rather than
        //  this weird pre-collect of all packages.
        final String[] allPackages = getPackageStates().keySet().toArray(new String[0]);
        mSuspendPackageHelper.removeSuspensionsBySuspendingPackage(
                allPackages, suspendingPackage::equals, userId);
    }

    private boolean isSuspendingAnyPackages(String suspendingPackage, int userId) {
        return mComputer.isSuspendingAnyPackages(suspendingPackage, userId);
    }

    void removeAllDistractingPackageRestrictions(int userId) {
        final String[] allPackages = mComputer.getAllAvailablePackageNames();
        removeDistractingPackageRestrictions(allPackages, userId);
    }

    /**
     * Removes any {@link android.content.pm.PackageManager.DistractionRestriction restrictions}
     * set on given packages.
     *
     * <p> Caller must flush package restrictions if it cares about immediate data consistency.
     *
     * @param packagesToChange The packages on which restrictions are to be removed.
     * @param userId the user for which changes are taking place.
     */
    private void removeDistractingPackageRestrictions(String[] packagesToChange, int userId) {
        final List<String> changedPackages = new ArrayList<>();
        final IntArray changedUids = new IntArray();
        for (String packageName : packagesToChange) {
            final PackageStateInternal ps = getPackageStateInternal(packageName);
            if (ps != null && ps.getUserStateOrDefault(userId).getDistractionFlags() != 0) {
                changedPackages.add(ps.getPackageName());
                changedUids.add(UserHandle.getUid(userId, ps.getAppId()));
            }
        }
        commitPackageStateMutation(null, mutator -> {
            for (int index = 0; index < changedPackages.size(); index++) {
                mutator.forPackage(changedPackages.get(index))
                        .userState(userId)
                        .setDistractionFlags(0);
            }
        });

        if (!changedPackages.isEmpty()) {
            final String[] packageArray = changedPackages.toArray(
                    new String[changedPackages.size()]);
            mHandler.post(() -> mBroadcastHelper.sendDistractingPackagesChanged(
                    packageArray, changedUids.toArray(), userId, 0));
            scheduleWritePackageRestrictions(userId);
        }
    }

    @Override
    public String[] getUnsuspendablePackagesForUser(String[] packageNames, int userId) {
        Objects.requireNonNull(packageNames, "packageNames cannot be null");
        mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                "getUnsuspendablePackagesForUser");
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid
                    + " cannot query getUnsuspendablePackagesForUser for user " + userId);
        }
        return mSuspendPackageHelper.getUnsuspendablePackagesForUser(
                packageNames, userId, callingUid);
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can verify applications");
        final int callingUid = Binder.getCallingUid();

        final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
        final PackageVerificationResponse response = new PackageVerificationResponse(
                verificationCode, callingUid);
        msg.arg1 = id;
        msg.obj = response;
        mHandler.sendMessage(msg);
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can extend verification timeouts");
        final int callingUid = Binder.getCallingUid();

        mHandler.post(() -> {
            final PackageVerificationState state = mPendingVerification.get(id);
            final PackageVerificationResponse response = new PackageVerificationResponse(
                    verificationCodeAtTimeout, callingUid);

            long delay = millisecondsToDelay;
            if (delay > PackageManager.MAXIMUM_VERIFICATION_TIMEOUT) {
                delay = PackageManager.MAXIMUM_VERIFICATION_TIMEOUT;
            }
            if (delay < 0) {
                delay = 0;
            }

            if ((state != null) && !state.timeoutExtended()) {
                state.extendTimeout();

                final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
                msg.arg1 = id;
                msg.obj = response;
                mHandler.sendMessageDelayed(msg, delay);
            }
        });
    }

    private void setEnableRollbackCode(int token, int enableRollbackCode) {
        final Message msg = mHandler.obtainMessage(ENABLE_ROLLBACK_STATUS);
        msg.arg1 = token;
        msg.arg2 = enableRollbackCode;
        mHandler.sendMessage(msg);
    }

    @Override
    public void finishPackageInstall(int token, boolean didLaunch) {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system is allowed to finish installs");

        if (DEBUG_INSTALL) {
            Slog.v(TAG, "BM finishing package install for " + token);
        }
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "restore", token);

        final Message msg = mHandler.obtainMessage(POST_INSTALL, token, didLaunch ? 1 : 0);
        mHandler.sendMessage(msg);
    }

    @Deprecated
    @Override
    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) {
        DomainVerificationProxyV1.queueLegacyVerifyResult(mContext, mDomainVerificationConnection,
                id, verificationCode, failedDomains, Binder.getCallingUid());
    }

    @Deprecated
    @Override
    public int getIntentVerificationStatus(String packageName, int userId) {
        return mDomainVerificationManager.getLegacyState(packageName, userId);
    }

    @Deprecated
    @Override
    public boolean updateIntentVerificationStatus(String packageName, int status, int userId) {
        return mDomainVerificationManager.setLegacyUserState(packageName, userId, status);
    }

    @Deprecated
    @Override
    public @NonNull ParceledListSlice<IntentFilterVerificationInfo> getIntentFilterVerifications(
            String packageName) {
        return ParceledListSlice.emptyList();
    }

    @NonNull
    @Override
    public ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        return mComputer.getAllIntentFilters(packageName);
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final FunctionalUtils.ThrowingCheckedFunction<Computer, Boolean, RuntimeException>
                implementation = computer -> {
            if (computer.getInstantAppPackageName(callingUid) != null) {
                return false;
            }

            PackageStateInternal targetPackageState =
                    computer.getPackageStateInternal(targetPackage);
            if (targetPackageState == null
                    || computer.shouldFilterApplication(targetPackageState, callingUid,
                    callingUserId)) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }

            PackageStateInternal installerPackageState = null;
            if (installerPackageName != null) {
                installerPackageState = computer.getPackageStateInternal(installerPackageName);
                if (installerPackageState == null
                        || shouldFilterApplication(
                        installerPackageState, callingUid, callingUserId)) {
                    throw new IllegalArgumentException("Unknown installer package: "
                            + installerPackageName);
                }
            }

            Signature[] callerSignature;
            final int appId = UserHandle.getAppId(callingUid);
            Pair<PackageStateInternal, SharedUserApi> either =
                    computer.getPackageOrSharedUser(appId);
            if (either != null) {
                if (either.first != null) {
                    callerSignature = either.first.getSigningDetails().getSignatures();
                } else {
                    callerSignature = either.second.getSigningDetails().getSignatures();
                }
            } else {
                throw new SecurityException("Unknown calling UID: " + callingUid);
            }

            // Verify: can't set installerPackageName to a package that is
            // not signed with the same cert as the caller.
            if (installerPackageState != null) {
                if (compareSignatures(callerSignature,
                        installerPackageState.getSigningDetails().getSignatures())
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as new installer package "
                                    + installerPackageName);
                }
            }

            // Verify: if target already has an installer package, it must
            // be signed with the same cert as the caller.
            String targetInstallerPackageName =
                    targetPackageState.getInstallSource().installerPackageName;
            PackageStateInternal targetInstallerPkgSetting = targetInstallerPackageName == null
                    ? null : computer.getPackageStateInternal(targetInstallerPackageName);

            if (targetInstallerPkgSetting != null) {
                if (compareSignatures(callerSignature,
                        targetInstallerPkgSetting.getSigningDetails().getSignatures())
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as old installer package "
                                    + targetInstallerPackageName);
                }
            } else if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                    != PERMISSION_GRANTED) {
                // This is probably an attempt to exploit vulnerability b/150857253 of taking
                // privileged installer permissions when the installer has been uninstalled or
                // was never set.
                EventLog.writeEvent(0x534e4554, "150857253", callingUid, "");

                final long binderToken = Binder.clearCallingIdentity();
                try {
                    if (mInjector.getCompatibility().isChangeEnabledByUid(
                            THROW_EXCEPTION_ON_REQUIRE_INSTALL_PACKAGES_TO_ADD_INSTALLER_PACKAGE,
                            callingUid)) {
                        throw new SecurityException("Neither user " + callingUid
                                + " nor current process has "
                                + Manifest.permission.INSTALL_PACKAGES);
                    } else {
                        // If change disabled, fail silently for backwards compatibility
                        return false;
                    }
                } finally {
                    Binder.restoreCallingIdentity(binderToken);
                }
            }

            return true;
        };
        PackageStateMutator.InitialState initialState = recordInitialState();
        boolean allowed = executeWithConsistentComputerReturningThrowing(implementation);
        if (allowed) {
            // TODO: Need to lock around here to handle mSettings.addInstallerPackageNames,
            //  should find an alternative which avoids any race conditions
            PackageStateInternal targetPackageState;
            synchronized (mLock) {
                PackageStateMutator.Result result = commitPackageStateMutation(initialState,
                        targetPackage, state -> state.setInstaller(installerPackageName));
                if (result.isPackagesChanged() || result.isStateChanged()) {
                    synchronized (mPackageStateWriteLock) {
                        allowed = executeWithConsistentComputerReturningThrowing(implementation);
                        if (allowed) {
                            commitPackageStateMutation(null, targetPackage,
                                    state -> state.setInstaller(installerPackageName));
                        } else {
                            return;
                        }
                    }
                }
                targetPackageState = getPackageStateInternal(targetPackage);
                mSettings.addInstallerPackageNames(targetPackageState.getInstallSource());
            }
            mAppsFilter.addPackage(targetPackageState);
            scheduleWriteSettings();
        }
    }

    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint,
            String callerPackageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        mInjector.getSystemService(AppOpsManager.class).checkPackage(Binder.getCallingUid(),
                callerPackageName);

        final PackageStateMutator.InitialState initialState = recordInitialState();

        final FunctionalUtils.ThrowingFunction<Computer, PackageStateMutator.Result>
                implementation = computer -> {
            PackageStateInternal packageState = computer.getPackageStateFiltered(packageName,
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
            if (packageState == null) {
                throw new IllegalArgumentException("Unknown target package " + packageName);
            }

            if (!Objects.equals(callerPackageName,
                    packageState.getInstallSource().installerPackageName)) {
                throw new IllegalArgumentException("Calling package " + callerPackageName
                        + " is not installer for " + packageName);
            }

            if (packageState.getCategoryOverride() != categoryHint) {
                return commitPackageStateMutation(initialState,
                        packageName, state -> state.setCategoryOverride(categoryHint));
            } else {
                return null;
            }
        };

        PackageStateMutator.Result result = executeWithConsistentComputerReturning(implementation);
        if (result != null && result.isStateChanged() && !result.isSpecificPackageNull()) {
            // TODO: Specific return value of what state changed?
            // The installer on record might have changed, retry with lock
            synchronized (mPackageStateWriteLock) {
                result = executeWithConsistentComputerReturning(implementation);
            }
        }

        if (result != null && result.isCommitted()) {
            scheduleWriteSettings();
        }
    }

    /**
     * Callback from PackageSettings whenever an app is first transitioned out of the
     * 'stopped' state.  Normally we just issue the broadcast, but we can't do that if
     * the app was "launched" for a restoreAtInstall operation.  Therefore we check
     * here whether the app is the target of an ongoing install, and only send the
     * broadcast immediately if it is not in that state.  If it *is* undergoing a restore,
     * the first-launch broadcast will be sent implicitly on that basis in POST_INSTALL
     * handling.
     */
    void notifyFirstLaunch(final String packageName, final String installerPackage,
            final int userId) {
        // Serialize this with the rest of the install-process message chain.  In the
        // restore-at-install case, this Runnable will necessarily run before the
        // POST_INSTALL message is processed, so the contents of mRunningInstalls
        // are coherent.  In the non-restore case, the app has already completed install
        // and been launched through some other means, so it is not in a problematic
        // state for observers to see the FIRST_LAUNCH signal.
        mHandler.post(() -> {
            for (int i = 0; i < mRunningInstalls.size(); i++) {
                final PostInstallData data = mRunningInstalls.valueAt(i);
                if (data.res.mReturnCode != PackageManager.INSTALL_SUCCEEDED) {
                    continue;
                }
                if (packageName.equals(data.res.mPkg.getPackageName())) {
                    // right package; but is it for the right user?
                    for (int uIndex = 0; uIndex < data.res.mNewUsers.length; uIndex++) {
                        if (userId == data.res.mNewUsers[uIndex]) {
                            if (DEBUG_BACKUP) {
                                Slog.i(TAG, "Package " + packageName
                                        + " being restored so deferring FIRST_LAUNCH");
                            }
                            return;
                        }
                    }
                }
            }
            // didn't find it, so not being restored
            if (DEBUG_BACKUP) {
                Slog.i(TAG, "Package " + packageName + " sending normal FIRST_LAUNCH");
            }
            final boolean isInstantApp = isInstantAppInternal(
                    packageName, userId, Process.SYSTEM_UID);
            final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
            final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
            mBroadcastHelper.sendFirstLaunchBroadcast(
                    packageName, installerPackage, userIds, instantUserIds);
        });
    }

    void notifyPackageChangeObservers(PackageChangeEvent event) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "notifyPackageChangeObservers");
            synchronized (mPackageChangeObservers) {
                for (IPackageChangeObserver observer : mPackageChangeObservers) {
                    try {
                        observer.onPackageChanged(event);
                    } catch (RemoteException e) {
                        Log.wtf(TAG, e);
                    }
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    VersionInfo getSettingsVersionForPackage(AndroidPackage pkg) {
        if (pkg.isExternalStorage()) {
            if (TextUtils.isEmpty(pkg.getVolumeUuid())) {
                return mSettings.getExternalVersion();
            } else {
                return mSettings.findOrCreateVersion(pkg.getVolumeUuid());
            }
        } else {
            return mSettings.getInternalVersion();
        }
    }

    @Override
    public void deletePackageAsUser(String packageName, int versionCode,
            IPackageDeleteObserver observer, int userId, int flags) {
        deletePackageVersioned(new VersionedPackage(packageName, versionCode),
                new LegacyPackageDeleteObserver(observer).getBinder(), userId, flags);
    }

    @Override
    public void deleteExistingPackageAsUser(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId) {
        mDeletePackageHelper.deleteExistingPackageAsUser(
                versionedPackage, observer, userId);
    }

    @Override
    public void deletePackageVersioned(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags) {
        mDeletePackageHelper.deletePackageVersionedInternal(
                versionedPackage, observer, userId, deleteFlags, false);
    }

    private String resolveExternalPackageName(AndroidPackage pkg) {
        return mComputer.resolveExternalPackageName(pkg);
    }

    String resolveInternalPackageName(String packageName, long versionCode) {
        return mComputer.resolveInternalPackageName(packageName, versionCode);
    }

    boolean isCallerVerifier(int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        return mRequiredVerifierPackage != null &&
                callingUid == getPackageUid(mRequiredVerifierPackage, 0, callingUserId);
    }

    @Override
    public boolean isPackageDeviceAdminOnAnyUser(String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (checkUidPermission(android.Manifest.permission.MANAGE_USERS, callingUid)
                != PERMISSION_GRANTED) {
            EventLog.writeEvent(0x534e4554, "128599183", -1, "");
            throw new SecurityException(android.Manifest.permission.MANAGE_USERS
                    + " permission is required to call this API");
        }
        if (getInstantAppPackageName(callingUid) != null
                && !isCallerSameApp(packageName, callingUid)) {
            return false;
        }
        return isPackageDeviceAdmin(packageName, UserHandle.USER_ALL);
    }

    boolean isPackageDeviceAdmin(String packageName, int userId) {
        final IDevicePolicyManager dpm = getDevicePolicyManager();
        try {
            if (dpm != null) {
                final ComponentName deviceOwnerComponentName = dpm.getDeviceOwnerComponent(
                        /* callingUserOnly =*/ false);
                final String deviceOwnerPackageName = deviceOwnerComponentName == null ? null
                        : deviceOwnerComponentName.getPackageName();
                // Does the package contains the device owner?
                // TODO Do we have to do it even if userId != UserHandle.USER_ALL?  Otherwise,
                // this check is probably not needed, since DO should be registered as a device
                // admin on some user too. (Original bug for this: b/17657954)
                if (packageName.equals(deviceOwnerPackageName)) {
                    return true;
                }
                // Does it contain a device admin for any user?
                int[] users;
                if (userId == UserHandle.USER_ALL) {
                    users = mUserManager.getUserIds();
                } else {
                    users = new int[]{userId};
                }
                for (int i = 0; i < users.length; ++i) {
                    if (dpm.packageHasActiveAdmins(packageName, users[i])) {
                        return true;
                    }
                }
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    /** Returns the device policy manager interface. */
    private IDevicePolicyManager getDevicePolicyManager() {
        if (mDevicePolicyManager == null) {
            // No need to synchronize; worst-case scenario it will be fetched twice.
            mDevicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                            ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        }
        return mDevicePolicyManager;
    }

    @Override
    public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall,
            int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState != null && packageState.getPkg() != null) {
            AndroidPackage pkg = packageState.getPkg();
            // Cannot block uninstall SDK libs as they are controlled by SDK manager.
            if (pkg.getSdkLibName() != null) {
                Slog.w(TAG, "Cannot block uninstall of package: " + packageName
                        + " providing SDK library: " + pkg.getSdkLibName());
                return false;
            }
            // Cannot block uninstall of static shared libs as they are
            // considered a part of the using app (emulating static linking).
            // Also static libs are installed always on internal storage.
            if (pkg.getStaticSharedLibName() != null) {
                Slog.w(TAG, "Cannot block uninstall of package: " + packageName
                        + " providing static shared library: " + pkg.getStaticSharedLibName());
                return false;
            }
        }
        synchronized (mLock) {
            mSettings.setBlockUninstallLPw(userId, packageName, blockUninstall);
        }

        scheduleWritePackageRestrictions(userId);
        return true;
    }

    @Override
    public boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.getBlockUninstallForUser(packageName, userId);
    }

    @Override
    public boolean setRequiredForSystemUser(String packageName, boolean requiredForSystemUser) {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "setRequiredForSystemUser can only be run by the system or root");

        PackageStateMutator.Result result = commitPackageStateMutation(null, packageName,
                packageState -> packageState.setRequiredForSystemUser(requiredForSystemUser));
        if (!result.isCommitted()) {
            return false;
        }

        scheduleWriteSettings();
        return true;
    }

    @Override
    public void clearApplicationProfileData(String packageName) {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can clear all profile data");

        final AndroidPackage pkg = getPackage(packageName);
        try (PackageFreezer ignored = freezePackage(packageName, "clearApplicationProfileData")) {
            synchronized (mInstallLock) {
                mAppDataHelper.clearAppProfilesLIF(pkg);
            }
        }
    }

    @Override
    public void clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, final int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_USER_DATA, null);

        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "clear application data");

        if (mComputer.getPackageStateFiltered(packageName, callingUid, userId) == null) {
            if (observer != null) {
                mHandler.post(() -> {
                    try {
                        observer.onRemoveCompleted(packageName, false);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                });
            }
            return;
        }
        if (mProtectedPackages.isPackageDataProtected(userId, packageName)) {
            throw new SecurityException("Cannot clear data for a protected package: "
                    + packageName);
        }

        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeeded;
                try (PackageFreezer freezer = freezePackage(packageName,
                        "clearApplicationUserData")) {
                    synchronized (mInstallLock) {
                        succeeded = clearApplicationUserDataLIF(packageName, userId);
                    }
                    mInstantAppRegistry.deleteInstantApplicationMetadata(packageName, userId);
                    synchronized (mLock) {
                        if (succeeded) {
                            resetComponentEnabledSettingsIfNeededLPw(packageName, userId);
                        }
                    }
                }
                if (succeeded) {
                    // invoke DeviceStorageMonitor's update method to clear any notifications
                    DeviceStorageMonitorInternal dsm = LocalServices
                            .getService(DeviceStorageMonitorInternal.class);
                    if (dsm != null) {
                        dsm.checkMemory();
                    }
                    if (checkPermission(Manifest.permission.SUSPEND_APPS, packageName, userId)
                            == PERMISSION_GRANTED) {
                        unsuspendForSuspendingPackage(packageName, userId);
                        removeAllDistractingPackageRestrictions(userId);
                        flushPackageRestrictionsAsUserInternalLocked(userId);
                    }
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean clearApplicationUserDataLIF(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }

        // Try finding details about the requested package
        AndroidPackage pkg = getPackage(packageName);
        if (pkg == null) {
            Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
            return false;
        }
        mPermissionManager.resetRuntimePermissions(pkg, userId);

        mAppDataHelper.clearAppDataLIF(pkg, userId,
                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);

        final int appId = UserHandle.getAppId(pkg.getUid());
        mAppDataHelper.clearKeystoreData(userId, appId);

        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mInjector.getLocalService(StorageManagerInternal.class);
        final int flags;
        if (StorageManager.isUserKeyUnlocked(userId) && smInternal.isCeStoragePrepared(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
        } else if (umInternal.isUserRunning(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE;
        } else {
            flags = 0;
        }
        mAppDataHelper.prepareAppDataContentsLIF(pkg, getPackageStateInternal(packageName), userId,
                flags);

        return true;
    }

    /**
     * Update component enabled settings to {@link PackageManager#COMPONENT_ENABLED_STATE_DEFAULT}
     * if the resetEnabledSettingsOnAppDataCleared is {@code true}.
     */
    private void resetComponentEnabledSettingsIfNeededLPw(String packageName, int userId) {
        final AndroidPackage pkg = packageName != null ? mPackages.get(packageName) : null;
        if (pkg == null || !pkg.isResetEnabledSettingsOnAppDataCleared()) {
            return;
        }
        final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
        if (pkgSetting == null) {
            return;
        }
        final ArrayList<String> updatedComponents = new ArrayList<>();
        final Consumer<? super ParsedMainComponent> resetSettings = (component) -> {
            if (pkgSetting.restoreComponentLPw(component.getClassName(), userId)) {
                updatedComponents.add(component.getClassName());
            }
        };
        for (int i = 0; i < pkg.getActivities().size(); i++) {
            resetSettings.accept(pkg.getActivities().get(i));
        }
        for (int i = 0; i < pkg.getReceivers().size(); i++) {
            resetSettings.accept(pkg.getReceivers().get(i));
        }
        for (int i = 0; i < pkg.getServices().size(); i++) {
            resetSettings.accept(pkg.getServices().get(i));
        }
        for (int i = 0; i < pkg.getProviders().size(); i++) {
            resetSettings.accept(pkg.getProviders().get(i));
        }
        if (ArrayUtils.isEmpty(updatedComponents)) {
            // nothing changed
            return;
        }

        updateSequenceNumberLP(pkgSetting, new int[] { userId });
        updateInstantAppInstallerLocked(packageName);
        scheduleWritePackageRestrictions(userId);

        final ArrayList<String> pendingComponents = mPendingBroadcasts.get(userId, packageName);
        if (pendingComponents == null) {
            mPendingBroadcasts.put(userId, packageName, updatedComponents);
        } else {
            for (int i = 0; i < updatedComponents.size(); i++) {
                final String updatedComponent = updatedComponents.get(i);
                if (!pendingComponents.contains(updatedComponent)) {
                    pendingComponents.add(updatedComponent);
                }
            }
        }
        if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
            mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
        }
    }

    @Override
    public void deleteApplicationCacheFiles(final String packageName,
            final IPackageDataObserver observer) {
        final int userId = UserHandle.getCallingUserId();
        deleteApplicationCacheFilesAsUser(packageName, userId, observer);
    }

    @Override
    public void deleteApplicationCacheFilesAsUser(final String packageName, final int userId,
            final IPackageDataObserver observer) {
        final int callingUid = Binder.getCallingUid();
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES)
                != PackageManager.PERMISSION_GRANTED) {
            // If the caller has the old delete cache permission, silently ignore.  Else throw.
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.DELETE_CACHE_FILES)
                    == PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Calling uid " + callingUid + " does not have " +
                        android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES +
                        ", silently ignoring");
                return;
            }
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES, null);
        }
        enforceCrossUserPermission(callingUid, userId, /* requireFullPermission= */ true,
                /* checkShell= */ false, "delete application cache files");
        final int hasAccessInstantApps = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_INSTANT_APPS);

        final AndroidPackage pkg = getPackage(packageName);

        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(() -> {
            final PackageStateInternal ps =
                    pkg == null ? null : getPackageStateInternal(pkg.getPackageName());
            boolean doClearData = true;
            if (ps != null) {
                final boolean targetIsInstantApp =
                        ps.getUserStateOrDefault(UserHandle.getUserId(callingUid)).isInstantApp();
                doClearData = !targetIsInstantApp
                        || hasAccessInstantApps == PackageManager.PERMISSION_GRANTED;
            }
            if (doClearData) {
                synchronized (mInstallLock) {
                    final int flags = FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL;
                    // We're only clearing cache files, so we don't care if the
                    // app is unfrozen and still able to run
                    mAppDataHelper.clearAppDataLIF(pkg, userId,
                            flags | Installer.FLAG_CLEAR_CACHE_ONLY);
                    mAppDataHelper.clearAppDataLIF(pkg, userId,
                            flags | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
                }
            }
            if (observer != null) {
                try {
                    observer.onRemoveCompleted(packageName, true);
                } catch (RemoteException e) {
                    Log.i(TAG, "Observer no longer exists.");
                }
            }
        });
    }

    @Override
    public void getPackageSizeInfo(final String packageName, int userId,
            final IPackageStatsObserver observer) {
        throw new UnsupportedOperationException(
                "Shame on you for calling the hidden API getPackageSizeInfo(). Shame!");
    }

    int getUidTargetSdkVersion(int uid) {
        return mComputer.getUidTargetSdkVersion(uid);
    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId, boolean removeExisting) {
        mPreferredActivityHelper.addPreferredActivity(
                new WatchedIntentFilter(filter), match, set, activity, true, userId,
                "Adding preferred", removeExisting);
    }

    void postPreferredActivityChangedBroadcast(int userId) {
        mHandler.post(() -> mBroadcastHelper.sendPreferredActivityChangedBroadcast(userId));
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        mPreferredActivityHelper.replacePreferredActivity(new WatchedIntentFilter(filter), match,
                                 set, activity, userId);
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        mPreferredActivityHelper.clearPackagePreferredActivities(packageName);
    }


    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    @GuardedBy("mLock")
    void clearPackagePreferredActivitiesLPw(String packageName,
            @NonNull SparseBooleanArray outUserChanged, int userId) {
        mSettings.clearPackagePreferredActivities(packageName, outUserChanged, userId);
    }

    void restorePermissionsAndUpdateRolesForNewUserInstall(String packageName,
            @UserIdInt int userId) {
        // We may also need to apply pending (restored) runtime permission grants
        // within these users.
        mPermissionManager.restoreDelayedRuntimePermissions(packageName, userId);

        // Persistent preferred activity might have came into effect due to this
        // install.
        mPreferredActivityHelper.updateDefaultHomeNotLocked(userId);
    }

    @Override
    public void resetApplicationPreferences(int userId) {
        mPreferredActivityHelper.resetApplicationPreferences(userId);
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        return mPreferredActivityHelper.getPreferredActivities(outFilters, outActivities,
                packageName, mComputer);
    }

    @Override
    public void addPersistentPreferredActivity(IntentFilter filter, ComponentName activity,
            int userId) {
        mPreferredActivityHelper.addPersistentPreferredActivity(new WatchedIntentFilter(filter),
                activity, userId);
    }

    @Override
    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        mPreferredActivityHelper.clearPackagePersistentPreferredActivities(packageName, userId);
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the
     * full set of preferred activities in its canonical XML format.  Returns the
     * XML output as a byte array, or null if there is none.
     */
    @Override
    public byte[] getPreferredActivityBackup(int userId) {
        return mPreferredActivityHelper.getPreferredActivityBackup(userId);
    }

    @Override
    public void restorePreferredActivities(byte[] backup, int userId) {
        mPreferredActivityHelper.restorePreferredActivities(backup, userId);
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the
     * default browser (etc) settings in its canonical XML format.  Returns the default
     * browser XML representation as a byte array, or null if there is none.
     */
    @Override
    public byte[] getDefaultAppsBackup(int userId) {
        return mPreferredActivityHelper.getDefaultAppsBackup(userId);
    }

    @Override
    public void restoreDefaultApps(byte[] backup, int userId) {
        mPreferredActivityHelper.restoreDefaultApps(backup, userId);
    }

    @Override
    public byte[] getDomainVerificationBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getDomainVerificationBackup()");
        }

        try {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                TypedXmlSerializer serializer = Xml.resolveSerializer(output);
                mDomainVerificationManager.writeSettings(serializer, true, userId);
                return output.toByteArray();
            }
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write domain verification for backup", e);
            }
            return null;
        }
    }

    @Override
    public void restoreDomainVerification(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }

        try {
            ByteArrayInputStream input = new ByteArrayInputStream(backup);
            TypedXmlPullParser parser = Xml.resolvePullParser(input);

            // User ID input isn't necessary here as it assumes the user integers match and that
            // the only states inside the backup XML are for the target user.
            mDomainVerificationManager.restoreSettings(parser);
            input.close();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring domain verification: " + e.getMessage());
            }
        }
    }

    @Override
    public void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        addCrossProfileIntentFilter(new WatchedIntentFilter(intentFilter), ownerPackage,
                                    sourceUserId, targetUserId, flags);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addCrossProfileIntentFilter(WatchedIntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        if (intentFilter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a crossProfile intent filter with no filter actions");
            return;
        }
        synchronized (mLock) {
            CrossProfileIntentFilter newFilter = new CrossProfileIntentFilter(intentFilter,
                    ownerPackage, targetUserId, flags);
            CrossProfileIntentResolver resolver =
                    mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArrayList<CrossProfileIntentFilter> existing = resolver.findFilters(intentFilter);
            // We have all those whose filter is equal. Now checking if the rest is equal as well.
            if (existing != null) {
                int size = existing.size();
                for (int i = 0; i < size; i++) {
                    if (newFilter.equalsIgnoreFilter(existing.get(i))) {
                        return;
                    }
                }
            }
            resolver.addFilter(snapshotComputer(), newFilter);
        }
        scheduleWritePackageRestrictions(sourceUserId);
    }

    @Override
    public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        final int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        synchronized (mLock) {
            CrossProfileIntentResolver resolver =
                    mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArraySet<CrossProfileIntentFilter> set =
                    new ArraySet<>(resolver.filterSet());
            for (CrossProfileIntentFilter filter : set) {
                if (filter.getOwnerPackage().equals(ownerPackage)) {
                    resolver.removeFilter(filter);
                }
            }
        }
        scheduleWritePackageRestrictions(sourceUserId);
    }

    // Enforcing that callingUid is owning pkg on userId
    private void enforceOwnerRights(String pkg, int callingUid) {
        // The system owns everything.
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }
        final String[] callerPackageNames = getPackagesForUid(callingUid);
        if (!ArrayUtils.contains(callerPackageNames, pkg)) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not own package " + pkg);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        PackageInfo pi = getPackageInfo(pkg, 0, callingUserId);
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg + " on user "
                    + callingUserId);
        }
    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return getHomeActivitiesAsUser(allHomeCandidates, UserHandle.getCallingUserId());
    }

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        UserManagerService ums = UserManagerService.getInstance();
        if (ums == null || sessionInfo.isStaged()) {
            return;
        }
        final UserInfo parent = ums.getProfileParent(userId);
        final int launcherUid = (parent != null) ? parent.id : userId;
        final ComponentName launcherComponent = getDefaultHomeActivity(launcherUid);
        mBroadcastHelper.sendSessionCommitBroadcast(sessionInfo, userId, launcherUid,
                launcherComponent, mAppPredictionServicePackage);
    }

    /**
     * Report the 'Home' activity which is currently set as "always use this one". If non is set
     * then reports the most likely home activity or null if there are more than one.
     */
    private ComponentName getDefaultHomeActivity(int userId) {
        return mComputer.getDefaultHomeActivity(userId);
    }

    Intent getHomeIntent() {
        return mComputer.getHomeIntent();
    }

    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId) {
        return mComputer.getHomeActivitiesAsUser(allHomeCandidates,
                userId);
    }

    @Override
    public void setHomeActivity(ComponentName comp, int userId) {
        mPreferredActivityHelper.setHomeActivity(comp, userId);
    }

    private @Nullable String getSetupWizardPackageNameImpl(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_SETUP_WIZARD);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
                        | MATCH_DISABLED_COMPONENTS,
                UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            Slog.e(TAG, "There should probably be exactly one setup wizard; found " + matches.size()
                    + ": matches=" + matches);
            return null;
        }
    }

    private @Nullable String getStorageManagerPackageName(@NonNull Computer computer) {
        final Intent intent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
                        | MATCH_DISABLED_COMPONENTS,
                UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            Slog.w(TAG, "There should probably be exactly one storage manager; found "
                    + matches.size() + ": matches=" + matches);
            return null;
        }
    }

    private @NonNull String getRequiredSupplementalProcessPackageName() {
        final Intent intent = new Intent(SupplementalProcessManagerLocal.SERVICE_INTERFACE);

        final List<ResolveInfo> matches = queryIntentServicesInternal(
                intent,
                /* resolvedType= */ null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM,
                /* callingUid= */ Process.myUid(),
                /* includeInstantApps= */ false);
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There should exactly one supplemental process; found "
                    + matches.size() + ": matches=" + matches);
        }
    }

    @Override
    public String getDefaultTextClassifierPackageName() {
        return ensureSystemPackageName(
                mContext.getString(R.string.config_servicesExtensionPackage));
    }

    @Override
    public String getSystemTextClassifierPackageName() {
        return ensureSystemPackageName(
                mContext.getString(R.string.config_defaultTextClassifierPackage));
    }

    @Override
    public @Nullable String getAttentionServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultAttentionService));
    }

    @Override
    public @Nullable String getRotationResolverPackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultRotationResolverService));
    }

    @Nullable
    private String getDeviceConfiguratorPackageName() {
        return ensureSystemPackageName(mContext.getString(
                R.string.config_deviceConfiguratorPackageName));
    }

    @Override
    public String getWellbeingPackageName() {
        final long identity = Binder.clearCallingIdentity();
        try {
            return CollectionUtils.firstOrNull(
                    mContext.getSystemService(RoleManager.class).getRoleHolders(
                            RoleManager.ROLE_SYSTEM_WELLBEING));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getAppPredictionServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultAppPredictionService));
    }

    @Override
    public String getSystemCaptionsServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultSystemCaptionsService));
    }

    @Override
    public String getSetupWizardPackageName() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }
        return mPmInternal.getSetupWizardPackageName();
    }

    public @Nullable String getAmbientContextDetectionPackageName() {
        return ensureSystemPackageName(getPackageFromComponentString(
                        R.string.config_defaultAmbientContextDetectionService));
    }

    public String getIncidentReportApproverPackageName() {
        return ensureSystemPackageName(mContext.getString(
                R.string.config_incidentReportApproverPackage));
    }

    @Override
    public String getContentCaptureServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultContentCaptureService));
    }

    public String getOverlayConfigSignaturePackageName() {
        return ensureSystemPackageName(mInjector.getSystemConfig()
                .getOverlayConfigSignaturePackage());
    }

    @Nullable
    private String getRetailDemoPackageName() {
        final String predefinedPkgName = mContext.getString(R.string.config_retailDemoPackage);
        final String predefinedSignature = mContext.getString(
                R.string.config_retailDemoPackageSignature);

        if (TextUtils.isEmpty(predefinedPkgName) || TextUtils.isEmpty(predefinedSignature)) {
            return null;
        }

        final AndroidPackage androidPkg = mPackages.get(predefinedPkgName);
        if (androidPkg != null) {
            final SigningDetails signingDetail = androidPkg.getSigningDetails();
            if (signingDetail != null && signingDetail.getSignatures() != null) {
                try {
                    final MessageDigest msgDigest = MessageDigest.getInstance("SHA-256");
                    for (Signature signature : signingDetail.getSignatures()) {
                        if (TextUtils.equals(predefinedSignature,
                                HexEncoding.encodeToString(msgDigest.digest(
                                        signature.toByteArray()), false))) {
                            return predefinedPkgName;
                        }
                    }
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(
                            TAG,
                            "Unable to verify signatures as getting the retail demo package name",
                            e);
                }
            }
        }

        return null;
    }

    @Nullable
    private String getRecentsPackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_recentsComponentName));

    }

    @Nullable
    private String getPackageFromComponentString(@StringRes int stringResId) {
        final String componentString = mContext.getString(stringResId);
        if (TextUtils.isEmpty(componentString)) {
            return null;
        }
        final ComponentName component = ComponentName.unflattenFromString(componentString);
        if (component == null) {
            return null;
        }
        return component.getPackageName();
    }

    @Nullable
    private String ensureSystemPackageName(@Nullable String packageName) {
        if (packageName == null) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (getPackageInfo(packageName, MATCH_FACTORY_ONLY, UserHandle.USER_SYSTEM) == null) {
                PackageInfo packageInfo = getPackageInfo(packageName, 0, UserHandle.USER_SYSTEM);
                if (packageInfo != null) {
                    EventLog.writeEvent(0x534e4554, "145981139", packageInfo.applicationInfo.uid,
                            "");
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return packageName;
    }

    @Override
    public void setApplicationEnabledSetting(String appPackageName,
            int newState, int flags, int userId, String callingPackage) {
        if (!mUserManager.exists(userId)) return;
        if (callingPackage == null) {
            callingPackage = Integer.toString(Binder.getCallingUid());
        }

        setEnabledSettings(List.of(new ComponentEnabledSetting(appPackageName, newState, flags)),
                userId, callingPackage);
    }

    @Override
    public void setUpdateAvailable(String packageName, boolean updateAvailable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES, null);
        commitPackageStateMutation(null, packageName, state ->
                state.setUpdateAvailable(updateAvailable));
    }

    @Override
    public void overrideLabelAndIcon(@NonNull ComponentName componentName,
            @NonNull String nonLocalizedLabel, int icon, int userId) {
        if (TextUtils.isEmpty(nonLocalizedLabel)) {
            throw new IllegalArgumentException("Override label should be a valid String");
        }
        updateComponentLabelIcon(componentName, nonLocalizedLabel, icon, userId);
    }

    @Override
    public void restoreLabelAndIcon(@NonNull ComponentName componentName, int userId) {
        updateComponentLabelIcon(componentName, null, null, userId);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void updateComponentLabelIcon(/*@NonNull*/ ComponentName componentName,
            @Nullable String nonLocalizedLabel, @Nullable Integer icon, int userId) {
        if (componentName == null) {
            throw new IllegalArgumentException("Must specify a component");
        }

        int callingUid = Binder.getCallingUid();
        String componentPkgName = componentName.getPackageName();

        boolean changed = executeWithConsistentComputerReturning(computer -> {
            int componentUid = getPackageUid(componentPkgName, 0, userId);
            if (!UserHandle.isSameApp(callingUid, componentUid)) {
                throw new SecurityException("The calling UID (" + callingUid + ")"
                        + " does not match the target UID");
            }

            String allowedCallerPkg =
                    mContext.getString(R.string.config_overrideComponentUiPackage);
            if (TextUtils.isEmpty(allowedCallerPkg)) {
                throw new SecurityException( "There is no package defined as allowed to change a "
                        + "component's label or icon");
            }

            int allowedCallerUid = getPackageUid(allowedCallerPkg, PackageManager.MATCH_SYSTEM_ONLY,
                    userId);
            if (allowedCallerUid == -1 || !UserHandle.isSameApp(callingUid, allowedCallerUid)) {
                throw new SecurityException("The calling UID (" + callingUid + ")"
                        + " is not allowed to change a component's label or icon");
            }
            PackageStateInternal packageState = computer.getPackageStateInternal(componentPkgName);
            if (packageState == null || packageState.getPkg() == null
                    || (!packageState.isSystem()
                    && !packageState.getTransientState().isUpdatedSystemApp())) {
                throw new SecurityException(
                        "Changing the label is not allowed for " + componentName);
            }

            if (!mComponentResolver.componentExists(componentName)) {
                throw new IllegalArgumentException("Component " + componentName + " not found");
            }

            Pair<String, Integer> overrideLabelIcon = packageState.getUserStateOrDefault(userId)
                    .getOverrideLabelIconForComponent(componentName);

            String existingLabel = overrideLabelIcon == null ? null : overrideLabelIcon.first;
            Integer existingIcon = overrideLabelIcon == null ? null : overrideLabelIcon.second;

            return !TextUtils.equals(existingLabel, nonLocalizedLabel)
                    || !Objects.equals(existingIcon, icon);
        });
        if (!changed) {
            return;
        }

        commitPackageStateMutation(null, componentPkgName,
                state -> state.userState(userId)
                        .setComponentLabelIcon(componentName, nonLocalizedLabel, icon));

        ArrayList<String> components = mPendingBroadcasts.get(userId, componentPkgName);
        if (components == null) {
            components = new ArrayList<>();
            mPendingBroadcasts.put(userId, componentPkgName, components);
        }

        String className = componentName.getClassName();
        if (!components.contains(className)) {
            components.add(className);
        }

        if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
            mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
        }
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags, int userId) {
        if (!mUserManager.exists(userId)) return;

        setEnabledSettings(List.of(new ComponentEnabledSetting(componentName, newState, flags)),
                userId, null /* callingPackage */);
    }

    @Override
    public void setComponentEnabledSettings(List<ComponentEnabledSetting> settings, int userId) {
        if (!mUserManager.exists(userId)) return;
        if (settings == null || settings.isEmpty()) {
            throw new IllegalArgumentException("The list of enabled settings is empty");
        }

        setEnabledSettings(settings, userId, null /* callingPackage */);
    }

    private void setEnabledSettings(List<ComponentEnabledSetting> settings, int userId,
            String callingPackage) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                true /* checkShell */, "set enabled");

        final int targetSize = settings.size();
        for (int i = 0; i < targetSize; i++) {
            final int newState = settings.get(i).getEnabledState();
            if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == COMPONENT_ENABLED_STATE_ENABLED
                    || newState == COMPONENT_ENABLED_STATE_DISABLED
                    || newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                    || newState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                throw new IllegalArgumentException("Invalid new component state: " + newState);
            }
        }
        if (targetSize > 1) {
            final ArraySet<String> checkDuplicatedPackage = new ArraySet<>();
            final ArraySet<ComponentName> checkDuplicatedComponent = new ArraySet<>();
            final ArrayMap<String, Integer> checkConflictFlag = new ArrayMap<>();
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (setting.isComponent()) {
                    final ComponentName componentName = setting.getComponentName();
                    if (checkDuplicatedComponent.contains(componentName)) {
                        throw new IllegalArgumentException("The component " + componentName
                                + " is duplicated");
                    }
                    checkDuplicatedComponent.add(componentName);

                    // check if there is a conflict of the DONT_KILL_APP flag between components
                    // in the package
                    final Integer enabledFlags = checkConflictFlag.get(packageName);
                    if (enabledFlags == null) {
                        checkConflictFlag.put(packageName, setting.getEnabledFlags());
                    } else if ((enabledFlags & PackageManager.DONT_KILL_APP)
                            != (setting.getEnabledFlags() & PackageManager.DONT_KILL_APP)) {
                        throw new IllegalArgumentException("A conflict of the DONT_KILL_APP flag "
                                + "between components in the package " + packageName);
                    }
                } else {
                    if (checkDuplicatedPackage.contains(packageName)) {
                        throw new IllegalArgumentException("The package " + packageName
                                + " is duplicated");
                    }
                    checkDuplicatedPackage.add(packageName);
                }
            }
        }

        final boolean allowedByPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE) == PERMISSION_GRANTED;
        final boolean[] updateAllowed = new boolean[targetSize];
        Arrays.fill(updateAllowed, true);

        final Map<String, PackageSetting> pkgSettings = new ArrayMap<>(targetSize);
        // reader
        synchronized (mLock) {
            // Checks for target packages
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (pkgSettings.containsKey(packageName)) {
                    // this package has verified
                    continue;
                }
                final boolean isCallerTargetApp = ArrayUtils.contains(
                        getPackagesForUid(callingUid), packageName);
                final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
                // Limit who can change which apps
                if (!isCallerTargetApp) {
                    // Don't allow apps that don't have permission to modify other apps
                    if (!allowedByPermission
                            || shouldFilterApplication(pkgSetting, callingUid, userId)) {
                        throw new SecurityException("Attempt to change component state; "
                                + "pid=" + Binder.getCallingPid()
                                + ", uid=" + callingUid
                                + (!setting.isComponent() ? ", package=" + packageName
                                        : ", component=" + setting.getComponentName()));
                    }
                    // Don't allow changing protected packages.
                    if (mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                        throw new SecurityException(
                                "Cannot disable a protected package: " + packageName);
                    }
                }
                if (pkgSetting == null) {
                    throw new IllegalArgumentException(setting.isComponent()
                            ? "Unknown component: " + setting.getComponentName()
                            : "Unknown package: " + packageName);
                }
                if (callingUid == Process.SHELL_UID
                        && (pkgSetting.getFlags() & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
                    // Shell can only change whole packages between ENABLED and DISABLED_USER states
                    // unless it is a test package.
                    final int oldState = pkgSetting.getEnabled(userId);
                    final int newState = setting.getEnabledState();
                    if (!setting.isComponent()
                            &&
                            (oldState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                    || oldState == COMPONENT_ENABLED_STATE_DEFAULT
                                    || oldState == COMPONENT_ENABLED_STATE_ENABLED)
                            &&
                            (newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                    || newState == COMPONENT_ENABLED_STATE_DEFAULT
                                    || newState == COMPONENT_ENABLED_STATE_ENABLED)) {
                        // ok
                    } else {
                        throw new SecurityException(
                                "Shell cannot change component state for "
                                        + setting.getComponentName() + " to " + newState);
                    }
                }
                pkgSettings.put(packageName, pkgSetting);
            }
            // Checks for target components
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                // skip if it's application
                if (!setting.isComponent()) continue;

                // Only allow apps with CHANGE_COMPONENT_ENABLED_STATE permission to change hidden
                // app details activity
                final String packageName = setting.getPackageName();
                final String className = setting.getClassName();
                if (!allowedByPermission
                        && PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    throw new SecurityException("Cannot disable a system-generated component");
                }
                // Verify that this is a valid class name.
                final AndroidPackage pkg = pkgSettings.get(packageName).getPkg();
                if (pkg == null || !AndroidPackageUtils.hasComponentClassName(pkg, className)) {
                    if (pkg != null
                            && pkg.getTargetSdkVersion() >= Build.VERSION_CODES.JELLY_BEAN) {
                        throw new IllegalArgumentException("Component class " + className
                                + " does not exist in " + packageName);
                    } else {
                        Slog.w(TAG, "Failed setComponentEnabledSetting: component class "
                                + className + " does not exist in " + packageName);
                        updateAllowed[i] = false;
                    }
                }
            }
        }

        // More work for application enabled setting updates
        for (int i = 0; i < targetSize; i++) {
            final ComponentEnabledSetting setting = settings.get(i);
            // skip if it's component
            if (setting.isComponent()) continue;

            final PackageSetting pkgSetting = pkgSettings.get(setting.getPackageName());
            final int newState = setting.getEnabledState();
            synchronized (mLock) {
                if (pkgSetting.getEnabled(userId) == newState) {
                    // Nothing to do
                    updateAllowed[i] = false;
                    continue;
                }
            }
            // If we're enabling a system stub, there's a little more work to do.
            // Prior to enabling the package, we need to decompress the APK(s) to the
            // data partition and then replace the version on the system partition.
            final AndroidPackage deletedPkg = pkgSetting.getPkg();
            final boolean isSystemStub = (deletedPkg != null)
                    && deletedPkg.isStub()
                    && deletedPkg.isSystem();
            if (isSystemStub
                    && (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
                if (!mInstallPackageHelper.enableCompressedPackage(deletedPkg, pkgSetting)) {
                    Slog.w(TAG, "Failed setApplicationEnabledSetting: failed to enable "
                            + "commpressed package " + setting.getPackageName());
                    updateAllowed[i] = false;
                }
            }
        }

        // packageName -> list of components to send broadcasts now
        final ArrayMap<String, ArrayList<String>> sendNowBroadcasts = new ArrayMap<>(targetSize);
        synchronized (mLock) {
            boolean scheduleBroadcastMessage = false;
            boolean isSynchronous = false;
            boolean anyChanged = false;

            for (int i = 0; i < targetSize; i++) {
                if (!updateAllowed[i]) {
                    continue;
                }
                // update enabled settings
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (!setEnabledSettingInternalLocked(pkgSettings.get(packageName), setting,
                        userId, callingPackage)) {
                    continue;
                }
                anyChanged = true;

                if ((setting.getEnabledFlags() & PackageManager.SYNCHRONOUS) != 0) {
                    isSynchronous = true;
                }
                // collect broadcast list for the package
                final String componentName = setting.isComponent()
                        ? setting.getClassName() : packageName;
                ArrayList<String> componentList = sendNowBroadcasts.get(packageName);
                if (componentList == null) {
                    componentList = mPendingBroadcasts.get(userId, packageName);
                }
                final boolean newPackage = componentList == null;
                if (newPackage) {
                    componentList = new ArrayList<>();
                }
                if (!componentList.contains(componentName)) {
                    componentList.add(componentName);
                }
                if ((setting.getEnabledFlags() & PackageManager.DONT_KILL_APP) == 0) {
                    sendNowBroadcasts.put(packageName, componentList);
                    // Purge entry from pending broadcast list if another one exists already
                    // since we are sending one right away.
                    mPendingBroadcasts.remove(userId, packageName);
                } else {
                    if (newPackage) {
                        mPendingBroadcasts.put(userId, packageName, componentList);
                    }
                    scheduleBroadcastMessage = true;
                }
            }
            if (!anyChanged) {
                // nothing changed, return immediately
                return;
            }

            if (isSynchronous) {
                flushPackageRestrictionsAsUserInternalLocked(userId);
            } else {
                scheduleWritePackageRestrictions(userId);
            }
            if (scheduleBroadcastMessage) {
                if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
                    // Schedule a message - if it has been a "reasonably long time" since the
                    // service started, send the broadcast with a delay of one second to avoid
                    // delayed reactions from the receiver, else keep the default ten second delay
                    // to avoid extreme thrashing on service startup.
                    final long broadcastDelay = SystemClock.uptimeMillis() > mServiceStartWithDelay
                            ? BROADCAST_DELAY
                            : BROADCAST_DELAY_DURING_STARTUP;
                    mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, broadcastDelay);
                }
            }
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < sendNowBroadcasts.size(); i++) {
                final String packageName = sendNowBroadcasts.keyAt(i);
                final ArrayList<String> components = sendNowBroadcasts.valueAt(i);
                final int packageUid = UserHandle.getUid(
                        userId, pkgSettings.get(packageName).getAppId());
                sendPackageChangedBroadcast(packageName, false /* dontKillApp */,
                        components, packageUid, null /* reason */);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private boolean setEnabledSettingInternalLocked(PackageSetting pkgSetting,
            ComponentEnabledSetting setting, int userId, String callingPackage) {
        final int newState = setting.getEnabledState();
        final String packageName = setting.getPackageName();
        boolean success = false;
        if (!setting.isComponent()) {
            // We're dealing with an application/package level state change
            if (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                // Don't care about who enables an app.
                callingPackage = null;
            }
            pkgSetting.setEnabled(newState, userId, callingPackage);
            if ((newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                    || newState == COMPONENT_ENABLED_STATE_DISABLED)
                    && checkPermission(Manifest.permission.SUSPEND_APPS, packageName, userId)
                    == PERMISSION_GRANTED) {
                // This app should not generally be allowed to get disabled by the UI, but
                // if it ever does, we don't want to end up with some of the user's apps
                // permanently suspended.
                unsuspendForSuspendingPackage(packageName, userId);
                removeAllDistractingPackageRestrictions(userId);
            }
            success = true;
        } else {
            // We're dealing with a component level state change
            final String className = setting.getClassName();
            switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    success = pkgSetting.enableComponentLPw(className, userId);
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    success = pkgSetting.disableComponentLPw(className, userId);
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    success = pkgSetting.restoreComponentLPw(className, userId);
                    break;
                default:
                    Slog.e(TAG, "Failed setComponentEnabledSetting: component "
                            + packageName + "/" + className
                            + " requested an invalid new component state: " + newState);
                    break;
            }
        }
        if (!success) {
            return false;
        }

        updateSequenceNumberLP(pkgSetting, new int[] { userId });
        final long callingId = Binder.clearCallingIdentity();
        try {
            updateInstantAppInstallerLocked(packageName);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return true;
    }

    @WorkerThread
    @Override
    public void flushPackageRestrictionsAsUser(int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        if (!mUserManager.exists(userId)) {
            return;
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false /* requireFullPermission*/,
                false /* checkShell */, "flushPackageRestrictions");
        synchronized (mLock) {
            flushPackageRestrictionsAsUserInternalLocked(userId);
        }
    }

    @GuardedBy("mLock")
    private void flushPackageRestrictionsAsUserInternalLocked(int userId) {
        // NOTE: this invokes synchronous disk access, so callers using this
        // method should consider running on a background thread
        mSettings.writePackageRestrictionsLPr(userId);
        synchronized (mDirtyUsers) {
            mDirtyUsers.remove(userId);
            if (mDirtyUsers.isEmpty()) {
                mHandler.removeMessages(WRITE_PACKAGE_RESTRICTIONS);
            }
        }
    }

    void sendPackageChangedBroadcast(String packageName,
            boolean dontKillApp, ArrayList<String> componentNames, int packageUid, String reason) {
        final int userId = UserHandle.getUserId(packageUid);
        final boolean isInstantApp = isInstantAppInternal(packageName, userId, Process.SYSTEM_UID);
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        final SparseArray<int[]> broadcastAllowList = getBroadcastAllowList(
                packageName, userIds, isInstantApp);
        mHandler.post(() -> mBroadcastHelper.sendPackageChangedBroadcast(
                packageName, dontKillApp, componentNames, packageUid, reason, userIds,
                instantUserIds, broadcastAllowList));
    }

    @Nullable
    private SparseArray<int[]> getBroadcastAllowList(@NonNull String packageName,
            @UserIdInt int[] userIds, boolean isInstantApp) {
        return mComputer.getBroadcastAllowList(packageName, userIds, isInstantApp);
    }

    @Override
    public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
        if (!mUserManager.exists(userId)) return;
        final int callingUid = Binder.getCallingUid();
        Pair<Boolean, String> wasNotLaunchedAndInstallerPackageName =
                executeWithConsistentComputerReturningThrowing(computer -> {
            if (computer.getInstantAppPackageName(callingUid) != null) {
                return null;
            }
            final int permission = mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
            final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
            if (!allowedByPermission
                    && !ArrayUtils.contains(computer.getPackagesForUid(callingUid), packageName)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change stopped state from pid="
                                + Binder.getCallingPid()
                                + ", uid=" + callingUid + ", package=" + packageName);
            }
            computer.enforceCrossUserPermission(callingUid, userId,
                    true /* requireFullPermission */, true /* checkShell */, "stop package");

            final PackageStateInternal packageState = computer.getPackageStateInternal(packageName);
            final PackageUserState PackageUserState = packageState == null
                    ? null : packageState.getUserStateOrDefault(userId);
            if (packageState == null
                    || computer.shouldFilterApplication(packageState, callingUid, userId)
                    || PackageUserState.isStopped() == stopped) {
                return null;
            }

            return Pair.create(PackageUserState.isNotLaunched(),
                    packageState.getInstallSource().installerPackageName);
        });
        if (wasNotLaunchedAndInstallerPackageName != null) {
            boolean wasNotLaunched = wasNotLaunchedAndInstallerPackageName.first;

            commitPackageStateMutation(null, packageName, packageState -> {
                PackageUserStateWrite userState = packageState.userState(userId);
                userState.setStopped(stopped);
                if (wasNotLaunched) {
                    userState.setNotLaunched(false);
                }
            });

            if (wasNotLaunched) {
                final String installerPackageName = wasNotLaunchedAndInstallerPackageName.second;
                if (installerPackageName != null) {
                    notifyFirstLaunch(packageName, installerPackageName, userId);
                }
            }

            scheduleWritePackageRestrictions(userId);
        }

        // If this would cause the app to leave force-stop, then also make sure to unhibernate the
        // app if needed.
        if (!stopped) {
            mHandler.post(() -> {
                AppHibernationManagerInternal ah =
                        mInjector.getLocalService(AppHibernationManagerInternal.class);
                if (ah != null && ah.isHibernatingForUser(packageName, userId)) {
                    ah.setHibernatingForUser(packageName, userId, false);
                    ah.setHibernatingGlobally(packageName, false);
                }
            });
        }
    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        return mComputer.getInstallerPackageName(packageName);
    }

    @Override
    @Nullable
    public InstallSourceInfo getInstallSourceInfo(@NonNull String packageName) {
        return mComputer.getInstallSourceInfo(packageName);
    }

    @PackageManager.EnabledState
    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.getApplicationEnabledSetting(packageName, userId);
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName component, int userId) {
        return mComputer.getComponentEnabledSetting(component, Binder.getCallingUid(), userId);
    }

    @Override
    public void enterSafeMode() {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can request entering safe mode");

        if (!mSystemReady) {
            mSafeMode = true;
        }
    }

    @Override
    public void systemReady() {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can claim the system is ready");

        final ContentResolver resolver = mContext.getContentResolver();
        if (mReleaseOnSystemReady != null) {
            for (int i = mReleaseOnSystemReady.size() - 1; i >= 0; --i) {
                final File dstCodePath = mReleaseOnSystemReady.get(i);
                F2fsUtils.releaseCompressedBlocks(resolver, dstCodePath);
            }
            mReleaseOnSystemReady = null;
        }
        mSystemReady = true;
        ContentObserver co = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean ephemeralFeatureDisabled =
                        Global.getInt(resolver, Global.ENABLE_EPHEMERAL_FEATURE, 1) == 0;
                for (int userId : UserManagerService.getInstance().getUserIds()) {
                    final boolean instantAppsDisabledForUser =
                            ephemeralFeatureDisabled || Secure.getIntForUser(resolver,
                                    Secure.INSTANT_APPS_ENABLED, 1, userId) == 0;
                    mWebInstantAppsDisabled.put(userId, instantAppsDisabledForUser);
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(android.provider.Settings.Global
                        .getUriFor(Global.ENABLE_EPHEMERAL_FEATURE),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(android.provider.Settings.Secure
                        .getUriFor(Secure.INSTANT_APPS_ENABLED), false, co, UserHandle.USER_ALL);
        co.onChange(true);

        mAppsFilter.onSystemReady();

        // Disable any carrier apps. We do this very early in boot to prevent the apps from being
        // disabled after already being started.
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(
                mContext.getOpPackageName(), UserHandle.USER_SYSTEM, mContext);

        disableSkuSpecificApps();

        // Read the compatibilty setting when the system is ready.
        boolean compatibilityModeEnabled = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.COMPATIBILITY_MODE, 1) == 1;
        ParsingPackageUtils.setCompatibilityModeEnabled(compatibilityModeEnabled);

        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }

        synchronized (mLock) {
            ArrayList<Integer> changed = mSettings.systemReady(mComponentResolver);
            for (int i = 0; i < changed.size(); i++) {
                mSettings.writePackageRestrictionsLPr(changed.get(i));
            }
        }

        mUserManager.systemReady();

        // Watch for external volumes that come and go over time
        final StorageEventHelper storageEventHelper = new StorageEventHelper(this,
                mDeletePackageHelper, mRemovePackageHelper);
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        storage.registerListener(storageEventHelper);

        mInstallerService.systemReady();
        mPackageDexOptimizer.systemReady();

        // Now that we're mostly running, clean up stale users and apps
        mUserManager.reconcileUsers(StorageManager.UUID_PRIVATE_INTERNAL);
        storageEventHelper.reconcileApps(StorageManager.UUID_PRIVATE_INTERNAL);

        mPermissionManager.onSystemReady();

        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        final List<UserInfo> livingUsers = mInjector.getUserManagerInternal().getUsers(
                /* excludePartial= */ true,
                /* excludeDying= */ true,
                /* excludePreCreated= */ false);
        final int livingUserCount = livingUsers.size();
        for (int i = 0; i < livingUserCount; i++) {
            final int userId = livingUsers.get(i).id;
            if (mPmInternal.isPermissionUpgradeNeeded(userId)) {
                grantPermissionsUserIds = ArrayUtils.appendInt(
                        grantPermissionsUserIds, userId);
            }
        }
        // If we upgraded grant all default permissions before kicking off.
        for (int userId : grantPermissionsUserIds) {
            mLegacyPermissionManager.grantDefaultPermissions(userId);
        }
        if (grantPermissionsUserIds == EMPTY_INT_ARRAY) {
            // If we did not grant default permissions, we preload from this the
            // default permission exceptions lazily to ensure we don't hit the
            // disk on a new user creation.
            mLegacyPermissionManager.scheduleReadDefaultPermissionExceptions();
        }

        if (mInstantAppResolverConnection != null) {
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mInstantAppResolverConnection.optimisticBind();
                    mContext.unregisterReceiver(this);
                }
            }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        }

        IntentFilter overlayFilter = new IntentFilter(Intent.ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                Uri data = intent.getData();
                if (data == null) {
                    return;
                }
                String packageName = data.getSchemeSpecificPart();
                if (packageName == null) {
                    return;
                }
                AndroidPackage pkg = mPackages.get(packageName);
                if (pkg == null) {
                    return;
                }
                sendPackageChangedBroadcast(pkg.getPackageName(),
                        true /* dontKillApp */,
                        new ArrayList<>(Collections.singletonList(pkg.getPackageName())),
                        pkg.getUid(),
                        Intent.ACTION_OVERLAY_CHANGED);
            }
        }, overlayFilter);

        mModuleInfoProvider.systemReady();

        // Installer service might attempt to install some packages that have been staged for
        // installation on reboot. Make sure this is the last component to be call since the
        // installation might require other components to be ready.
        mInstallerService.restoreAndApplyStagedSessionIfNeeded();

        mExistingPackages = null;

        // Clear cache on flags changes.
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE_PACKAGE_MANAGER_SERVICE, mInjector.getBackgroundExecutor(),
                properties -> {
                    final Set<String> keyset = properties.getKeyset();
                    if (keyset.contains(PROPERTY_INCFS_DEFAULT_TIMEOUTS) || keyset.contains(
                            PROPERTY_KNOWN_DIGESTERS_LIST)) {
                        mPerUidReadTimeoutsCache = null;
                    }
                });

        mBackgroundDexOptService.systemReady();

        // Prune unused static shared libraries which have been cached a period of time
        schedulePruneUnusedStaticSharedLibraries(false /* delay */);
    }

    /**
     * Used by SystemServer
     */
    public void waitForAppDataPrepared() {
        if (mPrepareAppDataFuture == null) {
            return;
        }
        ConcurrentUtils.waitForFutureNoInterrupt(mPrepareAppDataFuture, "wait for prepareAppData");
        mPrepareAppDataFuture = null;
    }

    @Override
    public boolean isSafeMode() {
        // allow instant applications
        return mSafeMode;
    }

    @Override
    public boolean hasSystemUidErrors() {
        // allow instant applications
        return false;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new PackageManagerShellCommand(this, mContext,mDomainVerificationManager.getShell()))
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    @SuppressWarnings("resource")
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;
        new DumpHelper(this).doDump(fd, pw, args);
    }

    void dumpSnapshotStats(PrintWriter pw, boolean isBrief) {
        if (mSnapshotStatistics == null) {
            return;
        }
        int hits = 0;
        synchronized (mSnapshotLock) {
            if (mSnapshotComputer != null) {
                hits = mSnapshotComputer.getUsed();
            }
        }
        final long now = SystemClock.currentTimeMicro();
        mSnapshotStatistics.dump(pw, "  ", now, hits, -1, isBrief);
    }

    /**
     * Dump package manager states to the file according to a given dumping type of
     * {@link DumpState}.
     */
    void dumpComputer(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState) {
        mComputer.dump(type, fd, pw, dumpState);
    }

    //TODO: b/111402650
    private void disableSkuSpecificApps() {
        String[] apkList = mContext.getResources().getStringArray(
                R.array.config_disableApksUnlessMatchedSku_apk_list);
        String[] skuArray = mContext.getResources().getStringArray(
                R.array.config_disableApkUnlessMatchedSku_skus_list);
        if (ArrayUtils.isEmpty(apkList)) {
           return;
        }
        String sku = SystemProperties.get("ro.boot.hardware.sku");
        if (!TextUtils.isEmpty(sku) && ArrayUtils.contains(skuArray, sku)) {
            return;
        }
        for (String packageName : apkList) {
            setSystemAppHiddenUntilInstalled(packageName, true);
            for (UserInfo user : mInjector.getUserManagerInternal().getUsers(false)) {
                setSystemAppInstallState(packageName, false, user.id);
            }
        }
    }

    public PackageFreezer freezePackage(String packageName, String killReason) {
        return freezePackage(packageName, UserHandle.USER_ALL, killReason);
    }

    public PackageFreezer freezePackage(String packageName, int userId, String killReason) {
        return new PackageFreezer(packageName, userId, killReason, this);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int deleteFlags,
            String killReason) {
        return freezePackageForDelete(packageName, UserHandle.USER_ALL, deleteFlags, killReason);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int userId, int deleteFlags,
            String killReason) {
        if ((deleteFlags & PackageManager.DELETE_DONT_KILL_APP) != 0) {
            return new PackageFreezer(this);
        } else {
            return freezePackage(packageName, userId, killReason);
        }
    }

    /**
     * Verify that given package is currently frozen.
     */
    void checkPackageFrozen(String packageName) {
        synchronized (mLock) {
            if (!mFrozenPackages.containsKey(packageName)) {
                Slog.wtf(TAG, "Expected " + packageName + " to be frozen!", new Throwable());
            }
        }
    }

    @Override
    public int movePackage(final String packageName, final String volumeUuid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);

        final int callingUid = Binder.getCallingUid();
        final UserHandle user = new UserHandle(UserHandle.getUserId(callingUid));
        final int moveId = mNextMoveId.getAndIncrement();
        mHandler.post(() -> {
            try {
                MovePackageHelper movePackageHelper = new MovePackageHelper(this);
                movePackageHelper.movePackageInternal(
                        packageName, volumeUuid, moveId, callingUid, user);
            } catch (PackageManagerException e) {
                Slog.w(TAG, "Failed to move " + packageName, e);
                mMoveCallbacks.notifyStatusChanged(moveId, e.error);
            }
        });
        return moveId;
    }

    @Override
    public int movePrimaryStorage(String volumeUuid) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);

        final int realMoveId = mNextMoveId.getAndIncrement();
        final Bundle extras = new Bundle();
        extras.putString(VolumeRecord.EXTRA_FS_UUID, volumeUuid);
        mMoveCallbacks.notifyCreated(realMoveId, extras);

        final IPackageMoveObserver callback = new IPackageMoveObserver.Stub() {
            @Override
            public void onCreated(int moveId, Bundle extras) {
                // Ignored
            }

            @Override
            public void onStatusChanged(int moveId, int status, long estMillis) {
                mMoveCallbacks.notifyStatusChanged(realMoveId, status, estMillis);
            }
        };

        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        storage.setPrimaryStorageUuid(volumeUuid, callback);
        return realMoveId;
    }

    @Override
    public int getMoveStatus(int moveId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        return mMoveCallbacks.mLastStatus.get(moveId);
    }

    @Override
    public void registerMoveCallback(IPackageMoveObserver callback) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mMoveCallbacks.register(callback);
    }

    @Override
    public void unregisterMoveCallback(IPackageMoveObserver callback) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mMoveCallbacks.unregister(callback);
    }

    @Override
    public boolean setInstallLocation(int loc) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                null);
        if (getInstallLocation() == loc) {
            return true;
        }
        if (loc == InstallLocationUtils.APP_INSTALL_AUTO
                || loc == InstallLocationUtils.APP_INSTALL_INTERNAL
                || loc == InstallLocationUtils.APP_INSTALL_EXTERNAL) {
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION, loc);
            return true;
        }
        return false;
   }

    @Override
    public int getInstallLocation() {
        // allow instant app access
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION,
                InstallLocationUtils.APP_INSTALL_AUTO);
    }

    /** Called by UserManagerService */
    void cleanUpUser(UserManagerService userManager, @UserIdInt int userId) {
        synchronized (mLock) {
            synchronized (mDirtyUsers) {
                mDirtyUsers.remove(userId);
            }
            mUserNeedsBadging.delete(userId);
            mPermissionManager.onUserRemoved(userId);
            mSettings.removeUserLPw(userId);
            mPendingBroadcasts.remove(userId);
            mDeletePackageHelper.removeUnusedPackagesLPw(userManager, userId);
            mAppsFilter.onUserDeleted(userId);
        }
        mInstantAppRegistry.onUserRemoved(userId);
    }

    /**
     * Called by UserManagerService.
     *
     * @param userTypeInstallablePackages system packages that should be initially installed for
     *                                    this type of user, or {@code null} if all system packages
     *                                    should be installed
     * @param disallowedPackages packages that should not be initially installed. Takes precedence
     *                           over installablePackages.
     */
    void createNewUser(int userId, @Nullable Set<String> userTypeInstallablePackages,
            String[] disallowedPackages) {
        synchronized (mInstallLock) {
            mSettings.createNewUserLI(this, mInstaller, userId,
                    userTypeInstallablePackages, disallowedPackages);
        }
        synchronized (mLock) {
            scheduleWritePackageRestrictions(userId);
            scheduleWritePackageListLocked(userId);
            mAppsFilter.onUserCreated(userId);
        }
    }

    void onNewUserCreated(@UserIdInt int userId, boolean convertedFromPreCreated) {
        if (DEBUG_PERMISSIONS) {
            Slog.d(TAG, "onNewUserCreated(id=" + userId
                    + ", convertedFromPreCreated=" + convertedFromPreCreated + ")");
        }
        if (!convertedFromPreCreated || !readPermissionStateForUser(userId)) {
            mPermissionManager.onUserCreated(userId);
            mLegacyPermissionManager.grantDefaultPermissions(userId);
            mDomainVerificationManager.clearUser(userId);
        }
    }

    private boolean readPermissionStateForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mPermissionManager.writeLegacyPermissionStateTEMP();
            mSettings.readPermissionStateForUserSyncLPr(userId);
            mPermissionManager.readLegacyPermissionStateTEMP();
            return mPmInternal.isPermissionUpgradeNeeded(userId);
        }
    }

    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can read the verifier device identity");

        synchronized (mLock) {
            return mSettings.getVerifierDeviceIdentityLPw();
        }
    }

    @Override
    public boolean isStorageLow() {
        // allow instant applications
        final long token = Binder.clearCallingIdentity();
        try {
            final DeviceStorageMonitorInternal
                    dsm = mInjector.getLocalService(DeviceStorageMonitorInternal.class);
            if (dsm != null) {
                return dsm.isMemoryLow();
            } else {
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public IPackageInstaller getPackageInstaller() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return mInstallerService;
    }

    @Override
    public IArtManager getArtManager() {
        return mArtManagerService;
    }

    boolean userNeedsBadging(int userId) {
        return mUserNeedsBadging.get(userId);
    }

    @Nullable
    @Override
    public KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        return mComputer.getKeySetByAlias(packageName, alias);
    }

    @Nullable
    @Override
    public KeySet getSigningKeySet(@NonNull String packageName) {
        return mComputer.getSigningKeySet(packageName);
    }

    @Override
    public boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks) {
        return mComputer.isPackageSignedByKeySet(packageName, ks);
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks) {
        return mComputer.isPackageSignedByKeySetExactly(packageName, ks);
    }

    private void deletePackageIfUnused(final String packageName) {
        PackageStateInternal ps = getPackageStateInternal(packageName);
        if (ps == null) {
            return;
        }
        final SparseArray<? extends PackageUserStateInternal> userStates = ps.getUserStates();
        for (int index = 0; index < userStates.size(); index++) {
            if (userStates.valueAt(index).isInstalled()) {
                return;
            }
        }
        // TODO Implement atomic delete if package is unused
        // It is currently possible that the package will be deleted even if it is installed
        // after this method returns.
        mHandler.post(() -> mDeletePackageHelper.deletePackageX(
                packageName, PackageManager.VERSION_CODE_HIGHEST,
                0, PackageManager.DELETE_ALL_USERS, true /*removedBySystem*/));
    }

    private AndroidPackage getPackage(String packageName) {
        return mComputer.getPackage(packageName);
    }

    private AndroidPackage getPackage(int uid) {
        return mComputer.getPackage(uid);
    }

    private SigningDetails getSigningDetails(@NonNull String packageName) {
        return mComputer.getSigningDetails(packageName);
    }

    private SigningDetails getSigningDetails(int uid) {
        return mComputer.getSigningDetails(uid);
    }

    private boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        return mComputer.filterAppAccess(pkg, callingUid, userId);
    }

    private boolean filterAppAccess(String packageName, int callingUid, int userId) {
        return mComputer.filterAppAccess(packageName, callingUid, userId);
    }

    private boolean filterAppAccess(int uid, int callingUid) {
        return mComputer.filterAppAccess(uid, callingUid);
    }

    @Nullable
    private int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.getVisibilityAllowList(packageName, userId);
    }

    boolean canQueryPackage(int callingUid, @Nullable String targetPackageName) {
        return mComputer.canQueryPackage(callingUid, targetPackageName);
    }

    private class PackageManagerInternalImpl extends PackageManagerInternal {
        @Override
        public List<ApplicationInfo> getInstalledApplications(
                @PackageManager.ApplicationInfoFlagsBits long flags, int userId, int callingUid) {
            return PackageManagerService.this.mComputer.getInstalledApplications(flags, userId,
                    callingUid);
        }

        @Override
        public boolean isPlatformSigned(String packageName) {
            PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return false;
            }
            SigningDetails signingDetails = packageState.getSigningDetails();
            return signingDetails.hasAncestorOrSelf(mPlatformPackage.getSigningDetails())
                    || mPlatformPackage.getSigningDetails().checkCapability(signingDetails,
                    SigningDetails.CertCapabilities.PERMISSION);
        }

        @Override
        public boolean isDataRestoreSafe(byte[] restoringFromSigHash, String packageName) {
            SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasSha256Certificate(restoringFromSigHash,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean isDataRestoreSafe(Signature restoringFromSig, String packageName) {
            SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasCertificate(restoringFromSig,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean hasSignatureCapability(int serverUid, int clientUid,
                @SigningDetails.CertCapabilities int capability) {
            SigningDetails serverSigningDetails = getSigningDetails(serverUid);
            SigningDetails clientSigningDetails = getSigningDetails(clientUid);
            return serverSigningDetails.checkCapability(clientSigningDetails, capability)
                    || clientSigningDetails.hasAncestorOrSelf(serverSigningDetails);

        }

        private SigningDetails getSigningDetails(@NonNull String packageName) {
            return PackageManagerService.this.getSigningDetails(packageName);
        }

        private SigningDetails getSigningDetails(int uid) {
            return PackageManagerService.this.getSigningDetails(uid);
        }

        @Override
        public boolean isInstantApp(String packageName, int userId) {
            return PackageManagerService.this.isInstantApp(packageName, userId);
        }

        @Override
        public String getInstantAppPackageName(int uid) {
            return PackageManagerService.this.getInstantAppPackageName(uid);
        }

        @Override
        public boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
            return PackageManagerService.this.filterAppAccess(pkg, callingUid, userId);
        }

        @Override
        public boolean filterAppAccess(String packageName, int callingUid, int userId) {
            return PackageManagerService.this.filterAppAccess(packageName, callingUid, userId);
        }

        @Override
        public boolean filterAppAccess(int uid, int callingUid) {
            return PackageManagerService.this.filterAppAccess(uid, callingUid);
        }

        @Nullable
        @Override
        public int[] getVisibilityAllowList(@NonNull String packageName, int userId) {
            return PackageManagerService.this.getVisibilityAllowList(packageName, userId);
        }

        @Override
        public boolean canQueryPackage(int callingUid, @Nullable String packageName) {
            return PackageManagerService.this.canQueryPackage(callingUid, packageName);
        }

        @Override
        public AndroidPackage getPackage(String packageName) {
            return PackageManagerService.this.getPackage(packageName);
        }

        @Nullable
        @Override
        public AndroidPackageApi getAndroidPackage(@NonNull String packageName) {
            return PackageManagerService.this.getPackage(packageName);
        }

        @Override
        public AndroidPackage getPackage(int uid) {
            return PackageManagerService.this.getPackage(uid);
        }

        @Override
        public List<AndroidPackage> getPackagesForAppId(int appId) {
            return mComputer.getPackagesForAppId(appId);
        }

        @Nullable
        @Override
        public PackageStateInternal getPackageStateInternal(String packageName) {
            return PackageManagerService.this.getPackageStateInternal(packageName);
        }

        @Nullable
        @Override
        public PackageState getPackageState(@NonNull String packageName) {
            return PackageManagerService.this.getPackageState(packageName);
        }

        @NonNull
        @Override
        public ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
            return PackageManagerService.this.getPackageStates();
        }

        @Override
        public PackageList getPackageList(@Nullable PackageListObserver observer) {
            final ArrayList<String> list = new ArrayList<>();
            forEachPackageState(packageState -> {
                AndroidPackage pkg = packageState.getPkg();
                if (pkg != null) {
                    list.add(pkg.getPackageName());
                }
            });
            final PackageList packageList = new PackageList(list, observer);
            if (observer != null) {
                mPackageObserverHelper.addObserver(packageList);
            }
            return packageList;
        }

        @Override
        public void removePackageListObserver(PackageListObserver observer) {
            mPackageObserverHelper.removeObserver(observer);
        }

        @Override
        public PackageStateInternal getDisabledSystemPackage(@NonNull String packageName) {
            return snapshotComputer().getDisabledSystemPackage(packageName);
        }

        @Override
        public @Nullable
        String getDisabledSystemPackageName(@NonNull String packageName) {
            PackageStateInternal disabledPkgSetting = getDisabledSystemPackage(
                    packageName);
            AndroidPackage disabledPkg = disabledPkgSetting == null
                    ? null : disabledPkgSetting.getPkg();
            return disabledPkg == null ? null : disabledPkg.getPackageName();
        }

        @Override
        public @NonNull String[] getKnownPackageNames(int knownPackage, int userId) {
            return PackageManagerService.this.getKnownPackageNamesInternal(knownPackage, userId);
        }

        @Override
        public boolean isResolveActivityComponent(ComponentInfo component) {
            return mResolveActivity.packageName.equals(component.packageName)
                    && mResolveActivity.name.equals(component.name);
        }

        @Override
        public void setKeepUninstalledPackages(final List<String> packageList) {
            PackageManagerService.this.setKeepUninstalledPackagesInternal(packageList);
        }

        @Override
        public boolean isPermissionsReviewRequired(String packageName, int userId) {
            return mPermissionManager.isPermissionsReviewRequired(packageName, userId);
        }

        @Override
        public PackageInfo getPackageInfo(
                String packageName, @PackageManager.PackageInfoFlagsBits long flags,
                int filterCallingUid, int userId) {
            return PackageManagerService.this.mComputer
                    .getPackageInfoInternal(packageName, PackageManager.VERSION_CODE_HIGHEST,
                            flags, filterCallingUid, userId);
        }

        @Override
        public long getCeDataInode(String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return 0;
            } else {
                return packageState.getUserStateOrDefault(userId).getCeDataInode();
            }
        }

        @Override
        public Bundle getSuspendedPackageLauncherExtras(String packageName, int userId) {
            return mSuspendPackageHelper.getSuspendedPackageLauncherExtras(
                    packageName, userId, Binder.getCallingUid());
        }

        @Override
        public boolean isPackageSuspended(String packageName, int userId) {
            return mSuspendPackageHelper.isPackageSuspended(
                    packageName, userId, Binder.getCallingUid());
        }

        @Override
        public void removeAllNonSystemPackageSuspensions(int userId) {
            final String[] allPackages = mComputer.getAllAvailablePackageNames();
            mSuspendPackageHelper.removeSuspensionsBySuspendingPackage(allPackages,
                    (suspendingPackage) -> !PLATFORM_PACKAGE_NAME.equals(suspendingPackage),
                    userId);
        }

        @Override
        public void removeNonSystemPackageSuspensions(String packageName, int userId) {
            mSuspendPackageHelper.removeSuspensionsBySuspendingPackage(
                    new String[]{packageName},
                    (suspendingPackage) -> !PLATFORM_PACKAGE_NAME.equals(suspendingPackage),
                    userId);
        }

        @Override
        public void flushPackageRestrictions(int userId) {
            synchronized (mLock) {
                PackageManagerService.this.flushPackageRestrictionsAsUserInternalLocked(userId);
            }
        }

        @Override
        public void removeDistractingPackageRestrictions(String packageName, int userId) {
            PackageManagerService.this.removeDistractingPackageRestrictions(
                    new String[]{packageName}, userId);
        }

        @Override
        public void removeAllDistractingPackageRestrictions(int userId) {
            PackageManagerService.this.removeAllDistractingPackageRestrictions(userId);
        }

        @Override
        public String getSuspendingPackage(String suspendedPackage, int userId) {
            return mSuspendPackageHelper.getSuspendingPackage(
                    suspendedPackage, userId, Binder.getCallingUid());
        }

        @Override
        public SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage,
                String suspendingPackage, int userId) {
            return mSuspendPackageHelper.getSuspendedDialogInfo(
                    suspendedPackage, suspendingPackage, userId, Binder.getCallingUid());
        }

        @Override
        public int getDistractingPackageRestrictions(String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            return (packageState == null) ? RESTRICTION_NONE
                    : packageState.getUserStateOrDefault(userId).getDistractionFlags();
        }

        @Override
        public int getPackageUid(String packageName,
                @PackageManager.PackageInfoFlagsBits long flags, int userId) {
            return PackageManagerService.this
                    .getPackageUidInternal(packageName, flags, userId, Process.SYSTEM_UID);
        }

        @Override
        public ApplicationInfo getApplicationInfo(
                String packageName, @PackageManager.ApplicationInfoFlagsBits long flags,
                int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .getApplicationInfoInternal(packageName, flags, filterCallingUid, userId);
        }

        @Override
        public ActivityInfo getActivityInfo(
                ComponentName component, @PackageManager.ComponentInfoFlagsBits long flags,
                int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .getActivityInfoInternal(component, flags, filterCallingUid, userId);
        }

        @Override
        public List<ResolveInfo> queryIntentActivities(
                Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
                int filterCallingUid, int userId) {
            return snapshotComputer().queryIntentActivitiesInternal(intent, resolvedType, flags,
                    userId);
        }

        @Override
        public List<ResolveInfo> queryIntentReceivers(Intent intent,
                String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
                int filterCallingUid, int userId) {
            return PackageManagerService.this.mResolveIntentHelper.queryIntentReceiversInternal(
                    snapshotComputer(), intent, resolvedType, flags, userId, filterCallingUid);
        }

        @Override
        public List<ResolveInfo> queryIntentServices(
                Intent intent, @PackageManager.ResolveInfoFlagsBits long flags, int callingUid,
                int userId) {
            final String resolvedType = intent.resolveTypeIfNeeded(mContext.getContentResolver());
            return PackageManagerService.this
                    .queryIntentServicesInternal(intent, resolvedType, flags, userId, callingUid,
                            false);
        }

        @Override
        public ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
                int userId) {
            return PackageManagerService.this.getHomeActivitiesAsUser(allHomeCandidates, userId);
        }

        @Override
        public ComponentName getDefaultHomeActivity(int userId) {
            return PackageManagerService.this.getDefaultHomeActivity(userId);
        }

        @Override
        public ComponentName getSystemUiServiceComponent() {
            return ComponentName.unflattenFromString(mContext.getResources().getString(
                    com.android.internal.R.string.config_systemUIServiceComponent));
        }

        @Override
        public void setDeviceAndProfileOwnerPackages(
                int deviceOwnerUserId, String deviceOwnerPackage,
                SparseArray<String> profileOwnerPackages) {
            mProtectedPackages.setDeviceAndProfileOwnerPackages(
                    deviceOwnerUserId, deviceOwnerPackage, profileOwnerPackages);
            final ArraySet<Integer> usersWithPoOrDo = new ArraySet<>();
            if (deviceOwnerPackage != null) {
                usersWithPoOrDo.add(deviceOwnerUserId);
            }
            final int sz = profileOwnerPackages.size();
            for (int i = 0; i < sz; i++) {
                if (profileOwnerPackages.valueAt(i) != null) {
                    removeAllNonSystemPackageSuspensions(profileOwnerPackages.keyAt(i));
                }
            }
        }

        @Override
        public void setDeviceOwnerProtectedPackages(
                String deviceOwnerPackageName, List<String> packageNames) {
            mProtectedPackages.setDeviceOwnerProtectedPackages(
                    deviceOwnerPackageName, packageNames);
        }

        @Override
        public boolean isPackageDataProtected(int userId, String packageName) {
            return mProtectedPackages.isPackageDataProtected(userId, packageName);
        }

        @Override
        public boolean isPackageStateProtected(String packageName, int userId) {
            return mProtectedPackages.isPackageStateProtected(userId, packageName);
        }

        @Override
        public boolean isPackageEphemeral(int userId, String packageName) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            return packageState != null
                    && packageState.getUserStateOrDefault(userId).isInstantApp();
        }

        @Override
        public boolean wasPackageEverLaunched(String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            return !packageState.getUserStateOrDefault(userId).isNotLaunched();
        }

        @Override
        public boolean isEnabledAndMatches(ParsedMainComponent component, long flags, int userId) {
            return PackageStateUtils.isEnabledAndMatches(
                    getPackageStateInternal(component.getPackageName()), component, flags, userId);
        }

        @Override
        public boolean userNeedsBadging(int userId) {
            synchronized (mLock) {
                return PackageManagerService.this.userNeedsBadging(userId);
            }
        }

        @Override
        public String getNameForUid(int uid) {
            return PackageManagerService.this.getNameForUid(uid);
        }

        @Override
        public void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
                Intent origIntent, String resolvedType, String callingPackage,
                @Nullable String callingFeatureId, boolean isRequesterInstantApp,
                Bundle verificationBundle, int userId) {
            PackageManagerService.this.requestInstantAppResolutionPhaseTwo(responseObj, origIntent,
                    resolvedType, callingPackage, callingFeatureId, isRequesterInstantApp,
                    verificationBundle, userId);
        }

        @Override
        public void grantImplicitAccess(int userId, Intent intent,
                int recipientAppId, int visibleUid, boolean direct) {
            grantImplicitAccess(userId, intent, recipientAppId, visibleUid, direct,
                    false /* retainOnUpdate */);
        }

        @Override
        public void grantImplicitAccess(int userId, Intent intent,
                int recipientAppId, int visibleUid, boolean direct, boolean retainOnUpdate) {
            boolean accessGranted = executeWithConsistentComputerReturning(computer -> {
                final AndroidPackage visiblePackage = computer.getPackage(visibleUid);
                final int recipientUid = UserHandle.getUid(userId, recipientAppId);
                if (visiblePackage == null || computer.getPackage(recipientUid) == null) {
                    return false;
                }

                final boolean instantApp = computer.isInstantAppInternal(
                        visiblePackage.getPackageName(), userId, visibleUid);
                if (instantApp) {
                    if (!direct) {
                        // if the interaction that lead to this granting access to an instant app
                        // was indirect (i.e.: URI permission grant), do not actually execute the
                        // grant.
                        return false;
                    }
                    return mInstantAppRegistry.grantInstantAccess(userId, intent,
                            recipientAppId, UserHandle.getAppId(visibleUid) /*instantAppId*/);
                } else {
                    return mAppsFilter.grantImplicitAccess(recipientUid, visibleUid,
                            retainOnUpdate);
                }
            });

            if (accessGranted) {
                ApplicationPackageManager.invalidateGetPackagesForUidCache();
            }
        }

        @Override
        public boolean isInstantAppInstallerComponent(ComponentName component) {
            final ActivityInfo instantAppInstallerActivity = mInstantAppInstallerActivity;
            return instantAppInstallerActivity != null
                    && instantAppInstallerActivity.getComponentName().equals(component);
        }

        @Override
        public void pruneInstantApps() {
            executeWithConsistentComputer(computer ->
                    mInstantAppRegistry.pruneInstantApps(computer));
        }

        @Override
        public void pruneCachedApksInApex(@NonNull List<PackageInfo> apexPackages) {
            if (mCacheDir == null) {
                return;
            }

            final PackageCacher cacher = new PackageCacher(mCacheDir);
            synchronized (mLock) {
                for (int i = 0, size = apexPackages.size(); i < size; i++) {
                    final List<String> apkNames =
                            mApexManager.getApksInApex(apexPackages.get(i).packageName);
                    for (int j = 0, apksInApex = apkNames.size(); j < apksInApex; j++) {
                        final AndroidPackage pkg = getPackage(apkNames.get(j));
                        cacher.cleanCachedResult(new File(pkg.getPath()));
                    }
                }
            }
        }

        @Override
        public String getSetupWizardPackageName() {
            return mSetupWizardPackage;
        }

        public void setExternalSourcesPolicy(ExternalSourcesPolicy policy) {
            if (policy != null) {
                mExternalSourcesPolicy = policy;
            }
        }

        @Override
        public boolean isPackagePersistent(String packageName) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return false;
            }

            AndroidPackage pkg = packageState.getPkg();
            return pkg != null && pkg.isSystem() && pkg.isPersistent();
        }

        @Override
        public List<PackageInfo> getOverlayPackages(int userId) {
            final ArrayList<PackageInfo> overlayPackages = new ArrayList<>();
            forEachPackageState(packageState -> {
                final AndroidPackage pkg = packageState.getPkg();
                if (pkg != null && pkg.getOverlayTarget() != null) {
                    PackageInfo pkgInfo = generatePackageInfo(packageState, 0, userId);
                    if (pkgInfo != null) {
                        overlayPackages.add(pkgInfo);
                    }
                }
            });

            return overlayPackages;
        }

        @Override
        public List<String> getTargetPackageNames(int userId) {
            List<String> targetPackages = new ArrayList<>();
            forEachPackageState(packageState -> {
                final AndroidPackage pkg = packageState.getPkg();
                if (pkg != null && !pkg.isOverlay()) {
                    targetPackages.add(pkg.getPackageName());
                }
            });
            return targetPackages;
        }

        @Override
        public boolean setEnabledOverlayPackages(int userId, @NonNull String targetPackageName,
                @Nullable OverlayPaths overlayPaths,
                @NonNull Set<String> outUpdatedPackageNames) {
            return PackageManagerService.this.setEnabledOverlayPackages(userId, targetPackageName,
                    overlayPaths, outUpdatedPackageNames);
        }

        @Override
        public ResolveInfo resolveIntent(Intent intent, String resolvedType,
                @PackageManager.ResolveInfoFlagsBits long flags,
                @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags, int userId,
                boolean resolveForStart, int filterCallingUid) {
            return mResolveIntentHelper.resolveIntentInternal(snapshotComputer(),
                    intent, resolvedType, flags, privateResolveFlags, userId, resolveForStart,
                    filterCallingUid);
        }

        @Override
        public ResolveInfo resolveService(Intent intent, String resolvedType,
                @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid) {
            return mResolveIntentHelper.resolveServiceInternal(snapshotComputer(), intent,
                    resolvedType, flags, userId, callingUid);
        }

        @Override
        public ProviderInfo resolveContentProvider(String name,
                @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid) {
            return PackageManagerService.this.mComputer
                    .resolveContentProvider(name, flags, userId,callingUid);
        }

        @Override
        public void addIsolatedUid(int isolatedUid, int ownerUid) {
            synchronized (mLock) {
                mIsolatedOwners.put(isolatedUid, ownerUid);
            }
        }

        @Override
        public void removeIsolatedUid(int isolatedUid) {
            synchronized (mLock) {
                mIsolatedOwners.delete(isolatedUid);
            }
        }

        @Override
        public int getUidTargetSdkVersion(int uid) {
            return PackageManagerService.this.getUidTargetSdkVersion(uid);
        }

        @Override
        public int getPackageTargetSdkVersion(String packageName) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState != null && packageState.getPkg() != null) {
                return packageState.getPkg().getTargetSdkVersion();
            }
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }

        @Override
        public boolean canAccessInstantApps(int callingUid, int userId) {
            return PackageManagerService.this.canViewInstantApps(callingUid, userId);
        }

        @Override
        public boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
                @UserIdInt int userId) {
            return mComputer.canAccessComponent(callingUid, component, userId);
        }

        @Override
        public boolean hasInstantApplicationMetadata(String packageName, int userId) {
            return mInstantAppRegistry.hasInstantApplicationMetadata(packageName, userId);
        }

        @Override
        public void notifyPackageUse(String packageName, int reason) {
            synchronized (mLock) {
                PackageManagerService.this.notifyPackageUseInternal(packageName, reason);
            }
        }

        @Override
        public void onPackageProcessKilledForUninstall(String packageName) {
            mHandler.post(() -> PackageManagerService.this.notifyInstallObserver(packageName,
                    true /* killApp */));
        }

        @Override
        public SparseArray<String> getAppsWithSharedUserIds() {
            return mComputer.getAppsWithSharedUserIds();
        }

        @Override
        @NonNull
        public String[] getSharedUserPackagesForPackage(String packageName, int userId) {
            return mComputer.getSharedUserPackagesForPackage(packageName, userId);
        }

        @Override
        public ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
            return mComputer.getProcessesForUid(uid);
        }

        @Override
        public int[] getPermissionGids(String permissionName, int userId) {
            return mPermissionManager.getPermissionGids(permissionName, userId);
        }

        @Override
        public boolean isOnlyCoreApps() {
            return PackageManagerService.this.isOnlyCoreApps();
        }

        @Override
        public void freeStorage(String volumeUuid, long bytes,
                @StorageManager.AllocateFlags int flags) throws IOException {
            PackageManagerService.this.freeStorage(volumeUuid, bytes, flags);
        }

        @Override
        public void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
            PackageManagerService.this.forEachPackageSetting(actionLocked);
        }

        @Override
        public void forEachPackageState(Consumer<PackageStateInternal> action) {
            PackageManagerService.this.forEachPackageState(action);
        }

        @Override
        public void forEachPackage(Consumer<AndroidPackage> action) {
            PackageManagerService.this.forEachPackage(action);
        }

        @Override
        public void forEachInstalledPackage(@NonNull Consumer<AndroidPackage> action,
                @UserIdInt int userId) {
            PackageManagerService.this.forEachInstalledPackage(action, userId);
        }

        @Override
        public ArraySet<String> getEnabledComponents(String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return new ArraySet<>();
            }
            return packageState.getUserStateOrDefault(userId).getEnabledComponents();
        }

        @Override
        public ArraySet<String> getDisabledComponents(String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return new ArraySet<>();
            }
            return packageState.getUserStateOrDefault(userId).getDisabledComponents();
        }

        @Override
        public @PackageManager.EnabledState int getApplicationEnabledState(
                String packageName, int userId) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                return COMPONENT_ENABLED_STATE_DEFAULT;
            }
            return packageState.getUserStateOrDefault(userId).getEnabledState();
        }

        @Override
        public @PackageManager.EnabledState int getComponentEnabledSetting(
                @NonNull ComponentName componentName, int callingUid, int userId) {
            return PackageManagerService.this.mComputer.getComponentEnabledSettingInternal(
                    componentName, callingUid, userId);
        }

        @Override
        public void setEnableRollbackCode(int token, int enableRollbackCode) {
            PackageManagerService.this.setEnableRollbackCode(token, enableRollbackCode);
        }

        /**
         * Ask the package manager to compile layouts in the given package.
         */
        @Override
        public boolean compileLayouts(String packageName) {
            AndroidPackage pkg;
            synchronized (mLock) {
                pkg = mPackages.get(packageName);
                if (pkg == null) {
                    return false;
                }
            }
            return mArtManagerService.compileLayouts(pkg);
        }

        @Override
        public void finishPackageInstall(int token, boolean didLaunch) {
            PackageManagerService.this.finishPackageInstall(token, didLaunch);
        }

        @Nullable
        @Override
        public String removeLegacyDefaultBrowserPackageName(int userId) {
            synchronized (mLock) {
                return mSettings.removeDefaultBrowserPackageNameLPw(userId);
            }
        }

        @Override
        public boolean isApexPackage(String packageName) {
            return PackageManagerService.this.mApexManager.isApexPackage(packageName);
        }

        @Override
        public List<String> getApksInApex(String apexPackageName) {
            return PackageManagerService.this.mApexManager.getApksInApex(apexPackageName);
        }

        @Override
        public void uninstallApex(String packageName, long versionCode, int userId,
                IntentSender intentSender, int flags) {
            final int callerUid = Binder.getCallingUid();
            if (callerUid != Process.ROOT_UID && callerUid != Process.SHELL_UID) {
                throw new SecurityException("Not allowed to uninstall apexes");
            }
            PackageInstallerService.PackageDeleteObserverAdapter adapter =
                    new PackageInstallerService.PackageDeleteObserverAdapter(
                            PackageManagerService.this.mContext, intentSender, packageName,
                            false, userId);
            if ((flags & PackageManager.DELETE_ALL_USERS) == 0) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Can't uninstall an apex for a single user");
                return;
            }
            final ApexManager am = PackageManagerService.this.mApexManager;
            PackageInfo activePackage = am.getPackageInfo(packageName,
                    ApexManager.MATCH_ACTIVE_PACKAGE);
            if (activePackage == null) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        packageName + " is not an apex package");
                return;
            }
            if (versionCode != PackageManager.VERSION_CODE_HIGHEST
                    && activePackage.getLongVersionCode() != versionCode) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Active version " + activePackage.getLongVersionCode()
                                + " is not equal to " + versionCode + "]");
                return;
            }
            if (!am.uninstallApex(activePackage.applicationInfo.sourceDir)) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Failed to uninstall apex " + packageName);
            } else {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_SUCCEEDED,
                        null);
            }
        }

        @Override
        public void updateRuntimePermissionsFingerprint(@UserIdInt int userId) {
            mSettings.updateRuntimePermissionsFingerprint(userId);
        }

        @Override
        public void migrateLegacyObbData() {
            try {
                mInstaller.migrateLegacyObbData();
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
        }

        @Override
        public void writeSettings(boolean async) {
            synchronized (mLock) {
                if (async) {
                    scheduleWriteSettings();
                } else {
                    writeSettingsLPrTEMP();
                }
            }
        }

        @Override
        public void writePermissionSettings(int[] userIds, boolean async) {
            synchronized (mLock) {
                for (int userId : userIds) {
                    mSettings.writePermissionStateForUserLPr(userId, !async);
                }
            }
        }

        @Override
        public boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid) {
            return mComputer.isCallerInstallerOfRecord(pkg, callingUid);
        }

        @Override
        public boolean isPermissionUpgradeNeeded(int userId) {
            return mSettings.isPermissionUpgradeNeeded(userId);
        }

        @Override
        public void setIntegrityVerificationResult(int verificationId, int verificationResult) {
            final Message msg = mHandler.obtainMessage(INTEGRITY_VERIFICATION_COMPLETE);
            msg.arg1 = verificationId;
            msg.obj = verificationResult;
            mHandler.sendMessage(msg);
        }

        @Override
        public List<String> getMimeGroup(String packageName, String mimeGroup) {
            return PackageManagerService.this.getMimeGroupInternal(packageName, mimeGroup);
        }

        @Override
        public void setVisibilityLogging(String packageName, boolean enable) {
            final PackageStateInternal packageState = getPackageStateInternal(packageName);
            if (packageState == null) {
                throw new IllegalStateException("No package found for " + packageName);
            }
            mAppsFilter.getFeatureConfig().enableLogging(packageState.getAppId(), enable);
        }

        @Override
        public boolean isSystemPackage(@NonNull String packageName) {
            return packageName.equals(
                    PackageManagerService.this.ensureSystemPackageName(packageName));
        }

        @Override
        public void clearBlockUninstallForUser(@UserIdInt int userId) {
            synchronized (mLock) {
                mSettings.clearBlockUninstallLPw(userId);
                mSettings.writePackageRestrictionsLPr(userId);
            }
        }

        @Override
        public void unsuspendForSuspendingPackage(final String packageName, int affectedUser) {
            PackageManagerService.this.unsuspendForSuspendingPackage(packageName, affectedUser);
        }

        @Override
        public boolean isSuspendingAnyPackages(String suspendingPackage, int userId) {
            return PackageManagerService.this.isSuspendingAnyPackages(suspendingPackage, userId);
        }

        @Override
        public boolean registerInstalledLoadingProgressCallback(String packageName,
                PackageManagerInternal.InstalledLoadingProgressCallback callback, int userId) {
            final PackageStateInternal ps =
                    getPackageStateInstalledFiltered(packageName, Binder.getCallingUid(), userId);
            if (ps == null) {
                return false;
            }
            if (!ps.isLoading()) {
                Slog.w(TAG,
                        "Failed registering loading progress callback. Package is fully loaded.");
                return false;
            }
            if (mIncrementalManager == null) {
                Slog.w(TAG,
                        "Failed registering loading progress callback. Incremental is not enabled");
                return false;
            }
            return mIncrementalManager.registerLoadingProgressCallback(ps.getPathString(),
                    (IPackageLoadingProgressCallback) callback.getBinder());
        }

        @Override
        public IncrementalStatesInfo getIncrementalStatesInfo(
                @NonNull String packageName, int filterCallingUid, int userId) {
            final PackageStateInternal ps =
                    getPackageStateInstalledFiltered(packageName, filterCallingUid, userId);
            if (ps == null) {
                return null;
            }
            return new IncrementalStatesInfo(ps.isLoading(), ps.getLoadingProgress());
        }

        @Override
        public void requestChecksums(@NonNull String packageName, boolean includeSplits,
                @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
                @Nullable List trustedInstallers,
                @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
                @NonNull Executor executor, @NonNull Handler handler) {
            requestChecksumsInternal(packageName, includeSplits, optional, required,
                    trustedInstallers, onChecksumsReadyListener, userId, executor, handler);
        }

        @Override
        public boolean isPackageFrozen(@NonNull String packageName,
                int callingUid, int userId) {
            return PackageManagerService.this.getPackageStartability(
                    packageName, callingUid, userId) == PACKAGE_STARTABILITY_FROZEN;
        }

        @Override
        public long deleteOatArtifactsOfPackage(String packageName) {
            return PackageManagerService.this.deleteOatArtifactsOfPackage(packageName);
        }

        @Override
        public void withPackageSettingsSnapshot(
                @NonNull Consumer<Function<String, PackageStateInternal>> block) {
            executeWithConsistentComputer(computer ->
                    block.accept(computer::getPackageStateInternal));
        }

        @Override
        public <Output> Output withPackageSettingsSnapshotReturning(
                @NonNull FunctionalUtils.ThrowingFunction<Function<String, PackageStateInternal>,
                        Output> block) {
            return executeWithConsistentComputerReturning(computer ->
                    block.apply(computer::getPackageStateInternal));
        }

        @Override
        public <ExceptionType extends Exception> void withPackageSettingsSnapshotThrowing(
                @NonNull FunctionalUtils.ThrowingCheckedConsumer<Function<String,
                        PackageStateInternal>, ExceptionType> block) throws ExceptionType {
            executeWithConsistentComputerThrowing(computer ->
                    block.accept(computer::getPackageStateInternal));
        }

        @Override
        public <ExceptionOne extends Exception, ExceptionTwo extends Exception> void
                withPackageSettingsSnapshotThrowing2(
                        @NonNull FunctionalUtils.ThrowingChecked2Consumer<
                                Function<String, PackageStateInternal>, ExceptionOne,
                                ExceptionTwo> block)
                throws ExceptionOne, ExceptionTwo {
            executeWithConsistentComputerThrowing2(
                    (FunctionalUtils.ThrowingChecked2Consumer<Computer, ExceptionOne,
                            ExceptionTwo>) computer -> block.accept(computer::getPackageStateInternal));
        }

        @Override
        public <Output, ExceptionType extends Exception> Output
                withPackageSettingsSnapshotReturningThrowing(
                        @NonNull FunctionalUtils.ThrowingCheckedFunction<
                                Function<String, PackageStateInternal>, Output,
                                ExceptionType> block)
                throws ExceptionType {
            return executeWithConsistentComputerReturningThrowing(computer ->
                    block.apply(computer::getPackageStateInternal));
        }

        @Override
        public void reconcileAppsData(int userId, @StorageManager.StorageFlags int flags,
                boolean migrateAppsData) {
            PackageManagerService.this.mAppDataHelper.reconcileAppsData(userId, flags,
                    migrateAppsData);
        }

        @Override
        @NonNull
        public ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId) {
            return PackageManagerService.this.mComputer.getSharedUserPackages(sharedUserAppId);
        }

        @Override
        @Nullable
        public SharedUserApi getSharedUserApi(int sharedUserAppId) {
            return mComputer.getSharedUser(sharedUserAppId);
        }

        @NonNull
        @Override
        public PackageStateMutator.InitialState recordInitialState() {
            return PackageManagerService.this.recordInitialState();
        }

        @Nullable
        @Override
        public PackageStateMutator.Result commitPackageStateMutation(
                @Nullable PackageStateMutator.InitialState state,
                @NonNull Consumer<PackageStateMutator> consumer) {
            return PackageManagerService.this.commitPackageStateMutation(state, consumer);
        }

        @NonNull
        @Override
        public Computer snapshot() {
            return snapshotComputer();
        }
    }

    private boolean setEnabledOverlayPackages(@UserIdInt int userId,
            @NonNull String targetPackageName, @Nullable OverlayPaths newOverlayPaths,
            @NonNull Set<String> outUpdatedPackageNames) {
        synchronized (mOverlayPathsLock) {
            final ArrayMap<String, ArraySet<String>> libNameToModifiedDependents = new ArrayMap<>();
            Boolean targetModified = executeWithConsistentComputerReturning(computer -> {
                final PackageStateInternal packageState = computer.getPackageStateInternal(
                        targetPackageName);
                final AndroidPackage targetPkg =
                        packageState == null ? null : packageState.getPkg();
                if (targetPackageName == null || targetPkg == null) {
                    Slog.e(TAG, "failed to find package " + targetPackageName);
                    return null;
                }

                if (Objects.equals(packageState.getUserStateOrDefault(userId).getOverlayPaths(),
                        newOverlayPaths)) {
                    return false;
                }

                if (targetPkg.getLibraryNames() != null) {
                    // Set the overlay paths for dependencies of the shared library.
                    for (final String libName : targetPkg.getLibraryNames()) {
                        ArraySet<String> modifiedDependents = null;

                        final SharedLibraryInfo info = computer.getSharedLibraryInfo(libName,
                                SharedLibraryInfo.VERSION_UNDEFINED);
                        if (info == null) {
                            continue;
                        }
                        final List<VersionedPackage> dependents = computer
                                .getPackagesUsingSharedLibrary(info, 0, Process.SYSTEM_UID, userId);
                        if (dependents == null) {
                            continue;
                        }
                        for (final VersionedPackage dependent : dependents) {
                            final PackageStateInternal dependentState =
                                    computer.getPackageStateInternal(dependent.getPackageName());
                            if (dependentState == null) {
                                continue;
                            }
                            if (!Objects.equals(dependentState.getUserStateOrDefault(userId)
                                    .getSharedLibraryOverlayPaths()
                                    .get(libName), newOverlayPaths)) {
                                String dependentPackageName = dependent.getPackageName();
                                modifiedDependents = ArrayUtils.add(modifiedDependents,
                                        dependentPackageName);
                                outUpdatedPackageNames.add(dependentPackageName);
                            }
                        }

                        if (modifiedDependents != null) {
                            libNameToModifiedDependents.put(libName, modifiedDependents);
                        }
                    }
                }

                outUpdatedPackageNames.add(targetPackageName);
                return true;
            });

            if (targetModified == null) {
                // Null indicates error
                return false;
            } else if (!targetModified) {
                // Treat non-modification as a successful commit
                return true;
            }

            commitPackageStateMutation(null, mutator -> {
                mutator.forPackage(targetPackageName)
                        .userState(userId)
                        .setOverlayPaths(newOverlayPaths);

                for (int mapIndex = 0; mapIndex < libNameToModifiedDependents.size(); mapIndex++) {
                    String libName = libNameToModifiedDependents.keyAt(mapIndex);
                    ArraySet<String> modifiedDependents =
                            libNameToModifiedDependents.valueAt(mapIndex);
                    for (int setIndex = 0; setIndex < modifiedDependents.size(); setIndex++) {
                        mutator.forPackage(modifiedDependents.valueAt(setIndex))
                                .userState(userId)
                                .setOverlayPathsForLibrary(libName, newOverlayPaths);
                    }
                }
            });
        }

        invalidatePackageInfoCache();

        return true;
    }

    @Override
    public int getRuntimePermissionsVersion(@UserIdInt int userId) {
        Preconditions.checkArgumentNonnegative(userId);
        enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                "getRuntimePermissionVersion");
        return mSettings.getDefaultRuntimePermissionsVersion(userId);
    }

    @Override
    public void setRuntimePermissionsVersion(int version, @UserIdInt int userId) {
        Preconditions.checkArgumentNonnegative(version);
        Preconditions.checkArgumentNonnegative(userId);
        enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                "setRuntimePermissionVersion");
        mSettings.setDefaultRuntimePermissionsVersion(version, userId);
    }

    private void enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
            @NonNull String message) {
        if (mContext.checkCallingOrSelfPermission(
                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
                != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(
                Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY + " or "
                    + Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS);
        }
    }

    // TODO: Remove
    @Deprecated
    @Nullable
    @GuardedBy("mLock")
    PackageSetting getPackageSettingForMutation(String packageName) {
        return mSettings.getPackageLPr(packageName);
    }

    // TODO: Remove
    @Deprecated
    @Nullable
    @GuardedBy("mLock")
    PackageSetting getDisabledPackageSettingForMutation(String packageName) {
        return mSettings.getDisabledSystemPkgLPr(packageName);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @Nullable
    PackageStateInternal getPackageStateInternal(String packageName) {
        return mComputer.getPackageStateInternal(packageName);
    }

    @Nullable
    PackageStateInternal getPackageStateInternal(String packageName, int callingUid) {
        return mComputer.getPackageStateInternal(packageName, callingUid);
    }

    @Nullable
    PackageStateInternal getPackageStateInstalledFiltered(@NonNull String packageName,
            int callingUid, @UserIdInt int userId) {
        return filterPackageStateForInstalledAndFiltered(mComputer, packageName, callingUid,
                userId);
    }

    @Nullable
    private PackageStateInternal filterPackageStateForInstalledAndFiltered(
            @NonNull Computer computer, @NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        PackageStateInternal packageState =
                computer.getPackageStateInternal(packageName, callingUid);
        if (packageState == null
                || computer.shouldFilterApplication(packageState, callingUid, userId)
                || !packageState.getUserStateOrDefault(userId).isInstalled()) {
            return null;
        } else {
            return packageState;
        }
    }

    @Nullable
    private PackageState getPackageState(String packageName) {
        return mComputer.getPackageStateCopied(packageName);
    }

    @NonNull
    ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
        Computer computer = snapshotComputer();
        if (computer == mLiveComputer) {
            return new ArrayMap<>(computer.getPackageStates());
        } else {
            return computer.getPackageStates();
        }
    }

    private void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
        synchronized (mLock) {
            int size = mSettings.getPackagesLocked().size();
            for (int index = 0; index < size; index++) {
                actionLocked.accept(mSettings.getPackagesLocked().valueAt(index));
            }
        }
    }

    void forEachPackageState(Consumer<PackageStateInternal> consumer) {
        forEachPackageState(mComputer.getPackageStates(), consumer);
    }

    void forEachPackage(Consumer<AndroidPackage> consumer) {
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                mComputer.getPackageStates();
        int size = packageStates.size();
        for (int index = 0; index < size; index++) {
            PackageStateInternal packageState = packageStates.valueAt(index);
            if (packageState.getPkg() != null) {
                consumer.accept(packageState.getPkg());
            }
        }
    }

    private void forEachPackageState(
            @NonNull ArrayMap<String, ? extends PackageStateInternal> packageStates,
            @NonNull Consumer<PackageStateInternal> consumer) {
        int size = packageStates.size();
        for (int index = 0; index < size; index++) {
            PackageStateInternal packageState = packageStates.valueAt(index);
            consumer.accept(packageState);
        }
    }

    void forEachInstalledPackage(@NonNull Consumer<AndroidPackage> action,
            @UserIdInt int userId) {
        Consumer<PackageStateInternal> actionWrapped = packageState -> {
            if (packageState.getPkg() != null
                    && packageState.getUserStateOrDefault(userId).isInstalled()) {
                action.accept(packageState.getPkg());
            }
        };
        forEachPackageState(mComputer.getPackageStates(), actionWrapped);
    }

    // TODO: Make private
    void executeWithConsistentComputer(
            @NonNull FunctionalUtils.ThrowingConsumer<Computer> consumer) {
        consumer.accept(snapshotComputer());
    }

    private <T> T executeWithConsistentComputerReturning(
            @NonNull FunctionalUtils.ThrowingFunction<Computer, T> function) {
        return function.apply(snapshotComputer());
    }

    private <ExceptionType extends Exception> void executeWithConsistentComputerThrowing(
            @NonNull FunctionalUtils.ThrowingCheckedConsumer<Computer, ExceptionType> consumer)
            throws ExceptionType {
        consumer.accept(snapshotComputer());
    }

    private <ExceptionOne extends Exception, ExceptionTwo extends Exception> void
    executeWithConsistentComputerThrowing2(
            @NonNull FunctionalUtils.ThrowingChecked2Consumer<Computer, ExceptionOne,
                    ExceptionTwo> consumer) throws ExceptionOne, ExceptionTwo {
        consumer.accept(snapshotComputer());
    }

    private <T, ExceptionType extends Exception> T executeWithConsistentComputerReturningThrowing(
            @NonNull FunctionalUtils.ThrowingCheckedFunction<Computer, T, ExceptionType> function)
            throws ExceptionType {
        return function.apply(snapshotComputer());
    }

    boolean isHistoricalPackageUsageAvailable() {
        return mPackageUsage.isHistoricalPackageUsageAvailable();
    }

    /**
     * Logs process start information (including base APK hash) to the security log.
     * @hide
     */
    @Override
    public void logAppProcessStartIfNeeded(String packageName, String processName, int uid,
            String seinfo, String apkFile, int pid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        mProcessLoggingHandler.logAppProcessStart(mContext, mPmInternal, apkFile, packageName,
                processName, uid, seinfo, pid);
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(AndroidPackage pkg) {
        return getOrCreateCompilerPackageStats(pkg.getPackageName());
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(String pkgName) {
        return mCompilerStats.getOrCreatePackageStats(pkgName);
    }

    @Override
    public boolean isAutoRevokeWhitelisted(String packageName) {
        int mode = mInjector.getSystemService(AppOpsManager.class).checkOpNoThrow(
                AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                Binder.getCallingUid(), packageName);
        return mode == MODE_IGNORED;
    }

    @PackageManager.InstallReason
    @Override
    public int getInstallReason(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.getInstallReason(packageName, userId);
    }

    @Override
    public boolean canRequestPackageInstalls(String packageName, int userId) {
        return mComputer.canRequestPackageInstalls(packageName, Binder.getCallingUid(), userId,
                true /* throwIfPermNotDeclared*/);
    }

    /**
     * Returns true if the system or user is explicitly preventing an otherwise valid installer to
     * complete an install. This includes checks like unknown sources and user restrictions.
     */
    public boolean isInstallDisabledForPackage(String packageName, int uid, int userId) {
        return mComputer.isInstallDisabledForPackage(packageName, uid, userId);
    }

    @Override
    public ComponentName getInstantAppResolverSettingsComponent() {
        return mInstantAppResolverSettingsComponent;
    }

    @Override
    public ComponentName getInstantAppInstallerComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return mInstantAppInstallerActivity == null
                ? null : mInstantAppInstallerActivity.getComponentName();
    }

    @Override
    public String getInstantAppAndroidId(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_INSTANT_APPS,
                "getInstantAppAndroidId");
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppAndroidId");
        // Make sure the target is an Instant App.
        if (!isInstantApp(packageName, userId)) {
            return null;
        }
        return mInstantAppRegistry.getInstantAppAndroidId(packageName, userId);
    }

    @Override
    public void grantImplicitAccess(int recipientUid, @NonNull String visibleAuthority) {
        final int callingUid = Binder.getCallingUid();
        final int recipientUserId = UserHandle.getUserId(recipientUid);
        final ProviderInfo providerInfo =
                mComputer.getGrantImplicitAccessProviderInfo(recipientUid, visibleAuthority);
        if (providerInfo == null) {
            return;
        }
        int visibleUid = providerInfo.applicationInfo.uid;
        mPmInternal.grantImplicitAccess(recipientUserId, null /*Intent*/,
                UserHandle.getAppId(recipientUid), visibleUid, false /*direct*/);
    }

    boolean canHaveOatDir(String packageName) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null || packageState.getPkg() == null) {
            return false;
        }
        return AndroidPackageUtils.canHaveOatDir(packageState.getPkg(),
                packageState.getTransientState().isUpdatedSystemApp());
    }

    long deleteOatArtifactsOfPackage(String packageName) {
        PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null || packageState.getPkg() == null) {
            return -1; // error code of deleteOptimizedFiles
        }
        return mDexManager.deleteOptimizedFiles(
                ArtUtils.createArtPackageInfo(packageState.getPkg(), packageState));
    }

    @NonNull
    Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        return mComputer.getUnusedPackages(downgradeTimeThresholdMillis);
    }

    @Override
    public void setHarmfulAppWarning(@NonNull String packageName, @Nullable CharSequence warning,
            int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, true /*requireFullPermission*/,
                true /*checkShell*/, "setHarmfulAppInfo");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID &&
                checkUidPermission(SET_HARMFUL_APP_WARNINGS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + SET_HARMFUL_APP_WARNINGS + " permission.");
        }

        PackageStateMutator.Result result = commitPackageStateMutation(null, packageName,
                packageState -> packageState.userState(userId)
                        .setHarmfulAppWarning(warning == null ? null : warning.toString()));
        if (result.isSpecificPackageNull()) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        scheduleWritePackageRestrictions(userId);
    }

    @Nullable
    @Override
    public CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId) {
        return mComputer.getHarmfulAppWarning(packageName, userId);
    }

    @Override
    public boolean isPackageStateProtected(@NonNull String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                true /*checkShell*/, "isPackageStateProtected");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID
                && checkUidPermission(MANAGE_DEVICE_ADMINS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + MANAGE_DEVICE_ADMINS + " permission.");
        }

        return mProtectedPackages.isPackageStateProtected(userId, packageName);
    }

    @Override
    public void sendDeviceCustomizationReadyBroadcast() {
        mContext.enforceCallingPermission(Manifest.permission.SEND_DEVICE_CUSTOMIZATION_READY,
                "sendDeviceCustomizationReadyBroadcast");

        final long ident = Binder.clearCallingIdentity();
        try {
            BroadcastHelper.sendDeviceCustomizationReadyBroadcast();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setMimeGroup(String packageName, String mimeGroup, List<String> mimeTypes) {
        enforceOwnerRights(packageName, Binder.getCallingUid());
        mimeTypes = CollectionUtils.emptyIfNull(mimeTypes);
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        Set<String> existingMimeTypes = packageState.getMimeGroups().get(mimeGroup);
        if (existingMimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + packageName);
        }
        if (existingMimeTypes.size() == mimeTypes.size()
                && existingMimeTypes.containsAll(mimeTypes)) {
            return;
        }

        ArraySet<String> mimeTypesSet = new ArraySet<>(mimeTypes);
        commitPackageStateMutation(null, packageName, packageStateWrite -> {
            packageStateWrite.setMimeGroup(mimeGroup, mimeTypesSet);
        });
        if (mComponentResolver.updateMimeGroup(snapshotComputer(), packageName, mimeGroup)) {
            Binder.withCleanCallingIdentity(() ->
                    mPreferredActivityHelper.clearPackagePreferredActivities(packageName,
                            UserHandle.USER_ALL));
        }

        scheduleWriteSettings();
    }

    @Override
    public List<String> getMimeGroup(String packageName, String mimeGroup) {
        enforceOwnerRights(packageName, Binder.getCallingUid());
        return getMimeGroupInternal(packageName, mimeGroup);
    }

    private List<String> getMimeGroupInternal(String packageName, String mimeGroup) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            return Collections.emptyList();
        }

        final Map<String, Set<String>> mimeGroups = packageState.getMimeGroups();
        Set<String> mimeTypes = mimeGroups != null ? mimeGroups.get(mimeGroup) : null;
        if (mimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + packageName);
        }
        return new ArrayList<>(mimeTypes);
    }

    @Override
    public void setSplashScreenTheme(@NonNull String packageName, @Nullable String themeId,
            int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "setSplashScreenTheme");
        enforceOwnerRights(packageName, callingUid);

        PackageStateInternal packageState = getPackageStateInstalledFiltered(packageName,
                callingUid, userId);
        if (packageState == null) {
            return;
        }

        commitPackageStateMutation(null, packageName, state ->
                state.userState(userId).setSplashScreenTheme(themeId));
    }

    @Override
    public String getSplashScreenTheme(@NonNull String packageName, int userId) {
        PackageStateInternal packageState =
                getPackageStateInstalledFiltered(packageName, Binder.getCallingUid(), userId);
        return packageState == null ? null
                : packageState.getUserStateOrDefault(userId).getSplashScreenTheme();
    }

    /**
     * Temporary method that wraps mSettings.writeLPr() and calls mPermissionManager's
     * writeLegacyPermissionsTEMP() beforehand.
     *
     * TODO: In the meantime, can this be moved to a schedule call?
     * TODO(b/182523293): This should be removed once we finish migration of permission storage.
     */
    void writeSettingsLPrTEMP() {
        mPermissionManager.writeLegacyPermissionsTEMP(mSettings.mPermissions);
        mSettings.writeLPr();
    }

    @Override
    public IBinder getHoldLockToken() {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("getHoldLockToken requires a debuggable build");
        }

        mContext.enforceCallingPermission(
                Manifest.permission.INJECT_EVENTS,
                "getHoldLockToken requires INJECT_EVENTS permission");

        final Binder token = new Binder();
        token.attachInterface(this, "holdLock:" + Binder.getCallingUid());
        return token;
    }

    @Override
    public void verifyHoldLockToken(IBinder token) {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("holdLock requires a debuggable build");
        }

        if (token == null) {
            throw new SecurityException("null holdLockToken");
        }

        if (token.queryLocalInterface("holdLock:" + Binder.getCallingUid()) != this) {
            throw new SecurityException("Invalid holdLock() token");
        }
    }

    @Override
    public void holdLock(IBinder token, int durationMs) {
        mTestUtilityService.verifyHoldLockToken(token);

        synchronized (mLock) {
            SystemClock.sleep(durationMs);
        }
    }

    static String getDefaultTimeouts() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_INCFS_DEFAULT_TIMEOUTS, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static String getKnownDigestersList() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_KNOWN_DIGESTERS_LIST, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the array containing per-uid timeout configuration.
     * This is derived from DeviceConfig flags.
     */
    public @NonNull PerUidReadTimeouts[] getPerUidReadTimeouts() {
        PerUidReadTimeouts[] result = mPerUidReadTimeoutsCache;
        if (result == null) {
            result = parsePerUidReadTimeouts();
            mPerUidReadTimeoutsCache = result;
        }
        return result;
    }

    private @NonNull PerUidReadTimeouts[] parsePerUidReadTimeouts() {
        final String defaultTimeouts = getDefaultTimeouts();
        final String knownDigestersList = getKnownDigestersList();
        final List<PerPackageReadTimeouts> perPackageReadTimeouts =
                PerPackageReadTimeouts.parseDigestersList(defaultTimeouts, knownDigestersList);

        if (perPackageReadTimeouts.size() == 0) {
            return EMPTY_PER_UID_READ_TIMEOUTS_ARRAY;
        }

        final int[] allUsers = mInjector.getUserManagerService().getUserIds();
        final List<PerUidReadTimeouts> result = new ArrayList<>(perPackageReadTimeouts.size());
        for (int i = 0, size = perPackageReadTimeouts.size(); i < size; ++i) {
            final PerPackageReadTimeouts perPackage = perPackageReadTimeouts.get(i);
            final PackageStateInternal ps = getPackageStateInternal(perPackage.packageName);
            if (ps == null) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: package not found = "
                            + perPackage.packageName);
                }
                continue;
            }
            if (ps.getAppId() < Process.FIRST_APPLICATION_UID) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: package is system, appId="
                            + ps.getAppId());
                }
                continue;
            }

            final AndroidPackage pkg = ps.getPkg();
            if (pkg.getLongVersionCode() < perPackage.versionCodes.minVersionCode
                    || pkg.getLongVersionCode() > perPackage.versionCodes.maxVersionCode) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: version code is not in range = "
                            + perPackage.packageName + ":" + pkg.getLongVersionCode());
                }
                continue;
            }
            if (perPackage.sha256certificate != null
                    && !pkg.getSigningDetails().hasSha256Certificate(
                    perPackage.sha256certificate)) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: invalid certificate = "
                            + perPackage.packageName + ":" + pkg.getLongVersionCode());
                }
                continue;
            }
            for (int userId : allUsers) {
                if (!ps.getUserStateOrDefault(userId).isInstalled()) {
                    continue;
                }
                final int uid = UserHandle.getUid(userId, ps.getAppId());
                final PerUidReadTimeouts perUid = new PerUidReadTimeouts();
                perUid.uid = uid;
                perUid.minTimeUs = perPackage.timeouts.minTimeUs;
                perUid.minPendingTimeUs = perPackage.timeouts.minPendingTimeUs;
                perUid.maxPendingTimeUs = perPackage.timeouts.maxPendingTimeUs;
                result.add(perUid);
            }
        }
        return result.toArray(new PerUidReadTimeouts[result.size()]);
    }

    @Override
    public void setKeepUninstalledPackages(List<String> packageList) {
        mContext.enforceCallingPermission(
                Manifest.permission.KEEP_UNINSTALLED_PACKAGES,
                "setKeepUninstalledPackages requires KEEP_UNINSTALLED_PACKAGES permission");
        Objects.requireNonNull(packageList);

        setKeepUninstalledPackagesInternal(packageList);
    }

    private void setKeepUninstalledPackagesInternal(List<String> packageList) {
        Preconditions.checkNotNull(packageList);
        synchronized (mKeepUninstalledPackages) {
            List<String> toRemove = new ArrayList<>(mKeepUninstalledPackages);
            toRemove.removeAll(packageList); // Do not remove anything still in the list

            mKeepUninstalledPackages.clear();
            mKeepUninstalledPackages.addAll(packageList);

            for (int i = 0; i < toRemove.size(); i++) {
                deletePackageIfUnused(toRemove.get(i));
            }
        }
    }

    boolean shouldKeepUninstalledPackageLPr(String packageName) {
        synchronized (mKeepUninstalledPackages) {
            return mKeepUninstalledPackages.contains(packageName);
        }
    }

    @Override
    public IntentSender getLaunchIntentSenderForPackage(String packageName, String callingPackage,
            String featureId, int userId) throws RemoteException {
        return mResolveIntentHelper.getLaunchIntentSenderForPackage(snapshotComputer(),
                packageName, callingPackage, featureId, userId);
    }

    @Override
    public boolean canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String targetPackageName, @UserIdInt int userId) {
        return mComputer.canPackageQuery(sourcePackageName, targetPackageName, userId);
    }

    boolean getSafeMode() {
        return mSafeMode;
    }

    ComponentName getResolveComponentName() {
        return mResolveComponentName;
    }

    DefaultAppProvider getDefaultAppProvider() {
        return mDefaultAppProvider;
    }

    File getCacheDir() {
        return mCacheDir;
    }

    PackageProperty getPackageProperty() {
        return mPackageProperty;
    }

    WatchedArrayMap<ComponentName, ParsedInstrumentation> getInstrumentation() {
        return mInstrumentation;
    }

    int getSdkVersion() {
        return mSdkVersion;
    }

    void addAllPackageProperties(@NonNull AndroidPackage pkg) {
        mPackageProperty.addAllProperties(pkg);
    }

    void addInstrumentation(ComponentName name, ParsedInstrumentation instrumentation) {
        mInstrumentation.put(name, instrumentation);
    }

    String[] getKnownPackageNamesInternal(int knownPackage, int userId) {
        switch (knownPackage) {
            case PackageManagerInternal.PACKAGE_BROWSER:
                return new String[] { mDefaultAppProvider.getDefaultBrowser(userId) };
            case PackageManagerInternal.PACKAGE_INSTALLER:
                return mComputer.filterOnlySystemPackages(mRequiredInstallerPackage);
            case PackageManagerInternal.PACKAGE_UNINSTALLER:
                return mComputer.filterOnlySystemPackages(mRequiredUninstallerPackage);
            case PackageManagerInternal.PACKAGE_SETUP_WIZARD:
                return mComputer.filterOnlySystemPackages(mSetupWizardPackage);
            case PackageManagerInternal.PACKAGE_SYSTEM:
                return new String[]{"android"};
            case PackageManagerInternal.PACKAGE_VERIFIER:
                return mComputer.filterOnlySystemPackages(mRequiredVerifierPackage);
            case PackageManagerInternal.PACKAGE_SYSTEM_TEXT_CLASSIFIER:
                return mComputer.filterOnlySystemPackages(
                        mDefaultTextClassifierPackage, mSystemTextClassifierPackageName);
            case PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER:
                return mComputer.filterOnlySystemPackages(mRequiredPermissionControllerPackage);
            case PackageManagerInternal.PACKAGE_CONFIGURATOR:
                return mComputer.filterOnlySystemPackages(mConfiguratorPackage);
            case PackageManagerInternal.PACKAGE_INCIDENT_REPORT_APPROVER:
                return mComputer.filterOnlySystemPackages(mIncidentReportApproverPackage);
            case PackageManagerInternal.PACKAGE_AMBIENT_CONTEXT_DETECTION:
                return mComputer.filterOnlySystemPackages(mAmbientContextDetectionPackage);
            case PackageManagerInternal.PACKAGE_APP_PREDICTOR:
                return mComputer.filterOnlySystemPackages(mAppPredictionServicePackage);
            case PackageManagerInternal.PACKAGE_COMPANION:
                return mComputer.filterOnlySystemPackages(COMPANION_PACKAGE_NAME);
            case PackageManagerInternal.PACKAGE_RETAIL_DEMO:
                return TextUtils.isEmpty(mRetailDemoPackage)
                        ? ArrayUtils.emptyArray(String.class)
                        : new String[] {mRetailDemoPackage};
            case PackageManagerInternal.PACKAGE_OVERLAY_CONFIG_SIGNATURE:
                return mComputer.filterOnlySystemPackages(getOverlayConfigSignaturePackageName());
            case PackageManagerInternal.PACKAGE_RECENTS:
                return mComputer.filterOnlySystemPackages(mRecentsPackage);
            default:
                return ArrayUtils.emptyArray(String.class);
        }
    }

    String getActiveLauncherPackageName(int userId) {
        return mDefaultAppProvider.getDefaultHome(userId);
    }

    boolean setActiveLauncherPackage(@NonNull String packageName, @UserIdInt int userId,
            @NonNull Consumer<Boolean> callback) {
        return mDefaultAppProvider.setDefaultHome(packageName, userId, mContext.getMainExecutor(),
                callback);
    }

    void setDefaultBrowser(@Nullable String packageName, boolean async, @UserIdInt int userId) {
        mDefaultAppProvider.setDefaultBrowser(packageName, async, userId);
    }

    ResolveInfo getInstantAppInstallerInfo() {
        return mInstantAppInstallerInfo;
    }

    PackageUsage getPackageUsage() {
        return mPackageUsage;
    }

    String getModuleMetadataPackageName() {
        return mModuleInfoProvider.getPackageName();
    }

    File getAppInstallDir() {
        return mAppInstallDir;
    }

    boolean isExpectingBetter(String packageName) {
        return mInitAndSystemPackageHelper.isExpectingBetter(packageName);
    }

    int getDefParseFlags() {
        return mDefParseFlags;
    }

    void setUpCustomResolverActivity(AndroidPackage pkg, PackageSetting pkgSetting) {
        synchronized (mLock) {
            mResolverReplaced = true;

            // The instance created in PackageManagerService is special cased to be non-user
            // specific, so initialize all the needed fields here.
            ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    PackageUserStateInternal.DEFAULT, UserHandle.USER_SYSTEM, pkgSetting);

            // Set up information for custom user intent resolution activity.
            mResolveActivity.applicationInfo = appInfo;
            mResolveActivity.name = mCustomResolverComponentName.getClassName();
            mResolveActivity.packageName = pkg.getPackageName();
            mResolveActivity.processName = pkg.getProcessName();
            mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                    | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
            mResolveActivity.theme = 0;
            mResolveActivity.exported = true;
            mResolveActivity.enabled = true;
            mResolveInfo.activityInfo = mResolveActivity;
            mResolveInfo.priority = 0;
            mResolveInfo.preferredOrder = 0;
            mResolveInfo.match = 0;
            mResolveComponentName = mCustomResolverComponentName;
            PackageManagerService.onChanged();
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: "
                    + mResolveComponentName);
        }
    }

    void setPlatformPackage(AndroidPackage pkg, PackageSetting pkgSetting) {
        synchronized (mLock) {
            // Set up information for our fall-back user intent resolution activity.
            mPlatformPackage = pkg;

            // The instance stored in PackageManagerService is special cased to be non-user
            // specific, so initialize all the needed fields here.
            mAndroidApplication = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    PackageUserStateInternal.DEFAULT, UserHandle.USER_SYSTEM, pkgSetting);

            if (!mResolverReplaced) {
                mResolveActivity.applicationInfo = mAndroidApplication;
                mResolveActivity.name = ResolverActivity.class.getName();
                mResolveActivity.packageName = mAndroidApplication.packageName;
                mResolveActivity.processName = "system:ui";
                mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                mResolveActivity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                        | ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
                mResolveActivity.theme = R.style.Theme_Material_Dialog_Alert;
                mResolveActivity.exported = true;
                mResolveActivity.enabled = true;
                mResolveActivity.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
                mResolveActivity.configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SCREEN_LAYOUT
                        | ActivityInfo.CONFIG_ORIENTATION
                        | ActivityInfo.CONFIG_KEYBOARD
                        | ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
                mResolveInfo.activityInfo = mResolveActivity;
                mResolveInfo.priority = 0;
                mResolveInfo.preferredOrder = 0;
                mResolveInfo.match = 0;
                mResolveComponentName = new ComponentName(
                        mAndroidApplication.packageName, mResolveActivity.name);
            }
            PackageManagerService.onChanged();
        }
    }

    ResolveInfo getResolveInfo() {
        return mResolveInfo;
    }

    ApplicationInfo getCoreAndroidApplication() {
        return mAndroidApplication;
    }

    boolean isSystemReady() {
        return mSystemReady;
    }

    AndroidPackage getPlatformPackage() {
        return mPlatformPackage;
    }

    boolean isPreNUpgrade() {
        return mIsPreNUpgrade;
    }

    boolean isPreNMR1Upgrade() {
        return mIsPreNMR1Upgrade;
    }

    boolean isOverlayMutable(String packageName) {
        return (mOverlayConfig != null ? mOverlayConfig
                : OverlayConfig.getSystemInstance()).isMutable(packageName);
    }

    @ScanFlags int getSystemPackageScanFlags(File codePath) {
        List<ScanPartition> dirsToScanAsSystem =
                mInitAndSystemPackageHelper.getDirsToScanAsSystem();
        @PackageManagerService.ScanFlags int scanFlags = SCAN_AS_SYSTEM;
        for (int i = dirsToScanAsSystem.size() - 1; i >= 0; i--) {
            ScanPartition partition = dirsToScanAsSystem.get(i);
            if (partition.containsFile(codePath)) {
                scanFlags |= partition.scanFlag;
                if (partition.containsPrivApp(codePath)) {
                    scanFlags |= SCAN_AS_PRIVILEGED;
                }
                break;
            }
        }
        return scanFlags;
    }

    Pair<Integer, Integer> getSystemPackageRescanFlagsAndReparseFlags(File scanFile,
            int systemScanFlags, int systemParseFlags) {
        List<ScanPartition> dirsToScanAsSystem =
                mInitAndSystemPackageHelper.getDirsToScanAsSystem();
        @ParsingPackageUtils.ParseFlags int reparseFlags = 0;
        @PackageManagerService.ScanFlags int rescanFlags = 0;
        for (int i1 = dirsToScanAsSystem.size() - 1; i1 >= 0; i1--) {
            final ScanPartition partition = dirsToScanAsSystem.get(i1);
            if (partition.containsPrivApp(scanFile)) {
                reparseFlags = systemParseFlags;
                rescanFlags = systemScanFlags | SCAN_AS_PRIVILEGED
                        | partition.scanFlag;
                break;
            }
            if (partition.containsApp(scanFile)) {
                reparseFlags = systemParseFlags;
                rescanFlags = systemScanFlags | partition.scanFlag;
                break;
            }
        }
        return new Pair<>(rescanFlags, reparseFlags);
    }


    /**
     * @see PackageManagerInternal#recordInitialState()
     */
    @NonNull
    public PackageStateMutator.InitialState recordInitialState() {
        return mPackageStateMutator.initialState(mChangedPackagesTracker.getSequenceNumber());
    }

    /**
     * @see PackageManagerInternal#commitPackageStateMutation(PackageStateMutator.InitialState,
     * Consumer)
     */
    @NonNull
    public PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState initialState,
            @NonNull Consumer<PackageStateMutator> consumer) {
        synchronized (mPackageStateWriteLock) {
            final PackageStateMutator.Result result = mPackageStateMutator.generateResult(
                    initialState, mChangedPackagesTracker.getSequenceNumber());
            if (result != PackageStateMutator.Result.SUCCESS) {
                return result;
            }

            consumer.accept(mPackageStateMutator);
            onChanged();
        }

        return PackageStateMutator.Result.SUCCESS;
    }

    /**
     * @see PackageManagerInternal#commitPackageStateMutation(PackageStateMutator.InitialState,
     * Consumer)
     */
    @NonNull
    public PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState initialState, @NonNull String packageName,
            @NonNull Consumer<PackageStateWrite> consumer) {
        synchronized (mPackageStateWriteLock) {
            final PackageStateMutator.Result result = mPackageStateMutator.generateResult(
                    initialState, mChangedPackagesTracker.getSequenceNumber());
            if (result != PackageStateMutator.Result.SUCCESS) {
                return result;
            }

            PackageStateWrite state = mPackageStateMutator.forPackage(packageName);
            if (state == null) {
                return PackageStateMutator.Result.SPECIFIC_PACKAGE_NULL;
            } else {
                consumer.accept(state);
            }

            state.onChanged();
        }

        return PackageStateMutator.Result.SUCCESS;
    }

    void notifyInstantAppPackageInstalled(String packageName, int[] newUsers) {
        executeWithConsistentComputer(computer ->
                mInstantAppRegistry.onPackageInstalled(computer, packageName, newUsers));
    }
}
