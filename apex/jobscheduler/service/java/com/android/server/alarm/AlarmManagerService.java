/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.alarm;

import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.os.UserHandle.USER_SYSTEM;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IAlarmCompleteListener;
import android.app.IAlarmListener;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.ThreadLocalWorkSource;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongArrayQueue;
import android.util.MutableBoolean;
import android.util.NtpTrustedTime;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.LocalLog;
import com.android.internal.util.StatLogger;
import com.android.server.AlarmManagerInternal;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.AppStateTrackerImpl.Listener;
import com.android.server.DeviceIdleInternal;
import com.android.server.EventLogTags;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Alarm manager implementation.
 *
 * Unit test:
 * atest FrameworksMockingServicesTests:com.android.server.alarm.AlarmManagerServiceTest
 */
public class AlarmManagerService extends SystemService {
    private static final int RTC_WAKEUP_MASK = 1 << RTC_WAKEUP;
    private static final int ELAPSED_REALTIME_WAKEUP_MASK = 1 << ELAPSED_REALTIME_WAKEUP;
    static final int TIME_CHANGED_MASK = 1 << 16;
    static final int IS_WAKEUP_MASK = RTC_WAKEUP_MASK | ELAPSED_REALTIME_WAKEUP_MASK;

    static final String TAG = "AlarmManager";
    static final String TIME_TICK_TAG = "TIME_TICK";
    static final boolean localLOGV = false;
    static final boolean DEBUG_BATCH = localLOGV || false;
    static final boolean DEBUG_ALARM_CLOCK = localLOGV || false;
    static final boolean DEBUG_LISTENER_CALLBACK = localLOGV || false;
    static final boolean DEBUG_WAKELOCK = localLOGV || false;
    static final boolean DEBUG_BG_LIMIT = localLOGV || false;
    static final boolean DEBUG_STANDBY = localLOGV || false;
    static final boolean RECORD_ALARMS_IN_HISTORY = true;
    static final boolean RECORD_DEVICE_IDLE_ALARMS = false;
    static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    static final int TICK_HISTORY_DEPTH = 10;
    static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

    // Indices into the KEYS_APP_STANDBY_QUOTAS array.
    static final int ACTIVE_INDEX = 0;
    static final int WORKING_INDEX = 1;
    static final int FREQUENT_INDEX = 2;
    static final int RARE_INDEX = 3;
    static final int NEVER_INDEX = 4;

    private final Intent mBackgroundIntent
            = new Intent().addFlags(Intent.FLAG_FROM_BACKGROUND);

    private static final Intent NEXT_ALARM_CLOCK_CHANGED_INTENT =
            new Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                    .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

    final LocalLog mLog = new LocalLog(TAG);

    AppOpsManager mAppOps;
    DeviceIdleInternal mLocalDeviceIdleController;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;

    final Object mLock = new Object();

    // List of alarms per uid deferred due to user applied background restrictions on the source app
    SparseArray<ArrayList<Alarm>> mPendingBackgroundAlarms = new SparseArray<>();
    private long mNextWakeup;
    private long mNextNonWakeup;
    private long mNextWakeUpSetAt;
    private long mNextNonWakeUpSetAt;
    private long mLastWakeup;
    private long mLastTrigger;

    private long mLastTickSet;
    private long mLastTickReceived;
    // ring buffer of recent TIME_TICK issuance, in the elapsed timebase
    private final long[] mTickHistory = new long[TICK_HISTORY_DEPTH];
    private int mNextTickHistory;

    private final Injector mInjector;
    int mBroadcastRefCount = 0;
    PowerManager.WakeLock mWakeLock;
    SparseIntArray mAlarmsPerUid = new SparseIntArray();
    ArrayList<Alarm> mPendingNonWakeupAlarms = new ArrayList<>();
    ArrayList<InFlight> mInFlight = new ArrayList<>();
    private final ArrayList<AlarmManagerInternal.InFlightListener> mInFlightListeners =
            new ArrayList<>();
    AlarmHandler mHandler;
    AppWakeupHistory mAppWakeupHistory;
    ClockReceiver mClockReceiver;
    final DeliveryTracker mDeliveryTracker = new DeliveryTracker();
    IBinder.DeathRecipient mListenerDeathRecipient;
    Intent mTimeTickIntent;
    IAlarmListener mTimeTickTrigger;
    PendingIntent mDateChangeSender;
    Random mRandom;
    boolean mInteractive = true;
    long mNonInteractiveStartTime;
    long mNonInteractiveTime;
    long mLastAlarmDeliveryTime;
    long mStartCurrentDelayTime;
    long mNextNonWakeupDeliveryTime;
    long mLastTimeChangeClockTime;
    long mLastTimeChangeRealtime;
    int mNumTimeChanged;

    /**
     * At boot we use SYSTEM_UI_SELF_PERMISSION to look up the definer's uid.
     */
    int mSystemUiUid;

    /**
     * For each uid, this is the last time we dispatched an "allow while idle" alarm,
     * used to determine the earliest we can dispatch the next such alarm. Times are in the
     * 'elapsed' timebase.
     */
    final SparseLongArray mLastAllowWhileIdleDispatch = new SparseLongArray();

    /**
     * For each uid, we store whether the last allow-while-idle alarm was dispatched while
     * the uid was in foreground or not. We will use the allow_while_idle_short_time in such cases.
     */
    final SparseBooleanArray mUseAllowWhileIdleShortTime = new SparseBooleanArray();

    static boolean isTimeTickAlarm(Alarm a) {
        return a.uid == Process.SYSTEM_UID && TIME_TICK_TAG.equals(a.listenerTag);
    }

    final static class IdleDispatchEntry {
        int uid;
        String pkg;
        String tag;
        String op;
        long elapsedRealtime;
        long argRealtime;
    }
    final ArrayList<IdleDispatchEntry> mAllowWhileIdleDispatches = new ArrayList();

    interface Stats {
        int REORDER_ALARMS_FOR_STANDBY = 0;
    }

    private final StatLogger mStatLogger = new StatLogger("Alarm manager stats", new String[]{
            "REORDER_ALARMS_FOR_STANDBY",
    });

    /**
     * Broadcast options to use for FLAG_ALLOW_WHILE_IDLE.
     */
    Bundle mIdleOptions;

    private final SparseArray<AlarmManager.AlarmClockInfo> mNextAlarmClockForUser =
            new SparseArray<>();
    private final SparseArray<AlarmManager.AlarmClockInfo> mTmpSparseAlarmClockArray =
            new SparseArray<>();
    private final SparseBooleanArray mPendingSendNextAlarmClockChangedForUser =
            new SparseBooleanArray();
    private boolean mNextAlarmClockMayChange;

    // May only use on mHandler's thread, locking not required.
    private final SparseArray<AlarmManager.AlarmClockInfo> mHandlerSparseAlarmClockArray =
            new SparseArray<>();

    private AppStateTrackerImpl mAppStateTracker;
    private boolean mAppStandbyParole;

    /**
     * A rolling window history of previous times when an alarm was sent to a package.
     */
    private static class AppWakeupHistory {
        private ArrayMap<Pair<String, Integer>, LongArrayQueue> mPackageHistory =
                new ArrayMap<>();
        private long mWindowSize;

        AppWakeupHistory(long windowSize) {
            mWindowSize = windowSize;
        }

        void recordAlarmForPackage(String packageName, int userId, long nowElapsed) {
            final Pair<String, Integer> packageUser = Pair.create(packageName, userId);
            LongArrayQueue history = mPackageHistory.get(packageUser);
            if (history == null) {
                history = new LongArrayQueue();
                mPackageHistory.put(packageUser, history);
            }
            if (history.size() == 0 || history.peekLast() < nowElapsed) {
                history.addLast(nowElapsed);
            }
            snapToWindow(history);
        }

        void removeForUser(int userId) {
            for (int i = mPackageHistory.size() - 1; i >= 0; i--) {
                final Pair<String, Integer> packageUserKey = mPackageHistory.keyAt(i);
                if (packageUserKey.second == userId) {
                    mPackageHistory.removeAt(i);
                }
            }
        }

        void removeForPackage(String packageName, int userId) {
            final Pair<String, Integer> packageUser = Pair.create(packageName, userId);
            mPackageHistory.remove(packageUser);
        }

        private void snapToWindow(LongArrayQueue history) {
            while (history.peekFirst() + mWindowSize < history.peekLast()) {
                history.removeFirst();
            }
        }

        int getTotalWakeupsInWindow(String packageName, int userId) {
            final LongArrayQueue history = mPackageHistory.get(Pair.create(packageName, userId));
            return (history == null) ? 0 : history.size();
        }

        /**
         * @param n The desired nth-last wakeup
         *          (1=1st-last=the ultimate wakeup and 2=2nd-last=the penultimate wakeup)
         */
        long getNthLastWakeupForPackage(String packageName, int userId, int n) {
            final LongArrayQueue history = mPackageHistory.get(Pair.create(packageName, userId));
            if (history == null) {
                return 0;
            }
            final int i = history.size() - n;
            return (i < 0) ? 0 : history.get(i);
        }

