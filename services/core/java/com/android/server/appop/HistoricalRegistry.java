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
package com.android.server.appop;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalMode;
import android.app.AppOpsManager.HistoricalOp;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.AppOpsManager.UidState;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.AtomicDirectory;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This class managers historical app op state. This includes reading, persistence,
 * accounting, querying.
 * <p>
 * The history is kept forever in multiple files. Each file time contains the
 * relative offset from the current time which time is encoded in the file name.
 * The files contain historical app op state snapshots which have times that
 * are relative to the time of the container file.
 *
 * The data in the files are stored in a logarithmic fashion where where every
 * subsequent file would contain data for ten times longer interval with ten
 * times more time distance between snapshots. Hence, the more time passes
 * the lesser the fidelity.
 * <p>
 * For example, the first file would contain data for 1 days with snapshots
 * every 0.1 days, the next file would contain data for the period 1 to 10
 * days with snapshots every 1 days, and so on.
 * <p>
 * THREADING AND LOCKING: Reported ops must be processed as quickly as possible.
 * We keep ops pending to be persisted in memory and write to disk on a background
 * thread. Hence, methods that report op changes are locking only the in memory
 * state guarded by the mInMemoryLock which happens to be the app ops service lock
 * avoiding a lock addition on the critical path. When a query comes we need to
 * evaluate it based off both in memory and on disk state. This means they need to
 * be frozen with respect to each other and not change from the querying caller's
 * perspective. To achieve this we add a dedicated mOnDiskLock to guard the on
 * disk state. To have fast critical path we need to limit the locking of the
 * mInMemoryLock, thus for operations that touch in memory and on disk state one
 * must grab first the mOnDiskLock and then the mInMemoryLock and limit the
 * in memory lock to extraction of relevant data. Locking order is critical to
 * avoid deadlocks. The convention is that xxxDLocked suffix means the method
 * must be called with the mOnDiskLock lock, xxxMLocked suffix means the method
 * must be called with the mInMemoryLock, xxxDMLocked suffix means the method
 * must be called with the mOnDiskLock and mInMemoryLock locks acquired in that
 * exact order.
 */
// TODO (bug:122218838): Make sure we handle start of epoch time
// TODO (bug:122218838): Validate changed time is handled correctly
final class HistoricalRegistry {
    private static final boolean DEBUG = false;

    private static final String LOG_TAG = HistoricalRegistry.class.getSimpleName();

    private static final String PARAMETER_DELIMITER = ",";
    private static final String PARAMETER_ASSIGNMENT = "=";

    @GuardedBy("mLock")
    private @NonNull LinkedList<HistoricalOps> mPendingWrites = new LinkedList<>();

    // Lock for read/write access to on disk state
    private final Object mOnDiskLock = new Object();

    //Lock for read/write access to in memory state
    private final @NonNull Object mInMemoryLock;

    private static final int MSG_WRITE_PENDING_HISTORY = 1;

    // See mMode
    private static final int DEFAULT_MODE = AppOpsManager.HISTORICAL_MODE_DISABLED;

    // See mBaseSnapshotInterval
    private static final long DEFAULT_SNAPSHOT_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    // See mIntervalCompressionMultiplier
    private static final long DEFAULT_COMPRESSION_STEP = 10;

    /**
     * Whether history is enabled.
     */
    @GuardedBy("mInMemoryLock")
    private int mMode = AppOpsManager.HISTORICAL_MODE_DISABLED;

    /**
     * This granularity has been chosen to allow clean delineation for intervals
     * humans understand, 15 min, 60, min, a day, a week, a month (30 days).
     */
    @GuardedBy("mInMemoryLock")
    private long mBaseSnapshotInterval = DEFAULT_SNAPSHOT_INTERVAL_MILLIS;

    /**
     * The compression between steps. Each subsequent step is this much longer
     * in terms of duration and each snapshot is this much more apart from the
     * previous step.
     */
    @GuardedBy("mInMemoryLock")
    private long mIntervalCompressionMultiplier = DEFAULT_COMPRESSION_STEP;

    // The current ops to which to add statistics.
    @GuardedBy("mInMemoryLock")
    private @Nullable HistoricalOps mCurrentHistoricalOps;

    // The time we should write the next snapshot.
    @GuardedBy("mInMemoryLock")
    private long mNextPersistDueTimeMillis;

    // How much to offset the history on the next write.
    @GuardedBy("mInMemoryLock")
    private long mPendingHistoryOffsetMillis;

    // Object managing persistence (read/write)
    @GuardedBy("mOnDiskLock")
    private Persistence mPersistence = new Persistence(mBaseSnapshotInterval,
            mIntervalCompressionMultiplier);

    HistoricalRegistry(@NonNull Object lock) {
        mInMemoryLock = lock;
        if (mMode != AppOpsManager.HISTORICAL_MODE_DISABLED) {
            synchronized (mInMemoryLock) {
                // When starting always adjust history to now.
                mPendingHistoryOffsetMillis = System.currentTimeMillis()
                        - mPersistence.getLastPersistTimeMillisDLocked();
            }
        }
    }

