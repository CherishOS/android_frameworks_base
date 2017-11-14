/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.job;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.app.job.JobInfo;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.AtomicFile;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.IoThread;
import com.android.server.job.JobSchedulerInternal.JobStorePersistStats;
import com.android.server.job.controllers.JobStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/**
 * Maintains the master list of jobs that the job scheduler is tracking. These jobs are compared by
 * reference, so none of the functions in this class should make a copy.
 * Also handles read/write of persisted jobs.
 *
 * Note on locking:
 *      All callers to this class must <strong>lock on the class object they are calling</strong>.
 *      This is important b/c {@link com.android.server.job.JobStore.WriteJobsMapToDiskRunnable}
 *      and {@link com.android.server.job.JobStore.ReadJobMapFromDiskRunnable} lock on that
 *      object.
 */
public final class JobStore {
    private static final String TAG = "JobStore";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    /** Threshold to adjust how often we want to write to the db. */
    private static final int MAX_OPS_BEFORE_WRITE = 1;

    final Object mLock;
    final JobSet mJobSet; // per-caller-uid tracking
    final Context mContext;

    // Bookkeeping around incorrect boot-time system clock
    private final long mXmlTimestamp;
    private boolean mRtcGood;

    private int mDirtyOperations;

    private static final Object sSingletonLock = new Object();
    private final AtomicFile mJobsFile;
    /** Handler backed by IoThread for writing to disk. */
    private final Handler mIoHandler = IoThread.getHandler();
    private static JobStore sSingleton;

    private JobStorePersistStats mPersistInfo = new JobStorePersistStats();

