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

package com.android.server;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Monitors the health of packages on the system and notifies interested observers when packages
 * fail. On failure, the registered observer with the least user impacting mitigation will
 * be notified.
 */
public class PackageWatchdog {
    private static final String TAG = "PackageWatchdog";
    // Duration to count package failures before it resets to 0
    private static final int TRIGGER_DURATION_MS = 60000;
    // Number of package failures within the duration above before we notify observers
    static final int TRIGGER_FAILURE_COUNT = 5;
    private static final int DB_VERSION = 1;
    private static final String TAG_PACKAGE_WATCHDOG = "package-watchdog";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_OBSERVER = "observer";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_DURATION = "duration";
    private static final String ATTR_PASSED_HEALTH_CHECK = "passed-health-check";

    private static PackageWatchdog sPackageWatchdog;

    private final Object mLock = new Object();
    // System server context
    private final Context mContext;
    // Handler to run short running tasks
    private final Handler mShortTaskHandler;
    // Handler for processing IO and long running tasks
    private final Handler mLongTaskHandler;
    // Contains (observer-name -> observer-handle) that have ever been registered from
    // previous boots. Observers with all packages expired are periodically pruned.
    // It is saved to disk on system shutdown and repouplated on startup so it survives reboots.
    @GuardedBy("mLock")
    private final ArrayMap<String, ObserverInternal> mAllObservers = new ArrayMap<>();
    // File containing the XML data of monitored packages /data/system/package-watchdog.xml
    private final AtomicFile mPolicyFile;
    // Runnable to prune monitored packages that have expired
    private final Runnable mPackageCleanup;
    private final ExplicitHealthCheckController mHealthCheckController;
    // Flag to control whether explicit health checks are supported or not
    @GuardedBy("mLock")
    private boolean mIsHealthCheckEnabled = true;
    @GuardedBy("mLock")
    private boolean mIsPackagesReady;
    // Last SystemClock#uptimeMillis a package clean up was executed.
    // 0 if mPackageCleanup not running.
    private long mUptimeAtLastRescheduleMs;
    // Duration a package cleanup was last scheduled for.
    // 0 if mPackageCleanup not running.
    private long mDurationAtLastReschedule;

    private PackageWatchdog(Context context) {
        // Needs to be constructed inline
        this(context, new AtomicFile(
                        new File(new File(Environment.getDataDirectory(), "system"),
                                "package-watchdog.xml")),
                new Handler(Looper.myLooper()), BackgroundThread.getHandler(),
                new ExplicitHealthCheckController(context));
    }

    /**
     * Creates a PackageWatchdog that allows injecting dependencies.
     */
    @VisibleForTesting
    PackageWatchdog(Context context, AtomicFile policyFile, Handler shortTaskHandler,
            Handler longTaskHandler, ExplicitHealthCheckController controller) {
        mContext = context;
        mPolicyFile = policyFile;
        mShortTaskHandler = shortTaskHandler;
        mLongTaskHandler = longTaskHandler;
        mPackageCleanup = this::rescheduleCleanup;
        mHealthCheckController = controller;
        loadFromFile();
    }

    /** Creates or gets singleton instance of PackageWatchdog. */
    public static PackageWatchdog getInstance(Context context) {
        synchronized (PackageWatchdog.class) {
            if (sPackageWatchdog == null) {
                sPackageWatchdog = new PackageWatchdog(context);
            }
            return sPackageWatchdog;
        }
    }

    /**
     * Called during boot to notify when packages are ready on the device so we can start
     * binding.
     */
    public void onPackagesReady() {
        synchronized (mLock) {
            mIsPackagesReady = true;
            mHealthCheckController.setCallbacks(packageName -> onHealthCheckPassed(packageName),
                    packages -> onSupportedPackages(packages),
                    () -> syncRequestsAsync());
            // Controller is initially disabled until here where we may enable it and sync requests
            setExplicitHealthCheckEnabled(mIsHealthCheckEnabled);
        }
    }