    void systemReady(@NonNull ContentResolver resolver) {
        updateParametersFromSetting(resolver);
        final Uri uri = Settings.Global.getUriFor(Settings.Global.APPOP_HISTORY_PARAMETERS);
        resolver.registerContentObserver(uri, false, new ContentObserver(
                FgThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateParametersFromSetting(resolver);
            }
        });
    }

    private void updateParametersFromSetting(@NonNull ContentResolver resolver) {
        final String setting = Settings.Global.getString(resolver,
                Settings.Global.APPOP_HISTORY_PARAMETERS);
        if (setting == null) {
            return;
        }
        String modeValue = null;
        String baseSnapshotIntervalValue = null;
        String intervalMultiplierValue = null;
        final String[] parameters = setting.split(PARAMETER_DELIMITER);
        for (String parameter : parameters) {
            final String[] parts = parameter.split(PARAMETER_ASSIGNMENT);
            if (parts.length == 2) {
                final String key = parts[0].trim();
                switch (key) {
                    case Settings.Global.APPOP_HISTORY_MODE: {
                        modeValue = parts[1].trim();
                    } break;
                    case Settings.Global.APPOP_HISTORY_BASE_INTERVAL_MILLIS: {
                        baseSnapshotIntervalValue = parts[1].trim();
                    } break;
                    case Settings.Global.APPOP_HISTORY_INTERVAL_MULTIPLIER: {
                        intervalMultiplierValue = parts[1].trim();
                    } break;
                    default: {
                        Slog.w(LOG_TAG, "Unknown parameter: " + parameter);
                    }
                }
            }
        }
        if (modeValue != null && baseSnapshotIntervalValue != null
                && intervalMultiplierValue != null) {
            try {
                final int mode = AppOpsManager.parseHistoricalMode(modeValue);
                final long baseSnapshotInterval = Long.parseLong(baseSnapshotIntervalValue);
                final int intervalCompressionMultiplier = Integer.parseInt(intervalMultiplierValue);
                setHistoryParameters(mode, baseSnapshotInterval,intervalCompressionMultiplier);
                return;
            } catch (NumberFormatException ignored) {}
        }
        Slog.w(LOG_TAG, "Bad value for" + Settings.Global.APPOP_HISTORY_PARAMETERS
                + "=" + setting + " resetting!");
    }

    void dump(String prefix, PrintWriter pw,  int filterUid,
            String filterPackage, int filterOp) {
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                pw.println();
                pw.print(prefix);
                pw.print("History:");

                pw.print("  mode=");
                pw.println(AppOpsManager.historicalModeToString(mMode));

                final StringDumpVisitor visitor = new StringDumpVisitor(prefix + "  ",
                        pw, filterUid, filterPackage, filterOp);
                final long nowMillis = System.currentTimeMillis();

                // Dump in memory state first
                final HistoricalOps currentOps = getUpdatedPendingHistoricalOpsMLocked(
                        nowMillis);
                makeRelativeToEpochStart(currentOps, nowMillis);
                currentOps.accept(visitor);

                final List<HistoricalOps> ops = mPersistence.readHistoryDLocked();
                if (ops != null) {
                    // TODO (bug:122218838): Make sure this is properly dumped
                    final long remainingToFillBatchMillis = mNextPersistDueTimeMillis
                            - nowMillis - mBaseSnapshotInterval;
                    final int opCount = ops.size();
                    for (int i = 0; i < opCount; i++) {
                        final HistoricalOps op = ops.get(i);
                        op.offsetBeginAndEndTime(remainingToFillBatchMillis);
                        makeRelativeToEpochStart(op, nowMillis);
                        op.accept(visitor);
                    }
                } else {
                    pw.println("  Empty");
                }
            }
        }
    }

    @HistoricalMode int getMode() {
        synchronized (mInMemoryLock) {
            return mMode;
        }
    }

    @Nullable void getHistoricalOpsFromDiskRaw(int uid, @NonNull String packageName,
            @Nullable String[] opNames, long beginTimeMillis, long endTimeMillis,
            @NonNull RemoteCallback callback) {
        final HistoricalOps result = new HistoricalOps(beginTimeMillis, endTimeMillis);
        mPersistence.collectHistoricalOpsDLocked(result, uid, packageName, opNames,
                beginTimeMillis, endTimeMillis);
        final Bundle payload = new Bundle();
        payload.putParcelable(AppOpsManager.KEY_HISTORICAL_OPS, result);
        callback.sendResult(payload);
    }

    @Nullable void getHistoricalOps(int uid, @NonNull String packageName,
            @Nullable String[] opNames, long beginTimeMillis, long endTimeMillis,
            @NonNull RemoteCallback callback) {
        final long currentTimeMillis = System.currentTimeMillis();
        if (endTimeMillis == Long.MAX_VALUE) {
            endTimeMillis = currentTimeMillis;
        }

        // Argument times are based off epoch start while our internal store is
        // based off now, so take this into account.
        final long inMemoryAdjBeginTimeMillis = Math.max(currentTimeMillis - endTimeMillis, 0);
        final long inMemoryAdjEndTimeMillis = Math.max(currentTimeMillis - beginTimeMillis, 0);
        final HistoricalOps result = new HistoricalOps(inMemoryAdjBeginTimeMillis,
                inMemoryAdjEndTimeMillis);

        synchronized (mOnDiskLock) {
            final List<HistoricalOps> pendingWrites;
            final HistoricalOps currentOps;
            synchronized (mInMemoryLock) {
                currentOps = getUpdatedPendingHistoricalOpsMLocked(currentTimeMillis);
                if (!(inMemoryAdjBeginTimeMillis >= currentOps.getEndTimeMillis()
                        || inMemoryAdjEndTimeMillis <= currentOps.getBeginTimeMillis())) {
                    // Some of the current batch falls into the query, so extract that.
                    final HistoricalOps currentOpsCopy = new HistoricalOps(currentOps);
                    currentOpsCopy.filter(uid, packageName, opNames, inMemoryAdjBeginTimeMillis,
                            inMemoryAdjEndTimeMillis);
                    result.merge(currentOpsCopy);
                }
                pendingWrites = new ArrayList<>(mPendingWrites);
                mPendingWrites.clear();
            }

            // If the query was only for in-memory state - done.
            if (inMemoryAdjEndTimeMillis > currentOps.getEndTimeMillis()) {
                // If there is a write in flight we need to force it now
                persistPendingHistory(pendingWrites);
                // Collect persisted state.
                final long onDiskAndInMemoryOffsetMillis = currentTimeMillis
                        - mNextPersistDueTimeMillis + mBaseSnapshotInterval;
                final long onDiskAdjBeginTimeMillis = Math.max(inMemoryAdjBeginTimeMillis
                        - onDiskAndInMemoryOffsetMillis, 0);
                final long onDiskAdjEndTimeMillis = Math.max(inMemoryAdjEndTimeMillis
                        - onDiskAndInMemoryOffsetMillis, 0);
                mPersistence.collectHistoricalOpsDLocked(result, uid, packageName, opNames,
                        onDiskAdjBeginTimeMillis, onDiskAdjEndTimeMillis);
            }

            // Rebase the result time to be since epoch.
            result.setBeginAndEndTime(beginTimeMillis, endTimeMillis);

            // Send back the result.
            final Bundle payload = new Bundle();
            payload.putParcelable(AppOpsManager.KEY_HISTORICAL_OPS, result);
            callback.sendResult(payload);
        }
    }

    void incrementOpAccessedCount(int op, int uid, @NonNull String packageName,
            @UidState int uidState) {
        synchronized (mInMemoryLock) {
            if (mMode == AppOpsManager.HISTORICAL_MODE_ENABLED_ACTIVE) {
                getUpdatedPendingHistoricalOpsMLocked(System.currentTimeMillis())
                        .increaseAccessCount(op, uid, packageName, uidState, 1);

            }
        }
    }

    void incrementOpRejected(int op, int uid, @NonNull String packageName,
            @UidState int uidState) {
        synchronized (mInMemoryLock) {
            if (mMode == AppOpsManager.HISTORICAL_MODE_ENABLED_ACTIVE) {
                getUpdatedPendingHistoricalOpsMLocked(System.currentTimeMillis())
                        .increaseRejectCount(op, uid, packageName, uidState, 1);
            }
        }
    }

    void increaseOpAccessDuration(int op, int uid, @NonNull String packageName,
            @UidState int uidState, long increment) {
        synchronized (mInMemoryLock) {
            if (mMode == AppOpsManager.HISTORICAL_MODE_ENABLED_ACTIVE) {
                getUpdatedPendingHistoricalOpsMLocked(System.currentTimeMillis())
                        .increaseAccessDuration(op, uid, packageName, uidState, increment);
            }
        }
    }

    void setHistoryParameters(@HistoricalMode int mode,
            long baseSnapshotInterval, long intervalCompressionMultiplier) {
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                boolean resampleHistory = false;
                Slog.i(LOG_TAG, "New history parameters: mode:"
                        + AppOpsManager.historicalModeToString(mMode) + " baseSnapshotInterval:"
                        + baseSnapshotInterval + " intervalCompressionMultiplier:"
                        + intervalCompressionMultiplier);
                if (mMode != mode) {
                    mMode = mode;
                    if (mMode == AppOpsManager.HISTORICAL_MODE_DISABLED) {
                        clearHistoryOnDiskLocked();
                    }
                }
                if (mBaseSnapshotInterval != baseSnapshotInterval) {
                    mBaseSnapshotInterval = baseSnapshotInterval;
                    resampleHistory = true;
                }
                if (mIntervalCompressionMultiplier != intervalCompressionMultiplier) {
                    mIntervalCompressionMultiplier = intervalCompressionMultiplier;
                    resampleHistory = true;
                }
                if (resampleHistory) {
                    resampleHistoryOnDiskInMemoryDMLocked(0);
                }
            }
        }
    }

    void offsetHistory(long offsetMillis) {
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                final List<HistoricalOps> history = mPersistence.readHistoryDLocked();
                clearHistory();
                if (history != null) {
                    final int historySize = history.size();
                    for (int i = 0; i < historySize; i++) {
                        final HistoricalOps ops = history.get(i);
                        ops.offsetBeginAndEndTime(offsetMillis);
                    }
                    if (offsetMillis < 0) {
                        pruneFutureOps(history);
                    }
                    mPersistence.persistHistoricalOpsDLocked(history);
                }
            }
        }
    }

    void addHistoricalOps(HistoricalOps ops) {
        final List<HistoricalOps> pendingWrites;
        synchronized (mInMemoryLock) {
            // The history files start from mBaseSnapshotInterval - take this into account.
            ops.offsetBeginAndEndTime(mBaseSnapshotInterval);
            mPendingWrites.offerFirst(ops);
            pendingWrites = new ArrayList<>(mPendingWrites);
            mPendingWrites.clear();
        }
        persistPendingHistory(pendingWrites);
    }

    private void resampleHistoryOnDiskInMemoryDMLocked(long offsetMillis) {
        mPersistence = new Persistence(mBaseSnapshotInterval, mIntervalCompressionMultiplier);
        offsetHistory(offsetMillis);
    }

    void resetHistoryParameters() {
        setHistoryParameters(DEFAULT_MODE, DEFAULT_SNAPSHOT_INTERVAL_MILLIS,
                DEFAULT_COMPRESSION_STEP);
    }

    void clearHistory() {
        synchronized (mOnDiskLock) {
            clearHistoryOnDiskLocked();
        }
    }

    private void clearHistoryOnDiskLocked() {
        BackgroundThread.getHandler().removeMessages(MSG_WRITE_PENDING_HISTORY);
        synchronized (mInMemoryLock) {
            mCurrentHistoricalOps = null;
            mNextPersistDueTimeMillis = System.currentTimeMillis();
            mPendingWrites.clear();
        }
        mPersistence.clearHistoryDLocked();
    }

    private @NonNull HistoricalOps getUpdatedPendingHistoricalOpsMLocked(long now) {
        if (mCurrentHistoricalOps != null) {
            final long remainingTimeMillis = mNextPersistDueTimeMillis - now;
            if (remainingTimeMillis > mBaseSnapshotInterval) {
                // If time went backwards we need to push history to the future with the
                // overflow over our snapshot interval. If time went forward do nothing
                // as we would naturally push history into the past on the next write.
                mPendingHistoryOffsetMillis = remainingTimeMillis - mBaseSnapshotInterval;
            }
            final long elapsedTimeMillis = mBaseSnapshotInterval - remainingTimeMillis;
            mCurrentHistoricalOps.setEndTime(elapsedTimeMillis);
            if (remainingTimeMillis > 0) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Returning current in-memory state");
                }
                return mCurrentHistoricalOps;
            }
            if (mCurrentHistoricalOps.isEmpty()) {
                mCurrentHistoricalOps.setBeginAndEndTime(0, 0);
                mNextPersistDueTimeMillis = now + mBaseSnapshotInterval;
                return mCurrentHistoricalOps;
            }
            // The current batch is full, so persist taking into account overdue persist time.
            mCurrentHistoricalOps.offsetBeginAndEndTime(mBaseSnapshotInterval);
            mCurrentHistoricalOps.setBeginTime(mCurrentHistoricalOps.getEndTimeMillis()
                    - mBaseSnapshotInterval);
            final long overdueTimeMillis = Math.abs(remainingTimeMillis);
            mCurrentHistoricalOps.offsetBeginAndEndTime(overdueTimeMillis);
            schedulePersistHistoricalOpsMLocked(mCurrentHistoricalOps);
        }
        // The current batch is in the future, i.e. not complete yet.
        mCurrentHistoricalOps = new HistoricalOps(0, 0);
        mNextPersistDueTimeMillis = now + mBaseSnapshotInterval;
        if (DEBUG) {
            Slog.i(LOG_TAG, "Returning new in-memory state");
        }
        return mCurrentHistoricalOps;
    }

    private void persistPendingHistory() {
        final List<HistoricalOps> pendingWrites;
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                pendingWrites = new ArrayList<>(mPendingWrites);
                mPendingWrites.clear();
                if (mPendingHistoryOffsetMillis != 0) {
                    resampleHistoryOnDiskInMemoryDMLocked(mPendingHistoryOffsetMillis);
                    mPendingHistoryOffsetMillis = 0;
                }
            }
            persistPendingHistory(pendingWrites);
        }
    }
    private void persistPendingHistory(@NonNull List<HistoricalOps> pendingWrites) {
        synchronized (mOnDiskLock) {
            BackgroundThread.getHandler().removeMessages(MSG_WRITE_PENDING_HISTORY);
            if (pendingWrites.isEmpty()) {
                return;
            }
            final int opCount = pendingWrites.size();
            // Pending writes are offset relative to each other, so take this
            // into account to persist everything in one shot - single write.
            for (int i = 0; i < opCount; i++) {
                final HistoricalOps current = pendingWrites.get(i);
                if (i > 0) {
                    final HistoricalOps previous = pendingWrites.get(i - 1);
                    current.offsetBeginAndEndTime(previous.getBeginTimeMillis());
                }
            }
            mPersistence.persistHistoricalOpsDLocked(pendingWrites);
        }
    }

    private void schedulePersistHistoricalOpsMLocked(@NonNull HistoricalOps ops) {
        final Message message = PooledLambda.obtainMessage(
                HistoricalRegistry::persistPendingHistory, HistoricalRegistry.this);
        message.what = MSG_WRITE_PENDING_HISTORY;
        BackgroundThread.getHandler().sendMessage(message);
        mPendingWrites.offerFirst(ops);
    }

    private static void makeRelativeToEpochStart(@NonNull HistoricalOps ops, long nowMillis) {
        ops.setBeginAndEndTime(nowMillis - ops.getEndTimeMillis(),
                nowMillis- ops.getBeginTimeMillis());
    }

    private void pruneFutureOps(@NonNull List<HistoricalOps> ops) {
        final int opCount = ops.size();
        for (int i = opCount - 1; i >= 0; i--) {
            final HistoricalOps op = ops.get(i);
            if (op.getEndTimeMillis() <= mBaseSnapshotInterval) {
                ops.remove(i);
            } else if (op.getBeginTimeMillis() < mBaseSnapshotInterval) {
                final double filterScale = (double) (op.getEndTimeMillis() - mBaseSnapshotInterval)
                        / (double) op.getDurationMillis();
                Persistence.spliceFromBeginning(op, filterScale);
            }
        }
    }

    private static final class Persistence {
        private static final boolean DEBUG = false;

        private static final String LOG_TAG = Persistence.class.getSimpleName();

        private static final String HISTORY_FILE_SUFFIX = ".xml";

        private static final String TAG_HISTORY = "history";
        private static final String TAG_OPS = "ops";
        private static final String TAG_UID = "uid";
        private static final String TAG_PACKAGE = "package";
        private static final String TAG_OP = "op";
        private static final String TAG_STATE = "state";

        private static final String ATTR_VERSION = "version";
        private static final String ATTR_NAME = "name";
        private static final String ATTR_ACCESS_COUNT = "accessCount";
        private static final String ATTR_REJECT_COUNT = "rejectCount";
        private static final String ATTR_ACCESS_DURATION = "accessDuration";
        private static final String ATTR_BEGIN_TIME = "beginTime";
        private static final String ATTR_END_TIME = "endTime";
        private static final String ATTR_OVERFLOW = "overflow";

        private static final int CURRENT_VERSION = 1;

        private final long mBaseSnapshotInterval;
        private final long mIntervalCompressionMultiplier;

        Persistence(long baseSnapshotInterval, long intervalCompressionMultiplier) {
            mBaseSnapshotInterval = baseSnapshotInterval;
            mIntervalCompressionMultiplier = intervalCompressionMultiplier;
        }

        private final AtomicDirectory mHistoricalAppOpsDir = new AtomicDirectory(
                new File(new File(Environment.getDataSystemDeDirectory(), "appops"), "history"));

        private File generateFile(@NonNull File baseDir, int depth) {
            final long globalBeginMillis = computeGlobalIntervalBeginMillis(depth);
            return new File(baseDir, Long.toString(globalBeginMillis) + HISTORY_FILE_SUFFIX);
        }

        void clearHistoryDLocked() {
            mHistoricalAppOpsDir.delete();
        }

        void persistHistoricalOpsDLocked(@NonNull List<HistoricalOps> ops) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Persisting ops:\n" + opsToDebugString(ops));
                enforceOpsWellFormed(ops);
            }
            try {
                final File newBaseDir = mHistoricalAppOpsDir.startWrite();
                final File oldBaseDir = mHistoricalAppOpsDir.getBackupDirectory();
                handlePersistHistoricalOpsRecursiveDLocked(newBaseDir, oldBaseDir, ops, 0);
                mHistoricalAppOpsDir.finishWrite();
            } catch (Throwable t) {
                Slog.wtf(LOG_TAG, "Failed to write historical app ops, restoring backup", t);
                mHistoricalAppOpsDir.failWrite();
            }
        }

        @Nullable List<HistoricalOps> readHistoryRawDLocked() {
            return collectHistoricalOpsBaseDLocked(Process.INVALID_UID /*filterUid*/,
                    null /*filterPackageName*/, null /*filterOpNames*/,
                    0 /*filterBeginTimeMills*/, Long.MAX_VALUE /*filterEndTimeMills*/);
        }

        @Nullable List<HistoricalOps> readHistoryDLocked() {
            final List<HistoricalOps> result = readHistoryRawDLocked();
            // Take into account in memory state duration.
            if (result != null) {
                final int opCount = result.size();
                for (int i = 0; i < opCount; i++) {
                    result.get(i).offsetBeginAndEndTime(mBaseSnapshotInterval);
                }
            }
            return result;
        }

        long getLastPersistTimeMillisDLocked() {
            try {
                final File baseDir = mHistoricalAppOpsDir.startRead();
                final File file = generateFile(baseDir, 0);
                if (file.exists()) {
                    return file.lastModified();
                }
                mHistoricalAppOpsDir.finishRead();
            } catch (IOException e) {
                Slog.wtf("Error reading historical app ops. Deleting history.", e);
                mHistoricalAppOpsDir.delete();
            }
            return 0;
        }

        private void collectHistoricalOpsDLocked(@NonNull HistoricalOps currentOps,
                int filterUid, @NonNull String filterPackageName, @Nullable String[] filterOpNames,
                long filterBeingMillis, long filterEndMillis) {
            final List<HistoricalOps> readOps = collectHistoricalOpsBaseDLocked(filterUid,
                    filterPackageName, filterOpNames, filterBeingMillis, filterEndMillis);
            if (readOps != null) {
                final int readCount = readOps.size();
                for (int i = 0; i < readCount; i++) {
                    final HistoricalOps readOp = readOps.get(i);
                    currentOps.merge(readOp);
                }
             }
        }

        private @Nullable LinkedList<HistoricalOps> collectHistoricalOpsBaseDLocked(
                int filterUid, @NonNull String filterPackageName, @Nullable String[] filterOpNames,
                long filterBeginTimeMillis, long filterEndTimeMillis) {
            try {
                final File baseDir = mHistoricalAppOpsDir.startRead();
                final File[] files = baseDir.listFiles();
                if (files == null) {
                    return null;
                }
                final ArraySet<File> historyFiles = new ArraySet<>(files.length);
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(HISTORY_FILE_SUFFIX)) {
                        historyFiles.add(file);
                    }
                }
                final long[] globalContentOffsetMillis = {0};
                final LinkedList<HistoricalOps> ops = collectHistoricalOpsRecursiveDLocked(
                        baseDir, filterUid, filterPackageName, filterOpNames, filterBeginTimeMillis,
                        filterEndTimeMillis, globalContentOffsetMillis, null /*outOps*/,
                        0 /*depth*/, historyFiles);
                mHistoricalAppOpsDir.finishRead();
                return ops;
            } catch (IOException | XmlPullParserException e) {
                Slog.wtf("Error reading historical app ops. Deleting history.", e);
                mHistoricalAppOpsDir.delete();
            }
            return null;
        }

        private @Nullable LinkedList<HistoricalOps> collectHistoricalOpsRecursiveDLocked(
                @NonNull File baseDir, int filterUid, @NonNull String filterPackageName,
                @Nullable String[] filterOpNames, long filterBeginTimeMillis,
                long filterEndTimeMillis, @NonNull long[] globalContentOffsetMillis,
                @Nullable LinkedList<HistoricalOps> outOps, int depth,
                @NonNull ArraySet<File> historyFiles)
                throws IOException, XmlPullParserException {
            final long previousIntervalEndMillis = (long) Math.pow(mIntervalCompressionMultiplier,
                    depth) * mBaseSnapshotInterval;
            final long currentIntervalEndMillis = (long) Math.pow(mIntervalCompressionMultiplier,
                    depth + 1) * mBaseSnapshotInterval;

            filterBeginTimeMillis = Math.max(filterBeginTimeMillis - previousIntervalEndMillis, 0);
            filterEndTimeMillis = filterEndTimeMillis - previousIntervalEndMillis;

            // Read historical data at this level
            final List<HistoricalOps> readOps = readHistoricalOpsLocked(baseDir,
                    previousIntervalEndMillis, currentIntervalEndMillis, filterUid,
                    filterPackageName, filterOpNames, filterBeginTimeMillis, filterEndTimeMillis,
                    globalContentOffsetMillis, depth, historyFiles);

            // Empty is a special signal to stop diving
            if (readOps != null && readOps.isEmpty()) {
                return outOps;
            }

            // Collect older historical data from subsequent levels
            outOps = collectHistoricalOpsRecursiveDLocked(
                    baseDir, filterUid, filterPackageName, filterOpNames, filterBeginTimeMillis,
                    filterEndTimeMillis, globalContentOffsetMillis, outOps, depth + 1,
                    historyFiles);

            // Make older historical data relative to the current historical level
            if (outOps != null) {
                final int opCount = outOps.size();
                for (int i = 0; i < opCount; i++) {
                    final HistoricalOps collectedOp = outOps.get(i);
                    collectedOp.offsetBeginAndEndTime(currentIntervalEndMillis);
                }
            }

            if (readOps != null) {
                if (outOps == null) {
                    outOps = new LinkedList<>();
                }
                // Add the read ops to output
                final int opCount = readOps.size();
                for (int i = opCount - 1; i >= 0; i--) {
                    outOps.offerFirst(readOps.get(i));
                }
            }

            return outOps;
        }

        private boolean createHardLinkToExistingFile(@NonNull File fromFile, @NonNull File toFile)
                throws IOException {
            if (!fromFile.exists()) {
                return false;
            }
            Files.createLink(toFile.toPath(), fromFile.toPath());
            return true;
        }

        private void handlePersistHistoricalOpsRecursiveDLocked(@NonNull File newBaseDir,
                @NonNull File oldBaseDir, @Nullable List<HistoricalOps> passedOps, int depth)
                throws IOException, XmlPullParserException {
            final long previousIntervalEndMillis = (long) Math.pow(mIntervalCompressionMultiplier,
                    depth) * mBaseSnapshotInterval;
            final long currentIntervalEndMillis = (long) Math.pow(mIntervalCompressionMultiplier,
                    depth + 1) * mBaseSnapshotInterval;

            if (passedOps == null || passedOps.isEmpty()) {
                // If there is an old file we need to copy it over to the new state.
                final File oldFile = generateFile(oldBaseDir, depth);
                final File newFile = generateFile(newBaseDir, depth);
                if (createHardLinkToExistingFile(oldFile, newFile)) {
                    handlePersistHistoricalOpsRecursiveDLocked(newBaseDir, oldBaseDir,
                            passedOps, depth + 1);
                }
                return;
            }

            if (DEBUG) {
                enforceOpsWellFormed(passedOps);
            }

            // Normalize passed ops time to be based off this interval start
            final int passedOpCount = passedOps.size();
            for (int i = 0; i < passedOpCount; i++) {
                final HistoricalOps passedOp = passedOps.get(i);
                passedOp.offsetBeginAndEndTime(-previousIntervalEndMillis);
            }

            if (DEBUG) {
                enforceOpsWellFormed(passedOps);
            }

            // Read persisted ops for this interval
            final List<HistoricalOps> existingOps = readHistoricalOpsLocked(oldBaseDir,
                    previousIntervalEndMillis, currentIntervalEndMillis,
                    Process.INVALID_UID /*filterUid*/, null /*filterPackageName*/,
                    null /*filterOpNames*/, Long.MIN_VALUE /*filterBeginTimeMillis*/,
                    Long.MAX_VALUE /*filterEndTimeMillis*/, null, depth,
                    null /*historyFiles*/);

            if (DEBUG) {
                enforceOpsWellFormed(existingOps);
            }

            // Offset existing ops to account for elapsed time
            final int existingOpCount = existingOps.size();
            if (existingOpCount > 0) {
                // Compute elapsed time
                final long elapsedTimeMillis = passedOps.get(passedOps.size() - 1)
                        .getEndTimeMillis();
                for (int i = 0; i < existingOpCount; i++) {
                    final HistoricalOps existingOp = existingOps.get(i);
                    existingOp.offsetBeginAndEndTime(elapsedTimeMillis);
                }
            }

            if (DEBUG) {
                enforceOpsWellFormed(existingOps);
            }

            final long slotDurationMillis = previousIntervalEndMillis;

            // Consolidate passed ops at the current slot duration ensuring each snapshot is
            // full. To achieve this we put all passed and existing ops in a list and will
            // merge them to ensure each represents a snapshot at the current granularity.
            final List<HistoricalOps> allOps = new LinkedList<>();
            allOps.addAll(passedOps);
            allOps.addAll(existingOps);

            if (DEBUG) {
                enforceOpsWellFormed(allOps);
            }

            // Compute ops to persist and overflow ops
            List<HistoricalOps> persistedOps = null;
            List<HistoricalOps> overflowedOps = null;

            // We move a snapshot into the next level only if the start time is
            // after the end of the current interval. This avoids rewriting all
            // files to propagate the information added to the history on every
            // iteration. Instead, we would rewrite the next level file only if
            // an entire snapshot from the previous level is being propagated.
            // The trade off is that we need to store how much the last snapshot
            // of the current interval overflows past the interval end. We write
            // the overflow data to avoid parsing all snapshots on query.
            long intervalOverflowMillis = 0;
            final int opCount = allOps.size();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOps op = allOps.get(i);
                final HistoricalOps persistedOp;
                final HistoricalOps overflowedOp;
                if (op.getEndTimeMillis() <= currentIntervalEndMillis) {
                    persistedOp = op;
                    overflowedOp = null;
                } else if (op.getBeginTimeMillis() < currentIntervalEndMillis) {
                    persistedOp = op;
                    intervalOverflowMillis = op.getEndTimeMillis() - currentIntervalEndMillis;
                    if (intervalOverflowMillis > previousIntervalEndMillis) {
                        final double splitScale = (double) intervalOverflowMillis
                                / op.getDurationMillis();
                        overflowedOp = spliceFromEnd(op, splitScale);
                        intervalOverflowMillis = op.getEndTimeMillis() - currentIntervalEndMillis;
                    } else {
                        overflowedOp = null;
                    }
                } else {
                    persistedOp = null;
                    overflowedOp = op;
                }
                if (persistedOp != null) {
                    if (persistedOps == null) {
                        persistedOps = new ArrayList<>();
                    }
                    persistedOps.add(persistedOp);
                }
                if (overflowedOp != null) {
                    if (overflowedOps == null) {
                        overflowedOps = new ArrayList<>();
                    }
                    overflowedOps.add(overflowedOp);
                }
            }

            if (DEBUG) {
                enforceOpsWellFormed(persistedOps);
                enforceOpsWellFormed(overflowedOps);
            }

            if (persistedOps != null) {
                normalizeSnapshotForSlotDuration(persistedOps, slotDurationMillis);
                final File newFile = generateFile(newBaseDir, depth);
                writeHistoricalOpsDLocked(persistedOps, intervalOverflowMillis, newFile);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Persisted at depth: " + depth
                            + " ops:\n" + opsToDebugString(persistedOps));
                    enforceOpsWellFormed(persistedOps);
                }
            }

            handlePersistHistoricalOpsRecursiveDLocked(newBaseDir, oldBaseDir,
                    overflowedOps, depth + 1);
        }

        private @NonNull List<HistoricalOps> readHistoricalOpsLocked(File baseDir,
                long intervalBeginMillis, long intervalEndMillis, int filterUid,
                @Nullable String filterPackageName, @Nullable String[] filterOpNames,
                long filterBeginTimeMillis, long filterEndTimeMillis,
                @Nullable long[] cumulativeOverflowMillis, int depth,
                @NonNull ArraySet<File> historyFiles)
                throws IOException, XmlPullParserException {
            final File file = generateFile(baseDir, depth);
            if (historyFiles != null) {
                historyFiles.remove(file);
            }
            if (filterBeginTimeMillis >= filterEndTimeMillis
                    || filterEndTimeMillis < intervalBeginMillis) {
                // Don't go deeper
                return Collections.emptyList();
            }
            if (filterBeginTimeMillis >= (intervalEndMillis
                    + ((intervalEndMillis - intervalBeginMillis) / mIntervalCompressionMultiplier)
                    + (cumulativeOverflowMillis != null ? cumulativeOverflowMillis[0] : 0))
                    || !file.exists()) {
                if (historyFiles == null || historyFiles.isEmpty()) {
                    // Don't go deeper
                    return Collections.emptyList();
                } else {
                    // Keep diving
                    return null;
                }
            }
            return readHistoricalOpsLocked(file, filterUid, filterPackageName, filterOpNames,
                    filterBeginTimeMillis, filterEndTimeMillis, cumulativeOverflowMillis);
        }

        private @Nullable List<HistoricalOps> readHistoricalOpsLocked(@NonNull File file,
                int filterUid, @Nullable String filterPackageName, @Nullable String[] filterOpNames,
                long filterBeginTimeMillis, long filterEndTimeMillis,
                @Nullable long[] cumulativeOverflowMillis)
                throws IOException, XmlPullParserException {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Reading ops from:" + file);
            }
            List<HistoricalOps> allOps = null;
            try (FileInputStream stream = new FileInputStream(file)) {
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());
                XmlUtils.beginDocument(parser, TAG_HISTORY);
                final long overflowMillis = XmlUtils.readLongAttribute(parser, ATTR_OVERFLOW, 0);
                final int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    if (TAG_OPS.equals(parser.getName())) {
                        final HistoricalOps ops = readeHistoricalOpsDLocked(parser,
                                filterUid, filterPackageName, filterOpNames, filterBeginTimeMillis,
                                filterEndTimeMillis, cumulativeOverflowMillis);
                        if (ops == null) {
                            continue;
                        }
                        if (ops.isEmpty()) {
                            XmlUtils.skipCurrentTag(parser);
                            continue;
                        }
                        if (allOps == null) {
                            allOps = new ArrayList<>();
                        }
                        allOps.add(ops);
                    }
                }
                if (cumulativeOverflowMillis != null) {
                    cumulativeOverflowMillis[0] += overflowMillis;
                }
            } catch (FileNotFoundException e) {
                Slog.i(LOG_TAG, "No history file: " + file.getName());
                return Collections.emptyList();
            }
            if (DEBUG) {
                if (allOps != null) {
                    Slog.i(LOG_TAG, "Read from file: " + file + "ops:\n"
                            + opsToDebugString(allOps));
                    enforceOpsWellFormed(allOps);
                }
            }
            return allOps;
        }

        private @Nullable HistoricalOps readeHistoricalOpsDLocked(
                @NonNull XmlPullParser parser, int filterUid, @Nullable String filterPackageName,
                @Nullable String[] filterOpNames, long filterBeginTimeMillis,
                long filterEndTimeMillis, @Nullable long[] cumulativeOverflowMillis)
                throws IOException, XmlPullParserException {
            final long beginTimeMillis = XmlUtils.readLongAttribute(parser, ATTR_BEGIN_TIME, 0)
                    + (cumulativeOverflowMillis != null ? cumulativeOverflowMillis[0] : 0);
            final long endTimeMillis = XmlUtils.readLongAttribute(parser, ATTR_END_TIME, 0)
                    + (cumulativeOverflowMillis != null ? cumulativeOverflowMillis[0] : 0);
            // Keep reading as subsequent records may start matching
            if (filterEndTimeMillis < beginTimeMillis) {
                return null;
            }
            // Stop reading as subsequent records will not match
            if (filterBeginTimeMillis > endTimeMillis) {
                return new HistoricalOps(0, 0);
            }
            final long filteredBeginTimeMillis = Math.max(beginTimeMillis, filterBeginTimeMillis);
            final long filteredEndTimeMillis = Math.min(endTimeMillis, filterEndTimeMillis);
            final double filterScale = (double) (filteredEndTimeMillis - filteredBeginTimeMillis)
                    / (double) (endTimeMillis - beginTimeMillis);
            HistoricalOps ops = null;
            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_UID.equals(parser.getName())) {
                    final HistoricalOps returnedOps = readHistoricalUidOpsDLocked(ops, parser,
                            filterUid, filterPackageName, filterOpNames, filterScale);
                    if (ops == null) {
                        ops = returnedOps;
                    }
                }
            }
            if (ops != null) {
                ops.setBeginAndEndTime(filteredBeginTimeMillis, filteredEndTimeMillis);
            }
            return ops;
        }

        private @Nullable HistoricalOps readHistoricalUidOpsDLocked(
                @Nullable HistoricalOps ops, @NonNull XmlPullParser parser, int filterUid,
                @Nullable String filterPackageName, @Nullable String[] filterOpNames,
                double filterScale) throws IOException, XmlPullParserException {
            final int uid = XmlUtils.readIntAttribute(parser, ATTR_NAME);
            if (filterUid != Process.INVALID_UID && filterUid != uid) {
                XmlUtils.skipCurrentTag(parser);
                return null;
            }
            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_PACKAGE.equals(parser.getName())) {
                    final HistoricalOps returnedOps = readHistoricalPackageOpsDLocked(ops,
                            uid, parser, filterPackageName, filterOpNames, filterScale);
                    if (ops == null) {
                        ops = returnedOps;
                    }
                }
            }
            return ops;
        }

        private @Nullable HistoricalOps readHistoricalPackageOpsDLocked(
                @Nullable HistoricalOps ops, int uid, @NonNull XmlPullParser parser,
                @Nullable String filterPackageName, @Nullable String[] filterOpNames,
                double filterScale) throws IOException, XmlPullParserException {
            final String packageName = XmlUtils.readStringAttribute(parser, ATTR_NAME);
            if (filterPackageName != null && !filterPackageName.equals(packageName)) {
                XmlUtils.skipCurrentTag(parser);
                return null;
            }
            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_OP.equals(parser.getName())) {
                    final HistoricalOps returnedOps = readHistoricalOpDLocked(ops, uid,
                            packageName, parser, filterOpNames, filterScale);
                    if (ops == null) {
                        ops = returnedOps;
                    }
                }
            }
            return ops;
        }

        private @Nullable HistoricalOps readHistoricalOpDLocked(@Nullable HistoricalOps ops,
                int uid, String packageName, @NonNull XmlPullParser parser,
                @Nullable String[] filterOpNames, double filterScale)
                throws IOException, XmlPullParserException {
            final int op = XmlUtils.readIntAttribute(parser, ATTR_NAME);
            if (filterOpNames != null && !ArrayUtils.contains(filterOpNames,
                    AppOpsManager.opToName(op))) {
                XmlUtils.skipCurrentTag(parser);
                return null;
            }
            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_STATE.equals(parser.getName())) {
                    final HistoricalOps returnedOps = readUidStateDLocked(ops, uid,
                            packageName, op, parser, filterScale);
                    if (ops == null) {
                        ops = returnedOps;
                    }
                }
            }
            return ops;
        }

        private @Nullable HistoricalOps readUidStateDLocked(@Nullable HistoricalOps ops,
                int uid, String packageName, int op, @NonNull XmlPullParser parser,
                double filterScale) throws IOException {
            final int uidState = XmlUtils.readIntAttribute(parser, ATTR_NAME);
            long accessCount = XmlUtils.readLongAttribute(parser, ATTR_ACCESS_COUNT, 0);
            if (accessCount > 0) {
                if (!Double.isNaN(filterScale)) {
                    accessCount = (long) HistoricalOps.round(
                            (double) accessCount * filterScale);
                }
                if (ops == null) {
                    ops = new HistoricalOps(0, 0);
                }
                ops.increaseAccessCount(op, uid, packageName, uidState, accessCount);
            }
            long rejectCount = XmlUtils.readLongAttribute(parser, ATTR_REJECT_COUNT, 0);
            if (rejectCount > 0) {
                if (!Double.isNaN(filterScale)) {
                    rejectCount = (long) HistoricalOps.round(
                            (double) rejectCount * filterScale);
                }
                if (ops == null) {
                    ops = new HistoricalOps(0, 0);
                }
                ops.increaseRejectCount(op, uid, packageName, uidState, rejectCount);
            }
            long accessDuration =  XmlUtils.readLongAttribute(parser, ATTR_ACCESS_DURATION, 0);
            if (accessDuration > 0) {
                if (!Double.isNaN(filterScale)) {
                    accessDuration = (long) HistoricalOps.round(
                            (double) accessDuration * filterScale);
                }
                if (ops == null) {
                    ops = new HistoricalOps(0, 0);
                }
                ops.increaseAccessDuration(op, uid, packageName, uidState, accessDuration);
            }
            return ops;
        }

        private void writeHistoricalOpsDLocked(@Nullable List<HistoricalOps> allOps,
                long intervalOverflowMillis, @NonNull File file) throws IOException {
            final FileOutputStream output = mHistoricalAppOpsDir.openWrite(file);
            try {
                final XmlSerializer serializer = Xml.newSerializer();
                serializer.setOutput(output, StandardCharsets.UTF_8.name());
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                        true);
                serializer.startDocument(null, true);
                serializer.startTag(null, TAG_HISTORY);
                serializer.attribute(null, ATTR_VERSION, String.valueOf(CURRENT_VERSION));
                if (intervalOverflowMillis != 0) {
                    serializer.attribute(null, ATTR_OVERFLOW,
                            Long.toString(intervalOverflowMillis));
                }
                if (allOps != null) {
                    final int opsCount = allOps.size();
                    for (int i = 0; i < opsCount; i++) {
                        final HistoricalOps ops = allOps.get(i);
                        writeHistoricalOpDLocked(ops, serializer);
                    }
                }
                serializer.endTag(null, TAG_HISTORY);
                serializer.endDocument();
                mHistoricalAppOpsDir.closeWrite(output);
            } catch (IOException e) {
                mHistoricalAppOpsDir.failWrite(output);
                throw e;
            }
        }

        private void writeHistoricalOpDLocked(@NonNull HistoricalOps ops,
                @NonNull XmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_OPS);
            serializer.attribute(null, ATTR_BEGIN_TIME, Long.toString(ops.getBeginTimeMillis()));
            serializer.attribute(null, ATTR_END_TIME, Long.toString(ops.getEndTimeMillis()));
            final int uidCount = ops.getUidCount();
            for (int i = 0; i < uidCount; i++) {
                final HistoricalUidOps uidOp = ops.getUidOpsAt(i);
                writeHistoricalUidOpsDLocked(uidOp, serializer);
            }
            serializer.endTag(null, TAG_OPS);
        }

        private void writeHistoricalUidOpsDLocked(@NonNull HistoricalUidOps uidOps,
                @NonNull XmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_UID);
            serializer.attribute(null, ATTR_NAME, Integer.toString(uidOps.getUid()));
            final int packageCount = uidOps.getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                final HistoricalPackageOps packageOps = uidOps.getPackageOpsAt(i);
                writeHistoricalPackageOpsDLocked(packageOps, serializer);
            }
            serializer.endTag(null, TAG_UID);
        }

        private void writeHistoricalPackageOpsDLocked(@NonNull HistoricalPackageOps packageOps,
                @NonNull XmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_PACKAGE);
            serializer.attribute(null, ATTR_NAME, packageOps.getPackageName());
            final int opCount = packageOps.getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp op = packageOps.getOpAt(i);
                writeHistoricalOpDLocked(op, serializer);
            }
            serializer.endTag(null, TAG_PACKAGE);
        }

        private void writeHistoricalOpDLocked(@NonNull HistoricalOp op,
                @NonNull XmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_OP);
            serializer.attribute(null, ATTR_NAME, Integer.toString(op.getOpCode()));
            for (int uidState = 0; uidState < AppOpsManager._NUM_UID_STATE; uidState++) {
                writeUidStateOnLocked(op, uidState, serializer);
            }
            serializer.endTag(null, TAG_OP);
        }

        private void writeUidStateOnLocked(@NonNull HistoricalOp op, @UidState int uidState,
                @NonNull XmlSerializer serializer) throws IOException {
            final long accessCount = op.getAccessCount(uidState);
            final long rejectCount = op.getRejectCount(uidState);
            final long accessDuration = op.getAccessDuration(uidState);
            if (accessCount == 0 && rejectCount == 0 && accessDuration == 0) {
                return;
            }
            serializer.startTag(null, TAG_STATE);
            serializer.attribute(null, ATTR_NAME, Integer.toString(uidState));
            if (accessCount > 0) {
                serializer.attribute(null, ATTR_ACCESS_COUNT, Long.toString(accessCount));
            }
            if (rejectCount > 0) {
                serializer.attribute(null, ATTR_REJECT_COUNT, Long.toString(rejectCount));
            }
            if (accessDuration > 0) {
                serializer.attribute(null, ATTR_ACCESS_DURATION, Long.toString(accessDuration));
            }
            serializer.endTag(null, TAG_STATE);
        }

        private static void enforceOpsWellFormed(@NonNull List<HistoricalOps> ops) {
            if (ops == null) {
                return;
            }
            HistoricalOps previous;
            HistoricalOps current = null;
            final int opsCount = ops.size();
            for (int i = 0; i < opsCount; i++) {
                previous = current;
                current = ops.get(i);
                if (current.isEmpty()) {
                    throw new IllegalStateException("Empty ops:\n"
                            + opsToDebugString(ops));
                }
                if (current.getEndTimeMillis() < current.getBeginTimeMillis()) {
                    throw new IllegalStateException("Begin after end:\n"
                            + opsToDebugString(ops));
                }
                if (previous != null) {
                    if (previous.getEndTimeMillis() > current.getBeginTimeMillis()) {
                        throw new IllegalStateException("Intersecting ops:\n"
                                + opsToDebugString(ops));
                    }
                    if (previous.getBeginTimeMillis() > current.getBeginTimeMillis()) {
                        throw new IllegalStateException("Non increasing ops:\n"
                                + opsToDebugString(ops));
                    }
                }
            }
        }

        private long computeGlobalIntervalBeginMillis(int depth) {
            long beginTimeMillis = 0;
            for (int i = 0; i < depth + 1; i++) {
                beginTimeMillis += Math.pow(mIntervalCompressionMultiplier, i);
            }
            return beginTimeMillis * mBaseSnapshotInterval;
        }

        private static @NonNull HistoricalOps spliceFromEnd(@NonNull HistoricalOps ops,
                double spliceRatio) {
            if (DEBUG) {
                Slog.w(LOG_TAG, "Splicing from end:" + ops + " ratio:" + spliceRatio);
            }
            final HistoricalOps splice = ops.spliceFromEnd(spliceRatio);
            if (DEBUG) {
                Slog.w(LOG_TAG, "Spliced into:" + ops + " and:" + splice);
            }
            return splice;
        }


        private static @NonNull HistoricalOps spliceFromBeginning(@NonNull HistoricalOps ops,
                double spliceRatio) {
            if (DEBUG) {
                Slog.w(LOG_TAG, "Splicing from beginning:" + ops + " ratio:" + spliceRatio);
            }
            final HistoricalOps splice = ops.spliceFromBeginning(spliceRatio);
            if (DEBUG) {
                Slog.w(LOG_TAG, "Spliced into:" + ops + " and:" + splice);
            }
            return splice;
        }

        private static void normalizeSnapshotForSlotDuration(@NonNull List<HistoricalOps> ops,
                long slotDurationMillis) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Normalizing for slot duration: " + slotDurationMillis
                        + " ops:\n" + opsToDebugString(ops));
                enforceOpsWellFormed(ops);
            }
            long slotBeginTimeMillis;
            final int opCount = ops.size();
            for (int processedIdx = opCount - 1; processedIdx >= 0; processedIdx--) {
                final HistoricalOps processedOp = ops.get(processedIdx);
                slotBeginTimeMillis = Math.max(processedOp.getEndTimeMillis()
                        - slotDurationMillis, 0);
                for (int candidateIdx = processedIdx - 1; candidateIdx >= 0; candidateIdx--) {
                    final HistoricalOps candidateOp = ops.get(candidateIdx);
                    final long candidateSlotIntersectionMillis = candidateOp.getEndTimeMillis()
                            - Math.min(slotBeginTimeMillis, processedOp.getBeginTimeMillis());
                    if (candidateSlotIntersectionMillis <= 0) {
                        break;
                    }
                    final float candidateSplitRatio = candidateSlotIntersectionMillis
                            / (float) candidateOp.getDurationMillis();
                    if (Float.compare(candidateSplitRatio, 1.0f) >= 0) {
                        ops.remove(candidateIdx);
                        processedIdx--;
                        processedOp.merge(candidateOp);
                    } else {
                        final HistoricalOps endSplice = spliceFromEnd(candidateOp,
                                candidateSplitRatio);
                        if (endSplice != null) {
                            processedOp.merge(endSplice);
                        }
                        if (candidateOp.isEmpty()) {
                            ops.remove(candidateIdx);
                            processedIdx--;
                        }
                    }
                }
            }
            if (DEBUG) {
                Slog.i(LOG_TAG, "Normalized for slot duration: " + slotDurationMillis
                        + " ops:\n" + opsToDebugString(ops));
                enforceOpsWellFormed(ops);
            }
        }

        private static @NonNull String opsToDebugString(@NonNull List<HistoricalOps> ops) {
            StringBuilder builder = new StringBuilder();
            final int opCount = ops.size();
            for (int i = 0; i < opCount; i++) {
                builder.append("  ");
                builder.append(ops.get(i));
                if (i < opCount - 1) {
                    builder.append('\n');
                }
            }
            return builder.toString();
        }
    }

    private final class StringDumpVisitor implements AppOpsManager.HistoricalOpsVisitor {
        private final long mNow = System.currentTimeMillis();

        private final SimpleDateFormat mDateFormatter = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS");
        private final Date mDate = new Date();

        private final @NonNull String mOpsPrefix;
        private final @NonNull String mUidPrefix;
        private final @NonNull String mPackagePrefix;
        private final @NonNull String mEntryPrefix;
        private final @NonNull String mUidStatePrefix;
        private final @NonNull PrintWriter mWriter;
        private final int mFilterUid;
        private final String mFilterPackage;
        private final int mFilterOp;

        StringDumpVisitor(@NonNull String prefix, @NonNull PrintWriter writer,
                int filterUid, String filterPackage, int filterOp) {
            mOpsPrefix = prefix + "  ";
            mUidPrefix = mOpsPrefix + "  ";
            mPackagePrefix = mUidPrefix + "  ";
            mEntryPrefix = mPackagePrefix + "  ";
            mUidStatePrefix = mEntryPrefix + "  ";
            mWriter = writer;
            mFilterUid = filterUid;
            mFilterPackage = filterPackage;
            mFilterOp = filterOp;
        }

        @Override
        public void visitHistoricalOps(HistoricalOps ops) {
            mWriter.println();
            mWriter.print(mOpsPrefix);
            mWriter.println("snapshot:");
            mWriter.print(mUidPrefix);
            mWriter.print("begin = ");
            mDate.setTime(ops.getBeginTimeMillis());
            mWriter.print(mDateFormatter.format(mDate));
            mWriter.print("  (");
            TimeUtils.formatDuration(ops.getBeginTimeMillis() - mNow, mWriter);
            mWriter.println(")");
            mWriter.print(mUidPrefix);
            mWriter.print("end = ");
            mDate.setTime(ops.getEndTimeMillis());
            mWriter.print(mDateFormatter.format(mDate));
            mWriter.print("  (");
            TimeUtils.formatDuration(ops.getEndTimeMillis() - mNow, mWriter);
            mWriter.println(")");
        }

        @Override
        public void visitHistoricalUidOps(HistoricalUidOps ops) {
            if (mFilterUid != Process.INVALID_UID && mFilterUid != ops.getUid()) {
                return;
            }
            mWriter.println();
            mWriter.print(mUidPrefix);
            mWriter.print("Uid ");
            UserHandle.formatUid(mWriter, ops.getUid());
            mWriter.println(":");
        }

        @Override
        public void visitHistoricalPackageOps(HistoricalPackageOps ops) {
            if (mFilterPackage != null && !mFilterPackage.equals(ops.getPackageName())) {
                return;
            }
            mWriter.print(mPackagePrefix);
            mWriter.print("Package ");
            mWriter.print(ops.getPackageName());
            mWriter.println(":");
        }

        @Override
        public void visitHistoricalOp(HistoricalOp ops) {
            if (mFilterOp != AppOpsManager.OP_NONE && mFilterOp != ops.getOpCode()) {
                return;
            }
            mWriter.print(mEntryPrefix);
            mWriter.print(AppOpsManager.opToName(ops.getOpCode()));
            mWriter.println(":");
            for (int uidState = 0; uidState < AppOpsManager._NUM_UID_STATE; uidState++) {
                boolean printedUidState = false;
                final long accessCount = ops.getAccessCount(uidState);
                if (accessCount > 0) {
                    if (!printedUidState) {
                        mWriter.print(mUidStatePrefix);
                        mWriter.print(AppOpsService.UID_STATE_NAMES[uidState]);
                        mWriter.print(" = ");
                        printedUidState = true;
                    }
                    mWriter.print("access=");
                    mWriter.print(accessCount);
                }
                final long rejectCount = ops.getRejectCount(uidState);
                if (rejectCount > 0) {
                    if (!printedUidState) {
                        mWriter.print(mUidStatePrefix);
                        mWriter.print(AppOpsService.UID_STATE_NAMES[uidState]);
                        mWriter.print(" = ");
                        printedUidState = true;
                    } else {
                        mWriter.print(", ");
                    }
                    mWriter.print("reject=");
                    mWriter.print(rejectCount);
                }
                final long accessDuration = ops.getAccessDuration(uidState);
                if (accessDuration > 0) {
                    if (!printedUidState) {
                        mWriter.print(mUidStatePrefix);
                        mWriter.print(AppOpsService.UID_STATE_NAMES[uidState]);
                        mWriter.print(" = ");
                        printedUidState = true;
                    } else {
                        mWriter.print(", ");
                    }
                    mWriter.print("duration=");
                    TimeUtils.formatDuration(accessDuration, mWriter);
                }
                if (printedUidState) {
                    mWriter.println("");
                }
            }
        }
    }
}
