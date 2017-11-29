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
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.content.Context;
import android.os.IDeviceIdleController;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.ForceAppStandbyTracker;
import com.android.server.ForceAppStandbyTracker.Listener;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;

import java.io.PrintWriter;

public final class BackgroundJobsController extends StateController {

    private static final String LOG_TAG = "BackgroundJobsController";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    // Singleton factory
    private static final Object sCreationLock = new Object();
    private static volatile BackgroundJobsController sController;

    private final JobSchedulerService mJobSchedulerService;
    private final IDeviceIdleController mDeviceIdleController;

    private final ForceAppStandbyTracker mForceAppStandbyTracker;


    public static BackgroundJobsController get(JobSchedulerService service) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new BackgroundJobsController(service, service.getContext(),
                        service.getLock());
            }
            return sController;
        }
    }

    private BackgroundJobsController(JobSchedulerService service, Context context, Object lock) {
        super(service, context, lock);
        mJobSchedulerService = service;
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

        mForceAppStandbyTracker = ForceAppStandbyTracker.getInstance(context);

        mForceAppStandbyTracker.addListener(mForceAppStandbyListener);
        mForceAppStandbyTracker.start();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateSingleJobRestrictionLocked(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
    }

    @Override
    public void dumpControllerStateLocked(final PrintWriter pw, final int filterUid) {
        pw.println("BackgroundJobsController");

        mForceAppStandbyTracker.dump(pw, "");

        pw.println("Job state:");
        mJobSchedulerService.getJobStore().forEachJob((jobStatus) -> {
            if (!jobStatus.shouldDump(filterUid)) {
                return;
            }
            final int uid = jobStatus.getSourceUid();
            pw.print("  #");
            jobStatus.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, uid);
            pw.print(mForceAppStandbyTracker.isInForeground(uid) ? " foreground" : " background");
            if (mForceAppStandbyTracker.isUidPowerSaveWhitelisted(uid) ||
                    mForceAppStandbyTracker.isUidTempPowerSaveWhitelisted(uid)) {
                pw.print(", whitelisted");
            }
            pw.print(": ");
            pw.print(jobStatus.getSourcePackageName());

            pw.print(" [RUN_ANY_IN_BACKGROUND ");
            pw.print(mForceAppStandbyTracker.isRunAnyInBackgroundAppOpsAllowed(
                    jobStatus.getSourceUid(), jobStatus.getSourcePackageName())
                    ? "allowed]" : "disallowed]");

            if ((jobStatus.satisfiedConstraints
                    & JobStatus.CONSTRAINT_BACKGROUND_NOT_RESTRICTED) != 0) {
                pw.println(" RUNNABLE");
            } else {
                pw.println(" WAITING");
            }
        });
    }

    private void updateAllJobRestrictionsLocked() {
        updateJobRestrictionsLocked(/*filterUid=*/ -1);
    }

    private void updateJobRestrictionsForUidLocked(int uid) {

        // TODO Use forEachJobForSourceUid() once we have it.

        updateJobRestrictionsLocked(/*filterUid=*/ uid);
    }

    private void updateJobRestrictionsLocked(int filterUid) {
        final UpdateJobFunctor updateTrackedJobs =
                new UpdateJobFunctor(filterUid);

        final long start = DEBUG ? SystemClock.elapsedRealtimeNanos() : 0;

        mJobSchedulerService.getJobStore().forEachJob(updateTrackedJobs);

        final long time = DEBUG ? (SystemClock.elapsedRealtimeNanos() - start) : 0;
        if (DEBUG) {
            Slog.d(LOG_TAG, String.format(
                    "Job status updated: %d/%d checked/total jobs, %d us",
                    updateTrackedJobs.mCheckedCount,
                    updateTrackedJobs.mTotalCount,
                    (time / 1000)
                    ));
        }

        if (updateTrackedJobs.mChanged) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus) {

        final int uid = jobStatus.getSourceUid();
        final String packageName = jobStatus.getSourcePackageName();

        final boolean canRun = !mForceAppStandbyTracker.areJobsRestricted(uid, packageName);

        return jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRun);
    }

    private final class UpdateJobFunctor implements JobStore.JobStatusFunctor {
        private final int mFilterUid;

        boolean mChanged = false;
        int mTotalCount = 0;
        int mCheckedCount = 0;

        UpdateJobFunctor(int filterUid) {
            mFilterUid = filterUid;
        }

        @Override
        public void process(JobStatus jobStatus) {
            mTotalCount++;
            if ((mFilterUid > 0) && (mFilterUid != jobStatus.getSourceUid())) {
                return;
            }
            mCheckedCount++;
            if (updateSingleJobRestrictionLocked(jobStatus)) {
                mChanged = true;
            }
        }
    }

    private final Listener mForceAppStandbyListener = new Listener() {
        @Override
        public void updateAllJobs() {
            updateAllJobRestrictionsLocked();
        }

        @Override
        public void updateJobsForUid(int uid) {
            updateJobRestrictionsForUidLocked(uid);
        }

        @Override
        public void updateJobsForUidPackage(int uid, String packageName) {
            updateJobRestrictionsForUidLocked(uid);
        }
    };
}
