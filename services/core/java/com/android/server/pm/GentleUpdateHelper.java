/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.WorkerThread;
import android.app.ActivityThread;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInstaller.InstallConstraints;
import android.content.pm.PackageInstaller.InstallConstraintsResult;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Slog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A helper class to coordinate install flow for sessions with install constraints.
 * These sessions will be pending and wait until the constraints are satisfied to
 * resume installation.
 */
public class GentleUpdateHelper {
    private static final String TAG = "GentleUpdateHelper";
    private static final int JOB_ID = 235306967; // bug id
    // The timeout used to determine whether the device is idle or not.
    private static final long PENDING_CHECK_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /**
     * A wrapper class used by JobScheduler to schedule jobs.
     */
    public static class Service extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            try {
                var pis = (PackageInstallerService) ActivityThread.getPackageManager()
                        .getPackageInstaller();
                var helper = pis.getGentleUpdateHelper();
                helper.mHandler.post(helper::runIdleJob);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get PackageInstallerService", e);
            }
            return false;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }

    private static class PendingInstallConstraintsCheck {
        public final List<String> packageNames;
        public final InstallConstraints constraints;
        public final CompletableFuture<InstallConstraintsResult> future;
        private final long mFinishTime;

        /**
         * Note {@code timeoutMillis} will be clamped to 0 ~ one week to avoid overflow.
         */
        PendingInstallConstraintsCheck(List<String> packageNames,
                InstallConstraints constraints,
                CompletableFuture<InstallConstraintsResult> future,
                long timeoutMillis) {
            this.packageNames = packageNames;
            this.constraints = constraints;
            this.future = future;

            timeoutMillis = Math.max(0, Math.min(DateUtils.WEEK_IN_MILLIS, timeoutMillis));
            mFinishTime = SystemClock.elapsedRealtime() + timeoutMillis;
        }
        public boolean isTimedOut() {
            return SystemClock.elapsedRealtime() >= mFinishTime;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final AppStateHelper mAppStateHelper;
    // Worker thread only
    private final ArrayDeque<PendingInstallConstraintsCheck> mPendingChecks = new ArrayDeque<>();
    private boolean mHasPendingIdleJob;

    GentleUpdateHelper(Context context, Looper looper, AppStateHelper appStateHelper) {
        mContext = context;
        mHandler = new Handler(looper);
        mAppStateHelper = appStateHelper;
    }

    /**
     * Checks if install constraints are satisfied for the given packages.
     */
    CompletableFuture<InstallConstraintsResult> checkInstallConstraints(
            List<String> packageNames, InstallConstraints constraints,
            long timeoutMillis) {
        var future = new CompletableFuture<InstallConstraintsResult>();
        mHandler.post(() -> {
            long clampedTimeoutMillis = timeoutMillis;
            if (constraints.isRequireDeviceIdle()) {
                // Device-idle-constraint is required. Clamp the timeout to ensure
                // timeout-check happens after device-idle-check.
                clampedTimeoutMillis = Math.max(timeoutMillis, PENDING_CHECK_MILLIS);
            }

            var pendingCheck = new PendingInstallConstraintsCheck(
                    packageNames, constraints, future, clampedTimeoutMillis);
            if (constraints.isRequireDeviceIdle()) {
                mPendingChecks.add(pendingCheck);
                // JobScheduler doesn't provide queries about whether the device is idle.
                // We schedule 2 tasks to determine device idle. If the idle job is executed
                // before the delayed runnable, we know the device is idle.
                // Note #processPendingCheck will be no-op for the task executed later.
                scheduleIdleJob();
                mHandler.postDelayed(() -> processPendingCheck(pendingCheck, false),
                        PENDING_CHECK_MILLIS);
            } else if (!processPendingCheck(pendingCheck, false)) {
                // Not resolved. Schedule a job for re-check
                // TODO(b/235306967): Listen to OnUidImportanceListener for package
                //  importance changes. This will resolve pending checks as soon as
                //  top-visible or foreground constraints are satisfied.
                mPendingChecks.add(pendingCheck);
                scheduleIdleJob();
            }

            if (!future.isDone()) {
                // Ensure the pending check is resolved after timeout, no matter constraints
                // satisfied or not.
                mHandler.postDelayed(() -> processPendingCheck(pendingCheck, false),
                        clampedTimeoutMillis);
            }
        });
        return future;
    }

    @WorkerThread
    private void scheduleIdleJob() {
        if (mHasPendingIdleJob) {
            // No need to schedule the job again
            return;
        }
        mHasPendingIdleJob = true;
        var componentName = new ComponentName(
                mContext.getPackageName(), GentleUpdateHelper.Service.class.getName());
        var jobInfo = new JobInfo.Builder(JOB_ID, componentName)
                .setRequiresDeviceIdle(true)
                .build();
        var jobScheduler = mContext.getSystemService(JobScheduler.class);
        jobScheduler.schedule(jobInfo);
    }

    @WorkerThread
    private void runIdleJob() {
        mHasPendingIdleJob = false;
        processPendingChecksInIdle();
    }

    @WorkerThread
    private boolean areConstraintsSatisfied(List<String> packageNames,
            InstallConstraints constraints, boolean isIdle) {
        return (!constraints.isRequireDeviceIdle() || isIdle)
                && (!constraints.isRequireAppNotForeground()
                || !mAppStateHelper.hasForegroundApp(packageNames))
                && (!constraints.isRequireAppNotInteracting()
                || !mAppStateHelper.hasInteractingApp(packageNames))
                && (!constraints.isRequireAppNotTopVisible()
                || !mAppStateHelper.hasTopVisibleApp(packageNames))
                && (!constraints.isRequireNotInCall()
                || !mAppStateHelper.isInCall());
    }

    @WorkerThread
    private boolean processPendingCheck(
            PendingInstallConstraintsCheck pendingCheck, boolean isIdle) {
        var future = pendingCheck.future;
        if (future.isDone()) {
            return true;
        }
        var constraints = pendingCheck.constraints;
        var packageNames = mAppStateHelper.getDependencyPackages(pendingCheck.packageNames);
        var satisfied = areConstraintsSatisfied(packageNames, constraints, isIdle);
        if (satisfied || pendingCheck.isTimedOut()) {
            future.complete(new InstallConstraintsResult((satisfied)));
            return true;
        }
        return false;
    }

    @WorkerThread
    private void processPendingChecksInIdle() {
        int size = mPendingChecks.size();
        for (int i = 0; i < size; ++i) {
            var pendingCheck = mPendingChecks.remove();
            if (!processPendingCheck(pendingCheck, true)) {
                // Not resolved. Put it back in the queue.
                mPendingChecks.add(pendingCheck);
            }
        }
        if (!mPendingChecks.isEmpty()) {
            // Schedule a job for remaining pending checks
            scheduleIdleJob();
        }
    }
}