    /**
     * Registers {@code observer} to listen for package failures
     *
     * <p>Observers are expected to call this on boot. It does not specify any packages but
     * it will resume observing any packages requested from a previous boot.
     */
    public void registerHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            ObserverInternal internalObserver = mAllObservers.get(observer.getName());
            if (internalObserver != null) {
                internalObserver.mRegisteredObserver = observer;
            }
            if (mDurationAtLastReschedule == 0) {
                // Nothing running, schedule
                rescheduleCleanup();
            }
        }
    }

    /**
     * Starts observing the health of the {@code packages} for {@code observer} and notifies
     * {@code observer} of any package failures within the monitoring duration.
     *
     * <p>If monitoring a package supporting explicit health check, at the end of the monitoring
     * duration if {@link #onHealthCheckPassed} was never called,
     * {@link PackageHealthObserver#execute} will be called as if the package failed.
     *
     * <p>If {@code observer} is already monitoring a package in {@code packageNames},
     * the monitoring window of that package will be reset to {@code durationMs} and the health
     * check state will be reset to a default depending on if the package is contained in
     * {@link mPackagesWithExplicitHealthCheckEnabled}.
     *
     * @throws IllegalArgumentException if {@code packageNames} is empty
     * or {@code durationMs} is less than 1
     */
    public void startObservingHealth(PackageHealthObserver observer, List<String> packageNames,
            long durationMs) {
        if (packageNames.isEmpty()) {
            Slog.wtf(TAG, "No packages to observe, " + observer.getName());
            return;
        }
        if (durationMs < 1) {
            // TODO: Instead of failing, monitor for default? 48hrs?
            throw new IllegalArgumentException("Invalid duration " + durationMs + "ms for observer "
                    + observer.getName() + ". Not observing packages " + packageNames);
        }

        List<MonitoredPackage> packages = new ArrayList<>();
        for (int i = 0; i < packageNames.size(); i++) {
            packages.add(new MonitoredPackage(packageNames.get(i), durationMs, false));
        }

        synchronized (mLock) {
            ObserverInternal oldObserver = mAllObservers.get(observer.getName());
            if (oldObserver == null) {
                Slog.d(TAG, observer.getName() + " started monitoring health "
                        + "of packages " + packageNames);
                mAllObservers.put(observer.getName(),
                        new ObserverInternal(observer.getName(), packages));
            } else {
                Slog.d(TAG, observer.getName() + " added the following "
                        + "packages to monitor " + packageNames);
                oldObserver.updatePackages(packages);
            }
        }
        registerHealthObserver(observer);
        // Always reschedule because we may need to expire packages
        // earlier than we are already scheduled for
        rescheduleCleanup();
        Slog.i(TAG, "Syncing health check requests, observing packages " + packageNames);
        syncRequestsAsync();
        saveToFileAsync();
    }

    /**
     * Unregisters {@code observer} from listening to package failure.
     * Additionally, this stops observing any packages that may have previously been observed
     * even from a previous boot.
     */
    public void unregisterHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            mAllObservers.remove(observer.getName());
        }
        saveToFileAsync();
    }

    /**
     * Returns packages observed by {@code observer}
     *
     * @return an empty set if {@code observer} has some packages observerd from a previous boot
     * but has not registered itself in the current boot to receive notifications. Returns null
     * if there are no active packages monitored from any boot.
     */
    @Nullable
    public Set<String> getPackages(PackageHealthObserver observer) {
        synchronized (mLock) {
            for (int i = 0; i < mAllObservers.size(); i++) {
                if (observer.getName().equals(mAllObservers.keyAt(i))) {
                    if (observer.equals(mAllObservers.valueAt(i).mRegisteredObserver)) {
                        return mAllObservers.valueAt(i).mPackages.keySet();
                    }
                    return Collections.emptySet();
                }
            }
        }
        return null;
    }

    /**
     * Called when a process fails either due to a crash or ANR.
     *
     * <p>For each package contained in the process, one registered observer with the least user
     * impact will be notified for mitigation.
     *
     * <p>This method could be called frequently if there is a severe problem on the device.
     */
    public void onPackageFailure(List<VersionedPackage> packages) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                if (mAllObservers.isEmpty()) {
                    return;
                }

                for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                    VersionedPackage versionedPackage = packages.get(pIndex);
                    // Observer that will receive failure for versionedPackage
                    PackageHealthObserver currentObserverToNotify = null;
                    int currentObserverImpact = Integer.MAX_VALUE;

                    // Find observer with least user impact
                    for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                        ObserverInternal observer = mAllObservers.valueAt(oIndex);
                        PackageHealthObserver registeredObserver = observer.mRegisteredObserver;
                        if (registeredObserver != null
                                && observer.onPackageFailure(versionedPackage.getPackageName())) {
                            int impact = registeredObserver.onHealthCheckFailed(versionedPackage);
                            if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
                                    && impact < currentObserverImpact) {
                                currentObserverToNotify = registeredObserver;
                                currentObserverImpact = impact;
                            }
                        }
                    }

                    // Execute action with least user impact
                    if (currentObserverToNotify != null) {
                        currentObserverToNotify.execute(versionedPackage);
                    }
                }
            }
        });
    }

    // TODO(b/120598832): Optimize write? Maybe only write a separate smaller file?
    // This currently adds about 7ms extra to shutdown thread
    /** Writes the package information to file during shutdown. */
    public void writeNow() {
        if (!mAllObservers.isEmpty()) {
            mLongTaskHandler.removeCallbacks(this::saveToFile);
            pruneObservers(SystemClock.uptimeMillis() - mUptimeAtLastRescheduleMs);
            saveToFile();
            Slog.i(TAG, "Last write to update package durations");
        }
    }

    // TODO(b/120598832): Set depending on DeviceConfig flag
    /**
     * Enables or disables explicit health checks.
     * <p> If explicit health checks are enabled, the health check service is started.
     * <p> If explicit health checks are disabled, pending explicit health check requests are
     * passed and the health check service is stopped.
     */
    public void setExplicitHealthCheckEnabled(boolean enabled) {
        synchronized (mLock) {
            mIsHealthCheckEnabled = enabled;
            mHealthCheckController.setEnabled(enabled);
            Slog.i(TAG, "Syncing health check requests, explicit health check is "
                    + (enabled ? "enabled" : "disabled"));
            syncRequestsAsync();
        }
    }

    /** Possible severity values of the user impact of a {@link PackageHealthObserver#execute}. */
    @Retention(SOURCE)
    @IntDef(value = {PackageHealthObserverImpact.USER_IMPACT_NONE,
                     PackageHealthObserverImpact.USER_IMPACT_LOW,
                     PackageHealthObserverImpact.USER_IMPACT_MEDIUM,
                     PackageHealthObserverImpact.USER_IMPACT_HIGH})
    public @interface PackageHealthObserverImpact {
        /** No action to take. */
        int USER_IMPACT_NONE = 0;
        /* Action has low user impact, user of a device will barely notice. */
        int USER_IMPACT_LOW = 1;
        /* Action has medium user impact, user of a device will likely notice. */
        int USER_IMPACT_MEDIUM = 3;
        /* Action has high user impact, a last resort, user of a device will be very frustrated. */
        int USER_IMPACT_HIGH = 5;
    }

    /** Register instances of this interface to receive notifications on package failure. */
    public interface PackageHealthObserver {
        /**
         * Called when health check fails for the {@code versionedPackage}.
         *
         * @return any one of {@link PackageHealthObserverImpact} to express the impact
         * to the user on {@link #execute}
         */
        @PackageHealthObserverImpact int onHealthCheckFailed(VersionedPackage versionedPackage);

        /**
         * Executes mitigation for {@link #onHealthCheckFailed}.
         *
         * @return {@code true} if action was executed successfully, {@code false} otherwise
         */
        boolean execute(VersionedPackage versionedPackage);

        // TODO(b/120598832): Ensure uniqueness?
        /**
         * Identifier for the observer, should not change across device updates otherwise the
         * watchdog may drop observing packages with the old name.
         */
        String getName();
    }

    /**
     * Serializes and syncs health check requests with the {@link ExplicitHealthCheckController}.
     */
    private void syncRequestsAsync() {
        if (!mShortTaskHandler.hasCallbacks(this::syncRequests)) {
            mShortTaskHandler.post(this::syncRequests);
        }
    }

    /**
     * Syncs health check requests with the {@link ExplicitHealthCheckController}.
     * Calls to this must be serialized.
     *
     * @see #syncRequestsAsync
     */
    private void syncRequests() {
        Set<String> packages = null;
        synchronized (mLock) {
            if (mIsPackagesReady) {
                packages = getPackagesPendingHealthChecksLocked();
            } // else, we will sync requests when packages become ready
        }

        // Call outside lock to avoid holding lock when calling into the controller.
        if (packages != null) {
            mHealthCheckController.syncRequests(packages);
        }
    }

    /**
     * Updates the observers monitoring {@code packageName} that explicit health check has passed.
     *
     * <p> This update is strictly for registered observers at the time of the call
     * Observers that register after this signal will have no knowledge of prior signals and will
     * effectively behave as if the explicit health check hasn't passed for {@code packageName}.
     *
     * <p> {@code packageName} can still be considered failed if reported by
     * {@link #onPackageFailure} before the package expires.
     *
     * <p> Triggered by components outside the system server when they are fully functional after an
     * update.
     */
    private void onHealthCheckPassed(String packageName) {
        Slog.i(TAG, "Health check passed for package: " + packageName);
        boolean shouldUpdateFile = false;
        synchronized (mLock) {
            for (int observerIdx = 0; observerIdx < mAllObservers.size(); observerIdx++) {
                ObserverInternal observer = mAllObservers.valueAt(observerIdx);
                MonitoredPackage monitoredPackage = observer.mPackages.get(packageName);
                if (monitoredPackage != null && !monitoredPackage.mHasPassedHealthCheck) {
                    monitoredPackage.mHasPassedHealthCheck = true;
                    shouldUpdateFile = true;
                }
            }
        }

        // So we can unbind from the service if this was the last result we expected
        Slog.i(TAG, "Syncing health check requests, health check passed for " + packageName);
        syncRequestsAsync();

        if (shouldUpdateFile) {
            saveToFileAsync();
        }
    }

    private void onSupportedPackages(List<String> supportedPackages) {
        boolean shouldUpdateFile = false;

        synchronized (mLock) {
            Slog.i(TAG, "Received supported packages " + supportedPackages);
            Iterator<ObserverInternal> oit = mAllObservers.values().iterator();
            while (oit.hasNext()) {
                ObserverInternal observer = oit.next();
                Iterator<MonitoredPackage> pit =
                        observer.mPackages.values().iterator();
                while (pit.hasNext()) {
                    MonitoredPackage monitoredPackage = pit.next();
                    String packageName = monitoredPackage.mName;
                    if (!monitoredPackage.mHasPassedHealthCheck
                            && !supportedPackages.contains(packageName)) {
                        // Hasn't passed health check but health check is not supported
                        Slog.i(TAG, packageName + " does not support health checks, passing");
                        shouldUpdateFile = true;
                        monitoredPackage.mHasPassedHealthCheck = true;
                    }
                }
            }
        }

        if (shouldUpdateFile) {
            saveToFileAsync();
        }
    }

    private Set<String> getPackagesPendingHealthChecksLocked() {
        Slog.d(TAG, "Getting all observed packages pending health checks");
        Set<String> packages = new ArraySet<>();
        Iterator<ObserverInternal> oit = mAllObservers.values().iterator();
        while (oit.hasNext()) {
            ObserverInternal observer = oit.next();
            Iterator<MonitoredPackage> pit =
                    observer.mPackages.values().iterator();
            while (pit.hasNext()) {
                MonitoredPackage monitoredPackage = pit.next();
                String packageName = monitoredPackage.mName;
                if (!monitoredPackage.mHasPassedHealthCheck) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    /** Reschedules handler to prune expired packages from observers. */
    private void rescheduleCleanup() {
        synchronized (mLock) {
            long nextDurationToScheduleMs = getEarliestPackageExpiryLocked();
            if (nextDurationToScheduleMs == Long.MAX_VALUE) {
                Slog.i(TAG, "No monitored packages, ending package cleanup");
                mDurationAtLastReschedule = 0;
                mUptimeAtLastRescheduleMs = 0;
                return;
            }
            long uptimeMs = SystemClock.uptimeMillis();
            // O if mPackageCleanup not running
            long elapsedDurationMs = mUptimeAtLastRescheduleMs == 0
                    ? 0 : uptimeMs - mUptimeAtLastRescheduleMs;
            // Less than O if mPackageCleanup unexpectedly didn't run yet even though
            // and we are past the last duration scheduled to run
            long remainingDurationMs = mDurationAtLastReschedule - elapsedDurationMs;
            if (mUptimeAtLastRescheduleMs == 0
                    || remainingDurationMs <= 0
                    || nextDurationToScheduleMs < remainingDurationMs) {
                // First schedule or an earlier reschedule
                pruneObservers(elapsedDurationMs);
                mShortTaskHandler.removeCallbacks(mPackageCleanup);
                mShortTaskHandler.postDelayed(mPackageCleanup, nextDurationToScheduleMs);
                mDurationAtLastReschedule = nextDurationToScheduleMs;
                mUptimeAtLastRescheduleMs = uptimeMs;
            }
        }
    }

    /**
     * Returns the earliest time a package should expire.
     * @returns Long#MAX_VALUE if there are no observed packages.
     */
    private long getEarliestPackageExpiryLocked() {
        long shortestDurationMs = Long.MAX_VALUE;
        for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
            ArrayMap<String, MonitoredPackage> packages = mAllObservers.valueAt(oIndex).mPackages;
            for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                long duration = packages.valueAt(pIndex).mDurationMs;
                if (duration < shortestDurationMs) {
                    shortestDurationMs = duration;
                }
            }
        }
        Slog.v(TAG, "Earliest package time is " + shortestDurationMs);

        return shortestDurationMs;
    }

    /**
     * Removes {@code elapsedMs} milliseconds from all durations on monitored packages.
     * Discards expired packages and discards observers without any packages.
     */
    private void pruneObservers(long elapsedMs) {
        if (elapsedMs == 0) {
            return;
        }
        synchronized (mLock) {
            Slog.d(TAG, "Removing expired packages after " + elapsedMs + "ms");
            Iterator<ObserverInternal> it = mAllObservers.values().iterator();
            while (it.hasNext()) {
                ObserverInternal observer = it.next();
                List<MonitoredPackage> failedPackages =
                        observer.updateMonitoringDurations(elapsedMs);
                if (!failedPackages.isEmpty()) {
                    onHealthCheckFailed(observer, failedPackages);
                }
                if (observer.mPackages.isEmpty()) {
                    Slog.i(TAG, "Discarding observer " + observer.mName + ". All packages expired");
                    it.remove();
                }
            }
        }
        Slog.i(TAG, "Syncing health check requests pruned packages");
        syncRequestsAsync();
        saveToFileAsync();
    }

    private void onHealthCheckFailed(ObserverInternal observer,
            List<MonitoredPackage> failedPackages) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                PackageHealthObserver registeredObserver = observer.mRegisteredObserver;
                if (registeredObserver != null) {
                    PackageManager pm = mContext.getPackageManager();
                    for (int i = 0; i < failedPackages.size(); i++) {
                        String packageName = failedPackages.get(i).mName;
                        long versionCode = 0;
                        Slog.i(TAG, "Explicit health check failed for package " + packageName);
                        try {
                            versionCode = pm.getPackageInfo(
                                    packageName, 0 /* flags */).getLongVersionCode();
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.w(TAG, "Explicit health check failed but could not find package "
                                    + packageName);
                            // TODO(b/120598832): Skip. We only continue to pass tests for now since
                            // the tests don't install any packages
                        }
                        registeredObserver.execute(new VersionedPackage(packageName, versionCode));
                    }
                }
            }
        });
    }

    /**
     * Loads mAllObservers from file.
     *
     * <p>Note that this is <b>not</b> thread safe and should only called be called
     * from the constructor.
     */
    private void loadFromFile() {
        InputStream infile = null;
        mAllObservers.clear();
        try {
            infile = mPolicyFile.openRead();
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(infile, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(parser, TAG_PACKAGE_WATCHDOG);
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                ObserverInternal observer = ObserverInternal.read(parser);
                if (observer != null) {
                    mAllObservers.put(observer.mName, observer);
                }
            }
        } catch (FileNotFoundException e) {
            // Nothing to monitor
        } catch (IOException | NumberFormatException | XmlPullParserException e) {
            Slog.wtf(TAG, "Unable to read monitored packages, deleting file", e);
            mPolicyFile.delete();
        } finally {
            IoUtils.closeQuietly(infile);
        }
    }

    /**
     * Persists mAllObservers to file. Threshold information is ignored.
     */
    private boolean saveToFile() {
        synchronized (mLock) {
            FileOutputStream stream;
            try {
                stream = mPolicyFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Cannot update monitored packages", e);
                return false;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, TAG_PACKAGE_WATCHDOG);
                out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
                for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                    mAllObservers.valueAt(oIndex).write(out);
                }
                out.endTag(null, TAG_PACKAGE_WATCHDOG);
                out.endDocument();
                mPolicyFile.finishWrite(stream);
                return true;
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save monitored packages, restoring backup", e);
                mPolicyFile.failWrite(stream);
                return false;
            } finally {
                IoUtils.closeQuietly(stream);
            }
        }
    }

    private void saveToFileAsync() {
        // TODO(b/120598832): Use Handler#hasCallbacks instead of removing and posting
        mLongTaskHandler.removeCallbacks(this::saveToFile);
        mLongTaskHandler.post(this::saveToFile);
    }

    /**
     * Represents an observer monitoring a set of packages along with the failure thresholds for
     * each package.
     */
    static class ObserverInternal {
        public final String mName;
        //TODO(b/120598832): Add getter for mPackages
        public final ArrayMap<String, MonitoredPackage> mPackages;
        @Nullable
        public PackageHealthObserver mRegisteredObserver;

        ObserverInternal(String name, List<MonitoredPackage> packages) {
            mName = name;
            mPackages = new ArrayMap<>();
            updatePackages(packages);
        }

        /**
         * Writes important details to file. Doesn't persist any package failure thresholds.
         *
         * <p>Note that this method is <b>not</b> thread safe. It should only be called from
         * #saveToFile which runs on a single threaded handler.
         */
        public boolean write(XmlSerializer out) {
            try {
                out.startTag(null, TAG_OBSERVER);
                out.attribute(null, ATTR_NAME, mName);
                for (int i = 0; i < mPackages.size(); i++) {
                    MonitoredPackage p = mPackages.valueAt(i);
                    out.startTag(null, TAG_PACKAGE);
                    out.attribute(null, ATTR_NAME, p.mName);
                    out.attribute(null, ATTR_DURATION, String.valueOf(p.mDurationMs));
                    out.attribute(null, ATTR_PASSED_HEALTH_CHECK,
                            String.valueOf(p.mHasPassedHealthCheck));
                    out.endTag(null, TAG_PACKAGE);
                }
                out.endTag(null, TAG_OBSERVER);
                return true;
            } catch (IOException e) {
                Slog.w(TAG, "Cannot save observer", e);
                return false;
            }
        }

        public void updatePackages(List<MonitoredPackage> packages) {
            synchronized (mName) {
                for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                    MonitoredPackage p = packages.get(pIndex);
                    mPackages.put(p.mName, p);
                }
            }
        }

        /**
         * Reduces the monitoring durations of all packages observed by this observer by
         *  {@code elapsedMs}. If any duration is less than 0, the package is removed from
         * observation.
         *
         * @returns a {@link List} of packages that were removed from the observer without explicit
         * health check passing, or an empty list if no package expired for which an explicit health
         * check was still pending
         */
        public List<MonitoredPackage> updateMonitoringDurations(long elapsedMs) {
            List<MonitoredPackage> removedPackages = new ArrayList<>();
            synchronized (mName) {
                Iterator<MonitoredPackage> it = mPackages.values().iterator();
                while (it.hasNext()) {
                    MonitoredPackage p = it.next();
                    long newDuration = p.mDurationMs - elapsedMs;
                    if (newDuration > 0) {
                        p.mDurationMs = newDuration;
                    } else {
                        if (!p.mHasPassedHealthCheck) {
                            removedPackages.add(p);
                        }
                        it.remove();
                    }
                }
                return removedPackages;
            }
        }

        /**
         * Increments failure counts of {@code packageName}.
         * @returns {@code true} if failure threshold is exceeded, {@code false} otherwise
         */
        public boolean onPackageFailure(String packageName) {
            synchronized (mName) {
                MonitoredPackage p = mPackages.get(packageName);
                if (p != null) {
                    return p.onFailure();
                }
                return false;
            }
        }

        /**
         * Returns one ObserverInternal from the {@code parser} and advances its state.
         *
         * <p>Note that this method is <b>not</b> thread safe. It should only be called from
         * #loadFromFile which in turn is only called on construction of the
         * singleton PackageWatchdog.
         **/
        public static ObserverInternal read(XmlPullParser parser) {
            String observerName = null;
            if (TAG_OBSERVER.equals(parser.getName())) {
                observerName = parser.getAttributeValue(null, ATTR_NAME);
                if (TextUtils.isEmpty(observerName)) {
                    Slog.wtf(TAG, "Unable to read observer name");
                    return null;
                }
            }
            List<MonitoredPackage> packages = new ArrayList<>();
            int innerDepth = parser.getDepth();
            try {
                while (XmlUtils.nextElementWithin(parser, innerDepth)) {
                    if (TAG_PACKAGE.equals(parser.getName())) {
                        try {
                            String packageName = parser.getAttributeValue(null, ATTR_NAME);
                            long duration = Long.parseLong(
                                    parser.getAttributeValue(null, ATTR_DURATION));
                            boolean hasPassedHealthCheck = Boolean.parseBoolean(
                                    parser.getAttributeValue(null, ATTR_PASSED_HEALTH_CHECK));
                            if (!TextUtils.isEmpty(packageName)) {
                                packages.add(new MonitoredPackage(packageName, duration,
                                        hasPassedHealthCheck));
                            }
                        } catch (NumberFormatException e) {
                            Slog.wtf(TAG, "Skipping package for observer " + observerName, e);
                            continue;
                        }
                    }
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.wtf(TAG, "Unable to read observer " + observerName, e);
                return null;
            }
            if (packages.isEmpty()) {
                return null;
            }
            return new ObserverInternal(observerName, packages);
        }
    }

    /** Represents a package along with the time it should be monitored for. */
    static class MonitoredPackage {
        public final String mName;
        // Whether an explicit health check has passed
        public boolean mHasPassedHealthCheck;
        // System uptime duration to monitor package
        public long mDurationMs;
        // System uptime of first package failure
        private long mUptimeStartMs;
        // Number of failures since mUptimeStartMs
        private int mFailures;

        MonitoredPackage(String name, long durationMs, boolean hasPassedHealthCheck) {
            mName = name;
            mDurationMs = durationMs;
            mHasPassedHealthCheck = hasPassedHealthCheck;
        }

        /**
         * Increment package failures or resets failure count depending on the last package failure.
         *
         * @return {@code true} if failure count exceeds a threshold, {@code false} otherwise
         */
        public synchronized boolean onFailure() {
            final long now = SystemClock.uptimeMillis();
            final long duration = now - mUptimeStartMs;
            if (duration > TRIGGER_DURATION_MS) {
                // TODO(b/120598832): Reseting to 1 is not correct
                // because there may be more than 1 failure in the last trigger window from now
                // This is the RescueParty impl, will leave for now
                mFailures = 1;
                mUptimeStartMs = now;
            } else {
                mFailures++;
            }
            boolean failed = mFailures >= TRIGGER_FAILURE_COUNT;
            if (failed) {
                mFailures = 0;
            }
            return failed;
        }
    }
}
