/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import static android.app.usage.UsageStatsManager.REASON_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_FORCED;
import static android.app.usage.UsageStatsManager.REASON_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_USAGE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;

import android.app.usage.UsageStatsManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps track of recent active state changes in apps.
 * Access should be guarded by a lock by the caller.
 */
public class AppIdleHistory {

    private static final String TAG = "AppIdleHistory";

    private static final boolean DEBUG = AppStandbyController.DEBUG;

    // History for all users and all packages
    private SparseArray<ArrayMap<String,AppUsageHistory>> mIdleHistory = new SparseArray<>();
    private static final long ONE_MINUTE = 60 * 1000;

    @VisibleForTesting
    static final String APP_IDLE_FILENAME = "app_idle_stats.xml";
    private static final String TAG_PACKAGES = "packages";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    // Screen on timebase time when app was last used
    private static final String ATTR_SCREEN_IDLE = "screenIdleTime";
    // Elapsed timebase time when app was last used
    private static final String ATTR_ELAPSED_IDLE = "elapsedIdleTime";
    // Elapsed timebase time when the app bucket was last predicted externally
    private static final String ATTR_LAST_PREDICTED_TIME = "lastPredictedTime";
    // The standby bucket for the app
    private static final String ATTR_CURRENT_BUCKET = "appLimitBucket";
    // The reason the app was put in the above bucket
    private static final String ATTR_BUCKETING_REASON = "bucketReason";
    // The last time a job was run for this app
    private static final String ATTR_LAST_RUN_JOB_TIME = "lastJobRunTime";
    // The time when the forced active state can be overridden.
    private static final String ATTR_BUCKET_TIMEOUT_TIME = "bucketTimeoutTime";

    // device on time = mElapsedDuration + (timeNow - mElapsedSnapshot)
    private long mElapsedSnapshot; // Elapsed time snapshot when last write of mDeviceOnDuration
    private long mElapsedDuration; // Total device on duration since device was "born"

    // screen on time = mScreenOnDuration + (timeNow - mScreenOnSnapshot)
    private long mScreenOnSnapshot; // Elapsed time snapshot when last write of mScreenOnDuration
    private long mScreenOnDuration; // Total screen on duration since device was "born"

    private final File mStorageDir;

    private boolean mScreenOn;

    static class AppUsageHistory {
        // Last used time using elapsed timebase
        long lastUsedElapsedTime;
        // Last used time using screen_on timebase
        long lastUsedScreenTime;
        // Last predicted time using elapsed timebase
        long lastPredictedTime;
        // Standby bucket
        @UsageStatsManager.StandbyBuckets
        int currentBucket;
        // Reason for setting the standby bucket. TODO: Switch to int.
        String bucketingReason;
        // In-memory only, last bucket for which the listeners were informed
        int lastInformedBucket;
        // The last time a job was run for this app, using elapsed timebase
        long lastJobRunTime;
        // When should the bucket state timeout, in elapsed timebase, if greater than
        // lastUsedElapsedTime.
        // This is used to keep the app in a high bucket regardless of other timeouts and
        // predictions.
        long bucketTimeoutTime;
    }

    AppIdleHistory(File storageDir, long elapsedRealtime) {
        mElapsedSnapshot = elapsedRealtime;
        mScreenOnSnapshot = elapsedRealtime;
        mStorageDir = storageDir;
        readScreenOnTime();
    }

    public void updateDisplay(boolean screenOn, long elapsedRealtime) {
        if (screenOn == mScreenOn) return;

        mScreenOn = screenOn;
        if (mScreenOn) {
            mScreenOnSnapshot = elapsedRealtime;
        } else {
            mScreenOnDuration += elapsedRealtime - mScreenOnSnapshot;
            mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
            mElapsedSnapshot = elapsedRealtime;
        }
        if (DEBUG) Slog.d(TAG, "mScreenOnSnapshot=" + mScreenOnSnapshot
                + ", mScreenOnDuration=" + mScreenOnDuration
                + ", mScreenOn=" + mScreenOn);
    }