    /** Used by the {@link JobSchedulerService} to instantiate the JobStore. */
    static JobStore initAndGet(JobSchedulerService jobManagerService) {
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(jobManagerService.getContext(),
                        jobManagerService.getLock(), Environment.getDataDirectory());
            }
            return sSingleton;
        }
    }

    /**
     * @return A freshly initialized job store object, with no loaded jobs.
     */
    @VisibleForTesting
    public static JobStore initAndGetForTesting(Context context, File dataDir) {
        JobStore jobStoreUnderTest = new JobStore(context, new Object(), dataDir);
        jobStoreUnderTest.clear();
        return jobStoreUnderTest;
    }

    /**
     * Construct the instance of the job store. This results in a blocking read from disk.
     */
    private JobStore(Context context, Object lock, File dataDir) {
        mLock = lock;
        mContext = context;
        mDirtyOperations = 0;

        File systemDir = new File(dataDir, "system");
        File jobDir = new File(systemDir, "job");
        jobDir.mkdirs();
        mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"));

        mJobSet = new JobSet();

        // If the current RTC is earlier than the timestamp on our persisted jobs file,
        // we suspect that the RTC is uninitialized and so we cannot draw conclusions
        // about persisted job scheduling.
        //
        // Note that if the persisted jobs file does not exist, we proceed with the
        // assumption that the RTC is good.  This is less work and is safe: if the
        // clock updates to sanity then we'll be saving the persisted jobs file in that
        // correct state, which is normal; or we'll wind up writing the jobs file with
        // an incorrect historical timestamp.  That's fine; at worst we'll reboot with
        // a *correct* timestamp, see a bunch of overdue jobs, and run them; then
        // settle into normal operation.
        mXmlTimestamp = mJobsFile.getLastModifiedTime();
        mRtcGood = (System.currentTimeMillis() > mXmlTimestamp);

        readJobMapFromDisk(mJobSet, mRtcGood);
    }

    public boolean jobTimesInflatedValid() {
        return mRtcGood;
    }

    public boolean clockNowValidToInflate(long now) {
        return now >= mXmlTimestamp;
    }

    /**
     * Find all the jobs that were affected by RTC clock uncertainty at boot time.  Returns
     * parallel lists of the existing JobStatus objects and of new, equivalent JobStatus instances
     * with now-corrected time bounds.
     */
    public void getRtcCorrectedJobsLocked(final ArrayList<JobStatus> toAdd,
            final ArrayList<JobStatus> toRemove) {
        final long elapsedNow = SystemClock.elapsedRealtime();

        // Find the jobs that need to be fixed up, collecting them for post-iteration
        // replacement with their new versions
        forEachJob(job -> {
            final Pair<Long, Long> utcTimes = job.getPersistedUtcTimes();
            if (utcTimes != null) {
                Pair<Long, Long> elapsedRuntimes =
                        convertRtcBoundsToElapsed(utcTimes, elapsedNow);
                toAdd.add(new JobStatus(job, elapsedRuntimes.first, elapsedRuntimes.second,
                        0, job.getLastSuccessfulRunTime(), job.getLastFailedRunTime()));
                toRemove.add(job);
            }
        });
    }

    /**
     * Add a job to the master list, persisting it if necessary. If the JobStatus already exists,
     * it will be replaced.
     * @param jobStatus Job to add.
     * @return Whether or not an equivalent JobStatus was replaced by this operation.
     */
    public boolean add(JobStatus jobStatus) {
        boolean replaced = mJobSet.remove(jobStatus);
        mJobSet.add(jobStatus);
        if (jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        if (DEBUG) {
            Slog.d(TAG, "Added job status to store: " + jobStatus);
        }
        return replaced;
    }

    boolean containsJob(JobStatus jobStatus) {
        return mJobSet.contains(jobStatus);
    }

    public int size() {
        return mJobSet.size();
    }

    public JobStorePersistStats getPersistStats() {
        return mPersistInfo;
    }

    public int countJobsForUid(int uid) {
        return mJobSet.countJobsForUid(uid);
    }

    /**
     * Remove the provided job. Will also delete the job if it was persisted.
     * @param writeBack If true, the job will be deleted (if it was persisted) immediately.
     * @return Whether or not the job existed to be removed.
     */
    public boolean remove(JobStatus jobStatus, boolean writeBack) {
        boolean removed = mJobSet.remove(jobStatus);
        if (!removed) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't remove job: didn't exist: " + jobStatus);
            }
            return false;
        }
        if (writeBack && jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        return removed;
    }

    /**
     * Remove the jobs of users not specified in the whitelist.
     * @param whitelist Array of User IDs whose jobs are not to be removed.
     */
    public void removeJobsOfNonUsers(int[] whitelist) {
        mJobSet.removeJobsOfNonUsers(whitelist);
    }

    @VisibleForTesting
    public void clear() {
        mJobSet.clear();
        maybeWriteStatusToDiskAsync();
    }

    /**
     * @param userHandle User for whom we are querying the list of jobs.
     * @return A list of all the jobs scheduled by the provided user. Never null.
     */
    public List<JobStatus> getJobsByUser(int userHandle) {
        return mJobSet.getJobsByUser(userHandle);
    }

    /**
     * @param uid Uid of the requesting app.
     * @return All JobStatus objects for a given uid from the master list. Never null.
     */
    public List<JobStatus> getJobsByUid(int uid) {
        return mJobSet.getJobsByUid(uid);
    }

    /**
     * @param uid Uid of the requesting app.
     * @param jobId Job id, specified at schedule-time.
     * @return the JobStatus that matches the provided uId and jobId, or null if none found.
     */
    public JobStatus getJobByUidAndJobId(int uid, int jobId) {
        return mJobSet.get(uid, jobId);
    }

    /**
     * Iterate over the set of all jobs, invoking the supplied functor on each.  This is for
     * customers who need to examine each job; we'd much rather not have to generate
     * transient unified collections for them to iterate over and then discard, or creating
     * iterators every time a client needs to perform a sweep.
     */
    public void forEachJob(JobStatusFunctor functor) {
        mJobSet.forEachJob(functor);
    }

    public void forEachJob(int uid, JobStatusFunctor functor) {
        mJobSet.forEachJob(uid, functor);
    }

    public interface JobStatusFunctor {
        public void process(JobStatus jobStatus);
    }

    /** Version of the db schema. */
    private static final int JOBS_FILE_VERSION = 0;
    /** Tag corresponds to constraints this job needs. */
    private static final String XML_TAG_PARAMS_CONSTRAINTS = "constraints";
    /** Tag corresponds to execution parameters. */
    private static final String XML_TAG_PERIODIC = "periodic";
    private static final String XML_TAG_ONEOFF = "one-off";
    private static final String XML_TAG_EXTRAS = "extras";

    /**
     * Every time the state changes we write all the jobs in one swath, instead of trying to
     * track incremental changes.
     */
    private void maybeWriteStatusToDiskAsync() {
        mDirtyOperations++;
        if (mDirtyOperations >= MAX_OPS_BEFORE_WRITE) {
            if (DEBUG) {
                Slog.v(TAG, "Writing jobs to disk.");
            }
            mIoHandler.removeCallbacks(mWriteRunnable);
            mIoHandler.post(mWriteRunnable);
        }
    }

    @VisibleForTesting
    public void readJobMapFromDisk(JobSet jobSet, boolean rtcGood) {
        new ReadJobMapFromDiskRunnable(jobSet, rtcGood).run();
    }

    /**
     * Runnable that writes {@link #mJobSet} out to xml.
     * NOTE: This Runnable locks on mLock
     */
    private final Runnable mWriteRunnable = new Runnable() {
        @Override
        public void run() {
            final long startElapsed = SystemClock.elapsedRealtime();
            final List<JobStatus> storeCopy = new ArrayList<JobStatus>();
            synchronized (mLock) {
                // Clone the jobs so we can release the lock before writing.
                mJobSet.forEachJob(new JobStatusFunctor() {
                    @Override
                    public void process(JobStatus job) {
                        if (job.isPersisted()) {
                            storeCopy.add(new JobStatus(job));
                        }
                    }
                });
            }
            writeJobsMapImpl(storeCopy);
            if (DEBUG) {
                Slog.v(TAG, "Finished writing, took " + (SystemClock.elapsedRealtime()
                        - startElapsed) + "ms");
            }
        }

        private void writeJobsMapImpl(List<JobStatus> jobList) {
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(baos, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

                out.startTag(null, "job-info");
                out.attribute(null, "version", Integer.toString(JOBS_FILE_VERSION));
                for (int i=0; i<jobList.size(); i++) {
                    JobStatus jobStatus = jobList.get(i);
                    if (DEBUG) {
                        Slog.d(TAG, "Saving job " + jobStatus.getJobId());
                    }
                    out.startTag(null, "job");
                    addAttributesToJobTag(out, jobStatus);
                    writeConstraintsToXml(out, jobStatus);
                    writeExecutionCriteriaToXml(out, jobStatus);
                    writeBundleToXml(jobStatus.getJob().getExtras(), out);
                    out.endTag(null, "job");

                    numJobs++;
                    if (jobStatus.getUid() == Process.SYSTEM_UID) {
                        numSystemJobs++;
                        if (isSyncJob(jobStatus)) {
                            numSyncJobs++;
                        }
                    }
                }
                out.endTag(null, "job-info");
                out.endDocument();

                // Write out to disk in one fell swoop.
                FileOutputStream fos = mJobsFile.startWrite();
                fos.write(baos.toByteArray());
                mJobsFile.finishWrite(fos);
                mDirtyOperations = 0;
            } catch (IOException e) {
                if (DEBUG) {
                    Slog.v(TAG, "Error writing out job data.", e);
                }
            } catch (XmlPullParserException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error persisting bundle.", e);
                }
            } finally {
                mPersistInfo.countAllJobsSaved = numJobs;
                mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
            }
        }

        /** Write out a tag with data comprising the required fields and priority of this job and
         * its client.
         */
        private void addAttributesToJobTag(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            out.attribute(null, "jobid", Integer.toString(jobStatus.getJobId()));
            out.attribute(null, "package", jobStatus.getServiceComponent().getPackageName());
            out.attribute(null, "class", jobStatus.getServiceComponent().getClassName());
            if (jobStatus.getSourcePackageName() != null) {
                out.attribute(null, "sourcePackageName", jobStatus.getSourcePackageName());
            }
            if (jobStatus.getSourceTag() != null) {
                out.attribute(null, "sourceTag", jobStatus.getSourceTag());
            }
            out.attribute(null, "sourceUserId", String.valueOf(jobStatus.getSourceUserId()));
            out.attribute(null, "uid", Integer.toString(jobStatus.getUid()));
            out.attribute(null, "priority", String.valueOf(jobStatus.getPriority()));
            out.attribute(null, "flags", String.valueOf(jobStatus.getFlags()));

            out.attribute(null, "lastSuccessfulRunTime",
                    String.valueOf(jobStatus.getLastSuccessfulRunTime()));
            out.attribute(null, "lastFailedRunTime",
                    String.valueOf(jobStatus.getLastFailedRunTime()));
        }

        private void writeBundleToXml(PersistableBundle extras, XmlSerializer out)
                throws IOException, XmlPullParserException {
            out.startTag(null, XML_TAG_EXTRAS);
            PersistableBundle extrasCopy = deepCopyBundle(extras, 10);
            extrasCopy.saveToXml(out);
            out.endTag(null, XML_TAG_EXTRAS);
        }

        private PersistableBundle deepCopyBundle(PersistableBundle bundle, int maxDepth) {
            if (maxDepth <= 0) {
                return null;
            }
            PersistableBundle copy = (PersistableBundle) bundle.clone();
            Set<String> keySet = bundle.keySet();
            for (String key: keySet) {
                Object o = copy.get(key);
                if (o instanceof PersistableBundle) {
                    PersistableBundle bCopy = deepCopyBundle((PersistableBundle) o, maxDepth-1);
                    copy.putPersistableBundle(key, bCopy);
                }
            }
            return copy;
        }

        /**
         * Write out a tag with data identifying this job's constraints. If the constraint isn't here
         * it doesn't apply.
         */
        private void writeConstraintsToXml(XmlSerializer out, JobStatus jobStatus) throws IOException {
            out.startTag(null, XML_TAG_PARAMS_CONSTRAINTS);
            if (jobStatus.needsAnyConnectivity()) {
                out.attribute(null, "connectivity", Boolean.toString(true));
            }
            if (jobStatus.needsMeteredConnectivity()) {
                out.attribute(null, "metered", Boolean.toString(true));
            }
            if (jobStatus.needsUnmeteredConnectivity()) {
                out.attribute(null, "unmetered", Boolean.toString(true));
            }
            if (jobStatus.needsNonRoamingConnectivity()) {
                out.attribute(null, "not-roaming", Boolean.toString(true));
            }
            if (jobStatus.hasIdleConstraint()) {
                out.attribute(null, "idle", Boolean.toString(true));
            }
            if (jobStatus.hasChargingConstraint()) {
                out.attribute(null, "charging", Boolean.toString(true));
            }
            if (jobStatus.hasBatteryNotLowConstraint()) {
                out.attribute(null, "battery-not-low", Boolean.toString(true));
            }
            out.endTag(null, XML_TAG_PARAMS_CONSTRAINTS);
        }

        private void writeExecutionCriteriaToXml(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            final JobInfo job = jobStatus.getJob();
            if (jobStatus.getJob().isPeriodic()) {
                out.startTag(null, XML_TAG_PERIODIC);
                out.attribute(null, "period", Long.toString(job.getIntervalMillis()));
                out.attribute(null, "flex", Long.toString(job.getFlexMillis()));
            } else {
                out.startTag(null, XML_TAG_ONEOFF);
            }

            // If we still have the persisted times, we need to record those directly because
            // we haven't yet been able to calculate the usual elapsed-timebase bounds
            // correctly due to wall-clock uncertainty.
            Pair <Long, Long> utcJobTimes = jobStatus.getPersistedUtcTimes();
            if (DEBUG && utcJobTimes != null) {
                Slog.i(TAG, "storing original UTC timestamps for " + jobStatus);
            }

            final long nowRTC = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();
            if (jobStatus.hasDeadlineConstraint()) {
                // Wall clock deadline.
                final long deadlineWallclock = (utcJobTimes == null)
                        ? nowRTC + (jobStatus.getLatestRunTimeElapsed() - nowElapsed)
                        : utcJobTimes.second;
                out.attribute(null, "deadline", Long.toString(deadlineWallclock));
            }
            if (jobStatus.hasTimingDelayConstraint()) {
                final long delayWallclock = (utcJobTimes == null)
                        ? nowRTC + (jobStatus.getEarliestRunTime() - nowElapsed)
                        : utcJobTimes.first;
                out.attribute(null, "delay", Long.toString(delayWallclock));
            }

            // Only write out back-off policy if it differs from the default.
            // This also helps the case where the job is idle -> these aren't allowed to specify
            // back-off.
            if (jobStatus.getJob().getInitialBackoffMillis() != JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS
                    || jobStatus.getJob().getBackoffPolicy() != JobInfo.DEFAULT_BACKOFF_POLICY) {
                out.attribute(null, "backoff-policy", Integer.toString(job.getBackoffPolicy()));
                out.attribute(null, "initial-backoff", Long.toString(job.getInitialBackoffMillis()));
            }
            if (job.isPeriodic()) {
                out.endTag(null, XML_TAG_PERIODIC);
            } else {
                out.endTag(null, XML_TAG_ONEOFF);
            }
        }
    };

    /**
     * Translate the supplied RTC times to the elapsed timebase, with clamping appropriate
     * to interpreting them as a job's delay + deadline times for alarm-setting purposes.
     * @param rtcTimes a Pair<Long, Long> in which {@code first} is the "delay" earliest
     *     allowable runtime for the job, and {@code second} is the "deadline" time at which
     *     the job becomes overdue.
     */
    private static Pair<Long, Long> convertRtcBoundsToElapsed(Pair<Long, Long> rtcTimes,
            long nowElapsed) {
        final long nowWallclock = System.currentTimeMillis();
        final long earliest = (rtcTimes.first > JobStatus.NO_EARLIEST_RUNTIME)
                ? nowElapsed + Math.max(rtcTimes.first - nowWallclock, 0)
                : JobStatus.NO_EARLIEST_RUNTIME;
        final long latest = (rtcTimes.second < JobStatus.NO_LATEST_RUNTIME)
                ? nowElapsed + Math.max(rtcTimes.second - nowWallclock, 0)
                : JobStatus.NO_LATEST_RUNTIME;
        return Pair.create(earliest, latest);
    }

    private static boolean isSyncJob(JobStatus status) {
        return com.android.server.content.SyncJobService.class.getName()
                .equals(status.getServiceComponent().getClassName());
    }

    /**
     * Runnable that reads list of persisted job from xml. This is run once at start up, so doesn't
     * need to go through {@link JobStore#add(com.android.server.job.controllers.JobStatus)}.
     */
    private final class ReadJobMapFromDiskRunnable implements Runnable {
        private final JobSet jobSet;
        private final boolean rtcGood;

        /**
         * @param jobSet Reference to the (empty) set of JobStatus objects that back the JobStore,
         *               so that after disk read we can populate it directly.
         */
        ReadJobMapFromDiskRunnable(JobSet jobSet, boolean rtcIsGood) {
            this.jobSet = jobSet;
            this.rtcGood = rtcIsGood;
        }

        @Override
        public void run() {
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            try {
                List<JobStatus> jobs;
                FileInputStream fis = mJobsFile.openRead();
                synchronized (mLock) {
                    jobs = readJobMapImpl(fis, rtcGood);
                    if (jobs != null) {
                        long now = SystemClock.elapsedRealtime();
                        IActivityManager am = ActivityManager.getService();
                        for (int i=0; i<jobs.size(); i++) {
                            JobStatus js = jobs.get(i);
                            js.prepareLocked(am);
                            js.enqueueTime = now;
                            this.jobSet.add(js);

                            numJobs++;
                            if (js.getUid() == Process.SYSTEM_UID) {
                                numSystemJobs++;
                                if (isSyncJob(js)) {
                                    numSyncJobs++;
                                }
                            }
                        }
                    }
                }
                fis.close();
            } catch (FileNotFoundException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Could not find jobs file, probably there was nothing to load.");
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.wtf(TAG, "Error jobstore xml.", e);
            } finally {
                if (mPersistInfo.countAllJobsLoaded < 0) { // Only set them once.
                    mPersistInfo.countAllJobsLoaded = numJobs;
                    mPersistInfo.countSystemServerJobsLoaded = numSystemJobs;
                    mPersistInfo.countSystemSyncManagerJobsLoaded = numSyncJobs;
                }
            }
            Slog.i(TAG, "Read " + numJobs + " jobs");
        }

        private List<JobStatus> readJobMapImpl(FileInputStream fis, boolean rtcIsGood)
                throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
                Slog.d(TAG, "Start tag: " + parser.getName());
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                if (DEBUG) {
                    Slog.d(TAG, "No persisted jobs.");
                }
                return null;
            }

            String tagName = parser.getName();
            if ("job-info".equals(tagName)) {
                final List<JobStatus> jobs = new ArrayList<JobStatus>();
                // Read in version info.
                try {
                    int version = Integer.parseInt(parser.getAttributeValue(null, "version"));
                    if (version != JOBS_FILE_VERSION) {
                        Slog.d(TAG, "Invalid version number, aborting jobs file read.");
                        return null;
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Invalid version number, aborting jobs file read.");
                    return null;
                }
                eventType = parser.next();
                do {
                    // Read each <job/>
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        // Start reading job.
                        if ("job".equals(tagName)) {
                            JobStatus persistedJob = restoreJobFromXml(rtcIsGood, parser);
                            if (persistedJob != null) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Read out " + persistedJob);
                                }
                                jobs.add(persistedJob);
                            } else {
                                Slog.d(TAG, "Error reading job from file.");
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
                return jobs;
            }
            return null;
        }

        /**
         * @param parser Xml parser at the beginning of a "<job/>" tag. The next "parser.next()" call
         *               will take the parser into the body of the job tag.
         * @return Newly instantiated job holding all the information we just read out of the xml tag.
         */
        private JobStatus restoreJobFromXml(boolean rtcIsGood, XmlPullParser parser)
                throws XmlPullParserException, IOException {
            JobInfo.Builder jobBuilder;
            int uid, sourceUserId;
            long lastSuccessfulRunTime;
            long lastFailedRunTime;

            // Read out job identifier attributes and priority.
            try {
                jobBuilder = buildBuilderFromXml(parser);
                jobBuilder.setPersisted(true);
                uid = Integer.parseInt(parser.getAttributeValue(null, "uid"));

                String val = parser.getAttributeValue(null, "priority");
                if (val != null) {
                    jobBuilder.setPriority(Integer.parseInt(val));
                }
                val = parser.getAttributeValue(null, "flags");
                if (val != null) {
                    jobBuilder.setFlags(Integer.parseInt(val));
                }
                val = parser.getAttributeValue(null, "sourceUserId");
                sourceUserId = val == null ? -1 : Integer.parseInt(val);

                val = parser.getAttributeValue(null, "lastSuccessfulRunTime");
                lastSuccessfulRunTime = val == null ? 0 : Long.parseLong(val);

                val = parser.getAttributeValue(null, "lastFailedRunTime");
                lastFailedRunTime = val == null ? 0 : Long.parseLong(val);
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Error parsing job's required fields, skipping");
                return null;
            }

            String sourcePackageName = parser.getAttributeValue(null, "sourcePackageName");

            final String sourceTag = parser.getAttributeValue(null, "sourceTag");

            int eventType;
            // Read out constraints tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);  // Push through to next START_TAG.

            if (!(eventType == XmlPullParser.START_TAG &&
                    XML_TAG_PARAMS_CONSTRAINTS.equals(parser.getName()))) {
                // Expecting a <constraints> start tag.
                return null;
            }
            try {
                buildConstraintsFromXml(jobBuilder, parser);
            } catch (NumberFormatException e) {
                Slog.d(TAG, "Error reading constraints, skipping.");
                return null;
            }
            parser.next(); // Consume </constraints>

            // Read out execution parameters tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (eventType != XmlPullParser.START_TAG) {
                return null;
            }

            // Tuple of (earliest runtime, latest runtime) in UTC.
            final Pair<Long, Long> rtcRuntimes;
            try {
                rtcRuntimes = buildRtcExecutionTimesFromXml(parser);
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error parsing execution time parameters, skipping.");
                }
                return null;
            }

            final long elapsedNow = SystemClock.elapsedRealtime();
            Pair<Long, Long> elapsedRuntimes = convertRtcBoundsToElapsed(rtcRuntimes, elapsedNow);

            if (XML_TAG_PERIODIC.equals(parser.getName())) {
                try {
                    String val = parser.getAttributeValue(null, "period");
                    final long periodMillis = Long.parseLong(val);
                    val = parser.getAttributeValue(null, "flex");
                    final long flexMillis = (val != null) ? Long.valueOf(val) : periodMillis;
                    jobBuilder.setPeriodic(periodMillis, flexMillis);
                    // As a sanity check, cap the recreated run time to be no later than flex+period
                    // from now. This is the latest the periodic could be pushed out. This could
                    // happen if the periodic ran early (at flex time before period), and then the
                    // device rebooted.
                    if (elapsedRuntimes.second > elapsedNow + periodMillis + flexMillis) {
                        final long clampedLateRuntimeElapsed = elapsedNow + flexMillis
                                + periodMillis;
                        final long clampedEarlyRuntimeElapsed = clampedLateRuntimeElapsed
                                - flexMillis;
                        Slog.w(TAG,
                                String.format("Periodic job for uid='%d' persisted run-time is" +
                                                " too big [%s, %s]. Clamping to [%s,%s]",
                                        uid,
                                        DateUtils.formatElapsedTime(elapsedRuntimes.first / 1000),
                                        DateUtils.formatElapsedTime(elapsedRuntimes.second / 1000),
                                        DateUtils.formatElapsedTime(
                                                clampedEarlyRuntimeElapsed / 1000),
                                        DateUtils.formatElapsedTime(
                                                clampedLateRuntimeElapsed / 1000))
                        );
                        elapsedRuntimes =
                                Pair.create(clampedEarlyRuntimeElapsed, clampedLateRuntimeElapsed);
                    }
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading periodic execution criteria, skipping.");
                    return null;
                }
            } else if (XML_TAG_ONEOFF.equals(parser.getName())) {
                try {
                    if (elapsedRuntimes.first != JobStatus.NO_EARLIEST_RUNTIME) {
                        jobBuilder.setMinimumLatency(elapsedRuntimes.first - elapsedNow);
                    }
                    if (elapsedRuntimes.second != JobStatus.NO_LATEST_RUNTIME) {
                        jobBuilder.setOverrideDeadline(
                                elapsedRuntimes.second - elapsedNow);
                    }
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading job execution criteria, skipping.");
                    return null;
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Invalid parameter tag, skipping - " + parser.getName());
                }
                // Expecting a parameters start tag.
                return null;
            }
            maybeBuildBackoffPolicyFromXml(jobBuilder, parser);

            parser.nextTag(); // Consume parameters end tag.

            // Read out extras Bundle.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (!(eventType == XmlPullParser.START_TAG
                    && XML_TAG_EXTRAS.equals(parser.getName()))) {
                if (DEBUG) {
                    Slog.d(TAG, "Error reading extras, skipping.");
                }
                return null;
            }

            PersistableBundle extras = PersistableBundle.restoreFromXml(parser);
            jobBuilder.setExtras(extras);
            parser.nextTag(); // Consume </extras>

            // Migrate sync jobs forward from earlier, incomplete representation
            if ("android".equals(sourcePackageName)
                    && extras != null
                    && extras.getBoolean("SyncManagerJob", false)) {
                sourcePackageName = extras.getString("owningPackage", sourcePackageName);
                if (DEBUG) {
                    Slog.i(TAG, "Fixing up sync job source package name from 'android' to '"
                            + sourcePackageName + "'");
                }
            }

            // And now we're done
            JobStatus js = new JobStatus(
                    jobBuilder.build(), uid, sourcePackageName, sourceUserId, sourceTag,
                    elapsedRuntimes.first, elapsedRuntimes.second,
                    lastSuccessfulRunTime, lastFailedRunTime,
                    (rtcIsGood) ? null : rtcRuntimes);
            return js;
        }

        private JobInfo.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            // Pull out required fields from <job> attributes.
            int jobId = Integer.parseInt(parser.getAttributeValue(null, "jobid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);

            return new JobInfo.Builder(jobId, cname);
        }

        private void buildConstraintsFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "connectivity");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            }
            val = parser.getAttributeValue(null, "metered");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_METERED);
            }
            val = parser.getAttributeValue(null, "unmetered");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            }
            val = parser.getAttributeValue(null, "not-roaming");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
            }
            val = parser.getAttributeValue(null, "idle");
            if (val != null) {
                jobBuilder.setRequiresDeviceIdle(true);
            }
            val = parser.getAttributeValue(null, "charging");
            if (val != null) {
                jobBuilder.setRequiresCharging(true);
            }
        }

        /**
         * Builds the back-off policy out of the params tag. These attributes may not exist, depending
         * on whether the back-off was set when the job was first scheduled.
         */
        private void maybeBuildBackoffPolicyFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "initial-backoff");
            if (val != null) {
                long initialBackoff = Long.parseLong(val);
                val = parser.getAttributeValue(null, "backoff-policy");
                int backoffPolicy = Integer.parseInt(val);  // Will throw NFE which we catch higher up.
                jobBuilder.setBackoffCriteria(initialBackoff, backoffPolicy);
            }
        }

        /**
         * Extract a job's earliest/latest run time data from XML.  These are returned in
         * unadjusted UTC wall clock time, because we do not yet know whether the system
         * clock is reliable for purposes of calculating deltas from 'now'.
         *
         * @param parser
         * @return A Pair of timestamps in UTC wall-clock time.  The first is the earliest
         *     time at which the job is to become runnable, and the second is the deadline at
         *     which it becomes overdue to execute.
         * @throws NumberFormatException
         */
        private Pair<Long, Long> buildRtcExecutionTimesFromXml(XmlPullParser parser)
                throws NumberFormatException {
            String val;
            // Pull out execution time data.
            val = parser.getAttributeValue(null, "delay");
            final long earliestRunTimeRtc = (val != null)
                    ? Long.parseLong(val)
                    : JobStatus.NO_EARLIEST_RUNTIME;
            val = parser.getAttributeValue(null, "deadline");
            final long latestRunTimeRtc = (val != null)
                    ? Long.parseLong(val)
                    : JobStatus.NO_LATEST_RUNTIME;
            return Pair.create(earliestRunTimeRtc, latestRunTimeRtc);
        }

        /**
         * Convenience function to read out and convert deadline and delay from xml into elapsed real
         * time.
         * @return A {@link android.util.Pair}, where the first value is the earliest elapsed runtime
         * and the second is the latest elapsed runtime.
         */
        private Pair<Long, Long> buildExecutionTimesFromXml(XmlPullParser parser)
                throws NumberFormatException {
            // Pull out execution time data.
            final long nowWallclock = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();

            long earliestRunTimeElapsed = JobStatus.NO_EARLIEST_RUNTIME;
            long latestRunTimeElapsed = JobStatus.NO_LATEST_RUNTIME;
            String val = parser.getAttributeValue(null, "deadline");
            if (val != null) {
                long latestRuntimeWallclock = Long.parseLong(val);
                long maxDelayElapsed =
                        Math.max(latestRuntimeWallclock - nowWallclock, 0);
                latestRunTimeElapsed = nowElapsed + maxDelayElapsed;
            }
            val = parser.getAttributeValue(null, "delay");
            if (val != null) {
                long earliestRuntimeWallclock = Long.parseLong(val);
                long minDelayElapsed =
                        Math.max(earliestRuntimeWallclock - nowWallclock, 0);
                earliestRunTimeElapsed = nowElapsed + minDelayElapsed;

            }
            return Pair.create(earliestRunTimeElapsed, latestRunTimeElapsed);
        }
    }

    static final class JobSet {
        // Key is the getUid() originator of the jobs in each sheaf
        private SparseArray<ArraySet<JobStatus>> mJobs;

        public JobSet() {
            mJobs = new SparseArray<ArraySet<JobStatus>>();
        }

        public List<JobStatus> getJobsByUid(int uid) {
            ArrayList<JobStatus> matchingJobs = new ArrayList<JobStatus>();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                matchingJobs.addAll(jobs);
            }
            return matchingJobs;
        }

        // By user, not by uid, so we need to traverse by key and check
        public List<JobStatus> getJobsByUser(int userId) {
            ArrayList<JobStatus> result = new ArrayList<JobStatus>();
            for (int i = mJobs.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(mJobs.keyAt(i)) == userId) {
                    ArraySet<JobStatus> jobs = mJobs.valueAt(i);
                    if (jobs != null) {
                        result.addAll(jobs);
                    }
                }
            }
            return result;
        }

        public boolean add(JobStatus job) {
            final int uid = job.getUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs == null) {
                jobs = new ArraySet<JobStatus>();
                mJobs.put(uid, jobs);
            }
            return jobs.add(job);
        }

        public boolean remove(JobStatus job) {
            final int uid = job.getUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            boolean didRemove = (jobs != null) ? jobs.remove(job) : false;
            if (didRemove && jobs.size() == 0) {
                // no more jobs for this uid; let the now-empty set object be GC'd.
                mJobs.remove(uid);
            }
            return didRemove;
        }

        // Remove the jobs all users not specified by the whitelist of user ids
        public void removeJobsOfNonUsers(int[] whitelist) {
            for (int jobIndex = mJobs.size() - 1; jobIndex >= 0; jobIndex--) {
                int jobUserId = UserHandle.getUserId(mJobs.keyAt(jobIndex));
                // check if job's user id is not in the whitelist
                if (!ArrayUtils.contains(whitelist, jobUserId)) {
                    mJobs.removeAt(jobIndex);
                }
            }
        }

        public boolean contains(JobStatus job) {
            final int uid = job.getUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            return jobs != null && jobs.contains(job);
        }

        public JobStatus get(int uid, int jobId) {
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getJobId() == jobId) {
                        return job;
                    }
                }
            }
            return null;
        }

        // Inefficient; use only for testing
        public List<JobStatus> getAllJobs() {
            ArrayList<JobStatus> allJobs = new ArrayList<JobStatus>(size());
            for (int i = mJobs.size() - 1; i >= 0; i--) {
                ArraySet<JobStatus> jobs = mJobs.valueAt(i);
                if (jobs != null) {
                    // Use a for loop over the ArraySet, so we don't need to make its
                    // optional collection class iterator implementation or have to go
                    // through a temporary array from toArray().
                    for (int j = jobs.size() - 1; j >= 0; j--) {
                        allJobs.add(jobs.valueAt(j));
                    }
                }
            }
            return allJobs;
        }

        public void clear() {
            mJobs.clear();
        }

        public int size() {
            int total = 0;
            for (int i = mJobs.size() - 1; i >= 0; i--) {
                total += mJobs.valueAt(i).size();
            }
            return total;
        }

        // We only want to count the jobs that this uid has scheduled on its own
        // behalf, not those that the app has scheduled on someone else's behalf.
        public int countJobsForUid(int uid) {
            int total = 0;
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getUid() == job.getSourceUid()) {
                        total++;
                    }
                }
            }
            return total;
        }

        public void forEachJob(JobStatusFunctor functor) {
            for (int uidIndex = mJobs.size() - 1; uidIndex >= 0; uidIndex--) {
                ArraySet<JobStatus> jobs = mJobs.valueAt(uidIndex);
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.process(jobs.valueAt(i));
                }
            }
        }

        public void forEachJob(int uid, JobStatusFunctor functor) {
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.process(jobs.valueAt(i));
                }
            }
        }
    }
}
