/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import static com.android.server.blob.BlobStoreConfig.IDLE_JOB_ID;
import static com.android.server.blob.BlobStoreConfig.IDLE_JOB_PERIOD_MILLIS;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;

import com.android.server.LocalServices;

/**
 * Maintenance job to clean up stale sessions and blobs.
 */
public class BlobStoreIdleJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        AsyncTask.execute(() -> {
            final BlobStoreManagerInternal blobStoreManagerInternal = LocalServices.getService(
                    BlobStoreManagerInternal.class);
            blobStoreManagerInternal.onIdleMaintenance();
            jobFinished(params, false);
        });
        return false;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        return false;
    }

    static void schedule(Context context) {
        final JobScheduler jobScheduler = (JobScheduler) context.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        final JobInfo job = new JobInfo.Builder(IDLE_JOB_ID,
                new ComponentName(context, BlobStoreIdleJobService.class))
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .setPeriodic(IDLE_JOB_PERIOD_MILLIS)
                        .build();
        jobScheduler.schedule(job);
    }
}
