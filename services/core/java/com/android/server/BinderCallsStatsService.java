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
 * limitations under the License
 */

package com.android.server;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.os.AppIdToPackageMap;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.CachedDeviceState;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class BinderCallsStatsService extends Binder {

    private static final String TAG = "BinderCallsStatsService";
    private static final String SERVICE_NAME = "binder_calls_stats";

    private static final String PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING
            = "persist.sys.binder_calls_detailed_tracking";

    /** Resolves the work source of an incoming binder transaction. */
    static class AuthorizedWorkSourceProvider implements BinderInternal.WorkSourceProvider {
        private ArraySet<Integer> mAppIdTrustlist;

        AuthorizedWorkSourceProvider() {
            mAppIdTrustlist = new ArraySet<>();
        }

        public int resolveWorkSourceUid(int untrustedWorkSourceUid) {
            final int callingUid = getCallingUid();
            final int appId = UserHandle.getAppId(callingUid);
            if (mAppIdTrustlist.contains(appId)) {
                final int workSource = untrustedWorkSourceUid;
                final boolean isWorkSourceSet = workSource != Binder.UNSET_WORKSOURCE;
                return isWorkSourceSet ?  workSource : callingUid;
            }
            return callingUid;
        }

        public void systemReady(Context context) {
            mAppIdTrustlist = createAppidTrustlist(context);
        }

        public void dump(PrintWriter pw, AppIdToPackageMap packageMap) {
            pw.println("AppIds of apps that can set the work source:");
            final ArraySet<Integer> trustlist = mAppIdTrustlist;
            for (Integer appId : trustlist) {
                pw.println("\t- " + packageMap.mapAppId(appId));
            }
        }

        protected int getCallingUid() {
            return Binder.getCallingUid();
        }

        private ArraySet<Integer> createAppidTrustlist(Context context) {
            // Use a local copy instead of mAppIdTrustlist to prevent concurrent read access.
            final ArraySet<Integer> trustlist = new ArraySet<>();

            // We trust our own process.
            trustlist.add(UserHandle.getAppId(Process.myUid()));
            // We only need to initialize it once. UPDATE_DEVICE_STATS is a system permission.
            final PackageManager pm = context.getPackageManager();
            final String[] permissions = { android.Manifest.permission.UPDATE_DEVICE_STATS };
            final int queryFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
            final List<PackageInfo> packages =
                    pm.getPackagesHoldingPermissions(permissions, queryFlags);
            final int packagesSize = packages.size();
            for (int i = 0; i < packagesSize; i++) {
                final PackageInfo pkgInfo = packages.get(i);
                try {
                    final int uid = pm.getPackageUid(pkgInfo.packageName, queryFlags);
                    final int appId = UserHandle.getAppId(uid);
                    trustlist.add(appId);
                } catch (NameNotFoundException e) {
                    Slog.e(TAG, "Cannot find uid for package name " + pkgInfo.packageName, e);
                }
            }
            return trustlist;
        }
    }

    /** Listens for flag changes. */
    private static class SettingsObserver extends ContentObserver {
        private static final String SETTINGS_ENABLED_KEY = "enabled";
        private static final String SETTINGS_DETAILED_TRACKING_KEY = "detailed_tracking";
        private static final String SETTINGS_UPLOAD_DATA_KEY = "upload_data";
        private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
        private static final String SETTINGS_TRACK_SCREEN_INTERACTIVE_KEY = "track_screen_state";
        private static final String SETTINGS_TRACK_DIRECT_CALLING_UID_KEY = "track_calling_uid";
        private static final String SETTINGS_MAX_CALL_STATS_KEY = "max_call_stats_count";

        private boolean mEnabled;
        private final Uri mUri = Settings.Global.getUriFor(Settings.Global.BINDER_CALLS_STATS);
        private final Context mContext;
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private final BinderCallsStats mBinderCallsStats;
        private final AuthorizedWorkSourceProvider mWorkSourceProvider;

        SettingsObserver(Context context, BinderCallsStats binderCallsStats,
                AuthorizedWorkSourceProvider workSourceProvider) {
            super(BackgroundThread.getHandler());
            mContext = context;
            context.getContentResolver().registerContentObserver(mUri, false, this,
                    UserHandle.USER_SYSTEM);
            mBinderCallsStats = binderCallsStats;
            mWorkSourceProvider = workSourceProvider;
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        public void onChange() {
            // Do not overwrite the default set manually.
            if (!SystemProperties.get(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING).isEmpty()) {
              return;
            }

            try {
                mParser.setString(Settings.Global.getString(mContext.getContentResolver(),
                        Settings.Global.BINDER_CALLS_STATS));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad binder call stats settings", e);
            }
            mBinderCallsStats.setDetailedTracking(mParser.getBoolean(
                    SETTINGS_DETAILED_TRACKING_KEY, BinderCallsStats.DETAILED_TRACKING_DEFAULT));
            mBinderCallsStats.setSamplingInterval(mParser.getInt(
                    SETTINGS_SAMPLING_INTERVAL_KEY,
                    BinderCallsStats.PERIODIC_SAMPLING_INTERVAL_DEFAULT));
            mBinderCallsStats.setMaxBinderCallStats(mParser.getInt(
                    SETTINGS_MAX_CALL_STATS_KEY,
                    BinderCallsStats.MAX_BINDER_CALL_STATS_COUNT_DEFAULT));
            mBinderCallsStats.setTrackScreenInteractive(
                    mParser.getBoolean(SETTINGS_TRACK_SCREEN_INTERACTIVE_KEY,
                    BinderCallsStats.DEFAULT_TRACK_SCREEN_INTERACTIVE));
            mBinderCallsStats.setTrackDirectCallerUid(
                    mParser.getBoolean(SETTINGS_TRACK_DIRECT_CALLING_UID_KEY,
                    BinderCallsStats.DEFAULT_TRACK_DIRECT_CALLING_UID));


            final boolean enabled =
                    mParser.getBoolean(SETTINGS_ENABLED_KEY, BinderCallsStats.ENABLED_DEFAULT);
            if (mEnabled != enabled) {
                if (enabled) {
                    Binder.setObserver(mBinderCallsStats);
                    Binder.setProxyTransactListener(
                            new Binder.PropagateWorkSourceTransactListener());
                    Binder.setWorkSourceProvider(mWorkSourceProvider);
                } else {
                    Binder.setObserver(null);
                    Binder.setProxyTransactListener(null);
                    Binder.setWorkSourceProvider((x) -> Binder.getCallingUid());
                }
                mEnabled = enabled;
                mBinderCallsStats.reset();
                mBinderCallsStats.setAddDebugEntries(enabled);
            }
        }
    }

    /**
     * @hide Only for use within the system server.
     */
    public static class Internal {
        private final BinderCallsStats mBinderCallsStats;

        Internal(BinderCallsStats binderCallsStats) {
            this.mBinderCallsStats = binderCallsStats;
        }

        /** @see BinderCallsStats#reset */
        public void reset() {
            mBinderCallsStats.reset();
        }

        /**
         * @see BinderCallsStats#getExportedCallStats.
         *
         * Note that binder calls stats will be reset by statsd every time
         * the data is exported.
         */
        public ArrayList<BinderCallsStats.ExportedCallStat> getExportedCallStats() {
            return mBinderCallsStats.getExportedCallStats();
        }

        /** @see BinderCallsStats#getExportedExceptionStats */
        public ArrayMap<String, Integer> getExportedExceptionStats() {
            return mBinderCallsStats.getExportedExceptionStats();
        }
    }

    public static class LifeCycle extends SystemService {
        private BinderCallsStatsService mService;
        private BinderCallsStats mBinderCallsStats;
        private AuthorizedWorkSourceProvider mWorkSourceProvider;

        public LifeCycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mBinderCallsStats = new BinderCallsStats(new BinderCallsStats.Injector());
            mWorkSourceProvider = new AuthorizedWorkSourceProvider();
            mService = new BinderCallsStatsService(
                    mBinderCallsStats, mWorkSourceProvider);
            publishLocalService(Internal.class, new Internal(mBinderCallsStats));
            publishBinderService(SERVICE_NAME, mService);
            boolean detailedTrackingEnabled = SystemProperties.getBoolean(
                    PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, false);

            if (detailedTrackingEnabled) {
                Slog.i(TAG, "Enabled CPU usage tracking for binder calls. Controlled by "
                        + PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING
                        + " or via dumpsys binder_calls_stats --enable-detailed-tracking");
                mBinderCallsStats.setDetailedTracking(true);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            if (SystemService.PHASE_SYSTEM_SERVICES_READY == phase) {
                CachedDeviceState.Readonly deviceState = getLocalService(
                        CachedDeviceState.Readonly.class);
                mBinderCallsStats.setDeviceState(deviceState);

                BatteryStatsInternal batteryStatsInternal = getLocalService(
                        BatteryStatsInternal.class);
                mBinderCallsStats.setCallStatsObserver(batteryStatsInternal::noteBinderCallStats);

                // It needs to be called before mService.systemReady to make sure the observer is
                // initialized before installing it.
                mWorkSourceProvider.systemReady(getContext());
                mService.systemReady(getContext());
            }
        }
    }

    private SettingsObserver mSettingsObserver;
    private final BinderCallsStats mBinderCallsStats;
    private final AuthorizedWorkSourceProvider mWorkSourceProvider;

    BinderCallsStatsService(BinderCallsStats binderCallsStats,
            AuthorizedWorkSourceProvider workSourceProvider) {
        mBinderCallsStats = binderCallsStats;
        mWorkSourceProvider = workSourceProvider;
    }

    public void systemReady(Context context) {
        mSettingsObserver = new SettingsObserver(context, mBinderCallsStats, mWorkSourceProvider);
    }

    public void reset() {
        Slog.i(TAG, "Resetting stats");
        mBinderCallsStats.reset();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(ActivityThread.currentApplication(),
                SERVICE_NAME, pw)) {
            return;
        }

        boolean verbose = false;
        int worksourceUid = Process.INVALID_UID;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("-a".equals(arg)) {
                    verbose = true;
                } else if ("-h".equals(arg)) {
                    pw.println("dumpsys binder_calls_stats options:");
                    pw.println("  -a: Verbose");
                    pw.println("  --work-source-uid <UID>: Dump binder calls from the UID");
                    return;
                } else if ("--work-source-uid".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        throw new IllegalArgumentException(
                                "Argument expected after \"" + arg + "\"");
                    }
                    String uidArg = args[i];
                    try {
                        worksourceUid = Integer.parseInt(uidArg);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid UID: " + uidArg);
                        return;
                    }
                }
            }

            if (args.length > 0 && worksourceUid == Process.INVALID_UID) {
                // For compatibility, support "cmd"-style commands when passed to "dumpsys".
                BinderCallsStatsShellCommand command = new BinderCallsStatsShellCommand(pw);
                int status = command.exec(this, null, FileDescriptor.out, FileDescriptor.err, args);
                if (status == 0) {
                    return;
                }
            }
        }
        mBinderCallsStats.dump(pw, AppIdToPackageMap.getSnapshot(), worksourceUid, verbose);
    }

    @Override
    public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
            ParcelFileDescriptor err, String[] args) {
        ShellCommand command = new BinderCallsStatsShellCommand(null);
        int status = command.exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                err.getFileDescriptor(), args);
        if (status != 0) {
            command.onHelp();
        }
        return status;
    }

    private class BinderCallsStatsShellCommand extends ShellCommand {
        private final PrintWriter mPrintWriter;

        BinderCallsStatsShellCommand(PrintWriter printWriter) {
            mPrintWriter = printWriter;
        }

        @Override
        public PrintWriter getOutPrintWriter() {
            if (mPrintWriter != null) {
                return mPrintWriter;
            }
            return super.getOutPrintWriter();
        }

        @Override
        public int onCommand(String cmd) {
            PrintWriter pw = getOutPrintWriter();
            if (cmd == null) {
                return -1;
            }

            switch (cmd) {
                case "--reset":
                    reset();
                    pw.println("binder_calls_stats reset.");
                    break;
                case "--enable":
                    Binder.setObserver(mBinderCallsStats);
                    break;
                case "--disable":
                    Binder.setObserver(null);
                    break;
                case "--no-sampling":
                    mBinderCallsStats.setSamplingInterval(1);
                    break;
                case "--enable-detailed-tracking":
                    SystemProperties.set(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, "1");
                    mBinderCallsStats.setDetailedTracking(true);
                    pw.println("Detailed tracking enabled");
                    break;
                case "--disable-detailed-tracking":
                    SystemProperties.set(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, "");
                    mBinderCallsStats.setDetailedTracking(false);
                    pw.println("Detailed tracking disabled");
                    break;
                case "--dump-worksource-provider":
                    mBinderCallsStats.setDetailedTracking(true);
                    mWorkSourceProvider.dump(pw, AppIdToPackageMap.getSnapshot());
                    break;
                case "--work-source-uid":
                    String uidArg = getNextArgRequired();
                    try {
                        int uid = Integer.parseInt(uidArg);
                        mBinderCallsStats.recordAllCallsForWorkSourceUid(uid);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid UID: " + uidArg);
                        return -1;
                    }
                    break;
                default:
                    return handleDefaultCommands(cmd);
            }
            return 0;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("binder_calls_stats commands:");
            pw.println("  --reset: Reset stats");
            pw.println("  --enable: Enable tracking binder calls");
            pw.println("  --disable: Disables tracking binder calls");
            pw.println("  --no-sampling: Tracks all calls");
            pw.println("  --enable-detailed-tracking: Enables detailed tracking");
            pw.println("  --disable-detailed-tracking: Disables detailed tracking");
            pw.println("  --work-source-uid <UID>: Track all binder calls from the UID");
        }
    }
}