    public long getScreenOnTime(long elapsedRealtime) {
        long screenOnTime = mScreenOnDuration;
        if (mScreenOn) {
            screenOnTime += elapsedRealtime - mScreenOnSnapshot;
        }
        return screenOnTime;
    }

    @VisibleForTesting
    File getScreenOnTimeFile() {
        return new File(mStorageDir, "screen_on_time");
    }

    private void readScreenOnTime() {
        File screenOnTimeFile = getScreenOnTimeFile();
        if (screenOnTimeFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(screenOnTimeFile));
                mScreenOnDuration = Long.parseLong(reader.readLine());
                mElapsedDuration = Long.parseLong(reader.readLine());
                reader.close();
            } catch (IOException | NumberFormatException e) {
            }
        } else {
            writeScreenOnTime();
        }
    }

    private void writeScreenOnTime() {
        AtomicFile screenOnTimeFile = new AtomicFile(getScreenOnTimeFile());
        FileOutputStream fos = null;
        try {
            fos = screenOnTimeFile.startWrite();
            fos.write((Long.toString(mScreenOnDuration) + "\n"
                    + Long.toString(mElapsedDuration) + "\n").getBytes());
            screenOnTimeFile.finishWrite(fos);
        } catch (IOException ioe) {
            screenOnTimeFile.failWrite(fos);
        }
    }

    /**
     * To be called periodically to keep track of elapsed time when app idle times are written
     */
    public void writeAppIdleDurations() {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        // Only bump up and snapshot the elapsed time. Don't change screen on duration.
        mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
        mElapsedSnapshot = elapsedRealtime;
        writeScreenOnTime();
    }

    /**
     * Mark the app as used and update the bucket if necessary. If there is a timeout specified
     * that's in the future, then the usage event is temporary and keeps the app in the specified
     * bucket at least until the timeout is reached. This can be used to keep the app in an
     * elevated bucket for a while until some important task gets to run.
     * @param packageName
     * @param userId
     * @param bucket the bucket to set the app to
     * @param elapsedRealtime mark as used time if non-zero
     * @param timeout set the timeout of the specified bucket, if non-zero
     * @return
     */
    public int reportUsage(String packageName, int userId, int bucket, long elapsedRealtime,
            long timeout) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, true);

        if (elapsedRealtime != 0) {
            appUsageHistory.lastUsedElapsedTime = mElapsedDuration
                    + (elapsedRealtime - mElapsedSnapshot);
            appUsageHistory.lastUsedScreenTime = getScreenOnTime(elapsedRealtime);
        }

        if (appUsageHistory.currentBucket > bucket) {
            appUsageHistory.currentBucket = bucket;
            if (DEBUG) {
                Slog.d(TAG, "Moved " + packageName + " to bucket=" + appUsageHistory
                        .currentBucket
                        + ", reason=" + appUsageHistory.bucketingReason);
            }
            if (timeout > elapsedRealtime) {
                // Convert to elapsed timebase
                appUsageHistory.bucketTimeoutTime = mElapsedDuration + (timeout - mElapsedSnapshot);
            }
        }
        appUsageHistory.bucketingReason = REASON_USAGE;

        return appUsageHistory.currentBucket;
    }

    private ArrayMap<String, AppUsageHistory> getUserHistory(int userId) {
        ArrayMap<String, AppUsageHistory> userHistory = mIdleHistory.get(userId);
        if (userHistory == null) {
            userHistory = new ArrayMap<>();
            mIdleHistory.put(userId, userHistory);
            readAppIdleTimes(userId, userHistory);
        }
        return userHistory;
    }

    private AppUsageHistory getPackageHistory(ArrayMap<String, AppUsageHistory> userHistory,
            String packageName, long elapsedRealtime, boolean create) {
        AppUsageHistory appUsageHistory = userHistory.get(packageName);
        if (appUsageHistory == null && create) {
            appUsageHistory = new AppUsageHistory();
            appUsageHistory.lastUsedElapsedTime = getElapsedTime(elapsedRealtime);
            appUsageHistory.lastUsedScreenTime = getScreenOnTime(elapsedRealtime);
            appUsageHistory.lastPredictedTime = getElapsedTime(0);
            appUsageHistory.currentBucket = STANDBY_BUCKET_NEVER;
            appUsageHistory.bucketingReason = REASON_DEFAULT;
            appUsageHistory.lastInformedBucket = -1;
            appUsageHistory.lastJobRunTime = Long.MIN_VALUE; // long long time ago
            userHistory.put(packageName, appUsageHistory);
        }
        return appUsageHistory;
    }

    public void onUserRemoved(int userId) {
        mIdleHistory.remove(userId);
    }

    public boolean isIdle(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        if (appUsageHistory == null) {
            return false; // Default to not idle
        } else {
            return appUsageHistory.currentBucket >= STANDBY_BUCKET_RARE;
            // Whether or not it's passed will now be externally calculated and the
            // bucket will be pushed to the history using setAppStandbyBucket()
            //return hasPassedThresholds(appUsageHistory, elapsedRealtime);
        }
    }

    public AppUsageHistory getAppUsageHistory(String packageName, int userId,
            long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        return appUsageHistory;
    }

    public void setAppStandbyBucket(String packageName, int userId, long elapsedRealtime,
            int bucket, String reason) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        appUsageHistory.currentBucket = bucket;
        appUsageHistory.bucketingReason = reason;
        if (reason.startsWith(REASON_PREDICTED)) {
            appUsageHistory.lastPredictedTime = getElapsedTime(elapsedRealtime);
        }
        if (DEBUG) {
            Slog.d(TAG, "Moved " + packageName + " to bucket=" + appUsageHistory.currentBucket
                    + ", reason=" + appUsageHistory.bucketingReason);
        }
    }

    /**
     * Marks the last time a job was run, with the given elapsedRealtime. The time stored is
     * based on the elapsed timebase.
     * @param packageName
     * @param userId
     * @param elapsedRealtime
     */
    public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        appUsageHistory.lastJobRunTime = getElapsedTime(elapsedRealtime);
    }

    /**
     * Returns the time since the last job was run for this app. This can be larger than the
     * current elapsedRealtime, in case it happened before boot or a really large value if no jobs
     * were ever run.
     * @param packageName
     * @param userId
     * @param elapsedRealtime
     * @return
     */
    public long getTimeSinceLastJobRun(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        // Don't adjust the default, else it'll wrap around to a positive value
        if (appUsageHistory.lastJobRunTime == Long.MIN_VALUE) return Long.MAX_VALUE;
        return getElapsedTime(elapsedRealtime) - appUsageHistory.lastJobRunTime;
    }

    public int getAppStandbyBucket(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        return appUsageHistory.currentBucket;
    }

    public Map<String, Integer> getAppStandbyBuckets(int userId, long elapsedRealtime,
            boolean appIdleEnabled) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        int size = userHistory.size();
        HashMap<String, Integer> buckets = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            buckets.put(userHistory.keyAt(i),
                    appIdleEnabled ? userHistory.valueAt(i).currentBucket : STANDBY_BUCKET_ACTIVE);
        }
        return buckets;
    }

    public String getAppStandbyReason(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, false);
        return appUsageHistory != null ? appUsageHistory.bucketingReason : null;
    }

    public long getElapsedTime(long elapsedRealtime) {
        return (elapsedRealtime - mElapsedSnapshot + mElapsedDuration);
    }

    /* Returns the new standby bucket the app is assigned to */
    public int setIdle(String packageName, int userId, boolean idle, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, true);
        if (idle) {
            appUsageHistory.currentBucket = STANDBY_BUCKET_RARE;
            appUsageHistory.bucketingReason = REASON_FORCED;
        } else {
            appUsageHistory.currentBucket = STANDBY_BUCKET_ACTIVE;
            // This is to pretend that the app was just used, don't freeze the state anymore.
            appUsageHistory.bucketingReason = REASON_USAGE;
        }
        return appUsageHistory.currentBucket;
    }

    public void clearUsage(String packageName, int userId) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        userHistory.remove(packageName);
    }

    boolean shouldInformListeners(String packageName, int userId,
            long elapsedRealtime, int bucket) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, true);
        if (appUsageHistory.lastInformedBucket != bucket) {
            appUsageHistory.lastInformedBucket = bucket;
            return true;
        }
        return false;
    }

    /**
     * Returns the index in the arrays of screenTimeThresholds and elapsedTimeThresholds
     * that corresponds to how long since the app was used.
     * @param packageName
     * @param userId
     * @param elapsedRealtime current time
     * @param screenTimeThresholds Array of screen times, in ascending order, first one is 0
     * @param elapsedTimeThresholds Array of elapsed time, in ascending order, first one is 0
     * @return The index whose values the app's used time exceeds (in both arrays)
     */
    int getThresholdIndex(String packageName, int userId, long elapsedRealtime,
            long[] screenTimeThresholds, long[] elapsedTimeThresholds) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, false);
        // If we don't have any state for the app, assume never used
        if (appUsageHistory == null) return screenTimeThresholds.length - 1;

        long screenOnDelta = getScreenOnTime(elapsedRealtime) - appUsageHistory.lastUsedScreenTime;
        long elapsedDelta = getElapsedTime(elapsedRealtime) - appUsageHistory.lastUsedElapsedTime;

        if (DEBUG) Slog.d(TAG, packageName
                + " lastUsedScreen=" + appUsageHistory.lastUsedScreenTime
                + " lastUsedElapsed=" + appUsageHistory.lastUsedElapsedTime);
        if (DEBUG) Slog.d(TAG, packageName + " screenOn=" + screenOnDelta
                + ", elapsed=" + elapsedDelta);
        for (int i = screenTimeThresholds.length - 1; i >= 0; i--) {
            if (screenOnDelta >= screenTimeThresholds[i]
                && elapsedDelta >= elapsedTimeThresholds[i]) {
                return i;
            }
        }
        return 0;
    }

    @VisibleForTesting
    File getUserFile(int userId) {
        return new File(new File(new File(mStorageDir, "users"),
                Integer.toString(userId)), APP_IDLE_FILENAME);
    }

    private void readAppIdleTimes(int userId, ArrayMap<String, AppUsageHistory> userHistory) {
        FileInputStream fis = null;
        try {
            AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
            fis = appIdleFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Skip
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(TAG, "Unable to read app idle file for user " + userId);
                return;
            }
            if (!parser.getName().equals(TAG_PACKAGES)) {
                return;
            }
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_PACKAGE)) {
                        final String packageName = parser.getAttributeValue(null, ATTR_NAME);
                        AppUsageHistory appUsageHistory = new AppUsageHistory();
                        appUsageHistory.lastUsedElapsedTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_ELAPSED_IDLE));
                        appUsageHistory.lastUsedScreenTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_SCREEN_IDLE));
                        appUsageHistory.lastPredictedTime = getLongValue(parser,
                                ATTR_LAST_PREDICTED_TIME, 0L);
                        String currentBucketString = parser.getAttributeValue(null,
                                ATTR_CURRENT_BUCKET);
                        appUsageHistory.currentBucket = currentBucketString == null
                                ? STANDBY_BUCKET_ACTIVE
                                : Integer.parseInt(currentBucketString);
                        appUsageHistory.bucketingReason =
                                parser.getAttributeValue(null, ATTR_BUCKETING_REASON);
                        appUsageHistory.lastJobRunTime = getLongValue(parser,
                                ATTR_LAST_RUN_JOB_TIME, Long.MIN_VALUE);
                        appUsageHistory.bucketTimeoutTime = getLongValue(parser,
                                ATTR_BUCKET_TIMEOUT_TIME, 0L);
                        if (appUsageHistory.bucketingReason == null) {
                            appUsageHistory.bucketingReason = REASON_DEFAULT;
                        }
                        appUsageHistory.lastInformedBucket = -1;
                        userHistory.put(packageName, appUsageHistory);
                    }
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Unable to read app idle file for user " + userId);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private long getLongValue(XmlPullParser parser, String attrName, long defValue) {
        String value = parser.getAttributeValue(null, attrName);
        if (value == null) return defValue;
        return Long.parseLong(value);
    }

    public void writeAppIdleTimes(int userId) {
        FileOutputStream fos = null;
        AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
        try {
            fos = appIdleFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(bos, StandardCharsets.UTF_8.name());
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            xml.startTag(null, TAG_PACKAGES);

            ArrayMap<String,AppUsageHistory> userHistory = getUserHistory(userId);
            final int N = userHistory.size();
            for (int i = 0; i < N; i++) {
                String packageName = userHistory.keyAt(i);
                AppUsageHistory history = userHistory.valueAt(i);
                xml.startTag(null, TAG_PACKAGE);
                xml.attribute(null, ATTR_NAME, packageName);
                xml.attribute(null, ATTR_ELAPSED_IDLE,
                        Long.toString(history.lastUsedElapsedTime));
                xml.attribute(null, ATTR_SCREEN_IDLE,
                        Long.toString(history.lastUsedScreenTime));
                xml.attribute(null, ATTR_LAST_PREDICTED_TIME,
                        Long.toString(history.lastPredictedTime));
                xml.attribute(null, ATTR_CURRENT_BUCKET,
                        Integer.toString(history.currentBucket));
                xml.attribute(null, ATTR_BUCKETING_REASON, history.bucketingReason);
                if (history.bucketTimeoutTime > 0) {
                    xml.attribute(null, ATTR_BUCKET_TIMEOUT_TIME, Long.toString(history
                            .bucketTimeoutTime));
                }
                if (history.lastJobRunTime != Long.MIN_VALUE) {
                    xml.attribute(null, ATTR_LAST_RUN_JOB_TIME, Long.toString(history
                            .lastJobRunTime));
                }
                xml.endTag(null, TAG_PACKAGE);
            }

            xml.endTag(null, TAG_PACKAGES);
            xml.endDocument();
            appIdleFile.finishWrite(fos);
        } catch (Exception e) {
            appIdleFile.failWrite(fos);
            Slog.e(TAG, "Error writing app idle file for user " + userId);
        }
    }

    public void dump(IndentingPrintWriter idpw, int userId, String pkg) {
        idpw.println("Package idle stats:");
        idpw.increaseIndent();
        ArrayMap<String, AppUsageHistory> userHistory = mIdleHistory.get(userId);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long totalElapsedTime = getElapsedTime(elapsedRealtime);
        final long screenOnTime = getScreenOnTime(elapsedRealtime);
        if (userHistory == null) return;
        final int P = userHistory.size();
        for (int p = 0; p < P; p++) {
            final String packageName = userHistory.keyAt(p);
            final AppUsageHistory appUsageHistory = userHistory.valueAt(p);
            if (pkg != null && !pkg.equals(packageName)) {
                continue;
            }
            idpw.print("package=" + packageName);
            idpw.print(" lastUsedElapsed=");
            TimeUtils.formatDuration(totalElapsedTime - appUsageHistory.lastUsedElapsedTime, idpw);
            idpw.print(" lastUsedScreenOn=");
            TimeUtils.formatDuration(screenOnTime - appUsageHistory.lastUsedScreenTime, idpw);
            idpw.print(" lastPredictedTime=");
            TimeUtils.formatDuration(totalElapsedTime - appUsageHistory.lastPredictedTime, idpw);
            idpw.print(" bucketTimeoutTime=");
            TimeUtils.formatDuration(totalElapsedTime - appUsageHistory.bucketTimeoutTime, idpw);
            idpw.print(" lastJobRunTime=");
            TimeUtils.formatDuration(totalElapsedTime - appUsageHistory.lastJobRunTime, idpw);
            idpw.print(" idle=" + (isIdle(packageName, userId, elapsedRealtime) ? "y" : "n"));
            idpw.print(" bucket=" + appUsageHistory.currentBucket
                    + " reason=" + appUsageHistory.bucketingReason);
            idpw.println();
        }
        idpw.println();
        idpw.print("totalElapsedTime=");
        TimeUtils.formatDuration(getElapsedTime(elapsedRealtime), idpw);
        idpw.println();
        idpw.print("totalScreenOnTime=");
        TimeUtils.formatDuration(getScreenOnTime(elapsedRealtime), idpw);
        idpw.println();
        idpw.decreaseIndent();
    }
}
