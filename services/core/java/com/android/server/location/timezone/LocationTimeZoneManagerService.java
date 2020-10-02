/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.timezone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timezonedetector.TimeZoneDetectorInternal;
import com.android.server.timezonedetector.TimeZoneDetectorService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * A service class that acts as a container for the {@link LocationTimeZoneProviderController},
 * which determines what {@link com.android.server.timezonedetector.GeolocationTimeZoneSuggestion}
 * are made to the {@link TimeZoneDetectorInternal}, and the {@link LocationTimeZoneProvider}s that
 * offer {@link android.location.timezone.LocationTimeZoneEvent}s.
 *
 * TODO(b/152744911): This implementation currently only supports a primary provider. Support for a
 *  secondary provider must be added in a later commit.
 *
 * <p>Implementation details:
 *
 * <p>For simplicity, with the exception of a few outliers like {@link #dump}, all processing in
 * this service (and package-private helper objects) takes place on a single thread / handler, the
 * one indicated by {@link ThreadingDomain}. Because methods like {@link #dump} can be invoked on
 * another thread, the service and its related objects must still be thread-safe.
 *
 * <p>For testing / reproduction of bugs, it is possible to put providers into "simulation
 * mode" where the real binder clients are replaced by {@link
 * SimulatedLocationTimeZoneProviderProxy}. This means that the real client providers are never
 * bound (ensuring no real location events will be received) and simulated events / behaviors
 * can be injected via the command line. To enter simulation mode for a provider, use
 * "{@code adb shell setprop persist.sys.location_tz_simulation_mode.<provider name> 1}" and reboot.
 * e.g. "{@code adb shell setprop persist.sys.location_tz_simulation_mode.primary 1}}"
 * Then use "{@code adb shell cmd location_time_zone_manager help}" for injection. Set the system
 * properties to "0" and reboot to return to exit simulation mode.
 */
public class LocationTimeZoneManagerService extends Binder {

    /**
     * Controls lifecycle of the {@link LocationTimeZoneManagerService}.
     */
    public static class Lifecycle extends SystemService {

        private LocationTimeZoneManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(Objects.requireNonNull(context));
        }

        @Override
        public void onStart() {
            if (TimeZoneDetectorService.GEOLOCATION_TIME_ZONE_DETECTION_ENABLED) {
                Context context = getContext();
                mService = new LocationTimeZoneManagerService(context);

                // The service currently exposes no LocalService or Binder API, but it extends
                // Binder and is registered as a binder service so it can receive shell commands.
                publishBinderService("location_time_zone_manager", mService);
            } else {
                Slog.i(TAG, getClass() + " is compile-time disabled");
            }
        }

        @Override
        public void onBootPhase(int phase) {
            if (TimeZoneDetectorService.GEOLOCATION_TIME_ZONE_DETECTION_ENABLED) {
                if (phase == PHASE_SYSTEM_SERVICES_READY) {
                    // The location service must be functioning after this boot phase.
                    mService.onSystemReady();
                } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                    // Some providers rely on non-platform code (e.g. gcore), so we wait to
                    // initialize providers until third party code is allowed to run.
                    mService.onSystemThirdPartyAppsCanStart();
                }
            }
        }
    }

    static final String TAG = "LocationTZDetector";

    static final String PRIMARY_PROVIDER_NAME = "primary";

    private static final String SIMULATION_MODE_SYSTEM_PROPERTY_PREFIX =
            "persist.sys.location_tz_simulation_mode.";

    private static final String ATTRIBUTION_TAG = "LocationTimeZoneService";

    private static final String PRIMARY_LOCATION_TIME_ZONE_SERVICE_ACTION =
            "com.android.location.timezone.service.v1.PrimaryLocationTimeZoneProvider";


    @NonNull private final Context mContext;

    /**
     * The {@link ThreadingDomain} used to supply the {@link android.os.Handler} and shared lock
     * object used by the controller and related components.
     *
     * <p>Most operations are executed on the associated handler thread <em>but not all</em>, hence
     * the requirement for additional synchronization using a shared lock.
     */
    @NonNull private final ThreadingDomain mThreadingDomain;

    /** The shared lock from {@link #mThreadingDomain}. */
    @NonNull private final Object mSharedLock;

    // Lazily initialized. Non-null and effectively final after onSystemThirdPartyAppsCanStart().
    @GuardedBy("mSharedLock")
    private ControllerImpl mLocationTimeZoneDetectorController;

    LocationTimeZoneManagerService(Context context) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mThreadingDomain = new HandlerThreadingDomain(FgThread.getHandler());
        mSharedLock = mThreadingDomain.getLockObject();
    }

    void onSystemReady() {
        // Called on an arbitrary thread during initialization.
        synchronized (mSharedLock) {
            // TODO(b/152744911): LocationManagerService watches for packages disappearing. Need to
            //  do anything here?

            // TODO(b/152744911): LocationManagerService watches for foreground app changes. Need to
            //  do anything here?
            // TODO(b/152744911): LocationManagerService watches screen state. Need to do anything
            //  here?
        }
    }

    void onSystemThirdPartyAppsCanStart() {
        // Called on an arbitrary thread during initialization.
        synchronized (mSharedLock) {
            LocationTimeZoneProvider primary = createPrimaryProvider();
            mLocationTimeZoneDetectorController =
                    new ControllerImpl(mThreadingDomain, primary);
            ControllerCallbackImpl callback = new ControllerCallbackImpl(mThreadingDomain);
            ControllerEnvironmentImpl environment = new ControllerEnvironmentImpl(
                    mThreadingDomain, mLocationTimeZoneDetectorController);

            // Initialize the controller on the mThreadingDomain thread: this ensures that the
            // ThreadingDomain requirements for the controller / environment methods are honored.
            mThreadingDomain.post(() ->
                    mLocationTimeZoneDetectorController.initialize(environment, callback));
        }
    }

    private LocationTimeZoneProvider createPrimaryProvider() {
        LocationTimeZoneProviderProxy proxy;
        if (isInSimulationMode(PRIMARY_PROVIDER_NAME)) {
            proxy = new SimulatedLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
        } else {
            proxy = RealLocationTimeZoneProviderProxy.createAndRegister(
                    mContext,
                    mThreadingDomain,
                    PRIMARY_LOCATION_TIME_ZONE_SERVICE_ACTION,
                    com.android.internal.R.bool.config_enablePrimaryLocationTimeZoneOverlay,
                    com.android.internal.R.string.config_primaryLocationTimeZoneProviderPackageName
            );
        }
        return createLocationTimeZoneProvider(PRIMARY_PROVIDER_NAME, proxy);
    }

    private boolean isInSimulationMode(String providerName) {
        return SystemProperties.getBoolean(
                SIMULATION_MODE_SYSTEM_PROPERTY_PREFIX + providerName, false);
    }

    private LocationTimeZoneProvider createLocationTimeZoneProvider(
            @NonNull String providerName, @NonNull LocationTimeZoneProviderProxy proxy) {
        LocationTimeZoneProvider provider;
        if (proxy != null) {
            debugLog("LocationTimeZoneProvider found for providerName=" + providerName);
            provider = new BinderLocationTimeZoneProvider(mThreadingDomain,
                    providerName, proxy);
        } else {
            debugLog("No LocationTimeZoneProvider found for providerName=" + providerName
                    + ": stubbing");
            provider = new NullLocationTimeZoneProvider(mThreadingDomain, providerName);
        }
        return provider;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new LocationTimeZoneManagerShellCommand(this)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    /**
     * Asynchronously passes a {@link SimulatedBinderProviderEvent] to the appropriate provider.
     * The device must be in simulation mode, otherwise an {@link IllegalStateException} will be
     * thrown.
     */
    void simulateBinderProviderEvent(SimulatedBinderProviderEvent event)
            throws IllegalStateException {
        if (!isInSimulationMode(event.getProviderName())) {
            throw new IllegalStateException("Use \"setprop "
                    + SIMULATION_MODE_SYSTEM_PROPERTY_PREFIX + event.getProviderName()
                    + " 1\" and reboot before injecting simulated binder events.");
        }
        mThreadingDomain.post(
                () -> mLocationTimeZoneDetectorController.simulateBinderProviderEvent(event));
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        // Called on an arbitrary thread at any time.
        synchronized (mSharedLock) {
            ipw.println("LocationTimeZoneManagerService:");
            ipw.increaseIndent();
            if (mLocationTimeZoneDetectorController == null) {
                ipw.println("{Uninitialized}");
            } else {
                mLocationTimeZoneDetectorController.dump(ipw, args);
            }
            ipw.decreaseIndent();
        }
    }

    static void debugLog(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, msg);
        }
    }

    static void warnLog(String msg) {
        if (Log.isLoggable(TAG, Log.WARN)) {
            Slog.w(TAG, msg);
        }
    }
}