        void dump(PrintWriter pw, String prefix, long nowElapsed) {
            dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix), nowElapsed);
        }

        void dump(IndentingPrintWriter pw, long nowElapsed) {
            pw.println("App Alarm history:");
            pw.increaseIndent();
            for (int i = 0; i < mPackageHistory.size(); i++) {
                final Pair<String, Integer> packageUser = mPackageHistory.keyAt(i);
                final LongArrayQueue timestamps = mPackageHistory.valueAt(i);
                pw.print(packageUser.first);
                pw.print(", u");
                pw.print(packageUser.second);
                pw.print(": ");
                // limit dumping to a max of 100 values
                final int lastIdx = Math.max(0, timestamps.size() - 100);
                for (int j = timestamps.size() - 1; j >= lastIdx; j--) {
                    TimeUtils.formatDuration(timestamps.get(j), nowElapsed, pw);
                    pw.print(", ");
                }
                pw.println();
            }
            pw.decreaseIndent();
        }
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AlarmManagerService.mLock lock.
     */
    @VisibleForTesting
    final class Constants implements DeviceConfig.OnPropertiesChangedListener {
        // Key names stored in the settings value.
        @VisibleForTesting
        static final String KEY_MIN_FUTURITY = "min_futurity";
        @VisibleForTesting
        static final String KEY_MIN_INTERVAL = "min_interval";
        @VisibleForTesting
        static final String KEY_MAX_INTERVAL = "max_interval";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_SHORT_TIME = "allow_while_idle_short_time";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_LONG_TIME = "allow_while_idle_long_time";
        @VisibleForTesting
        static final String KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = "allow_while_idle_whitelist_duration";
        @VisibleForTesting
        static final String KEY_LISTENER_TIMEOUT = "listener_timeout";
        @VisibleForTesting
        static final String KEY_MAX_ALARMS_PER_UID = "max_alarms_per_uid";
        private static final String KEY_APP_STANDBY_WINDOW = "app_standby_window";
        private static final String KEY_PREFIX_STANDBY_QUOTA = "standby_quota_";
        @VisibleForTesting
        final String[] KEYS_APP_STANDBY_QUOTAS = {
                KEY_PREFIX_STANDBY_QUOTA + "active",
                KEY_PREFIX_STANDBY_QUOTA + "working",
                KEY_PREFIX_STANDBY_QUOTA + "frequent",
                KEY_PREFIX_STANDBY_QUOTA + "rare",
                KEY_PREFIX_STANDBY_QUOTA + "never",
        };
        // Not putting this in the KEYS_APP_STANDBY_QUOTAS array because this uses a different
        // window size.
        private static final String KEY_APP_STANDBY_RESTRICTED_QUOTA =
                KEY_PREFIX_STANDBY_QUOTA + "restricted";
        private static final String KEY_APP_STANDBY_RESTRICTED_WINDOW =
                "app_standby_restricted_window";

        private static final long DEFAULT_MIN_FUTURITY = 5 * 1000;
        private static final long DEFAULT_MIN_INTERVAL = 60 * 1000;
        private static final long DEFAULT_MAX_INTERVAL = 365 * DateUtils.DAY_IN_MILLIS;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_MIN_FUTURITY;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME = 9 * 60 * 1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10 * 1000;
        private static final long DEFAULT_LISTENER_TIMEOUT = 5 * 1000;
        private static final int DEFAULT_MAX_ALARMS_PER_UID = 500;
        private static final long DEFAULT_APP_STANDBY_WINDOW = 60 * 60 * 1000;  // 1 hr
        /**
         * Max number of times an app can receive alarms in {@link #APP_STANDBY_WINDOW}
         */
        private final int[] DEFAULT_APP_STANDBY_QUOTAS = {
                720,    // Active
                10,     // Working
                2,      // Frequent
                1,      // Rare
                0       // Never
        };
        private static final int DEFAULT_APP_STANDBY_RESTRICTED_QUOTA = 1;
        private static final long DEFAULT_APP_STANDBY_RESTRICTED_WINDOW = MILLIS_IN_DAY;

        // Minimum futurity of a new alarm
        public long MIN_FUTURITY = DEFAULT_MIN_FUTURITY;

        // Minimum alarm recurrence interval
        public long MIN_INTERVAL = DEFAULT_MIN_INTERVAL;

        // Maximum alarm recurrence interval
        public long MAX_INTERVAL = DEFAULT_MAX_INTERVAL;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is not idle.
        public long ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME;

        // Minimum time between ALLOW_WHILE_IDLE alarms when system is idling.
        public long ALLOW_WHILE_IDLE_LONG_TIME = DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME;

        // BroadcastOptions.setTemporaryAppWhitelistDuration() to use for FLAG_ALLOW_WHILE_IDLE.
        public long ALLOW_WHILE_IDLE_WHITELIST_DURATION
                = DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION;

        // Direct alarm listener callback timeout
        public long LISTENER_TIMEOUT = DEFAULT_LISTENER_TIMEOUT;
        public int MAX_ALARMS_PER_UID = DEFAULT_MAX_ALARMS_PER_UID;

        public long APP_STANDBY_WINDOW = DEFAULT_APP_STANDBY_WINDOW;
        public int[] APP_STANDBY_QUOTAS = new int[DEFAULT_APP_STANDBY_QUOTAS.length];
        public int APP_STANDBY_RESTRICTED_QUOTA = DEFAULT_APP_STANDBY_RESTRICTED_QUOTA;
        public long APP_STANDBY_RESTRICTED_WINDOW = DEFAULT_APP_STANDBY_RESTRICTED_WINDOW;

        private long mLastAllowWhileIdleWhitelistDuration = -1;

        Constants() {
            updateAllowWhileIdleWhitelistDurationLocked();
            for (int i = 0; i < APP_STANDBY_QUOTAS.length; i++) {
                APP_STANDBY_QUOTAS[i] = DEFAULT_APP_STANDBY_QUOTAS[i];
            }
        }

        public void start() {
            mInjector.registerDeviceConfigListener(this);
            onPropertiesChanged(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_ALARM_MANAGER));
        }

        public void updateAllowWhileIdleWhitelistDurationLocked() {
            if (mLastAllowWhileIdleWhitelistDuration != ALLOW_WHILE_IDLE_WHITELIST_DURATION) {
                mLastAllowWhileIdleWhitelistDuration = ALLOW_WHILE_IDLE_WHITELIST_DURATION;
                BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setTemporaryAppWhitelistDuration(ALLOW_WHILE_IDLE_WHITELIST_DURATION);
                mIdleOptions = opts.toBundle();
            }
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            boolean standbyQuotaUpdated = false;
            synchronized (mLock) {
                for (String name : properties.getKeyset()) {
                    if (name == null) {
                        continue;
                    }

                    switch (name) {
                        case KEY_MIN_FUTURITY:
                            MIN_FUTURITY = properties.getLong(
                                    KEY_MIN_FUTURITY, DEFAULT_MIN_FUTURITY);
                            break;
                        case KEY_MIN_INTERVAL:
                            MIN_INTERVAL = properties.getLong(
                                    KEY_MIN_INTERVAL, DEFAULT_MIN_INTERVAL);
                            break;
                        case KEY_MAX_INTERVAL:
                            MAX_INTERVAL = properties.getLong(
                                    KEY_MAX_INTERVAL, DEFAULT_MAX_INTERVAL);
                            break;
                        case KEY_ALLOW_WHILE_IDLE_SHORT_TIME:
                            ALLOW_WHILE_IDLE_SHORT_TIME = properties.getLong(
                                    KEY_ALLOW_WHILE_IDLE_SHORT_TIME,
                                    DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME);
                            break;
                        case KEY_ALLOW_WHILE_IDLE_LONG_TIME:
                            ALLOW_WHILE_IDLE_LONG_TIME = properties.getLong(
                                    KEY_ALLOW_WHILE_IDLE_LONG_TIME,
                                    DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME);
                            break;
                        case KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION:
                            ALLOW_WHILE_IDLE_WHITELIST_DURATION = properties.getLong(
                                    KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION,
                                    DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION);
                            updateAllowWhileIdleWhitelistDurationLocked();
                            break;
                        case KEY_LISTENER_TIMEOUT:
                            LISTENER_TIMEOUT = properties.getLong(
                                    KEY_LISTENER_TIMEOUT, DEFAULT_LISTENER_TIMEOUT);
                            break;
                        case KEY_MAX_ALARMS_PER_UID:
                            MAX_ALARMS_PER_UID = properties.getInt(
                                    KEY_MAX_ALARMS_PER_UID, DEFAULT_MAX_ALARMS_PER_UID);
                            if (MAX_ALARMS_PER_UID < DEFAULT_MAX_ALARMS_PER_UID) {
                                Slog.w(TAG, "Cannot set " + KEY_MAX_ALARMS_PER_UID + " lower than "
                                        + DEFAULT_MAX_ALARMS_PER_UID);
                                MAX_ALARMS_PER_UID = DEFAULT_MAX_ALARMS_PER_UID;
                            }
                            break;
                        case KEY_APP_STANDBY_WINDOW:
                        case KEY_APP_STANDBY_RESTRICTED_WINDOW:
                            updateStandbyWindowsLocked();
                            break;
                        default:
                            if (name.startsWith(KEY_PREFIX_STANDBY_QUOTA) && !standbyQuotaUpdated) {
                                // The quotas need to be updated in order, so we can't just rely
                                // on the property iteration order.
                                updateStandbyQuotasLocked();
                                standbyQuotaUpdated = true;
                            }
                            break;
                    }
                }
            }
        }

        private void updateStandbyQuotasLocked() {
            // The bucket quotas need to be read as an atomic unit but the properties passed to
            // onPropertiesChanged may only have one key populated at a time.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_ALARM_MANAGER, KEYS_APP_STANDBY_QUOTAS);

            APP_STANDBY_QUOTAS[ACTIVE_INDEX] = properties.getInt(
                    KEYS_APP_STANDBY_QUOTAS[ACTIVE_INDEX],
                    DEFAULT_APP_STANDBY_QUOTAS[ACTIVE_INDEX]);
            for (int i = WORKING_INDEX; i < KEYS_APP_STANDBY_QUOTAS.length; i++) {
                APP_STANDBY_QUOTAS[i] = properties.getInt(
                        KEYS_APP_STANDBY_QUOTAS[i],
                        Math.min(APP_STANDBY_QUOTAS[i - 1], DEFAULT_APP_STANDBY_QUOTAS[i]));
            }

            APP_STANDBY_RESTRICTED_QUOTA = Math.max(1,
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_ALARM_MANAGER,
                            KEY_APP_STANDBY_RESTRICTED_QUOTA,
                            DEFAULT_APP_STANDBY_RESTRICTED_QUOTA));
        }

        private void updateStandbyWindowsLocked() {
            // The bucket windows need to be read as an atomic unit but the properties passed to
            // onPropertiesChanged may only have one key populated at a time.
            final DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_ALARM_MANAGER,
                    KEY_APP_STANDBY_WINDOW, KEY_APP_STANDBY_RESTRICTED_WINDOW);
            APP_STANDBY_WINDOW = properties.getLong(
                    KEY_APP_STANDBY_WINDOW, DEFAULT_APP_STANDBY_WINDOW);
            if (APP_STANDBY_WINDOW > DEFAULT_APP_STANDBY_WINDOW) {
                Slog.w(TAG, "Cannot exceed the app_standby_window size of "
                        + DEFAULT_APP_STANDBY_WINDOW);
                APP_STANDBY_WINDOW = DEFAULT_APP_STANDBY_WINDOW;
            } else if (APP_STANDBY_WINDOW < DEFAULT_APP_STANDBY_WINDOW) {
                // Not recommended outside of testing.
                Slog.w(TAG, "Using a non-default app_standby_window of " + APP_STANDBY_WINDOW);
            }

            APP_STANDBY_RESTRICTED_WINDOW = Math.max(APP_STANDBY_WINDOW,
                    properties.getLong(
                            KEY_APP_STANDBY_RESTRICTED_WINDOW,
                            DEFAULT_APP_STANDBY_RESTRICTED_WINDOW));
        }

        void dump(PrintWriter pw, String prefix) {
            dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix));
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Settings:");

            pw.increaseIndent();

            pw.print(KEY_MIN_FUTURITY);
            pw.print("=");
            TimeUtils.formatDuration(MIN_FUTURITY, pw);
            pw.println();

            pw.print(KEY_MIN_INTERVAL);
            pw.print("=");
            TimeUtils.formatDuration(MIN_INTERVAL, pw);
            pw.println();

            pw.print(KEY_MAX_INTERVAL);
            pw.print("=");
            TimeUtils.formatDuration(MAX_INTERVAL, pw);
            pw.println();

            pw.print(KEY_LISTENER_TIMEOUT);
            pw.print("=");
            TimeUtils.formatDuration(LISTENER_TIMEOUT, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_SHORT_TIME);
            pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_SHORT_TIME, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_LONG_TIME);
            pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_LONG_TIME, pw);
            pw.println();

            pw.print(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION);
            pw.print("=");
            TimeUtils.formatDuration(ALLOW_WHILE_IDLE_WHITELIST_DURATION, pw);
            pw.println();

            pw.print(KEY_MAX_ALARMS_PER_UID);
            pw.print("=");
            pw.println(MAX_ALARMS_PER_UID);

            pw.print(KEY_APP_STANDBY_WINDOW);
            pw.print("=");
            TimeUtils.formatDuration(APP_STANDBY_WINDOW, pw);
            pw.println();

            for (int i = 0; i < KEYS_APP_STANDBY_QUOTAS.length; i++) {
                pw.print(KEYS_APP_STANDBY_QUOTAS[i]);
                pw.print("=");
                pw.println(APP_STANDBY_QUOTAS[i]);
            }

            pw.print(KEY_APP_STANDBY_RESTRICTED_QUOTA);
            pw.print("=");
            pw.println(APP_STANDBY_RESTRICTED_QUOTA);

            pw.print(KEY_APP_STANDBY_RESTRICTED_WINDOW);
            pw.print("=");
            TimeUtils.formatDuration(APP_STANDBY_RESTRICTED_WINDOW, pw);
            pw.println();

            pw.decreaseIndent();
        }

        void dumpProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(ConstantsProto.MIN_FUTURITY_DURATION_MS, MIN_FUTURITY);
            proto.write(ConstantsProto.MIN_INTERVAL_DURATION_MS, MIN_INTERVAL);
            proto.write(ConstantsProto.MAX_INTERVAL_DURATION_MS, MAX_INTERVAL);
            proto.write(ConstantsProto.LISTENER_TIMEOUT_DURATION_MS, LISTENER_TIMEOUT);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_SHORT_DURATION_MS,
                    ALLOW_WHILE_IDLE_SHORT_TIME);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_LONG_DURATION_MS,
                    ALLOW_WHILE_IDLE_LONG_TIME);
            proto.write(ConstantsProto.ALLOW_WHILE_IDLE_WHITELIST_DURATION_MS,
                    ALLOW_WHILE_IDLE_WHITELIST_DURATION);

            proto.end(token);
        }
    }

    Constants mConstants;

    // Alarm delivery ordering bookkeeping
    static final int PRIO_TICK = 0;
    static final int PRIO_WAKEUP = 1;
    static final int PRIO_NORMAL = 2;

    final class PriorityClass {
        int seq;
        int priority;

        PriorityClass() {
            seq = mCurrentSeq - 1;
            priority = PRIO_NORMAL;
        }
    }

    final HashMap<String, PriorityClass> mPriorities = new HashMap<>();
    int mCurrentSeq = 0;

    static final class WakeupEvent {
        public long when;
        public int uid;
        public String action;

        public WakeupEvent(long theTime, int theUid, String theAction) {
            when = theTime;
            uid = theUid;
            action = theAction;
        }
    }

    final Comparator<Alarm> mAlarmDispatchComparator = new Comparator<Alarm>() {
        @Override
        public int compare(Alarm lhs, Alarm rhs) {
            // priority class trumps everything.  TICK < WAKEUP < NORMAL
            if (lhs.priorityClass.priority < rhs.priorityClass.priority) {
                return -1;
            } else if (lhs.priorityClass.priority > rhs.priorityClass.priority) {
                return 1;
            }

            // within each class, sort by nominal delivery time
            if (lhs.whenElapsed < rhs.whenElapsed) {
                return -1;
            } else if (lhs.whenElapsed > rhs.whenElapsed) {
                return 1;
            }

            // same priority class + same target delivery time
            return 0;
        }
    };

    void calculateDeliveryPriorities(ArrayList<Alarm> alarms) {
        final int N = alarms.size();
        for (int i = 0; i < N; i++) {
            Alarm a = alarms.get(i);

            final int alarmPrio;
            if (a.listener == mTimeTickTrigger) {
                alarmPrio = PRIO_TICK;
            } else if (a.wakeup) {
                alarmPrio = PRIO_WAKEUP;
            } else {
                alarmPrio = PRIO_NORMAL;
            }

            PriorityClass packagePrio = a.priorityClass;
            String alarmPackage = a.sourcePackage;
            if (packagePrio == null) packagePrio = mPriorities.get(alarmPackage);
            if (packagePrio == null) {
                packagePrio = a.priorityClass = new PriorityClass(); // lowest prio & stale sequence
                mPriorities.put(alarmPackage, packagePrio);
            }
            a.priorityClass = packagePrio;

            if (packagePrio.seq != mCurrentSeq) {
                // first alarm we've seen in the current delivery generation from this package
                packagePrio.priority = alarmPrio;
                packagePrio.seq = mCurrentSeq;
            } else {
                // Multiple alarms from this package being delivered in this generation;
                // bump the package's delivery class if it's warranted.
                // TICK < WAKEUP < NORMAL
                if (alarmPrio < packagePrio.priority) {
                    packagePrio.priority = alarmPrio;
                }
            }
        }
    }

    // minimum recurrence period or alarm futurity for us to be able to fuzz it
    static final long MIN_FUZZABLE_INTERVAL = 10000;
    @GuardedBy("mLock")
    final AlarmStore mAlarmStore;

    // set to non-null if in idle mode; while in this mode, any alarms we don't want
    // to run during this time are placed in mPendingWhileIdleAlarms
    Alarm mPendingIdleUntil = null;
    Alarm mNextWakeFromIdle = null;
    ArrayList<Alarm> mPendingWhileIdleAlarms = new ArrayList<>();

    @VisibleForTesting
    AlarmManagerService(Context context, Injector injector) {
        super(context);
        mInjector = injector;
        mAlarmStore = new BatchingAlarmStore(() -> mNextAlarmClockMayChange = true);
    }

    public AlarmManagerService(Context context) {
        this(context, new Injector(context));
    }

    private long convertToElapsed(long when, int type) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        if (isRtc) {
            when -= mInjector.getCurrentTimeMillis() - mInjector.getElapsedRealtime();
        }
        return when;
    }

    // Apply a heuristic to { recurrence interval, futurity of the trigger time } to
    // calculate the end of our nominal delivery window for the alarm.
    static long maxTriggerTime(long now, long triggerAtTime, long interval) {
        // Current heuristic: batchable window is 75% of either the recurrence interval
        // [for a periodic alarm] or of the time from now to the desired delivery time,
        // with a minimum delay/interval of 10 seconds, under which we will simply not
        // defer the alarm.
        long futurity = (interval == 0)
                ? (triggerAtTime - now)
                : interval;
        if (futurity < MIN_FUZZABLE_INTERVAL) {
            futurity = 0;
        }
        return clampPositive(triggerAtTime + (long) (.75 * futurity));
    }

    // The RTC clock has moved arbitrarily, so we need to recalculate all the RTC alarm deliveries.
    void reevaluateRtcAlarms(final long nowElapsed) {
        synchronized (mLock) {
            final ArrayList<Alarm> rtcAlarms = mAlarmStore.remove(a -> (a.type == RTC
                    || a.type == RTC_WAKEUP));
            for (final Alarm a : rtcAlarms) {
                restoreAlarmLocked(a, nowElapsed);
                setImplLocked(a);
            }
        }
    }

    /**
     * Recalculates alarm send times based on the current app-standby buckets
     *
     * @param targetPackages [Package, User] pairs for which alarms need to be re-evaluated,
     *                       null indicates all
     * @return True if there was any reordering done to the current list.
     */
    boolean reorderAlarmsBasedOnStandbyBuckets(ArraySet<Pair<String, Integer>> targetPackages) {
        final long start = mStatLogger.getTime();

        final boolean changed = mAlarmStore.recalculateAlarmDeliveries(a -> {
            final Pair<String, Integer> packageUser =
                    Pair.create(a.sourcePackage, UserHandle.getUserId(a.creatorUid));
            if (targetPackages != null && !targetPackages.contains(packageUser)) {
                return false;
            }
            return adjustDeliveryTimeBasedOnBucketLocked(a);
        });

        mStatLogger.logDurationStat(Stats.REORDER_ALARMS_FOR_STANDBY, start);
        return changed;
    }

    private void restoreAlarmLocked(Alarm a, long nowElapsed) {
        a.when = a.origWhen;
        long whenElapsed = convertToElapsed(a.when, a.type);
        final long maxElapsed;
        if (a.windowLength == AlarmManager.WINDOW_EXACT) {
            // Exact
            maxElapsed = whenElapsed;
        } else {
            // Not exact.  Preserve any explicit window, otherwise recalculate
            // the window based on the alarm's new futurity.  Note that this
            // reflects a policy of preferring timely to deferred delivery.
            maxElapsed = (a.windowLength > 0)
                    ? clampPositive(whenElapsed + a.windowLength)
                    : maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
        }
        a.expectedWhenElapsed = a.whenElapsed = whenElapsed;
        a.expectedMaxWhenElapsed = a.maxWhenElapsed = maxElapsed;
    }

    static long clampPositive(long val) {
        return (val >= 0) ? val : Long.MAX_VALUE;
    }

    /**
     * Sends alarms that were blocked due to user applied background restrictions - either because
     * the user lifted those or the uid came to foreground.
     *
     * @param uid         uid to filter on
     * @param packageName package to filter on, or null for all packages in uid
     */
    void sendPendingBackgroundAlarmsLocked(int uid, String packageName) {
        final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.get(uid);
        if (alarmsForUid == null || alarmsForUid.size() == 0) {
            return;
        }
        final ArrayList<Alarm> alarmsToDeliver;
        if (packageName != null) {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Sending blocked alarms for uid " + uid + ", package " + packageName);
            }
            alarmsToDeliver = new ArrayList<>();
            for (int i = alarmsForUid.size() - 1; i >= 0; i--) {
                final Alarm a = alarmsForUid.get(i);
                if (a.matches(packageName)) {
                    alarmsToDeliver.add(alarmsForUid.remove(i));
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.remove(uid);
            }
        } else {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Sending blocked alarms for uid " + uid);
            }
            alarmsToDeliver = alarmsForUid;
            mPendingBackgroundAlarms.remove(uid);
        }
        deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, mInjector.getElapsedRealtime());
    }

    /**
     * Check all alarms in {@link #mPendingBackgroundAlarms} and send the ones that are not
     * restricted.
     *
     * This is only called when the global "force all apps-standby" flag changes or when the
     * power save whitelist changes, so it's okay to be slow.
     */
    void sendAllUnrestrictedPendingBackgroundAlarmsLocked() {
        final ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();

        findAllUnrestrictedPendingBackgroundAlarmsLockedInner(
                mPendingBackgroundAlarms, alarmsToDeliver, this::isBackgroundRestricted);

        if (alarmsToDeliver.size() > 0) {
            deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, mInjector.getElapsedRealtime());
        }
    }

    @VisibleForTesting
    static void findAllUnrestrictedPendingBackgroundAlarmsLockedInner(
            SparseArray<ArrayList<Alarm>> pendingAlarms, ArrayList<Alarm> unrestrictedAlarms,
            Predicate<Alarm> isBackgroundRestricted) {

        for (int uidIndex = pendingAlarms.size() - 1; uidIndex >= 0; uidIndex--) {
            final int uid = pendingAlarms.keyAt(uidIndex);
            final ArrayList<Alarm> alarmsForUid = pendingAlarms.valueAt(uidIndex);

            for (int alarmIndex = alarmsForUid.size() - 1; alarmIndex >= 0; alarmIndex--) {
                final Alarm alarm = alarmsForUid.get(alarmIndex);

                if (isBackgroundRestricted.test(alarm)) {
                    continue;
                }

                unrestrictedAlarms.add(alarm);
                alarmsForUid.remove(alarmIndex);
            }

            if (alarmsForUid.size() == 0) {
                pendingAlarms.removeAt(uidIndex);
            }
        }
    }

    private void deliverPendingBackgroundAlarmsLocked(ArrayList<Alarm> alarms, long nowELAPSED) {
        final int N = alarms.size();
        boolean hasWakeup = false;
        for (int i = 0; i < N; i++) {
            final Alarm alarm = alarms.get(i);
            if (alarm.wakeup) {
                hasWakeup = true;
            }
            alarm.count = 1;
            // Recurring alarms may have passed several alarm intervals while the
            // alarm was kept pending. Send the appropriate trigger count.
            if (alarm.repeatInterval > 0) {
                alarm.count += (nowELAPSED - alarm.expectedWhenElapsed) / alarm.repeatInterval;
                // Also schedule its next recurrence
                final long delta = alarm.count * alarm.repeatInterval;
                final long nextElapsed = alarm.expectedWhenElapsed + delta;
                setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                        maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
                        alarm.repeatInterval, alarm.operation, null, null, alarm.flags,
                        alarm.workSource, alarm.alarmClock, alarm.uid, alarm.packageName);
                // Kernel alarms will be rescheduled as needed in setImplLocked
            }
        }
        if (!hasWakeup && checkAllowNonWakeupDelayLocked(nowELAPSED)) {
            // No need to wakeup for non wakeup alarms
            if (mPendingNonWakeupAlarms.size() == 0) {
                mStartCurrentDelayTime = nowELAPSED;
                mNextNonWakeupDeliveryTime = nowELAPSED
                        + ((currentNonWakeupFuzzLocked(nowELAPSED) * 3) / 2);
            }
            mPendingNonWakeupAlarms.addAll(alarms);
            mNumDelayedAlarms += alarms.size();
        } else {
            if (DEBUG_BG_LIMIT) {
                Slog.d(TAG, "Waking up to deliver pending blocked alarms");
            }
            // Since we are waking up, also deliver any pending non wakeup alarms we have.
            if (mPendingNonWakeupAlarms.size() > 0) {
                alarms.addAll(mPendingNonWakeupAlarms);
                final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                mTotalDelayTime += thisDelayTime;
                if (mMaxDelayTime < thisDelayTime) {
                    mMaxDelayTime = thisDelayTime;
                }
                mPendingNonWakeupAlarms.clear();
            }
            calculateDeliveryPriorities(alarms);
            Collections.sort(alarms, mAlarmDispatchComparator);
            deliverAlarmsLocked(alarms, nowELAPSED);
        }
    }

    void restorePendingWhileIdleAlarmsLocked() {
        if (RECORD_DEVICE_IDLE_ALARMS) {
            IdleDispatchEntry ent = new IdleDispatchEntry();
            ent.uid = 0;
            ent.pkg = "FINISH IDLE";
            ent.elapsedRealtime = mInjector.getElapsedRealtime();
            mAllowWhileIdleDispatches.add(ent);
        }

        // Bring pending alarms back into the main list.
        if (mPendingWhileIdleAlarms.size() > 0) {
            ArrayList<Alarm> alarms = mPendingWhileIdleAlarms;
            mPendingWhileIdleAlarms = new ArrayList<>();
            final long nowElapsed = mInjector.getElapsedRealtime();
            for (int i = alarms.size() - 1; i >= 0; i--) {
                Alarm a = alarms.get(i);
                restoreAlarmLocked(a, nowElapsed);
                setImplLocked(a);
            }
        }

        // Reschedule everything.
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();

    }

    static final class InFlight {
        final PendingIntent mPendingIntent;
        final long mWhenElapsed;
        final IBinder mListener;
        final WorkSource mWorkSource;
        final int mUid;
        final int mCreatorUid;
        final String mTag;
        final BroadcastStats mBroadcastStats;
        final FilterStats mFilterStats;
        final int mAlarmType;

        InFlight(AlarmManagerService service, Alarm alarm, long nowELAPSED) {
            mPendingIntent = alarm.operation;
            mWhenElapsed = nowELAPSED;
            mListener = alarm.listener != null ? alarm.listener.asBinder() : null;
            mWorkSource = alarm.workSource;
            mUid = alarm.uid;
            mCreatorUid = alarm.creatorUid;
            mTag = alarm.statsTag;
            mBroadcastStats = (alarm.operation != null)
                    ? service.getStatsLocked(alarm.operation)
                    : service.getStatsLocked(alarm.uid, alarm.packageName);
            FilterStats fs = mBroadcastStats.filterStats.get(mTag);
            if (fs == null) {
                fs = new FilterStats(mBroadcastStats, mTag);
                mBroadcastStats.filterStats.put(mTag, fs);
            }
            fs.lastTime = nowELAPSED;
            mFilterStats = fs;
            mAlarmType = alarm.type;
        }

        boolean isBroadcast() {
            return mPendingIntent != null && mPendingIntent.isBroadcast();
        }

        @Override
        public String toString() {
            return "InFlight{"
                    + "pendingIntent=" + mPendingIntent
                    + ", when=" + mWhenElapsed
                    + ", workSource=" + mWorkSource
                    + ", uid=" + mUid
                    + ", creatorUid=" + mCreatorUid
                    + ", tag=" + mTag
                    + ", broadcastStats=" + mBroadcastStats
                    + ", filterStats=" + mFilterStats
                    + ", alarmType=" + mAlarmType
                    + "}";
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(InFlightProto.UID, mUid);
            proto.write(InFlightProto.TAG, mTag);
            proto.write(InFlightProto.WHEN_ELAPSED_MS, mWhenElapsed);
            proto.write(InFlightProto.ALARM_TYPE, mAlarmType);
            if (mPendingIntent != null) {
                mPendingIntent.dumpDebug(proto, InFlightProto.PENDING_INTENT);
            }
            if (mBroadcastStats != null) {
                mBroadcastStats.dumpDebug(proto, InFlightProto.BROADCAST_STATS);
            }
            if (mFilterStats != null) {
                mFilterStats.dumpDebug(proto, InFlightProto.FILTER_STATS);
            }
            if (mWorkSource != null) {
                mWorkSource.dumpDebug(proto, InFlightProto.WORK_SOURCE);
            }

            proto.end(token);
        }
    }

    private void notifyBroadcastAlarmPendingLocked(int uid) {
        final int numListeners = mInFlightListeners.size();
        for (int i = 0; i < numListeners; i++) {
            mInFlightListeners.get(i).broadcastAlarmPending(uid);
        }
    }

    private void notifyBroadcastAlarmCompleteLocked(int uid) {
        final int numListeners = mInFlightListeners.size();
        for (int i = 0; i < numListeners; i++) {
            mInFlightListeners.get(i).broadcastAlarmComplete(uid);
        }
    }

    static final class FilterStats {
        final BroadcastStats mBroadcastStats;
        final String mTag;

        long lastTime;
        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;

        FilterStats(BroadcastStats broadcastStats, String tag) {
            mBroadcastStats = broadcastStats;
            mTag = tag;
        }

        @Override
        public String toString() {
            return "FilterStats{"
                    + "tag=" + mTag
                    + ", lastTime=" + lastTime
                    + ", aggregateTime=" + aggregateTime
                    + ", count=" + count
                    + ", numWakeup=" + numWakeup
                    + ", startTime=" + startTime
                    + ", nesting=" + nesting
                    + "}";
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(FilterStatsProto.TAG, mTag);
            proto.write(FilterStatsProto.LAST_FLIGHT_TIME_REALTIME, lastTime);
            proto.write(FilterStatsProto.TOTAL_FLIGHT_DURATION_MS, aggregateTime);
            proto.write(FilterStatsProto.COUNT, count);
            proto.write(FilterStatsProto.WAKEUP_COUNT, numWakeup);
            proto.write(FilterStatsProto.START_TIME_REALTIME, startTime);
            proto.write(FilterStatsProto.NESTING, nesting);

            proto.end(token);
        }
    }

    static final class BroadcastStats {
        final int mUid;
        final String mPackageName;

        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;
        final ArrayMap<String, FilterStats> filterStats = new ArrayMap<String, FilterStats>();

        BroadcastStats(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }

        @Override
        public String toString() {
            return "BroadcastStats{"
                    + "uid=" + mUid
                    + ", packageName=" + mPackageName
                    + ", aggregateTime=" + aggregateTime
                    + ", count=" + count
                    + ", numWakeup=" + numWakeup
                    + ", startTime=" + startTime
                    + ", nesting=" + nesting
                    + "}";
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(BroadcastStatsProto.UID, mUid);
            proto.write(BroadcastStatsProto.PACKAGE_NAME, mPackageName);
            proto.write(BroadcastStatsProto.TOTAL_FLIGHT_DURATION_MS, aggregateTime);
            proto.write(BroadcastStatsProto.COUNT, count);
            proto.write(BroadcastStatsProto.WAKEUP_COUNT, numWakeup);
            proto.write(BroadcastStatsProto.START_TIME_REALTIME, startTime);
            proto.write(BroadcastStatsProto.NESTING, nesting);

            proto.end(token);
        }
    }

    final SparseArray<ArrayMap<String, BroadcastStats>> mBroadcastStats
            = new SparseArray<ArrayMap<String, BroadcastStats>>();

    int mNumDelayedAlarms = 0;
    long mTotalDelayTime = 0;
    long mMaxDelayTime = 0;

    @Override
    public void onStart() {
        mInjector.init();

        mListenerDeathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
            }

            @Override
            public void binderDied(IBinder who) {
                final IAlarmListener listener = IAlarmListener.Stub.asInterface(who);
                removeImpl(null, listener);
            }
        };

        synchronized (mLock) {
            mHandler = new AlarmHandler();
            mConstants = new Constants();
            mAppWakeupHistory = new AppWakeupHistory(Constants.DEFAULT_APP_STANDBY_WINDOW);

            mNextWakeup = mNextNonWakeup = 0;

            // We have to set current TimeZone info to kernel
            // because kernel doesn't keep this after reboot
            setTimeZoneImpl(SystemProperties.get(TIMEZONE_PROPERTY));

            // Ensure that we're booting with a halfway sensible current time.  Use the
            // most recent of Build.TIME, the root file system's timestamp, and the
            // value of the ro.build.date.utc system property (which is in seconds).
            final long systemBuildTime = Long.max(
                    1000L * SystemProperties.getLong("ro.build.date.utc", -1L),
                    Long.max(Environment.getRootDirectory().lastModified(), Build.TIME));
            if (mInjector.getCurrentTimeMillis() < systemBuildTime) {
                Slog.i(TAG, "Current time only " + mInjector.getCurrentTimeMillis()
                        + ", advancing to build time " + systemBuildTime);
                mInjector.setKernelTime(systemBuildTime);
            }

            // Determine SysUI's uid
            mSystemUiUid = mInjector.getSystemUiUid();
            if (mSystemUiUid <= 0) {
                Slog.wtf(TAG, "SysUI package not found!");
            }
            mWakeLock = mInjector.getAlarmWakeLock();

            mTimeTickIntent = new Intent(Intent.ACTION_TIME_TICK).addFlags(
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);

            mTimeTickTrigger = new IAlarmListener.Stub() {
                @Override
                public void doAlarm(final IAlarmCompleteListener callback) throws RemoteException {
                    if (DEBUG_BATCH) {
                        Slog.v(TAG, "Received TIME_TICK alarm; rescheduling");
                    }

                    // Via handler because dispatch invokes this within its lock.  OnAlarmListener
                    // takes care of this automatically, but we're using the direct internal
                    // interface here rather than that client-side wrapper infrastructure.
                    mHandler.post(() -> {
                        getContext().sendBroadcastAsUser(mTimeTickIntent, UserHandle.ALL);

                        try {
                            callback.alarmComplete(this);
                        } catch (RemoteException e) { /* local method call */ }
                    });

                    synchronized (mLock) {
                        mLastTickReceived = mInjector.getCurrentTimeMillis();
                    }
                    mClockReceiver.scheduleTimeTickEvent();
                }
            };

            Intent intent = new Intent(Intent.ACTION_DATE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            mDateChangeSender = PendingIntent.getBroadcastAsUser(getContext(), 0, intent,
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT, UserHandle.ALL);

            mClockReceiver = mInjector.getClockReceiver(this);
            new ChargingReceiver();
            new InteractiveStateReceiver();
            new UninstallReceiver();

            if (mInjector.isAlarmDriverPresent()) {
                AlarmThread waitThread = new AlarmThread();
                waitThread.start();
            } else {
                Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
            }
        }
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        publishLocalService(AlarmManagerInternal.class, new LocalService());
        publishBinderService(Context.ALARM_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                mConstants.start();
                mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
                mLocalDeviceIdleController =
                        LocalServices.getService(DeviceIdleInternal.class);
                mUsageStatsManagerInternal =
                        LocalServices.getService(UsageStatsManagerInternal.class);
                AppStandbyInternal appStandbyInternal =
                        LocalServices.getService(AppStandbyInternal.class);
                appStandbyInternal.addListener(new AppStandbyTracker());

                mAppStateTracker =
                        (AppStateTrackerImpl) LocalServices.getService(AppStateTracker.class);
                mAppStateTracker.addListener(mForceAppStandbyListener);

                mClockReceiver.scheduleTimeTickEvent();
                mClockReceiver.scheduleDateChangedEvent();
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mInjector.close();
        } finally {
            super.finalize();
        }
    }

    boolean setTimeImpl(long millis) {
        if (!mInjector.isAlarmDriverPresent()) {
            Slog.w(TAG, "Not setting time since no alarm driver is available.");
            return false;
        }

        synchronized (mLock) {
            final long currentTimeMillis = mInjector.getCurrentTimeMillis();
            mInjector.setKernelTime(millis);
            final TimeZone timeZone = TimeZone.getDefault();
            final int currentTzOffset = timeZone.getOffset(currentTimeMillis);
            final int newTzOffset = timeZone.getOffset(millis);
            if (currentTzOffset != newTzOffset) {
                Slog.i(TAG, "Timezone offset has changed, updating kernel timezone");
                mInjector.setKernelTimezone(-(newTzOffset / 60000));
            }
            // The native implementation of setKernelTime can return -1 even when the kernel
            // time was set correctly, so assume setting kernel time was successful and always
            // return true.
            return true;
        }
    }

    void setTimeZoneImpl(String tz) {
        if (TextUtils.isEmpty(tz)) {
            return;
        }

        TimeZone zone = TimeZone.getTimeZone(tz);
        // Prevent reentrant calls from stepping on each other when writing
        // the time zone property
        boolean timeZoneWasChanged = false;
        synchronized (this) {
            String current = SystemProperties.get(TIMEZONE_PROPERTY);
            if (current == null || !current.equals(zone.getID())) {
                if (localLOGV) {
                    Slog.v(TAG, "timezone changed: " + current + ", new=" + zone.getID());
                }
                timeZoneWasChanged = true;
                SystemProperties.set(TIMEZONE_PROPERTY, zone.getID());
            }

            // Update the kernel timezone information
            // Kernel tracks time offsets as 'minutes west of GMT'
            int gmtOffset = zone.getOffset(mInjector.getCurrentTimeMillis());
            mInjector.setKernelTimezone(-(gmtOffset / 60000));
        }

        TimeZone.setDefault(null);

        if (timeZoneWasChanged) {
            // Don't wait for broadcasts to update our midnight alarm
            mClockReceiver.scheduleDateChangedEvent();

            // And now let everyone else know
            Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                    | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            intent.putExtra(Intent.EXTRA_TIMEZONE, zone.getID());
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void removeImpl(PendingIntent operation, IAlarmListener listener) {
        synchronized (mLock) {
            removeLocked(operation, listener);
        }
    }

    void setImpl(int type, long triggerAtTime, long windowLength, long interval,
            PendingIntent operation, IAlarmListener directReceiver, String listenerTag,
            int flags, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock,
            int callingUid, String callingPackage) {
        if ((operation == null && directReceiver == null)
                || (operation != null && directReceiver != null)) {
            Slog.w(TAG, "Alarms must either supply a PendingIntent or an AlarmReceiver");
            // NB: previous releases failed silently here, so we are continuing to do the same
            // rather than throw an IllegalArgumentException.
            return;
        }

        if (directReceiver != null) {
            try {
                directReceiver.asBinder().linkToDeath(mListenerDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Dropping unreachable alarm listener " + listenerTag);
                return;
            }
        }

        // Sanity check the window length.  This will catch people mistakenly
        // trying to pass an end-of-window timestamp rather than a duration.
        if (windowLength > AlarmManager.INTERVAL_HALF_DAY) {
            Slog.w(TAG, "Window length " + windowLength
                    + "ms suspiciously long; limiting to 1 hour");
            windowLength = AlarmManager.INTERVAL_HOUR;
        }

        // Sanity check the recurrence interval.  This will catch people who supply
        // seconds when the API expects milliseconds, or apps trying shenanigans
        // around intentional period overflow, etc.
        final long minInterval = mConstants.MIN_INTERVAL;
        if (interval > 0 && interval < minInterval) {
            Slog.w(TAG, "Suspiciously short interval " + interval
                    + " millis; expanding to " + (minInterval / 1000)
                    + " seconds");
            interval = minInterval;
        } else if (interval > mConstants.MAX_INTERVAL) {
            Slog.w(TAG, "Suspiciously long interval " + interval
                    + " millis; clamping");
            interval = mConstants.MAX_INTERVAL;
        }

        if (type < RTC_WAKEUP || type > ELAPSED_REALTIME) {
            throw new IllegalArgumentException("Invalid alarm type " + type);
        }

        if (triggerAtTime < 0) {
            final long what = Binder.getCallingPid();
            Slog.w(TAG, "Invalid alarm trigger time! " + triggerAtTime + " from uid=" + callingUid
                    + " pid=" + what);
            triggerAtTime = 0;
        }

        final long nowElapsed = mInjector.getElapsedRealtime();
        final long nominalTrigger = convertToElapsed(triggerAtTime, type);
        // Try to prevent spamming by making sure apps aren't firing alarms in the immediate future
        final long minTrigger = nowElapsed
                + (UserHandle.isCore(callingUid) ? 0L : mConstants.MIN_FUTURITY);
        final long triggerElapsed = (nominalTrigger > minTrigger) ? nominalTrigger : minTrigger;

        final long maxElapsed;
        if (windowLength == AlarmManager.WINDOW_EXACT) {
            maxElapsed = triggerElapsed;
        } else if (windowLength < 0) {
            maxElapsed = maxTriggerTime(nowElapsed, triggerElapsed, interval);
            // Fix this window in place, so that as time approaches we don't collapse it.
            windowLength = maxElapsed - triggerElapsed;
        } else {
            maxElapsed = triggerElapsed + windowLength;
        }
        synchronized (mLock) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "set(" + operation + ") : type=" + type
                        + " triggerAtTime=" + triggerAtTime + " win=" + windowLength
                        + " tElapsed=" + triggerElapsed + " maxElapsed=" + maxElapsed
                        + " interval=" + interval + " flags=0x" + Integer.toHexString(flags));
            }
            if (mAlarmsPerUid.get(callingUid, 0) >= mConstants.MAX_ALARMS_PER_UID) {
                final String errorMsg =
                        "Maximum limit of concurrent alarms " + mConstants.MAX_ALARMS_PER_UID
                                + " reached for uid: " + UserHandle.formatUid(callingUid)
                                + ", callingPackage: " + callingPackage;
                Slog.w(TAG, errorMsg);
                throw new IllegalStateException(errorMsg);
            }
            setImplLocked(type, triggerAtTime, triggerElapsed, windowLength, maxElapsed,
                    interval, operation, directReceiver, listenerTag, flags, workSource,
                    alarmClock, callingUid, callingPackage);
        }
    }

    private void setImplLocked(int type, long when, long whenElapsed, long windowLength,
            long maxWhen, long interval, PendingIntent operation, IAlarmListener directReceiver,
            String listenerTag, int flags, WorkSource workSource,
            AlarmManager.AlarmClockInfo alarmClock, int callingUid, String callingPackage) {
        Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval,
                operation, directReceiver, listenerTag, workSource, flags, alarmClock,
                callingUid, callingPackage);
        if (mActivityManagerInternal.isAppStartModeDisabled(callingUid, callingPackage)) {
            Slog.w(TAG, "Not setting alarm from " + callingUid + ":" + a
                    + " -- package not allowed to start");
            return;
        }
        removeLocked(operation, directReceiver);
        incrementAlarmCount(a.uid);
        setImplLocked(a);
    }

    /**
     * Returns the maximum alarms that an app in the specified bucket can receive in a rolling time
     * window given by {@link Constants#APP_STANDBY_WINDOW}
     */
    @VisibleForTesting
    int getQuotaForBucketLocked(int bucket) {
        final int index;
        if (bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
            index = ACTIVE_INDEX;
        } else if (bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
            index = WORKING_INDEX;
        } else if (bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT) {
            index = FREQUENT_INDEX;
        } else if (bucket < UsageStatsManager.STANDBY_BUCKET_NEVER) {
            index = RARE_INDEX;
        } else {
            index = NEVER_INDEX;
        }
        return mConstants.APP_STANDBY_QUOTAS[index];
    }

    /**
     * Adjusts the idle-until alarm delivery time based on the upcoming wake-from-idle alarm.
     *
     * @param alarm The alarm to adjust
     * @return true if the alarm delivery time was updated.
     */
    private boolean adjustIdleUntilTime(Alarm alarm) {
        if ((alarm.flags & AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            return false;
        }
        // This is a special alarm that will put the system into idle until it goes off.
        // The caller has given the time they want this to happen at, however we need
        // to pull that earlier if there are existing alarms that have requested to
        // bring us out of idle at an earlier time.
        if (mNextWakeFromIdle != null && alarm.whenElapsed > mNextWakeFromIdle.whenElapsed) {
            alarm.when = alarm.whenElapsed = alarm.maxWhenElapsed = mNextWakeFromIdle.whenElapsed;
        }
        // Add fuzz to make the alarm go off some time before the actual desired time.
        final long nowElapsed = mInjector.getElapsedRealtime();
        final int fuzz = fuzzForDuration(alarm.whenElapsed - nowElapsed);
        if (fuzz > 0) {
            if (mRandom == null) {
                mRandom = new Random();
            }
            final int delta = mRandom.nextInt(fuzz);
            alarm.whenElapsed -= delta;
            if (false) {
                Slog.d(TAG, "Alarm when: " + alarm.whenElapsed);
                Slog.d(TAG, "Delta until alarm: " + (alarm.whenElapsed - nowElapsed));
                Slog.d(TAG, "Applied fuzz: " + fuzz);
                Slog.d(TAG, "Final delta: " + delta);
                Slog.d(TAG, "Final when: " + alarm.whenElapsed);
            }
            alarm.when = alarm.maxWhenElapsed = alarm.whenElapsed;
        }
        return true;
    }

    /**
     * Adjusts the alarm delivery time based on the current app standby bucket.
     *
     * @param alarm The alarm to adjust
     * @return true if the alarm delivery time was updated.
     */
    private boolean adjustDeliveryTimeBasedOnBucketLocked(Alarm alarm) {
        if (isExemptFromAppStandby(alarm)) {
            return false;
        }
        if (mAppStandbyParole) {
            if (alarm.whenElapsed > alarm.expectedWhenElapsed) {
                // We did defer this alarm earlier, restore original requirements
                alarm.whenElapsed = alarm.expectedWhenElapsed;
                alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
                return true;
            }
            return false;
        }
        final long oldWhenElapsed = alarm.whenElapsed;
        final long oldMaxWhenElapsed = alarm.maxWhenElapsed;

        final String sourcePackage = alarm.sourcePackage;
        final int sourceUserId = UserHandle.getUserId(alarm.creatorUid);
        final int standbyBucket = mUsageStatsManagerInternal.getAppStandbyBucket(
                sourcePackage, sourceUserId, mInjector.getElapsedRealtime());

        // Quota deferring implementation:
        boolean deferred = false;
        final int wakeupsInWindow = mAppWakeupHistory.getTotalWakeupsInWindow(sourcePackage,
                sourceUserId);
        if (standbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
            // Special case because it's 1/day instead of 1/hour.
            // AppWakeupHistory doesn't delete old wakeup times until a new one is logged, so we
            // should always have the last wakeup available.
            if (wakeupsInWindow > 0) {
                final long lastWakeupTime = mAppWakeupHistory.getNthLastWakeupForPackage(
                        sourcePackage, sourceUserId, mConstants.APP_STANDBY_RESTRICTED_QUOTA);
                if (mInjector.getElapsedRealtime() - lastWakeupTime
                        < mConstants.APP_STANDBY_RESTRICTED_WINDOW) {
                    final long minElapsed =
                            lastWakeupTime + mConstants.APP_STANDBY_RESTRICTED_WINDOW;
                    if (alarm.expectedWhenElapsed < minElapsed) {
                        alarm.whenElapsed = alarm.maxWhenElapsed = minElapsed;
                        deferred = true;
                    }
                }
            }
        } else {
            final int quotaForBucket = getQuotaForBucketLocked(standbyBucket);
            if (wakeupsInWindow >= quotaForBucket) {
                final long minElapsed;
                if (quotaForBucket <= 0) {
                    // Just keep deferring for a day till the quota changes
                    minElapsed = mInjector.getElapsedRealtime() + MILLIS_IN_DAY;
                } else {
                    // Suppose the quota for window was q, and the qth last delivery time for this
                    // package was t(q) then the next delivery must be after t(q) + <window_size>
                    final long t = mAppWakeupHistory.getNthLastWakeupForPackage(
                            sourcePackage, sourceUserId, quotaForBucket);
                    minElapsed = t + mConstants.APP_STANDBY_WINDOW;
                }
                if (alarm.expectedWhenElapsed < minElapsed) {
                    alarm.whenElapsed = alarm.maxWhenElapsed = minElapsed;
                    deferred = true;
                }
            }
        }
        if (!deferred) {
            // Restore original requirements in case they were changed earlier.
            alarm.whenElapsed = alarm.expectedWhenElapsed;
            alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
        }

        return (oldWhenElapsed != alarm.whenElapsed || oldMaxWhenElapsed != alarm.maxWhenElapsed);
    }

    private static boolean isAllowedWhileIdle(Alarm a) {
        return ((a.flags & (AlarmManager.FLAG_ALLOW_WHILE_IDLE
                | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED
                | AlarmManager.FLAG_WAKE_FROM_IDLE)) != 0);
    }

    private void setImplLocked(Alarm a) {
        if ((a.flags & AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            adjustIdleUntilTime(a);

            if (RECORD_DEVICE_IDLE_ALARMS) {
                IdleDispatchEntry ent = new IdleDispatchEntry();
                ent.uid = a.uid;
                ent.pkg = a.operation.getCreatorPackage();
                ent.tag = a.operation.getTag("");
                ent.op = "SET";
                ent.elapsedRealtime = mInjector.getElapsedRealtime();
                ent.argRealtime = a.whenElapsed;
                mAllowWhileIdleDispatches.add(ent);
                if (mPendingIdleUntil == null) {
                    IdleDispatchEntry ent2 = new IdleDispatchEntry();
                    ent2.uid = 0;
                    ent2.pkg = "START IDLE";
                    ent2.elapsedRealtime = mInjector.getElapsedRealtime();
                    mAllowWhileIdleDispatches.add(ent2);
                }
            }
            if ((mPendingIdleUntil != a) && (mPendingIdleUntil != null)) {
                Slog.wtfStack(TAG, "setImplLocked: idle until changed from " + mPendingIdleUntil
                        + " to " + a);
            }
            mPendingIdleUntil = a;
            final ArrayList<Alarm> notAllowedWhileIdleAlarms = mAlarmStore.remove(
                    alarm -> !isAllowedWhileIdle(alarm));
            mPendingWhileIdleAlarms.addAll(notAllowedWhileIdleAlarms);
        } else if (mPendingIdleUntil != null) {
            // We currently have an idle until alarm scheduled; if the new alarm has
            // not explicitly stated it wants to run while idle, then put it on hold.
            if (!isAllowedWhileIdle(a)) {
                mPendingWhileIdleAlarms.add(a);
                return;
            }
        }
        if ((a.flags & AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
            if (mNextWakeFromIdle == null || mNextWakeFromIdle.whenElapsed > a.whenElapsed) {
                mNextWakeFromIdle = a;
                // If this wake from idle is earlier than whatever was previously scheduled,
                // and we are currently idling, then we need to rebatch alarms in case the idle
                // until time needs to be updated.
                if (mPendingIdleUntil != null) {
                    final long nowElapsed = mInjector.getElapsedRealtime();
                    mAlarmStore.recalculateAlarmDeliveries(alarm -> {
                        if (alarm != mPendingIdleUntil) {
                            return false;
                        }
                        restoreAlarmLocked(alarm, nowElapsed);
                        return adjustIdleUntilTime(alarm);
                    });
                }
            }
        }
        if (a.alarmClock != null) {
            mNextAlarmClockMayChange = true;
        }
        adjustDeliveryTimeBasedOnBucketLocked(a);
        mAlarmStore.add(a);
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();
    }

    /**
     * System-process internal API
     */
    private final class LocalService implements AlarmManagerInternal {
        @Override
        public boolean isIdling() {
            return isIdlingImpl();
        }

        @Override
        public void removeAlarmsForUid(int uid) {
            synchronized (mLock) {
                removeLocked(uid);
            }
        }

        @Override
        public void remove(PendingIntent pi) {
            mHandler.obtainMessage(AlarmHandler.REMOVE_FOR_CANCELED, pi).sendToTarget();
        }

        @Override
        public void registerInFlightListener(InFlightListener callback) {
            synchronized (mLock) {
                mInFlightListeners.add(callback);
            }
        }
    }

    /**
     * Public-facing binder interface
     */
    private final IBinder mService = new IAlarmManager.Stub() {
        @Override
        public void set(String callingPackage,
                int type, long triggerAtTime, long windowLength, long interval, int flags,
                PendingIntent operation, IAlarmListener directReceiver, String listenerTag,
                WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock) {
            final int callingUid = Binder.getCallingUid();

            // make sure the caller is not lying about which package should be blamed for
            // wakelock time spent in alarm delivery
            mAppOps.checkPackage(callingUid, callingPackage);

            // Repeating alarms must use PendingIntent, not direct listener
            if (interval != 0) {
                if (directReceiver != null) {
                    throw new IllegalArgumentException(
                            "Repeating alarms cannot use AlarmReceivers");
                }
            }

            if (workSource != null) {
                getContext().enforcePermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS,
                        Binder.getCallingPid(), callingUid, "AlarmManager.set");
            }

            // No incoming callers can request either WAKE_FROM_IDLE or
            // ALLOW_WHILE_IDLE_UNRESTRICTED -- we will apply those later as appropriate.
            flags &= ~(AlarmManager.FLAG_WAKE_FROM_IDLE
                    | AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED);

            // Only the system can use FLAG_IDLE_UNTIL -- this is used to tell the alarm
            // manager when to come out of idle mode, which is only for DeviceIdleController.
            if (callingUid != Process.SYSTEM_UID) {
                flags &= ~AlarmManager.FLAG_IDLE_UNTIL;
            }

            // If this is an exact time alarm, then it can't be batched with other alarms.
            if (windowLength == AlarmManager.WINDOW_EXACT) {
                flags |= AlarmManager.FLAG_STANDALONE;
            }

            // If this alarm is for an alarm clock, then it must be standalone and we will
            // use it to wake early from idle if needed.
            if (alarmClock != null) {
                flags |= AlarmManager.FLAG_WAKE_FROM_IDLE | AlarmManager.FLAG_STANDALONE;

            // If the caller is a core system component or on the user's whitelist, and not calling
            // to do work on behalf of someone else, then always set ALLOW_WHILE_IDLE_UNRESTRICTED.
            // This means we will allow these alarms to go off as normal even while idle, with no
            // timing restrictions.
            } else if (workSource == null && (callingUid < Process.FIRST_APPLICATION_UID
                    || UserHandle.isSameApp(callingUid, mSystemUiUid)
                    || ((mAppStateTracker != null)
                        && mAppStateTracker.isUidPowerSaveUserExempt(callingUid)))) {
                flags |= AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
                flags &= ~AlarmManager.FLAG_ALLOW_WHILE_IDLE;
            }

            setImpl(type, triggerAtTime, windowLength, interval, operation, directReceiver,
                    listenerTag, flags, workSource, alarmClock, callingUid, callingPackage);
        }

        @Override
        public boolean setTime(long millis) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME",
                    "setTime");

            return setTimeImpl(millis);
        }

        @Override
        public void setTimeZone(String tz) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME_ZONE",
                    "setTimeZone");

            final long oldId = Binder.clearCallingIdentity();
            try {
                setTimeZoneImpl(tz);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
        }

        @Override
        public void remove(PendingIntent operation, IAlarmListener listener) {
            if (operation == null && listener == null) {
                Slog.w(TAG, "remove() with no intent or listener");
                return;
            }
            synchronized (mLock) {
                removeLocked(operation, listener);
            }
        }

        @Override
        public long getNextWakeFromIdleTime() {
            return getNextWakeFromIdleTimeImpl();
        }

        @Override
        public AlarmManager.AlarmClockInfo getNextAlarmClock(int userId) {
            userId = mActivityManagerInternal.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, /*allowAll=*/false, ALLOW_NON_FULL,
                    "getNextAlarmClock", null);
            return getNextAlarmClockImpl(userId);
        }

        @Override
        public long currentNetworkTimeMillis() {
            final NtpTrustedTime time = NtpTrustedTime.getInstance(getContext());
            NtpTrustedTime.TimeResult ntpResult = time.getCachedTimeResult();
            if (ntpResult != null) {
                return ntpResult.currentTimeMillis();
            } else {
                throw new ParcelableException(new DateTimeException("Missing NTP fix"));
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

            if (args.length > 0 && "--proto".equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpImpl(pw);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new ShellCmd()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current Alarm Manager state:");
            mConstants.dump(pw, "  ");
            pw.println();

            if (mAppStateTracker != null) {
                mAppStateTracker.dump(pw, "  ");
                pw.println();
            }

            pw.println("  App Standby Parole: " + mAppStandbyParole);
            pw.println();

            final long nowELAPSED = mInjector.getElapsedRealtime();
            final long nowUPTIME = SystemClock.uptimeMillis();
            final long nowRTC = mInjector.getCurrentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            pw.print("  nowRTC=");
            pw.print(nowRTC);
            pw.print("=");
            pw.print(sdf.format(new Date(nowRTC)));
            pw.print(" nowELAPSED=");
            pw.print(nowELAPSED);
            pw.println();

            pw.print("  mLastTimeChangeClockTime=");
            pw.print(mLastTimeChangeClockTime);
            pw.print("=");
            pw.println(sdf.format(new Date(mLastTimeChangeClockTime)));

            pw.print("  mLastTimeChangeRealtime=");
            pw.println(mLastTimeChangeRealtime);

            pw.print("  mLastTickReceived=");
            pw.println(sdf.format(new Date(mLastTickReceived)));

            pw.print("  mLastTickSet=");
            pw.println(sdf.format(new Date(mLastTickSet)));

            if (RECORD_ALARMS_IN_HISTORY) {
                pw.println();
                pw.println("  Recent TIME_TICK history:");
                int i = mNextTickHistory;
                do {
                    i--;
                    if (i < 0) i = TICK_HISTORY_DEPTH - 1;
                    final long time = mTickHistory[i];
                    pw.print("    ");
                    pw.println((time > 0)
                            ? sdf.format(new Date(nowRTC - (nowELAPSED - time)))
                            : "-");
                } while (i != mNextTickHistory);
            }

            SystemServiceManager ssm = LocalServices.getService(SystemServiceManager.class);
            if (ssm != null) {
                pw.println();
                pw.print("  RuntimeStarted=");
                pw.print(sdf.format(
                        new Date(nowRTC - nowELAPSED + ssm.getRuntimeStartElapsedTime())));
                if (ssm.isRuntimeRestarted()) {
                    pw.print("  (Runtime restarted)");
                }
                pw.println();
                pw.print("  Runtime uptime (elapsed): ");
                TimeUtils.formatDuration(nowELAPSED, ssm.getRuntimeStartElapsedTime(), pw);
                pw.println();
                pw.print("  Runtime uptime (uptime): ");
                TimeUtils.formatDuration(nowUPTIME, ssm.getRuntimeStartUptime(), pw);
                pw.println();
            }

            pw.println();
            if (!mInteractive) {
                pw.print("  Time since non-interactive: ");
                TimeUtils.formatDuration(nowELAPSED - mNonInteractiveStartTime, pw);
                pw.println();
            }
            pw.print("  Max wakeup delay: ");
            TimeUtils.formatDuration(currentNonWakeupFuzzLocked(nowELAPSED), pw);
            pw.println();
            pw.print("  Time since last dispatch: ");
            TimeUtils.formatDuration(nowELAPSED - mLastAlarmDeliveryTime, pw);
            pw.println();
            pw.print("  Next non-wakeup delivery time: ");
            TimeUtils.formatDuration(mNextNonWakeupDeliveryTime, nowELAPSED, pw);
            pw.println();

            long nextWakeupRTC = mNextWakeup + (nowRTC - nowELAPSED);
            long nextNonWakeupRTC = mNextNonWakeup + (nowRTC - nowELAPSED);
            pw.print("  Next non-wakeup alarm: ");
            TimeUtils.formatDuration(mNextNonWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.print(mNextNonWakeup);
            pw.print(" = ");
            pw.println(sdf.format(new Date(nextNonWakeupRTC)));
            pw.print("    set at ");
            TimeUtils.formatDuration(mNextNonWakeUpSetAt, nowELAPSED, pw);
            pw.println();
            pw.print("  Next wakeup alarm: ");
            TimeUtils.formatDuration(mNextWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.print(mNextWakeup);
            pw.print(" = ");
            pw.println(sdf.format(new Date(nextWakeupRTC)));
            pw.print("    set at ");
            TimeUtils.formatDuration(mNextWakeUpSetAt, nowELAPSED, pw);
            pw.println();

            pw.print("  Next kernel non-wakeup alarm: ");
            TimeUtils.formatDuration(mInjector.getNextAlarm(ELAPSED_REALTIME), pw);
            pw.println();
            pw.print("  Next kernel wakeup alarm: ");
            TimeUtils.formatDuration(mInjector.getNextAlarm(ELAPSED_REALTIME_WAKEUP), pw);
            pw.println();

            pw.print("  Last wakeup: ");
            TimeUtils.formatDuration(mLastWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.println(mLastWakeup);
            pw.print("  Last trigger: ");
            TimeUtils.formatDuration(mLastTrigger, nowELAPSED, pw);
            pw.print(" = ");
            pw.println(mLastTrigger);
            pw.print("  Num time change events: ");
            pw.println(mNumTimeChanged);

            pw.println();
            pw.println("  Next alarm clock information: ");
            final TreeSet<Integer> users = new TreeSet<>();
            for (int i = 0; i < mNextAlarmClockForUser.size(); i++) {
                users.add(mNextAlarmClockForUser.keyAt(i));
            }
            for (int i = 0; i < mPendingSendNextAlarmClockChangedForUser.size(); i++) {
                users.add(mPendingSendNextAlarmClockChangedForUser.keyAt(i));
            }
            for (int user : users) {
                final AlarmManager.AlarmClockInfo next = mNextAlarmClockForUser.get(user);
                final long time = next != null ? next.getTriggerTime() : 0;
                final boolean pendingSend = mPendingSendNextAlarmClockChangedForUser.get(user);
                pw.print("    user:");
                pw.print(user);
                pw.print(" pendingSend:");
                pw.print(pendingSend);
                pw.print(" time:");
                pw.print(time);
                if (time > 0) {
                    pw.print(" = ");
                    pw.print(sdf.format(new Date(time)));
                    pw.print(" = ");
                    TimeUtils.formatDuration(time, nowRTC, pw);
                }
                pw.println();
            }
            if (mAlarmStore.size() > 0) {
                pw.println();
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", "  ");
                mAlarmStore.dump(ipw, nowELAPSED, sdf);
            }
            pw.println();
            pw.println("  Pending user blocked background alarms: ");
            boolean blocked = false;
            for (int i = 0; i < mPendingBackgroundAlarms.size(); i++) {
                final ArrayList<Alarm> blockedAlarms = mPendingBackgroundAlarms.valueAt(i);
                if (blockedAlarms != null && blockedAlarms.size() > 0) {
                    blocked = true;
                    dumpAlarmList(pw, blockedAlarms, "    ", nowELAPSED, sdf);
                }
            }
            if (!blocked) {
                pw.println("    none");
            }
            pw.println();
            pw.print("  Pending alarms per uid: [");
            for (int i = 0; i < mAlarmsPerUid.size(); i++) {
                if (i > 0) {
                    pw.print(", ");
                }
                UserHandle.formatUid(pw, mAlarmsPerUid.keyAt(i));
                pw.print(":");
                pw.print(mAlarmsPerUid.valueAt(i));
            }
            pw.println("]");
            pw.println();

            mAppWakeupHistory.dump(pw, "  ", nowELAPSED);

            if (mPendingIdleUntil != null || mPendingWhileIdleAlarms.size() > 0) {
                pw.println();
                pw.println("    Idle mode state:");
                pw.print("      Idling until: ");
                if (mPendingIdleUntil != null) {
                    pw.println(mPendingIdleUntil);
                    mPendingIdleUntil.dump(pw, "        ", nowELAPSED, sdf);
                } else {
                    pw.println("null");
                }
                pw.println("      Pending alarms:");
                dumpAlarmList(pw, mPendingWhileIdleAlarms, "      ", nowELAPSED, sdf);
            }
            if (mNextWakeFromIdle != null) {
                pw.println();
                pw.print("  Next wake from idle: ");
                pw.println(mNextWakeFromIdle);
                mNextWakeFromIdle.dump(pw, "    ", nowELAPSED, sdf);
            }

            pw.println();
            pw.print("  Past-due non-wakeup alarms: ");
            if (mPendingNonWakeupAlarms.size() > 0) {
                pw.println(mPendingNonWakeupAlarms.size());
                dumpAlarmList(pw, mPendingNonWakeupAlarms, "    ", nowELAPSED, sdf);
            } else {
                pw.println("(none)");
            }
            pw.print("    Number of delayed alarms: ");
            pw.print(mNumDelayedAlarms);
            pw.print(", total delay time: ");
            TimeUtils.formatDuration(mTotalDelayTime, pw);
            pw.println();
            pw.print("    Max delay time: ");
            TimeUtils.formatDuration(mMaxDelayTime, pw);
            pw.print(", max non-interactive time: ");
            TimeUtils.formatDuration(mNonInteractiveTime, pw);
            pw.println();

            pw.println();
            pw.print("  Broadcast ref count: ");
            pw.println(mBroadcastRefCount);
            pw.print("  PendingIntent send count: ");
            pw.println(mSendCount);
            pw.print("  PendingIntent finish count: ");
            pw.println(mSendFinishCount);
            pw.print("  Listener send count: ");
            pw.println(mListenerCount);
            pw.print("  Listener finish count: ");
            pw.println(mListenerFinishCount);
            pw.println();

            if (mInFlight.size() > 0) {
                pw.println("Outstanding deliveries:");
                for (int i = 0; i < mInFlight.size(); i++) {
                    pw.print("   #");
                    pw.print(i);
                    pw.print(": ");
                    pw.println(mInFlight.get(i));
                }
                pw.println();
            }

            if (mLastAllowWhileIdleDispatch.size() > 0) {
                pw.println("  Last allow while idle dispatch times:");
                for (int i = 0; i < mLastAllowWhileIdleDispatch.size(); i++) {
                    pw.print("    UID ");
                    final int uid = mLastAllowWhileIdleDispatch.keyAt(i);
                    UserHandle.formatUid(pw, uid);
                    pw.print(": ");
                    final long lastTime = mLastAllowWhileIdleDispatch.valueAt(i);
                    TimeUtils.formatDuration(lastTime, nowELAPSED, pw);

                    final long minInterval = getWhileIdleMinIntervalLocked(uid);
                    pw.print("  Next allowed:");
                    TimeUtils.formatDuration(lastTime + minInterval, nowELAPSED, pw);
                    pw.print(" (");
                    TimeUtils.formatDuration(minInterval, 0, pw);
                    pw.print(")");

                    pw.println();
                }
            }

            pw.print("  mUseAllowWhileIdleShortTime: [");
            for (int i = 0; i < mUseAllowWhileIdleShortTime.size(); i++) {
                if (mUseAllowWhileIdleShortTime.valueAt(i)) {
                    UserHandle.formatUid(pw, mUseAllowWhileIdleShortTime.keyAt(i));
                    pw.print(" ");
                }
            }
            pw.println("]");
            pw.println();

            if (mLog.dump(pw, "  Recent problems", "    ")) {
                pw.println();
            }

            final FilterStats[] topFilters = new FilterStats[10];
            final Comparator<FilterStats> comparator = new Comparator<FilterStats>() {
                @Override
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    } else if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            // Get the top 10 FilterStats, ordered by aggregateTime.
            for (int iu = 0; iu < mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    for (int is = 0; is < bs.filterStats.size(); is++) {
                        FilterStats fs = bs.filterStats.valueAt(is);
                        int pos = len > 0
                                ? Arrays.binarySearch(topFilters, 0, len, fs, comparator) : 0;
                        if (pos < 0) {
                            pos = -pos - 1;
                        }
                        if (pos < topFilters.length) {
                            int copylen = topFilters.length - pos - 1;
                            if (copylen > 0) {
                                System.arraycopy(topFilters, pos, topFilters, pos + 1, copylen);
                            }
                            topFilters[pos] = fs;
                            if (len < topFilters.length) {
                                len++;
                            }
                        }
                    }
                }
            }
            if (len > 0) {
                pw.println("  Top Alarms:");
                for (int i = 0; i < len; i++) {
                    FilterStats fs = topFilters[i];
                    pw.print("    ");
                    if (fs.nesting > 0) pw.print("*ACTIVE* ");
                    TimeUtils.formatDuration(fs.aggregateTime, pw);
                    pw.print(" running, ");
                    pw.print(fs.numWakeup);
                    pw.print(" wakeups, ");
                    pw.print(fs.count);
                    pw.print(" alarms: ");
                    UserHandle.formatUid(pw, fs.mBroadcastStats.mUid);
                    pw.print(":");
                    pw.print(fs.mBroadcastStats.mPackageName);
                    pw.println();
                    pw.print("      ");
                    pw.print(fs.mTag);
                    pw.println();
                }
            }

            pw.println(" ");
            pw.println("  Alarm Stats:");
            final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
            for (int iu = 0; iu < mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    pw.print("  ");
                    if (bs.nesting > 0) pw.print("*ACTIVE* ");
                    UserHandle.formatUid(pw, bs.mUid);
                    pw.print(":");
                    pw.print(bs.mPackageName);
                    pw.print(" ");
                    TimeUtils.formatDuration(bs.aggregateTime, pw);
                    pw.print(" running, ");
                    pw.print(bs.numWakeup);
                    pw.println(" wakeups:");
                    tmpFilters.clear();
                    for (int is = 0; is < bs.filterStats.size(); is++) {
                        tmpFilters.add(bs.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    for (int i = 0; i < tmpFilters.size(); i++) {
                        FilterStats fs = tmpFilters.get(i);
                        pw.print("    ");
                        if (fs.nesting > 0) pw.print("*ACTIVE* ");
                        TimeUtils.formatDuration(fs.aggregateTime, pw);
                        pw.print(" ");
                        pw.print(fs.numWakeup);
                        pw.print(" wakes ");
                        pw.print(fs.count);
                        pw.print(" alarms, last ");
                        TimeUtils.formatDuration(fs.lastTime, nowELAPSED, pw);
                        pw.println(":");
                        pw.print("      ");
                        pw.print(fs.mTag);
                        pw.println();
                    }
                }
            }
            pw.println();
            mStatLogger.dump(pw, "  ");

            if (RECORD_DEVICE_IDLE_ALARMS) {
                pw.println();
                pw.println("  Allow while idle dispatches:");
                for (int i = 0; i < mAllowWhileIdleDispatches.size(); i++) {
                    IdleDispatchEntry ent = mAllowWhileIdleDispatches.get(i);
                    pw.print("    ");
                    TimeUtils.formatDuration(ent.elapsedRealtime, nowELAPSED, pw);
                    pw.print(": ");
                    UserHandle.formatUid(pw, ent.uid);
                    pw.print(":");
                    pw.println(ent.pkg);
                    if (ent.op != null) {
                        pw.print("      ");
                        pw.print(ent.op);
                        pw.print(" / ");
                        pw.print(ent.tag);
                        if (ent.argRealtime != 0) {
                            pw.print(" (");
                            TimeUtils.formatDuration(ent.argRealtime, nowELAPSED, pw);
                            pw.print(")");
                        }
                        pw.println();
                    }
                }
            }
        }
    }

    void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            final long nowRTC = mInjector.getCurrentTimeMillis();
            final long nowElapsed = mInjector.getElapsedRealtime();
            proto.write(AlarmManagerServiceDumpProto.CURRENT_TIME, nowRTC);
            proto.write(AlarmManagerServiceDumpProto.ELAPSED_REALTIME, nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.LAST_TIME_CHANGE_CLOCK_TIME,
                    mLastTimeChangeClockTime);
            proto.write(AlarmManagerServiceDumpProto.LAST_TIME_CHANGE_REALTIME,
                    mLastTimeChangeRealtime);

            mConstants.dumpProto(proto, AlarmManagerServiceDumpProto.SETTINGS);

            if (mAppStateTracker != null) {
                mAppStateTracker.dumpProto(proto, AlarmManagerServiceDumpProto.APP_STATE_TRACKER);
            }

            proto.write(AlarmManagerServiceDumpProto.IS_INTERACTIVE, mInteractive);
            if (!mInteractive) {
                // Durations
                proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_NON_INTERACTIVE_MS,
                        nowElapsed - mNonInteractiveStartTime);
                proto.write(AlarmManagerServiceDumpProto.MAX_WAKEUP_DELAY_MS,
                        currentNonWakeupFuzzLocked(nowElapsed));
                proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_DISPATCH_MS,
                        nowElapsed - mLastAlarmDeliveryTime);
                proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_NON_WAKEUP_DELIVERY_MS,
                        nowElapsed - mNextNonWakeupDeliveryTime);
            }

            proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_NON_WAKEUP_ALARM_MS,
                    mNextNonWakeup - nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.TIME_UNTIL_NEXT_WAKEUP_MS,
                    mNextWakeup - nowElapsed);
            proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_WAKEUP_MS,
                    nowElapsed - mLastWakeup);
            proto.write(AlarmManagerServiceDumpProto.TIME_SINCE_LAST_WAKEUP_SET_MS,
                    nowElapsed - mNextWakeUpSetAt);
            proto.write(AlarmManagerServiceDumpProto.TIME_CHANGE_EVENT_COUNT, mNumTimeChanged);

            final TreeSet<Integer> users = new TreeSet<>();
            final int nextAlarmClockForUserSize = mNextAlarmClockForUser.size();
            for (int i = 0; i < nextAlarmClockForUserSize; i++) {
                users.add(mNextAlarmClockForUser.keyAt(i));
            }
            final int pendingSendNextAlarmClockChangedForUserSize =
                    mPendingSendNextAlarmClockChangedForUser.size();
            for (int i = 0; i < pendingSendNextAlarmClockChangedForUserSize; i++) {
                users.add(mPendingSendNextAlarmClockChangedForUser.keyAt(i));
            }
            for (int user : users) {
                final AlarmManager.AlarmClockInfo next = mNextAlarmClockForUser.get(user);
                final long time = next != null ? next.getTriggerTime() : 0;
                final boolean pendingSend = mPendingSendNextAlarmClockChangedForUser.get(user);
                final long aToken = proto.start(
                        AlarmManagerServiceDumpProto.NEXT_ALARM_CLOCK_METADATA);
                proto.write(AlarmClockMetadataProto.USER, user);
                proto.write(AlarmClockMetadataProto.IS_PENDING_SEND, pendingSend);
                proto.write(AlarmClockMetadataProto.TRIGGER_TIME_MS, time);
                proto.end(aToken);
            }
            mAlarmStore.dumpProto(proto, nowElapsed);

            for (int i = 0; i < mPendingBackgroundAlarms.size(); i++) {
                final ArrayList<Alarm> blockedAlarms = mPendingBackgroundAlarms.valueAt(i);
                if (blockedAlarms != null) {
                    for (Alarm a : blockedAlarms) {
                        a.dumpDebug(proto,
                                AlarmManagerServiceDumpProto.PENDING_USER_BLOCKED_BACKGROUND_ALARMS,
                                nowElapsed);
                    }
                }
            }
            if (mPendingIdleUntil != null) {
                mPendingIdleUntil.dumpDebug(
                        proto, AlarmManagerServiceDumpProto.PENDING_IDLE_UNTIL, nowElapsed);
            }
            for (Alarm a : mPendingWhileIdleAlarms) {
                a.dumpDebug(proto, AlarmManagerServiceDumpProto.PENDING_WHILE_IDLE_ALARMS,
                        nowElapsed);
            }
            if (mNextWakeFromIdle != null) {
                mNextWakeFromIdle.dumpDebug(proto, AlarmManagerServiceDumpProto.NEXT_WAKE_FROM_IDLE,
                        nowElapsed);
            }

            for (Alarm a : mPendingNonWakeupAlarms) {
                a.dumpDebug(proto, AlarmManagerServiceDumpProto.PAST_DUE_NON_WAKEUP_ALARMS,
                        nowElapsed);
            }

            proto.write(AlarmManagerServiceDumpProto.DELAYED_ALARM_COUNT, mNumDelayedAlarms);
            proto.write(AlarmManagerServiceDumpProto.TOTAL_DELAY_TIME_MS, mTotalDelayTime);
            proto.write(AlarmManagerServiceDumpProto.MAX_DELAY_DURATION_MS, mMaxDelayTime);
            proto.write(AlarmManagerServiceDumpProto.MAX_NON_INTERACTIVE_DURATION_MS,
                    mNonInteractiveTime);

            proto.write(AlarmManagerServiceDumpProto.BROADCAST_REF_COUNT, mBroadcastRefCount);
            proto.write(AlarmManagerServiceDumpProto.PENDING_INTENT_SEND_COUNT, mSendCount);
            proto.write(AlarmManagerServiceDumpProto.PENDING_INTENT_FINISH_COUNT, mSendFinishCount);
            proto.write(AlarmManagerServiceDumpProto.LISTENER_SEND_COUNT, mListenerCount);
            proto.write(AlarmManagerServiceDumpProto.LISTENER_FINISH_COUNT, mListenerFinishCount);

            for (InFlight f : mInFlight) {
                f.dumpDebug(proto, AlarmManagerServiceDumpProto.OUTSTANDING_DELIVERIES);
            }

            for (int i = 0; i < mLastAllowWhileIdleDispatch.size(); ++i) {
                final long token = proto.start(
                        AlarmManagerServiceDumpProto.LAST_ALLOW_WHILE_IDLE_DISPATCH_TIMES);
                final int uid = mLastAllowWhileIdleDispatch.keyAt(i);
                final long lastTime = mLastAllowWhileIdleDispatch.valueAt(i);

                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.UID, uid);
                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.TIME_MS,
                        lastTime);
                proto.write(AlarmManagerServiceDumpProto.LastAllowWhileIdleDispatch.NEXT_ALLOWED_MS,
                        lastTime + getWhileIdleMinIntervalLocked(uid));
                proto.end(token);
            }

            for (int i = 0; i < mUseAllowWhileIdleShortTime.size(); i++) {
                if (mUseAllowWhileIdleShortTime.valueAt(i)) {
                    proto.write(AlarmManagerServiceDumpProto.USE_ALLOW_WHILE_IDLE_SHORT_TIME,
                            mUseAllowWhileIdleShortTime.keyAt(i));
                }
            }

            mLog.dumpDebug(proto, AlarmManagerServiceDumpProto.RECENT_PROBLEMS);

            final FilterStats[] topFilters = new FilterStats[10];
            final Comparator<FilterStats> comparator = new Comparator<FilterStats>() {
                @Override
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    } else if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            // Get the top 10 FilterStats, ordered by aggregateTime.
            for (int iu = 0; iu < mBroadcastStats.size(); ++iu) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ++ip) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    for (int is = 0; is < bs.filterStats.size(); ++is) {
                        FilterStats fs = bs.filterStats.valueAt(is);
                        int pos = len > 0
                                ? Arrays.binarySearch(topFilters, 0, len, fs, comparator) : 0;
                        if (pos < 0) {
                            pos = -pos - 1;
                        }
                        if (pos < topFilters.length) {
                            int copylen = topFilters.length - pos - 1;
                            if (copylen > 0) {
                                System.arraycopy(topFilters, pos, topFilters, pos + 1, copylen);
                            }
                            topFilters[pos] = fs;
                            if (len < topFilters.length) {
                                len++;
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < len; ++i) {
                final long token = proto.start(AlarmManagerServiceDumpProto.TOP_ALARMS);
                FilterStats fs = topFilters[i];

                proto.write(AlarmManagerServiceDumpProto.TopAlarm.UID, fs.mBroadcastStats.mUid);
                proto.write(AlarmManagerServiceDumpProto.TopAlarm.PACKAGE_NAME,
                        fs.mBroadcastStats.mPackageName);
                fs.dumpDebug(proto, AlarmManagerServiceDumpProto.TopAlarm.FILTER);

                proto.end(token);
            }

            final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
            for (int iu = 0; iu < mBroadcastStats.size(); ++iu) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip = 0; ip < uidStats.size(); ++ip) {
                    final long token = proto.start(AlarmManagerServiceDumpProto.ALARM_STATS);

                    BroadcastStats bs = uidStats.valueAt(ip);
                    bs.dumpDebug(proto, AlarmManagerServiceDumpProto.AlarmStat.BROADCAST);

                    // uidStats is an ArrayMap, which we can't sort.
                    tmpFilters.clear();
                    for (int is = 0; is < bs.filterStats.size(); ++is) {
                        tmpFilters.add(bs.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    for (FilterStats fs : tmpFilters) {
                        fs.dumpDebug(proto, AlarmManagerServiceDumpProto.AlarmStat.FILTERS);
                    }

                    proto.end(token);
                }
            }

            if (RECORD_DEVICE_IDLE_ALARMS) {
                for (int i = 0; i < mAllowWhileIdleDispatches.size(); i++) {
                    IdleDispatchEntry ent = mAllowWhileIdleDispatches.get(i);
                    final long token = proto.start(
                            AlarmManagerServiceDumpProto.ALLOW_WHILE_IDLE_DISPATCHES);

                    proto.write(IdleDispatchEntryProto.UID, ent.uid);
                    proto.write(IdleDispatchEntryProto.PKG, ent.pkg);
                    proto.write(IdleDispatchEntryProto.TAG, ent.tag);
                    proto.write(IdleDispatchEntryProto.OP, ent.op);
                    proto.write(IdleDispatchEntryProto.ENTRY_CREATION_REALTIME,
                            ent.elapsedRealtime);
                    proto.write(IdleDispatchEntryProto.ARG_REALTIME, ent.argRealtime);

                    proto.end(token);
                }
            }
        }

        proto.flush();
    }

    long getNextWakeFromIdleTimeImpl() {
        synchronized (mLock) {
            return mNextWakeFromIdle != null ? mNextWakeFromIdle.whenElapsed : Long.MAX_VALUE;
        }
    }

    private boolean isIdlingImpl() {
        synchronized (mLock) {
            return mPendingIdleUntil != null;
        }
    }

    AlarmManager.AlarmClockInfo getNextAlarmClockImpl(int userId) {
        synchronized (mLock) {
            return mNextAlarmClockForUser.get(userId);
        }
    }

    /**
     * Recomputes the next alarm clock for all users.
     */
    private void updateNextAlarmClockLocked() {
        if (!mNextAlarmClockMayChange) {
            return;
        }
        mNextAlarmClockMayChange = false;

        final SparseArray<AlarmManager.AlarmClockInfo> nextForUser = mTmpSparseAlarmClockArray;
        nextForUser.clear();

        final ArrayList<Alarm> allAlarms = mAlarmStore.asList();
        for (final Alarm a : allAlarms) {
            if (a.alarmClock != null) {
                final int userId = UserHandle.getUserId(a.uid);
                final AlarmManager.AlarmClockInfo current = mNextAlarmClockForUser.get(userId);

                if (DEBUG_ALARM_CLOCK) {
                    Log.v(TAG, "Found AlarmClockInfo " + a.alarmClock + " at "
                            + formatNextAlarm(getContext(), a.alarmClock, userId)
                            + " for user " + userId);
                }

                // AlarmClocks are sorted by time, so no need to compare times here.
                if (nextForUser.get(userId) == null) {
                    nextForUser.put(userId, a.alarmClock);
                } else if (a.alarmClock.equals(current)
                        && current.getTriggerTime() <= nextForUser.get(userId).getTriggerTime()) {
                    // same/earlier time and it's the one we cited before, so stick with it
                    nextForUser.put(userId, current);
                }
            }
        }

        final int newUserCount = nextForUser.size();
        for (int i = 0; i < newUserCount; i++) {
            AlarmManager.AlarmClockInfo newAlarm = nextForUser.valueAt(i);
            int userId = nextForUser.keyAt(i);
            AlarmManager.AlarmClockInfo currentAlarm = mNextAlarmClockForUser.get(userId);
            if (!newAlarm.equals(currentAlarm)) {
                updateNextAlarmInfoForUserLocked(userId, newAlarm);
            }
        }

        final int oldUserCount = mNextAlarmClockForUser.size();
        for (int i = oldUserCount - 1; i >= 0; i--) {
            int userId = mNextAlarmClockForUser.keyAt(i);
            if (nextForUser.get(userId) == null) {
                updateNextAlarmInfoForUserLocked(userId, null);
            }
        }
    }

    private void updateNextAlarmInfoForUserLocked(int userId,
            AlarmManager.AlarmClockInfo alarmClock) {
        if (alarmClock != null) {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): " +
                        formatNextAlarm(getContext(), alarmClock, userId));
            }
            mNextAlarmClockForUser.put(userId, alarmClock);
        } else {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): None");
            }
            mNextAlarmClockForUser.remove(userId);
        }

        mPendingSendNextAlarmClockChangedForUser.put(userId, true);
        mHandler.removeMessages(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
        mHandler.sendEmptyMessage(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
    }

    /**
     * Updates NEXT_ALARM_FORMATTED and sends NEXT_ALARM_CLOCK_CHANGED_INTENT for all users
     * for which alarm clocks have changed since the last call to this.
     *
     * Do not call with a lock held. Only call from mHandler's thread.
     *
     * @see AlarmHandler#SEND_NEXT_ALARM_CLOCK_CHANGED
     */
    private void sendNextAlarmClockChanged() {
        SparseArray<AlarmManager.AlarmClockInfo> pendingUsers = mHandlerSparseAlarmClockArray;
        pendingUsers.clear();

        synchronized (mLock) {
            final int n = mPendingSendNextAlarmClockChangedForUser.size();
            for (int i = 0; i < n; i++) {
                int userId = mPendingSendNextAlarmClockChangedForUser.keyAt(i);
                pendingUsers.append(userId, mNextAlarmClockForUser.get(userId));
            }
            mPendingSendNextAlarmClockChangedForUser.clear();
        }

        final int n = pendingUsers.size();
        for (int i = 0; i < n; i++) {
            int userId = pendingUsers.keyAt(i);
            AlarmManager.AlarmClockInfo alarmClock = pendingUsers.valueAt(i);
            Settings.System.putStringForUser(getContext().getContentResolver(),
                    Settings.System.NEXT_ALARM_FORMATTED,
                    formatNextAlarm(getContext(), alarmClock, userId),
                    userId);

            getContext().sendBroadcastAsUser(NEXT_ALARM_CLOCK_CHANGED_INTENT,
                    new UserHandle(userId));
        }
    }

    /**
     * Formats an alarm like platform/packages/apps/DeskClock used to.
     */
    private static String formatNextAlarm(final Context context, AlarmManager.AlarmClockInfo info,
            int userId) {
        String skeleton = DateFormat.is24HourFormat(context, userId) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return (info == null) ? "" :
                DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    void rescheduleKernelAlarmsLocked() {
        // Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
        // prior to that which contains no wakeups, we schedule that as well.
        final long nowElapsed = mInjector.getElapsedRealtime();
        long nextNonWakeup = 0;
        if (mAlarmStore.size() > 0) {
            final long firstWakeup = mAlarmStore.getNextWakeupDeliveryTime();
            final long first = mAlarmStore.getNextDeliveryTime();
            if (firstWakeup != 0) {
                mNextWakeup = firstWakeup;
                mNextWakeUpSetAt = nowElapsed;
                setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup);
            }
            if (first != firstWakeup) {
                nextNonWakeup = first;
            }
        }
        if (mPendingNonWakeupAlarms.size() > 0) {
            if (nextNonWakeup == 0 || mNextNonWakeupDeliveryTime < nextNonWakeup) {
                nextNonWakeup = mNextNonWakeupDeliveryTime;
            }
        }
        if (nextNonWakeup != 0) {
            mNextNonWakeup = nextNonWakeup;
            mNextNonWakeUpSetAt = nowElapsed;
            setLocked(ELAPSED_REALTIME, nextNonWakeup);
        }
    }

    void removeLocked(PendingIntent operation, IAlarmListener directReceiver) {
        if (operation == null && directReceiver == null) {
            if (localLOGV) {
                Slog.w(TAG, "requested remove() of null operation",
                        new RuntimeException("here"));
            }
            return;
        }

        final Predicate<Alarm> whichAlarms = (Alarm a) -> a.matches(operation, directReceiver);
        final ArrayList<Alarm> removedAlarms = mAlarmStore.remove(whichAlarms);
        for (final Alarm removed : removedAlarms) {
            decrementAlarmCount(removed.uid, 1);
        }
        final boolean didRemove = !removedAlarms.isEmpty();

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm alarm = mPendingWhileIdleAlarms.get(i);
            if (alarm.matches(operation, directReceiver)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
                decrementAlarmCount(alarm.uid, 1);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                final Alarm alarm = alarmsForUid.get(j);
                if (alarm.matches(operation, directReceiver)) {
                    // Don't set didRemove, since this doesn't impact the scheduled alarms.
                    alarmsForUid.remove(j);
                    decrementAlarmCount(alarm.uid, 1);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        for (int i = mPendingNonWakeupAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingNonWakeupAlarms.get(i);
            if (a.matches(operation, directReceiver)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingNonWakeupAlarms.remove(i);
                decrementAlarmCount(a.uid, 1);
            }
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(operation) changed bounds; rebatching");
            }
            boolean restorePending = false;
            if (mPendingIdleUntil != null && mPendingIdleUntil.matches(operation, directReceiver)) {
                mPendingIdleUntil = null;
                restorePending = true;
            }
            if (mNextWakeFromIdle != null && mNextWakeFromIdle.matches(operation, directReceiver)) {
                mNextWakeFromIdle = null;
                mAlarmStore.recalculateAlarmDeliveries(alarm -> {
                    if (alarm != mPendingIdleUntil) {
                        return false;
                    }
                    restoreAlarmLocked(alarm, mInjector.getElapsedRealtime());
                    return adjustIdleUntilTime(alarm);
                });
            }
            if (restorePending) {
                restorePendingWhileIdleAlarmsLocked();
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(final int uid) {
        if (uid == Process.SYSTEM_UID) {
            // If a force-stop occurs for a system-uid package, ignore it.
            return;
        }

        final Predicate<Alarm> whichAlarms = (Alarm a) -> a.uid == uid;
        final ArrayList<Alarm> removed = mAlarmStore.remove(whichAlarms);
        final boolean didRemove = !removed.isEmpty();
        if (didRemove) {
            decrementAlarmCount(uid, removed.size());
        }

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.uid == uid) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
                decrementAlarmCount(uid, 1);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).uid == uid) {
                    alarmsForUid.remove(j);
                    decrementAlarmCount(uid, 1);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        // If we're currently keying off of this app's alarms for doze transitions,
        // make sure to reset to other triggers.
        if (mNextWakeFromIdle != null && mNextWakeFromIdle.uid == uid) {
            mNextWakeFromIdle = null;
            mAlarmStore.recalculateAlarmDeliveries(alarm -> {
                if (alarm != mPendingIdleUntil) {
                    return false;
                }
                restoreAlarmLocked(alarm, mInjector.getElapsedRealtime());
                return adjustIdleUntilTime(alarm);
            });
        }
        if (mPendingIdleUntil != null && mPendingIdleUntil.uid == uid) {
            // Should never happen - only the system uid is allowed to set idle-until alarms
            Slog.wtf(TAG, "Removed app uid " + uid + " set idle-until alarm!");
            mPendingIdleUntil = null;
            restorePendingWhileIdleAlarmsLocked();
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(uid) changed bounds; rebatching");
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(final String packageName) {
        if (packageName == null) {
            if (localLOGV) {
                Slog.w(TAG, "requested remove() of null packageName",
                        new RuntimeException("here"));
            }
            return;
        }

        final MutableBoolean removedNextWakeFromIdle = new MutableBoolean(false);
        final Predicate<Alarm> whichAlarms = (Alarm a) -> {
            final boolean didMatch = a.matches(packageName);
            if (didMatch && a == mNextWakeFromIdle) {
                removedNextWakeFromIdle.value = true;
            }
            return didMatch;
        };

        final ArrayList<Alarm> removed = mAlarmStore.remove(whichAlarms);
        final boolean didRemove = !removed.isEmpty();
        if (didRemove) {
            decrementAlarmCount(removed.get(0).uid, removed.size());
        }

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.matches(packageName)) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
                decrementAlarmCount(a.uid, 1);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            final ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.valueAt(i);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                final Alarm alarm = alarmsForUid.get(j);
                if (alarm.matches(packageName)) {
                    alarmsForUid.remove(j);
                    decrementAlarmCount(alarm.uid, 1);
                }
            }
            if (alarmsForUid.size() == 0) {
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        // If we're currently keying off of this app's alarms for doze transitions,
        // make sure to reset to other triggers.
        if (removedNextWakeFromIdle.value) {
            mNextWakeFromIdle = null;
            mAlarmStore.recalculateAlarmDeliveries(alarm -> {
                if (alarm != mPendingIdleUntil) {
                    return false;
                }
                restoreAlarmLocked(alarm, mInjector.getElapsedRealtime());
                return adjustIdleUntilTime(alarm);
            });
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(package) changed bounds; rebatching");
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    // Only called for ephemeral apps
    void removeForStoppedLocked(final int uid) {
        if (uid == Process.SYSTEM_UID) {
            // If a force-stop occurs for a system-uid package, ignore it.
            return;
        }
        final Predicate<Alarm> whichAlarms = (a) -> (a.uid == uid
                && mActivityManagerInternal.isAppStartModeDisabled(uid, a.packageName));
        final ArrayList<Alarm> removed = mAlarmStore.remove(whichAlarms);
        final boolean didRemove = !removed.isEmpty();
        if (didRemove) {
            decrementAlarmCount(uid, removed.size());
        }

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.uid == uid) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                mPendingWhileIdleAlarms.remove(i);
                decrementAlarmCount(uid, 1);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            if (mPendingBackgroundAlarms.keyAt(i) == uid) {
                final ArrayList<Alarm> toRemove = mPendingBackgroundAlarms.valueAt(i);
                if (toRemove != null) {
                    decrementAlarmCount(uid, toRemove.size());
                }
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(package) changed bounds; rebatching");
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeUserLocked(int userHandle) {
        if (userHandle == USER_SYSTEM) {
            // If we're told we're removing the system user, ignore it.
            return;
        }
        final Predicate<Alarm> whichAlarms =
                (Alarm a) -> UserHandle.getUserId(a.creatorUid) == userHandle;
        final ArrayList<Alarm> removedAlarms = mAlarmStore.remove(whichAlarms);
        for (final Alarm removed : removedAlarms) {
            decrementAlarmCount(removed.uid, 1);
        }
        final boolean didRemove = !removedAlarms.isEmpty();

        for (int i = mPendingWhileIdleAlarms.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mPendingWhileIdleAlarms.get(i).creatorUid)
                    == userHandle) {
                // Don't set didRemove, since this doesn't impact the scheduled alarms.
                final Alarm removed = mPendingWhileIdleAlarms.remove(i);
                decrementAlarmCount(removed.uid, 1);
            }
        }
        for (int i = mPendingBackgroundAlarms.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mPendingBackgroundAlarms.keyAt(i)) == userHandle) {
                final ArrayList<Alarm> toRemove = mPendingBackgroundAlarms.valueAt(i);
                if (toRemove != null) {
                    for (int j = 0; j < toRemove.size(); j++) {
                        decrementAlarmCount(toRemove.get(j).uid, 1);
                    }
                }
                mPendingBackgroundAlarms.removeAt(i);
            }
        }
        for (int i = mLastAllowWhileIdleDispatch.size() - 1; i >= 0; i--) {
            if (UserHandle.getUserId(mLastAllowWhileIdleDispatch.keyAt(i)) == userHandle) {
                mLastAllowWhileIdleDispatch.removeAt(i);
            }
        }

        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(user) changed bounds; rebatching");
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void interactiveStateChangedLocked(boolean interactive) {
        if (mInteractive != interactive) {
            mInteractive = interactive;
            final long nowELAPSED = mInjector.getElapsedRealtime();
            if (interactive) {
                if (mPendingNonWakeupAlarms.size() > 0) {
                    final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                    mTotalDelayTime += thisDelayTime;
                    if (mMaxDelayTime < thisDelayTime) {
                        mMaxDelayTime = thisDelayTime;
                    }
                    deliverAlarmsLocked(mPendingNonWakeupAlarms, nowELAPSED);
                    mPendingNonWakeupAlarms.clear();
                }
                if (mNonInteractiveStartTime > 0) {
                    long dur = nowELAPSED - mNonInteractiveStartTime;
                    if (dur > mNonInteractiveTime) {
                        mNonInteractiveTime = dur;
                    }
                }
                // And send a TIME_TICK right now, since it is important to get the UI updated.
                mHandler.post(() ->
                        getContext().sendBroadcastAsUser(mTimeTickIntent, UserHandle.ALL));
            } else {
                mNonInteractiveStartTime = nowELAPSED;
            }
        }
    }

    boolean lookForPackageLocked(String packageName) {
        final ArrayList<Alarm> allAlarms = mAlarmStore.asList();
        for (final Alarm alarm : allAlarms) {
            if (alarm.matches(packageName)) {
                return true;
            }
        }
        for (int i = 0; i < mPendingWhileIdleAlarms.size(); i++) {
            final Alarm a = mPendingWhileIdleAlarms.get(i);
            if (a.matches(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void setLocked(int type, long when) {
        if (mInjector.isAlarmDriverPresent()) {
            mInjector.setAlarm(type, when);
        } else {
            Message msg = Message.obtain();
            msg.what = AlarmHandler.ALARM_EVENT;

            mHandler.removeMessages(msg.what);
            mHandler.sendMessageAtTime(msg, when);
        }
    }

    private static final String labelForType(int type) {
        switch (type) {
            case RTC:
                return "RTC";
            case RTC_WAKEUP:
                return "RTC_WAKEUP";
            case ELAPSED_REALTIME:
                return "ELAPSED";
            case ELAPSED_REALTIME_WAKEUP:
                return "ELAPSED_WAKEUP";
        }
        return "--unknown--";
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, long nowELAPSED, SimpleDateFormat sdf) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, prefix, prefix);
        dumpAlarmList(ipw, list, nowELAPSED, sdf);
    }

    static final void dumpAlarmList(IndentingPrintWriter ipw, ArrayList<Alarm> list,
            long nowELAPSED, SimpleDateFormat sdf) {
        for (int i = list.size() - 1; i >= 0; i--) {
            final Alarm a = list.get(i);
            final String label = labelForType(a.type);
            ipw.print(label);
            ipw.print(" #");
            ipw.print(i);
            ipw.print(": ");
            ipw.println(a);
            ipw.increaseIndent();
            a.dump(ipw, nowELAPSED, sdf);
            ipw.decreaseIndent();
        }
    }

    private boolean isBackgroundRestricted(Alarm alarm) {
        boolean exemptOnBatterySaver = (alarm.flags & FLAG_ALLOW_WHILE_IDLE) != 0;
        if (alarm.alarmClock != null) {
            // Don't defer alarm clocks
            return false;
        }
        if (alarm.operation != null) {
            if (alarm.operation.isActivity()) {
                // Don't defer starting actual UI
                return false;
            }
            if (alarm.operation.isForegroundService()) {
                // FG service alarms are nearly as important; consult AST policy
                exemptOnBatterySaver = true;
            }
        }
        final String sourcePackage = alarm.sourcePackage;
        final int sourceUid = alarm.creatorUid;
        return (mAppStateTracker != null) &&
                mAppStateTracker.areAlarmsRestricted(sourceUid, sourcePackage,
                        exemptOnBatterySaver);
    }

    private static native long init();
    private static native void close(long nativeData);
    private static native int set(long nativeData, int type, long seconds, long nanoseconds);
    private static native int waitForAlarm(long nativeData);
    private static native int setKernelTime(long nativeData, long millis);
    private static native int setKernelTimezone(long nativeData, int minuteswest);
    private static native long getNextAlarm(long nativeData, int type);

    private long getWhileIdleMinIntervalLocked(int uid) {
        final boolean dozing = mPendingIdleUntil != null;
        final boolean ebs = (mAppStateTracker != null)
                && mAppStateTracker.isForceAllAppsStandbyEnabled();
        if (!dozing && !ebs) {
            return mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        if (dozing) {
            return mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
        }
        if (mUseAllowWhileIdleShortTime.get(uid)) {
            // if the last allow-while-idle went off while uid was fg, or the uid
            // recently came into fg, don't block the alarm for long.
            return mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        return mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
    }

    boolean triggerAlarmsLocked(ArrayList<Alarm> triggerList, final long nowELAPSED) {
        boolean hasWakeup = false;
        final ArrayList<Alarm> pendingAlarms = mAlarmStore.removePendingAlarms(nowELAPSED);
        for (final Alarm alarm : pendingAlarms) {
            if ((alarm.flags & AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0) {
                // If this is an ALLOW_WHILE_IDLE alarm, we constrain how frequently the app can
                // schedule such alarms.  The first such alarm from an app is always delivered.
                final long lastTime = mLastAllowWhileIdleDispatch.get(alarm.creatorUid, -1);
                final long minTime = lastTime + getWhileIdleMinIntervalLocked(alarm.creatorUid);
                if (lastTime >= 0 && nowELAPSED < minTime) {
                    // Whoops, it hasn't been long enough since the last ALLOW_WHILE_IDLE
                    // alarm went off for this app.  Reschedule the alarm to be in the
                    // correct time period.
                    alarm.expectedWhenElapsed = alarm.whenElapsed = minTime;
                    if (alarm.maxWhenElapsed < minTime) {
                        alarm.maxWhenElapsed = minTime;
                    }
                    alarm.expectedMaxWhenElapsed = alarm.maxWhenElapsed;
                    if (RECORD_DEVICE_IDLE_ALARMS) {
                        IdleDispatchEntry ent = new IdleDispatchEntry();
                        ent.uid = alarm.uid;
                        ent.pkg = alarm.operation.getCreatorPackage();
                        ent.tag = alarm.operation.getTag("");
                        ent.op = "RESCHEDULE";
                        ent.elapsedRealtime = nowELAPSED;
                        ent.argRealtime = lastTime;
                        mAllowWhileIdleDispatches.add(ent);
                    }
                    setImplLocked(alarm);
                    continue;
                }
            }
            if (isBackgroundRestricted(alarm)) {
                // Alarms with FLAG_WAKE_FROM_IDLE or mPendingIdleUntil alarm are not deferred
                if (DEBUG_BG_LIMIT) {
                    Slog.d(TAG, "Deferring alarm " + alarm + " due to user forced app standby");
                }
                ArrayList<Alarm> alarmsForUid = mPendingBackgroundAlarms.get(alarm.creatorUid);
                if (alarmsForUid == null) {
                    alarmsForUid = new ArrayList<>();
                    mPendingBackgroundAlarms.put(alarm.creatorUid, alarmsForUid);
                }
                alarmsForUid.add(alarm);
                continue;
            }

            alarm.count = 1;
            triggerList.add(alarm);
            if ((alarm.flags & AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
                EventLogTags.writeDeviceIdleWakeFromIdle(mPendingIdleUntil != null ? 1 : 0,
                        alarm.statsTag);
            }
            if (mPendingIdleUntil == alarm) {
                mPendingIdleUntil = null;
                restorePendingWhileIdleAlarmsLocked();
            }
            if (mNextWakeFromIdle == alarm) {
                mNextWakeFromIdle = null;
                mAlarmStore.recalculateAlarmDeliveries(a -> {
                    if (a != mPendingIdleUntil) {
                        return false;
                    }
                    restoreAlarmLocked(a, nowELAPSED);
                    return adjustIdleUntilTime(a);
                });
            }

            // Recurring alarms may have passed several alarm intervals while the
            // phone was asleep or off, so pass a trigger count when sending them.
            if (alarm.repeatInterval > 0) {
                // this adjustment will be zero if we're late by
                // less than one full repeat interval
                alarm.count += (nowELAPSED - alarm.expectedWhenElapsed) / alarm.repeatInterval;
                // Also schedule its next recurrence
                final long delta = alarm.count * alarm.repeatInterval;
                final long nextElapsed = alarm.expectedWhenElapsed + delta;
                setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                        maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
                        alarm.repeatInterval, alarm.operation, null, null, alarm.flags,
                        alarm.workSource, alarm.alarmClock, alarm.uid, alarm.packageName);
            }

            if (alarm.wakeup) {
                hasWakeup = true;
            }

            // We removed an alarm clock. Let the caller recompute the next alarm clock.
            if (alarm.alarmClock != null) {
                mNextAlarmClockMayChange = true;
            }
        }

        // This is a new alarm delivery set; bump the sequence number to indicate that
        // all apps' alarm delivery classes should be recalculated.
        mCurrentSeq++;
        calculateDeliveryPriorities(triggerList);
        Collections.sort(triggerList, mAlarmDispatchComparator);

        if (localLOGV) {
            for (int i = 0; i < triggerList.size(); i++) {
                Slog.v(TAG, "Triggering alarm #" + i + ": " + triggerList.get(i));
            }
        }

        return hasWakeup;
    }

    long currentNonWakeupFuzzLocked(long nowELAPSED) {
        long timeSinceOn = nowELAPSED - mNonInteractiveStartTime;
        if (timeSinceOn < 5 * 60 * 1000) {
            // If the screen has been off for 5 minutes, only delay by at most two minutes.
            return 2 * 60 * 1000;
        } else if (timeSinceOn < 30 * 60 * 1000) {
            // If the screen has been off for 30 minutes, only delay by at most 15 minutes.
            return 15 * 60 * 1000;
        } else {
            // Otherwise, we will delay by at most an hour.
            return 60 * 60 * 1000;
        }
    }

    static int fuzzForDuration(long duration) {
        if (duration < 15 * 60 * 1000) {
            // If the duration until the time is less than 15 minutes, the maximum fuzz
            // is the duration.
            return (int) duration;
        } else if (duration < 90 * 60 * 1000) {
            // If duration is less than 1 1/2 hours, the maximum fuzz is 15 minutes,
            return 15 * 60 * 1000;
        } else {
            // Otherwise, we will fuzz by at most half an hour.
            return 30 * 60 * 1000;
        }
    }

    boolean checkAllowNonWakeupDelayLocked(long nowELAPSED) {
        if (mInteractive) {
            return false;
        }
        if (mLastAlarmDeliveryTime <= 0) {
            return false;
        }
        if (mPendingNonWakeupAlarms.size() > 0 && mNextNonWakeupDeliveryTime < nowELAPSED) {
            // This is just a little paranoia, if somehow we have pending non-wakeup alarms
            // and the next delivery time is in the past, then just deliver them all.  This
            // avoids bugs where we get stuck in a loop trying to poll for alarms.
            return false;
        }
        long timeSinceLast = nowELAPSED - mLastAlarmDeliveryTime;
        return timeSinceLast <= currentNonWakeupFuzzLocked(nowELAPSED);
    }

    void deliverAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED) {
        mLastAlarmDeliveryTime = nowELAPSED;
        for (int i = 0; i < triggerList.size(); i++) {
            Alarm alarm = triggerList.get(i);
            final boolean allowWhileIdle = (alarm.flags & AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0;
            if (alarm.wakeup) {
                Trace.traceBegin(Trace.TRACE_TAG_POWER,
                        "Dispatch wakeup alarm to " + alarm.packageName);
            } else {
                Trace.traceBegin(Trace.TRACE_TAG_POWER,
                        "Dispatch non-wakeup alarm to " + alarm.packageName);
            }
            try {
                if (localLOGV) {
                    Slog.v(TAG, "sending alarm " + alarm);
                }
                if (RECORD_ALARMS_IN_HISTORY) {
                    mActivityManagerInternal.noteAlarmStart(alarm.operation, alarm.workSource,
                            alarm.uid, alarm.statsTag);
                }
                mDeliveryTracker.deliverLocked(alarm, nowELAPSED, allowWhileIdle);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure sending alarm.", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
            decrementAlarmCount(alarm.uid, 1);
        }
    }

    private boolean isExemptFromAppStandby(Alarm a) {
        return a.alarmClock != null || UserHandle.isCore(a.creatorUid)
                || (a.flags & FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED) != 0;
    }

    @VisibleForTesting
    static class Injector {
        private long mNativeData;
        private Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        void init() {
            System.loadLibrary("alarm_jni");
            mNativeData = AlarmManagerService.init();
        }

        int waitForAlarm() {
            return AlarmManagerService.waitForAlarm(mNativeData);
        }

        boolean isAlarmDriverPresent() {
            return mNativeData != 0;
        }

        void setAlarm(int type, long millis) {
            // The kernel never triggers alarms with negative wakeup times
            // so we ensure they are positive.
            final long alarmSeconds, alarmNanoseconds;
            if (millis < 0) {
                alarmSeconds = 0;
                alarmNanoseconds = 0;
            } else {
                alarmSeconds = millis / 1000;
                alarmNanoseconds = (millis % 1000) * 1000 * 1000;
            }

            final int result = AlarmManagerService.set(mNativeData, type, alarmSeconds,
                    alarmNanoseconds);
            if (result != 0) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                Slog.wtf(TAG, "Unable to set kernel alarm, now=" + nowElapsed
                        + " type=" + type + " @ (" + alarmSeconds + "," + alarmNanoseconds
                        + "), ret = " + result + " = " + Os.strerror(result));
            }
        }

        long getNextAlarm(int type) {
            return AlarmManagerService.getNextAlarm(mNativeData, type);
        }

        void setKernelTimezone(int minutesWest) {
            AlarmManagerService.setKernelTimezone(mNativeData, minutesWest);
        }

        void setKernelTime(long millis) {
            if (mNativeData != 0) {
                AlarmManagerService.setKernelTime(mNativeData, millis);
            }
        }

        void close() {
            AlarmManagerService.close(mNativeData);
        }

        long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        long getCurrentTimeMillis() {
            return System.currentTimeMillis();
        }

        PowerManager.WakeLock getAlarmWakeLock() {
            final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*alarm*");
        }

        int getSystemUiUid() {
            PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
            return pm.getPackageUid(pm.getSystemUiServiceComponent().getPackageName(),
                    MATCH_SYSTEM_ONLY, USER_SYSTEM);
        }

        ClockReceiver getClockReceiver(AlarmManagerService service) {
            return service.new ClockReceiver();
        }

        void registerDeviceConfigListener(DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ALARM_MANAGER,
                    JobSchedulerBackgroundThread.getExecutor(), listener);
        }
    }

    private class AlarmThread extends Thread {
        private int mFalseWakeups;
        private int mWtfThreshold;

        AlarmThread() {
            super("AlarmManager");
            mFalseWakeups = 0;
            mWtfThreshold = 100;
        }

        public void run() {
            ArrayList<Alarm> triggerList = new ArrayList<Alarm>();

            while (true) {
                int result = mInjector.waitForAlarm();
                final long nowRTC = mInjector.getCurrentTimeMillis();
                final long nowELAPSED = mInjector.getElapsedRealtime();
                synchronized (mLock) {
                    mLastWakeup = nowELAPSED;
                }
                if (result == 0) {
                    Slog.wtf(TAG, "waitForAlarm returned 0, nowRTC = " + nowRTC
                            + ", nowElapsed = " + nowELAPSED);
                }
                triggerList.clear();

                if ((result & TIME_CHANGED_MASK) != 0) {
                    // The kernel can give us spurious time change notifications due to
                    // small adjustments it makes internally; we want to filter those out.
                    final long lastTimeChangeClockTime;
                    final long expectedClockTime;
                    synchronized (mLock) {
                        lastTimeChangeClockTime = mLastTimeChangeClockTime;
                        expectedClockTime = lastTimeChangeClockTime
                                + (nowELAPSED - mLastTimeChangeRealtime);
                    }
                    if (lastTimeChangeClockTime == 0 || nowRTC < (expectedClockTime - 1000)
                            || nowRTC > (expectedClockTime + 1000)) {
                        // The change is by at least +/- 1000 ms (or this is the first change),
                        // let's do it!
                        if (DEBUG_BATCH) {
                            Slog.v(TAG, "Time changed notification from kernel; rebatching");
                        }
                        // StatsLog requires currentTimeMillis(), which == nowRTC to within usecs.
                        FrameworkStatsLog.write(FrameworkStatsLog.WALL_CLOCK_TIME_SHIFTED, nowRTC);
                        removeImpl(null, mTimeTickTrigger);
                        removeImpl(mDateChangeSender, null);
                        reevaluateRtcAlarms(nowELAPSED);
                        mClockReceiver.scheduleTimeTickEvent();
                        mClockReceiver.scheduleDateChangedEvent();
                        synchronized (mLock) {
                            mNumTimeChanged++;
                            mLastTimeChangeClockTime = nowRTC;
                            mLastTimeChangeRealtime = nowELAPSED;
                        }
                        Intent intent = new Intent(Intent.ACTION_TIME_CHANGED);
                        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                                | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                                | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
                        getContext().sendBroadcastAsUser(intent, UserHandle.ALL);

                        // The world has changed on us, so we need to re-evaluate alarms
                        // regardless of whether the kernel has told us one went off.
                        result |= IS_WAKEUP_MASK;
                    }
                }

                if (result != TIME_CHANGED_MASK) {
                    // If this was anything besides just a time change, then figure what if
                    // anything to do about alarms.
                    synchronized (mLock) {
                        if (localLOGV) {
                            Slog.v(TAG, "Checking for alarms... rtc=" + nowRTC
                                            + ", elapsed=" + nowELAPSED);
                        }

                        mLastTrigger = nowELAPSED;
                        boolean hasWakeup = triggerAlarmsLocked(triggerList, nowELAPSED);
                        if (!hasWakeup && checkAllowNonWakeupDelayLocked(nowELAPSED)) {
                            // if there are no wakeup alarms and the screen is off, we can
                            // delay what we have so far until the future.
                            if (mPendingNonWakeupAlarms.size() == 0) {
                                mStartCurrentDelayTime = nowELAPSED;
                                mNextNonWakeupDeliveryTime = nowELAPSED
                                        + ((currentNonWakeupFuzzLocked(nowELAPSED) * 3) / 2);
                            }
                            mPendingNonWakeupAlarms.addAll(triggerList);
                            mNumDelayedAlarms += triggerList.size();
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        } else {
                            // now deliver the alarm intents; if there are pending non-wakeup
                            // alarms, we need to merge them in to the list.  note we don't
                            // just deliver them first because we generally want non-wakeup
                            // alarms delivered after wakeup alarms.
                            if (mPendingNonWakeupAlarms.size() > 0) {
                                calculateDeliveryPriorities(mPendingNonWakeupAlarms);
                                triggerList.addAll(mPendingNonWakeupAlarms);
                                Collections.sort(triggerList, mAlarmDispatchComparator);
                                final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                                mTotalDelayTime += thisDelayTime;
                                if (mMaxDelayTime < thisDelayTime) {
                                    mMaxDelayTime = thisDelayTime;
                                }
                                mPendingNonWakeupAlarms.clear();
                            }
                            if (mLastTimeChangeRealtime != nowELAPSED && triggerList.isEmpty()) {
                                if (++mFalseWakeups >= mWtfThreshold) {
                                    Slog.wtf(TAG, "Too many (" + mFalseWakeups
                                            + ") false wakeups, nowElapsed=" + nowELAPSED);
                                    if (mWtfThreshold < 100_000) {
                                        mWtfThreshold *= 10;
                                    } else {
                                        mFalseWakeups = 0;
                                    }
                                }
                            }
                            final ArraySet<Pair<String, Integer>> triggerPackages =
                                    new ArraySet<>();
                            for (int i = 0; i < triggerList.size(); i++) {
                                final Alarm a = triggerList.get(i);
                                if (!isExemptFromAppStandby(a)) {
                                    triggerPackages.add(Pair.create(
                                            a.sourcePackage, UserHandle.getUserId(a.creatorUid)));
                                }
                            }
                            deliverAlarmsLocked(triggerList, nowELAPSED);
                            reorderAlarmsBasedOnStandbyBuckets(triggerPackages);
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }

                } else {
                    // Just in case -- even though no wakeup flag was set, make sure
                    // we have updated the kernel to the next alarm time.
                    synchronized (mLock) {
                        rescheduleKernelAlarmsLocked();
                    }
                }
            }
        }
    }

    /**
     * Attribute blame for a WakeLock.
     *
     * @param ws       WorkSource to attribute blame.
     * @param knownUid attribution uid; < 0 values are ignored.
     */
    void setWakelockWorkSource(WorkSource ws, int knownUid, String tag, boolean first) {
        try {
            mWakeLock.setHistoryTag(first ? tag : null);

            if (ws != null) {
                mWakeLock.setWorkSource(ws);
                return;
            }

            if (knownUid >= 0) {
                mWakeLock.setWorkSource(new WorkSource(knownUid));
                return;
            }
        } catch (Exception e) {
        }

        // Something went wrong; fall back to attributing the lock to the OS
        mWakeLock.setWorkSource(null);
    }

    private static int getAlarmAttributionUid(Alarm alarm) {
        if (alarm.workSource != null && !alarm.workSource.isEmpty()) {
            return alarm.workSource.getAttributionUid();
        }

        return alarm.creatorUid;
    }

    @VisibleForTesting
    class AlarmHandler extends Handler {
        public static final int ALARM_EVENT = 1;
        public static final int SEND_NEXT_ALARM_CLOCK_CHANGED = 2;
        public static final int LISTENER_TIMEOUT = 3;
        public static final int REPORT_ALARMS_ACTIVE = 4;
        public static final int APP_STANDBY_BUCKET_CHANGED = 5;
        public static final int CHARGING_STATUS_CHANGED = 6;
        public static final int REMOVE_FOR_CANCELED = 7;

        AlarmHandler() {
            super(Looper.myLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALARM_EVENT: {
                    // This code is used when the kernel timer driver is not available, which
                    // shouldn't happen. Here, we try our best to simulate it, which may be useful
                    // when porting Android to a new device. Note that we can't wake up a device
                    // this way, so WAKE_UP alarms will be delivered only when the device is awake.
                    ArrayList<Alarm> triggerList = new ArrayList<Alarm>();
                    synchronized (mLock) {
                        final long nowELAPSED = mInjector.getElapsedRealtime();
                        triggerAlarmsLocked(triggerList, nowELAPSED);
                        updateNextAlarmClockLocked();
                    }

                    // now trigger the alarms without the lock held
                    for (int i = 0; i < triggerList.size(); i++) {
                        Alarm alarm = triggerList.get(i);
                        try {
                            alarm.operation.send();
                        } catch (PendingIntent.CanceledException e) {
                            if (alarm.repeatInterval > 0) {
                                // This IntentSender is no longer valid, but this
                                // is a repeating alarm, so toss the hoser.
                                removeImpl(alarm.operation, null);
                            }
                        }
                        decrementAlarmCount(alarm.uid, 1);
                    }
                    break;
                }

                case SEND_NEXT_ALARM_CLOCK_CHANGED:
                    sendNextAlarmClockChanged();
                    break;

                case LISTENER_TIMEOUT:
                    mDeliveryTracker.alarmTimedOut((IBinder) msg.obj);
                    break;

                case REPORT_ALARMS_ACTIVE:
                    if (mLocalDeviceIdleController != null) {
                        mLocalDeviceIdleController.setAlarmsActive(msg.arg1 != 0);
                    }
                    break;

                case CHARGING_STATUS_CHANGED:
                    synchronized (mLock) {
                        mAppStandbyParole = (Boolean) msg.obj;
                        if (reorderAlarmsBasedOnStandbyBuckets(null)) {
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }
                    break;

                case APP_STANDBY_BUCKET_CHANGED:
                    synchronized (mLock) {
                        final ArraySet<Pair<String, Integer>> filterPackages = new ArraySet<>();
                        filterPackages.add(Pair.create((String) msg.obj, msg.arg1));
                        if (reorderAlarmsBasedOnStandbyBuckets(filterPackages)) {
                            rescheduleKernelAlarmsLocked();
                            updateNextAlarmClockLocked();
                        }
                    }
                    break;

                case REMOVE_FOR_CANCELED:
                    final PendingIntent operation = (PendingIntent) msg.obj;
                    synchronized (mLock) {
                        removeLocked(operation, null);
                    }
                    break;

                default:
                    // nope, just ignore it
                    break;
            }
        }
    }

    @VisibleForTesting
    class ChargingReceiver extends BroadcastReceiver {
        ChargingReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BatteryManager.ACTION_CHARGING);
            filter.addAction(BatteryManager.ACTION_DISCHARGING);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final boolean charging;
            if (BatteryManager.ACTION_CHARGING.equals(action)) {
                if (DEBUG_STANDBY) {
                    Slog.d(TAG, "Device is charging.");
                }
                charging = true;
            } else {
                if (DEBUG_STANDBY) {
                    Slog.d(TAG, "Disconnected from power.");
                }
                charging = false;
            }
            mHandler.removeMessages(AlarmHandler.CHARGING_STATUS_CHANGED);
            mHandler.obtainMessage(AlarmHandler.CHARGING_STATUS_CHANGED, charging)
                    .sendToTarget();
        }
    }

    @VisibleForTesting
    class ClockReceiver extends BroadcastReceiver {
        public ClockReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_DATE_CHANGED)) {
                // Since the kernel does not keep track of DST, we need to
                // reset the TZ information at the beginning of each day
                // based off of the current Zone gmt offset + userspace tracked
                // daylight savings information.
                TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(TIMEZONE_PROPERTY));
                int gmtOffset = zone.getOffset(mInjector.getCurrentTimeMillis());
                mInjector.setKernelTimezone(-(gmtOffset / 60000));
                scheduleDateChangedEvent();
            }
        }

        public void scheduleTimeTickEvent() {
            final long currentTime = mInjector.getCurrentTimeMillis();
            final long nextTime = 60000 * ((currentTime / 60000) + 1);

            // Schedule this event for the amount of time that it would take to get to
            // the top of the next minute.
            final long tickEventDelay = nextTime - currentTime;

            final WorkSource workSource = null; // Let system take blame for time tick events.
            setImpl(ELAPSED_REALTIME, mInjector.getElapsedRealtime() + tickEventDelay, 0,
                    0, null, mTimeTickTrigger, TIME_TICK_TAG, AlarmManager.FLAG_STANDALONE,
                    workSource, null, Process.myUid(), "android");

            // Finally, remember when we set the tick alarm
            synchronized (mLock) {
                mLastTickSet = currentTime;
            }
        }

        public void scheduleDateChangedEvent() {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(mInjector.getCurrentTimeMillis());
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);

            final WorkSource workSource = null; // Let system take blame for date change events.
            setImpl(RTC, calendar.getTimeInMillis(), 0, 0, mDateChangeSender, null, null,
                    AlarmManager.FLAG_STANDALONE, workSource, null,
                    Process.myUid(), "android");
        }
    }

    class InteractiveStateReceiver extends BroadcastReceiver {
        public InteractiveStateReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                interactiveStateChangedLocked(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }
        }
    }

    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
            getContext().registerReceiver(this, filter);
            // Register for events related to sdcard installation.
            IntentFilter sdFilter = new IntentFilter();
            sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            sdFilter.addAction(Intent.ACTION_USER_STOPPED);
            sdFilter.addAction(Intent.ACTION_UID_REMOVED);
            getContext().registerReceiver(this, sdFilter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            synchronized (mLock) {
                String pkgList[] = null;
                switch (intent.getAction()) {
                    case Intent.ACTION_QUERY_PACKAGE_RESTART:
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                        for (String packageName : pkgList) {
                            if (lookForPackageLocked(packageName)) {
                                setResultCode(Activity.RESULT_OK);
                                return;
                            }
                        }
                        return;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        break;
                    case Intent.ACTION_USER_STOPPED:
                        final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userHandle >= 0) {
                            removeUserLocked(userHandle);
                            mAppWakeupHistory.removeForUser(userHandle);
                        }
                        return;
                    case Intent.ACTION_UID_REMOVED:
                        if (uid >= 0) {
                            mLastAllowWhileIdleDispatch.delete(uid);
                            mUseAllowWhileIdleShortTime.delete(uid);
                        }
                        return;
                    case Intent.ACTION_PACKAGE_REMOVED:
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            // This package is being updated; don't kill its alarms.
                            return;
                        }
                        // Intentional fall-through.
                    case Intent.ACTION_PACKAGE_RESTARTED:
                        final Uri data = intent.getData();
                        if (data != null) {
                            final String pkg = data.getSchemeSpecificPart();
                            if (pkg != null) {
                                pkgList = new String[]{pkg};
                            }
                        }
                        break;
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkg : pkgList) {
                        if (uid >= 0) {
                            // package-removed and package-restarted case
                            mAppWakeupHistory.removeForPackage(pkg, UserHandle.getUserId(uid));
                            removeLocked(uid);
                        } else {
                            // external-applications-unavailable case
                            removeLocked(pkg);
                        }
                        mPriorities.remove(pkg);
                        for (int i = mBroadcastStats.size() - 1; i >= 0; i--) {
                            ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(i);
                            if (uidStats.remove(pkg) != null) {
                                if (uidStats.size() <= 0) {
                                    mBroadcastStats.removeAt(i);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Tracking of app assignments to standby buckets
     */
    private final class AppStandbyTracker extends AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
                boolean idle, int bucket, int reason) {
            if (DEBUG_STANDBY) {
                Slog.d(TAG, "Package " + packageName + " for user " + userId + " now in bucket " +
                        bucket);
            }
            mHandler.removeMessages(AlarmHandler.APP_STANDBY_BUCKET_CHANGED);
            mHandler.obtainMessage(AlarmHandler.APP_STANDBY_BUCKET_CHANGED, userId, -1, packageName)
                    .sendToTarget();
        }
    }

    private final Listener mForceAppStandbyListener = new Listener() {
        @Override
        public void unblockAllUnrestrictedAlarms() {
            synchronized (mLock) {
                sendAllUnrestrictedPendingBackgroundAlarmsLocked();
            }
        }

        @Override
        public void unblockAlarmsForUid(int uid) {
            synchronized (mLock) {
                sendPendingBackgroundAlarmsLocked(uid, null);
            }
        }

        @Override
        public void unblockAlarmsForUidPackage(int uid, String packageName) {
            synchronized (mLock) {
                sendPendingBackgroundAlarmsLocked(uid, packageName);
            }
        }

        @Override
        public void onUidForeground(int uid, boolean foreground) {
            synchronized (mLock) {
                if (foreground) {
                    mUseAllowWhileIdleShortTime.put(uid, true);

                    // Note we don't have to drain the pending while-idle alarms here, because
                    // this event should coincide with unblockAlarmsForUid().
                }
            }
        }

        @Override
        public void removeAlarmsForUid(int uid) {
            synchronized (mLock) {
                removeForStoppedLocked(uid);
            }
        }
    };

    private final BroadcastStats getStatsLocked(PendingIntent pi) {
        String pkg = pi.getCreatorPackage();
        int uid = pi.getCreatorUid();
        return getStatsLocked(uid, pkg);
    }

    private final BroadcastStats getStatsLocked(int uid, String pkgName) {
        ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.get(uid);
        if (uidStats == null) {
            uidStats = new ArrayMap<String, BroadcastStats>();
            mBroadcastStats.put(uid, uidStats);
        }
        BroadcastStats bs = uidStats.get(pkgName);
        if (bs == null) {
            bs = new BroadcastStats(uid, pkgName);
            uidStats.put(pkgName, bs);
        }
        return bs;
    }

    /**
     * Canonical count of (operation.send() - onSendFinished()) and
     * listener send/complete/timeout invocations.
     * Guarded by the usual lock.
     */
    @GuardedBy("mLock")
    private int mSendCount = 0;
    @GuardedBy("mLock")
    private int mSendFinishCount = 0;
    @GuardedBy("mLock")
    private int mListenerCount = 0;
    @GuardedBy("mLock")
    private int mListenerFinishCount = 0;

    class DeliveryTracker extends IAlarmCompleteListener.Stub implements PendingIntent.OnFinished {

        private InFlight removeLocked(PendingIntent pi, Intent intent) {
            for (int i = 0; i < mInFlight.size(); i++) {
                final InFlight inflight = mInFlight.get(i);
                if (inflight.mPendingIntent == pi) {
                    if (pi.isBroadcast()) {
                        notifyBroadcastAlarmCompleteLocked(inflight.mUid);
                    }
                    return mInFlight.remove(i);
                }
            }
            mLog.w("No in-flight alarm for " + pi + " " + intent);
            return null;
        }

        private InFlight removeLocked(IBinder listener) {
            for (int i = 0; i < mInFlight.size(); i++) {
                if (mInFlight.get(i).mListener == listener) {
                    return mInFlight.remove(i);
                }
            }
            mLog.w("No in-flight alarm for listener " + listener);
            return null;
        }

        private void updateStatsLocked(InFlight inflight) {
            final long nowELAPSED = mInjector.getElapsedRealtime();
            BroadcastStats bs = inflight.mBroadcastStats;
            bs.nesting--;
            if (bs.nesting <= 0) {
                bs.nesting = 0;
                bs.aggregateTime += nowELAPSED - bs.startTime;
            }
            FilterStats fs = inflight.mFilterStats;
            fs.nesting--;
            if (fs.nesting <= 0) {
                fs.nesting = 0;
                fs.aggregateTime += nowELAPSED - fs.startTime;
            }
            if (RECORD_ALARMS_IN_HISTORY) {
                mActivityManagerInternal.noteAlarmFinish(inflight.mPendingIntent,
                        inflight.mWorkSource, inflight.mUid, inflight.mTag);
            }
        }

        private void updateTrackingLocked(InFlight inflight) {
            if (inflight != null) {
                updateStatsLocked(inflight);
            }
            mBroadcastRefCount--;
            if (DEBUG_WAKELOCK) {
                Slog.d(TAG, "mBroadcastRefCount -> " + mBroadcastRefCount);
            }
            if (mBroadcastRefCount == 0) {
                mHandler.obtainMessage(AlarmHandler.REPORT_ALARMS_ACTIVE, 0).sendToTarget();
                mWakeLock.release();
                if (mInFlight.size() > 0) {
                    mLog.w("Finished all dispatches with " + mInFlight.size()
                            + " remaining inflights");
                    for (int i = 0; i < mInFlight.size(); i++) {
                        mLog.w("  Remaining #" + i + ": " + mInFlight.get(i));
                    }
                    mInFlight.clear();
                }
            } else {
                // the next of our alarms is now in flight.  reattribute the wakelock.
                if (mInFlight.size() > 0) {
                    InFlight inFlight = mInFlight.get(0);
                    setWakelockWorkSource(inFlight.mWorkSource, inFlight.mCreatorUid, inFlight.mTag,
                            false);
                } else {
                    // should never happen
                    mLog.w("Alarm wakelock still held but sent queue empty");
                    mWakeLock.setWorkSource(null);
                }
            }
        }

        /**
         * Callback that arrives when a direct-call alarm reports that delivery has finished
         */
        @Override
        public void alarmComplete(IBinder who) {
            if (who == null) {
                mLog.w("Invalid alarmComplete: uid=" + Binder.getCallingUid()
                        + " pid=" + Binder.getCallingPid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mHandler.removeMessages(AlarmHandler.LISTENER_TIMEOUT, who);
                    InFlight inflight = removeLocked(who);
                    if (inflight != null) {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "alarmComplete() from " + who);
                        }
                        updateTrackingLocked(inflight);
                        mListenerFinishCount++;
                    } else {
                        // Delivery timed out, and the timeout handling already took care of
                        // updating our tracking here, so we needn't do anything further.
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "Late alarmComplete() from " + who);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Callback that arrives when a PendingIntent alarm has finished delivery
         */
        @Override
        public void onSendFinished(PendingIntent pi, Intent intent, int resultCode,
                String resultData, Bundle resultExtras) {
            synchronized (mLock) {
                mSendFinishCount++;
                updateTrackingLocked(removeLocked(pi, intent));
            }
        }

        /**
         * Timeout of a direct-call alarm delivery
         */
        public void alarmTimedOut(IBinder who) {
            synchronized (mLock) {
                InFlight inflight = removeLocked(who);
                if (inflight != null) {
                    // TODO: implement ANR policy for the target
                    if (DEBUG_LISTENER_CALLBACK) {
                        Slog.i(TAG, "Alarm listener " + who + " timed out in delivery");
                    }
                    updateTrackingLocked(inflight);
                    mListenerFinishCount++;
                } else {
                    if (DEBUG_LISTENER_CALLBACK) {
                        Slog.i(TAG, "Spurious timeout of listener " + who);
                    }
                    mLog.w("Spurious timeout of listener " + who);
                }
            }
        }

        /**
         * Deliver an alarm and set up the post-delivery handling appropriately
         */
        @GuardedBy("mLock")
        public void deliverLocked(Alarm alarm, long nowELAPSED, boolean allowWhileIdle) {
            final long workSourceToken = ThreadLocalWorkSource.setUid(
                    getAlarmAttributionUid(alarm));
            try {
                if (alarm.operation != null) {
                    // PendingIntent alarm
                    mSendCount++;

                    try {
                        alarm.operation.send(getContext(), 0,
                                mBackgroundIntent.putExtra(
                                        Intent.EXTRA_ALARM_COUNT, alarm.count),
                                mDeliveryTracker, mHandler, null,
                                allowWhileIdle ? mIdleOptions : null);
                    } catch (PendingIntent.CanceledException e) {
                        if (alarm.repeatInterval > 0) {
                            // This IntentSender is no longer valid, but this
                            // is a repeating alarm, so toss it
                            removeImpl(alarm.operation, null);
                        }
                        // No actual delivery was possible, so the delivery tracker's
                        // 'finished' callback won't be invoked.  We also don't need
                        // to do any wakelock or stats tracking, so we have nothing
                        // left to do here but go on to the next thing.
                        mSendFinishCount++;
                        return;
                    }
                } else {
                    // Direct listener callback alarm
                    mListenerCount++;

                    if (RECORD_ALARMS_IN_HISTORY) {
                        if (alarm.listener == mTimeTickTrigger) {
                            mTickHistory[mNextTickHistory++] = nowELAPSED;
                            if (mNextTickHistory >= TICK_HISTORY_DEPTH) {
                                mNextTickHistory = 0;
                            }
                        }
                    }

                    try {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.v(TAG, "Alarm to uid=" + alarm.uid
                                    + " listener=" + alarm.listener.asBinder());
                        }
                        alarm.listener.doAlarm(this);
                        mHandler.sendMessageDelayed(
                                mHandler.obtainMessage(AlarmHandler.LISTENER_TIMEOUT,
                                        alarm.listener.asBinder()),
                                mConstants.LISTENER_TIMEOUT);
                    } catch (Exception e) {
                        if (DEBUG_LISTENER_CALLBACK) {
                            Slog.i(TAG, "Alarm undeliverable to listener "
                                    + alarm.listener.asBinder(), e);
                        }
                        // As in the PendingIntent.CanceledException case, delivery of the
                        // alarm was not possible, so we have no wakelock or timeout or
                        // stats management to do.  It threw before we posted the delayed
                        // timeout message, so we're done here.
                        mListenerFinishCount++;
                        return;
                    }
                }
            } finally {
                ThreadLocalWorkSource.restore(workSourceToken);
            }

            // The alarm is now in flight; now arrange wakelock and stats tracking
            if (DEBUG_WAKELOCK) {
                Slog.d(TAG, "mBroadcastRefCount -> " + (mBroadcastRefCount + 1));
            }
            if (mBroadcastRefCount == 0) {
                setWakelockWorkSource(alarm.workSource, alarm.creatorUid, alarm.statsTag, true);
                mWakeLock.acquire();
                mHandler.obtainMessage(AlarmHandler.REPORT_ALARMS_ACTIVE, 1).sendToTarget();
            }
            final InFlight inflight = new InFlight(AlarmManagerService.this, alarm, nowELAPSED);
            mInFlight.add(inflight);
            mBroadcastRefCount++;
            if (inflight.isBroadcast()) {
                notifyBroadcastAlarmPendingLocked(alarm.uid);
            }
            if (allowWhileIdle) {
                // Record the last time this uid handled an ALLOW_WHILE_IDLE alarm.
                mLastAllowWhileIdleDispatch.put(alarm.creatorUid, nowELAPSED);
                if ((mAppStateTracker == null)
                        || mAppStateTracker.isUidInForeground(alarm.creatorUid)) {
                    mUseAllowWhileIdleShortTime.put(alarm.creatorUid, true);
                } else {
                    mUseAllowWhileIdleShortTime.put(alarm.creatorUid, false);
                }
                if (RECORD_DEVICE_IDLE_ALARMS) {
                    IdleDispatchEntry ent = new IdleDispatchEntry();
                    ent.uid = alarm.uid;
                    ent.pkg = alarm.packageName;
                    ent.tag = alarm.statsTag;
                    ent.op = "DELIVER";
                    ent.elapsedRealtime = nowELAPSED;
                    mAllowWhileIdleDispatches.add(ent);
                }
            }
            if (!isExemptFromAppStandby(alarm)) {
                final Pair<String, Integer> packageUser = Pair.create(alarm.sourcePackage,
                        UserHandle.getUserId(alarm.creatorUid));
                mAppWakeupHistory.recordAlarmForPackage(alarm.sourcePackage,
                        UserHandle.getUserId(alarm.creatorUid), nowELAPSED);
            }
            final BroadcastStats bs = inflight.mBroadcastStats;
            bs.count++;
            if (bs.nesting == 0) {
                bs.nesting = 1;
                bs.startTime = nowELAPSED;
            } else {
                bs.nesting++;
            }
            final FilterStats fs = inflight.mFilterStats;
            fs.count++;
            if (fs.nesting == 0) {
                fs.nesting = 1;
                fs.startTime = nowELAPSED;
            } else {
                fs.nesting++;
            }
            if (alarm.type == ELAPSED_REALTIME_WAKEUP
                    || alarm.type == RTC_WAKEUP) {
                bs.numWakeup++;
                fs.numWakeup++;
                mActivityManagerInternal.noteWakeupAlarm(
                        alarm.operation, alarm.workSource, alarm.uid, alarm.packageName,
                        alarm.statsTag);
            }
        }
    }

    private void incrementAlarmCount(int uid) {
        final int uidIndex = mAlarmsPerUid.indexOfKey(uid);
        if (uidIndex >= 0) {
            mAlarmsPerUid.setValueAt(uidIndex, mAlarmsPerUid.valueAt(uidIndex) + 1);
        } else {
            mAlarmsPerUid.put(uid, 1);
        }
    }

    private void decrementAlarmCount(int uid, int decrement) {
        int oldCount = 0;
        final int uidIndex = mAlarmsPerUid.indexOfKey(uid);
        if (uidIndex >= 0) {
            oldCount = mAlarmsPerUid.valueAt(uidIndex);
            if (oldCount > decrement) {
                mAlarmsPerUid.setValueAt(uidIndex, oldCount - decrement);
            } else {
                mAlarmsPerUid.removeAt(uidIndex);
            }
        }
        if (oldCount < decrement) {
            Slog.wtf(TAG, "Attempt to decrement existing alarm count " + oldCount + " by "
                    + decrement + " for uid " + uid);
        }
    }

    private class ShellCmd extends ShellCommand {

        IAlarmManager getBinderService() {
            return IAlarmManager.Stub.asInterface(mService);
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "set-time":
                        final long millis = Long.parseLong(getNextArgRequired());
                        return (getBinderService().setTime(millis)) ? 0 : -1;
                    case "set-timezone":
                        final String tz = getNextArgRequired();
                        getBinderService().setTimeZone(tz);
                        return 0;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println(e);
            }
            return -1;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Alarm manager service (alarm) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  set-time TIME");
            pw.println("    Set the system clock time to TIME where TIME is milliseconds");
            pw.println("    since the Epoch.");
            pw.println("  set-timezone TZ");
            pw.println("    Set the system timezone to TZ where TZ is an Olson id.");
        }
    }
}
