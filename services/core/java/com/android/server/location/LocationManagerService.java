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

package com.android.server.location;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.app.compat.CompatChanges.isChangeEnabled;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.BLOCK_PENDING_INTENT_SYSTEM_API_USAGE;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static android.location.LocationRequest.LOW_POWER_EXCEPTIONS;

import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementRequest;
import android.location.IGeocodeListener;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssNmeaListener;
import android.location.IGnssStatusListener;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.location.ProviderProperties;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.stats.location.LocationStatsEnums;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.location.geofence.GeofenceManager;
import com.android.server.location.geofence.GeofenceProxy;
import com.android.server.location.gnss.GnssConfiguration;
import com.android.server.location.gnss.GnssManagerService;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AlarmHelper;
import com.android.server.location.injector.AppForegroundHelper;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.EmergencyHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationAttributionHelper;
import com.android.server.location.injector.LocationEventLog;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationPowerSaveModeHelper;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.ScreenInteractiveHelper;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.SystemAlarmHelper;
import com.android.server.location.injector.SystemAppForegroundHelper;
import com.android.server.location.injector.SystemAppOpsHelper;
import com.android.server.location.injector.SystemEmergencyHelper;
import com.android.server.location.injector.SystemLocationPermissionsHelper;
import com.android.server.location.injector.SystemLocationPowerSaveModeHelper;
import com.android.server.location.injector.SystemScreenInteractiveHelper;
import com.android.server.location.injector.SystemSettingsHelper;
import com.android.server.location.injector.SystemUserInfoHelper;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.location.provider.LocationProviderManager;
import com.android.server.location.provider.MockLocationProvider;
import com.android.server.location.provider.PassiveLocationProvider;
import com.android.server.location.provider.PassiveLocationProviderManager;
import com.android.server.location.provider.proxy.ProxyLocationProvider;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The service class that manages LocationProviders and issues location
 * updates and alerts.
 */
public class LocationManagerService extends ILocationManager.Stub {

    /**
     * Controls lifecycle of LocationManagerService.
     */
    public static class Lifecycle extends SystemService {

        private final LifecycleUserInfoHelper mUserInfoHelper;
        private final SystemInjector mSystemInjector;
        private final LocationManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mUserInfoHelper = new LifecycleUserInfoHelper(context);
            mSystemInjector = new SystemInjector(context, mUserInfoHelper);
            mService = new LocationManagerService(context, mSystemInjector);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.LOCATION_SERVICE, mService);

            // client caching behavior is only enabled after seeing the first invalidate
            LocationManager.invalidateLocalLocationEnabledCaches();
            // disable caching for our own process
            Objects.requireNonNull(mService.mContext.getSystemService(LocationManager.class))
                    .disableLocalLocationEnabledCaches();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                // the location service must be functioning after this boot phase
                mSystemInjector.onSystemReady();
                mService.onSystemReady();
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                // some providers rely on third party code, so we wait to initialize
                // providers until third party code is allowed to run
                mService.onSystemThirdPartyAppsCanStart();
            }
        }

        @Override
        public void onUserStarting(TargetUser user) {
            mUserInfoHelper.onUserStarted(user.getUserIdentifier());
        }

        @Override
        public void onUserSwitching(TargetUser from, TargetUser to) {
            mUserInfoHelper.onCurrentUserChanged(from.getUserIdentifier(),
                    to.getUserIdentifier());
        }

        @Override
        public void onUserStopped(TargetUser user) {
            mUserInfoHelper.onUserStopped(user.getUserIdentifier());
        }

        private static class LifecycleUserInfoHelper extends SystemUserInfoHelper {

            LifecycleUserInfoHelper(Context context) {
                super(context);
            }

            void onUserStarted(int userId) {
                dispatchOnUserStarted(userId);
            }

            void onUserStopped(int userId) {
                dispatchOnUserStopped(userId);
            }

            void onCurrentUserChanged(int fromUserId, int toUserId) {
                dispatchOnCurrentUserChanged(fromUserId, toUserId);
            }
        }
    }

    public static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETWORK_LOCATION_SERVICE_ACTION =
            "com.android.location.service.v3.NetworkLocationProvider";
    private static final String FUSED_LOCATION_SERVICE_ACTION =
            "com.android.location.service.FusedLocationProvider";

    private static final String ATTRIBUTION_TAG = "LocationService";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Injector mInjector;
    private final LocalService mLocalService;

    private final GeofenceManager mGeofenceManager;
    private volatile @Nullable GnssManagerService mGnssManagerService = null;
    private GeocoderProxy mGeocodeProvider;

    private final Object mDeprecatedGnssBatchingLock = new Object();
    @GuardedBy("mDeprecatedGnssBatchingLock")
    private @Nullable ILocationListener mDeprecatedGnssBatchingListener;

    @GuardedBy("mLock")
    private String mExtraLocationControllerPackage;
    @GuardedBy("mLock")
    private boolean mExtraLocationControllerPackageEnabled;

    // location provider managers

    private final PassiveLocationProviderManager mPassiveManager;

    // @GuardedBy("mProviderManagers")
    // hold lock for writes, no lock necessary for simple reads
    private final CopyOnWriteArrayList<LocationProviderManager> mProviderManagers =
            new CopyOnWriteArrayList<>();

    LocationManagerService(Context context, Injector injector) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mInjector = injector;

        mLocalService = new LocalService();
        LocalServices.addService(LocationManagerInternal.class, mLocalService);

        mGeofenceManager = new GeofenceManager(mContext, injector);

        // set up passive provider first since it will be required for all other location providers,
        // which are loaded later once the system is ready.
        mPassiveManager = new PassiveLocationProviderManager(mContext, injector);
        addLocationProviderManager(mPassiveManager, new PassiveLocationProvider(mContext));

        // TODO: load the gps provider here as well, which will require refactoring

        // Let the package manager query which are the default location
        // providers as they get certain permissions granted by default.
        LegacyPermissionManagerInternal permissionManagerInternal = LocalServices.getService(
                LegacyPermissionManagerInternal.class);
        permissionManagerInternal.setLocationPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_locationProviderPackageNames));
        permissionManagerInternal.setLocationExtraPackagesProvider(
                userId -> mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_locationExtraPackageNames));
    }

    @Nullable
    private LocationProviderManager getLocationProviderManager(String providerName) {
        if (providerName == null) {
            return null;
        }

        for (LocationProviderManager manager : mProviderManagers) {
            if (providerName.equals(manager.getName())) {
                return manager;
            }
        }

        return null;
    }

    private LocationProviderManager getOrAddLocationProviderManager(String providerName) {
        synchronized (mProviderManagers) {
            for (LocationProviderManager manager : mProviderManagers) {
                if (providerName.equals(manager.getName())) {
                    return manager;
                }
            }

            LocationProviderManager manager = new LocationProviderManager(mContext, mInjector,
                    providerName, mPassiveManager);
            addLocationProviderManager(manager, null);
            return manager;
        }
    }

    private void addLocationProviderManager(LocationProviderManager manager,
            @Nullable AbstractLocationProvider realProvider) {
        synchronized (mProviderManagers) {
            Preconditions.checkState(getLocationProviderManager(manager.getName()) == null);

            manager.startManager();
            if (realProvider != null) {
                manager.setRealProvider(realProvider);
            }
            mProviderManagers.add(manager);
        }
    }

    private void removeLocationProviderManager(LocationProviderManager manager) {
        synchronized (mProviderManagers) {
            boolean removed = mProviderManagers.remove(manager);
            Preconditions.checkArgument(removed);
            manager.setMockProvider(null);
            manager.setRealProvider(null);
            manager.stopManager();
        }
    }

    void onSystemReady() {
        mInjector.getSettingsHelper().addOnLocationEnabledChangedListener(
                this::onLocationModeChanged);
    }

    void onSystemThirdPartyAppsCanStart() {
        // network provider should always be initialized before the gps provider since the gps
        // provider has unfortunate hard dependencies on the network provider
        ProxyLocationProvider networkProvider = ProxyLocationProvider.create(
                mContext,
                NETWORK_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableNetworkLocationOverlay,
                com.android.internal.R.string.config_networkLocationProviderPackageName);
        if (networkProvider != null) {
            LocationProviderManager networkManager = new LocationProviderManager(mContext,
                    mInjector, NETWORK_PROVIDER, mPassiveManager);
            addLocationProviderManager(networkManager, networkProvider);
        } else {
            Log.w(TAG, "no network location provider found");
        }

        // ensure that a fused provider exists which will work in direct boot
        Preconditions.checkState(!mContext.getPackageManager().queryIntentServicesAsUser(
                new Intent(FUSED_LOCATION_SERVICE_ACTION),
                MATCH_DIRECT_BOOT_AWARE | MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM).isEmpty(),
                "Unable to find a direct boot aware fused location provider");

        ProxyLocationProvider fusedProvider = ProxyLocationProvider.create(
                mContext,
                FUSED_LOCATION_SERVICE_ACTION,
                com.android.internal.R.bool.config_enableFusedLocationOverlay,
                com.android.internal.R.string.config_fusedLocationProviderPackageName);
        if (fusedProvider != null) {
            LocationProviderManager fusedManager = new LocationProviderManager(mContext, mInjector,
                    FUSED_PROVIDER, mPassiveManager);
            addLocationProviderManager(fusedManager, fusedProvider);
        } else {
            Log.wtf(TAG, "no fused location provider found");
        }

        // initialize gnss last because it has no awareness of boot phases and blindly assumes that
        // all other location providers are loaded at initialization
        if (GnssNative.isSupported()) {
            GnssConfiguration gnssConfiguration = new GnssConfiguration(mContext);
            GnssNative gnssNative = GnssNative.create(mInjector, gnssConfiguration);
            mGnssManagerService = new GnssManagerService(mContext, mInjector, gnssNative);
            mGnssManagerService.onSystemReady();

            LocationProviderManager gnssManager = new LocationProviderManager(mContext, mInjector,
                    GPS_PROVIDER, mPassiveManager);
            addLocationProviderManager(gnssManager, mGnssManagerService.getGnssLocationProvider());
        }

        // bind to geocoder provider
        mGeocodeProvider = GeocoderProxy.createAndRegister(mContext);
        if (mGeocodeProvider == null) {
            Log.e(TAG, "no geocoder provider found");
        }

        // bind to hardware activity recognition
        HardwareActivityRecognitionProxy hardwareActivityRecognitionProxy =
                HardwareActivityRecognitionProxy.createAndRegister(mContext);
        if (hardwareActivityRecognitionProxy == null) {
            Log.e(TAG, "unable to bind ActivityRecognitionProxy");
        }

        // bind to gnss geofence proxy
        if (mGnssManagerService != null) {
            GeofenceProxy provider = GeofenceProxy.createAndBind(mContext,
                    mGnssManagerService.getGnssGeofenceProxy());
            if (provider == null) {
                Log.e(TAG, "unable to bind to GeofenceProxy");
            }
        }

        // create any predefined test providers
        String[] testProviderStrings = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_testLocationProviders);
        for (String testProviderString : testProviderStrings) {
            String[] fragments = testProviderString.split(",");
            String name = fragments[0].trim();
            ProviderProperties properties = new ProviderProperties(
                    Boolean.parseBoolean(fragments[1]) /* requiresNetwork */,
                    Boolean.parseBoolean(fragments[2]) /* requiresSatellite */,
                    Boolean.parseBoolean(fragments[3]) /* requiresCell */,
                    Boolean.parseBoolean(fragments[4]) /* hasMonetaryCost */,
                    Boolean.parseBoolean(fragments[5]) /* supportsAltitude */,
                    Boolean.parseBoolean(fragments[6]) /* supportsSpeed */,
                    Boolean.parseBoolean(fragments[7]) /* supportsBearing */,
                    Integer.parseInt(fragments[8]) /* powerUsage */,
                    Integer.parseInt(fragments[9]) /* accuracy */);
            getOrAddLocationProviderManager(name).setMockProvider(
                    new MockLocationProvider(properties, CallerIdentity.fromContext(mContext)));
        }
    }

    private void onLocationModeChanged(int userId) {
        boolean enabled = mInjector.getSettingsHelper().isLocationEnabled(userId);
        LocationManager.invalidateLocalLocationEnabledCaches();

        if (D) {
            Log.d(TAG, "[u" + userId + "] location enabled = " + enabled);
        }

        mInjector.getLocationEventLog().logLocationEnabled(userId, enabled);

        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION)
                .putExtra(LocationManager.EXTRA_LOCATION_ENABLED, enabled)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
    }

    @Override
    public int getGnssYearOfHardware() {
        return mGnssManagerService == null ? 0 : mGnssManagerService.getGnssYearOfHardware();
    }

    @Override
    @Nullable
    public String getGnssHardwareModelName() {
        return mGnssManagerService == null ? "" : mGnssManagerService.getGnssHardwareModelName();
    }

    @Override
    public int getGnssBatchSize() {
        return mGnssManagerService == null ? 0 : mGnssManagerService.getGnssBatchSize();
    }

    @Override
    public void startGnssBatch(long periodNanos, ILocationListener listener, String packageName,
            String attributionTag, String listenerId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        if (mGnssManagerService == null) {
            return;
        }

        long intervalMs = NANOSECONDS.toMillis(periodNanos);

        synchronized (mDeprecatedGnssBatchingLock) {
            stopGnssBatch();

            registerLocationListener(
                    GPS_PROVIDER,
                    new LocationRequest.Builder(intervalMs)
                            .setMaxUpdateDelayMillis(
                                    intervalMs * mGnssManagerService.getGnssBatchSize())
                            .setHiddenFromAppOps(true)
                            .build(),
                    listener,
                    packageName,
                    attributionTag,
                    listenerId);
            mDeprecatedGnssBatchingListener = listener;
        }
    }

    @Override
    public void flushGnssBatch() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        if (mGnssManagerService == null) {
            return;
        }

        synchronized (mDeprecatedGnssBatchingLock) {
            if (mDeprecatedGnssBatchingListener != null) {
                requestListenerFlush(GPS_PROVIDER, mDeprecatedGnssBatchingListener, 0);
            }
        }
    }

    @Override
    public void stopGnssBatch() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        if (mGnssManagerService == null) {
            return;
        }

        synchronized (mDeprecatedGnssBatchingLock) {
            if (mDeprecatedGnssBatchingListener != null) {
                ILocationListener listener = mDeprecatedGnssBatchingListener;
                mDeprecatedGnssBatchingListener = null;
                unregisterLocationListener(listener);
            }
        }
    }

    @Override
    public boolean hasProvider(String provider) {
        return getLocationProviderManager(provider) != null;
    }

    @Override
    public List<String> getAllProviders() {
        ArrayList<String> providers = new ArrayList<>(mProviderManagers.size());
        for (LocationProviderManager manager : mProviderManagers) {
            providers.add(manager.getName());
        }
        return providers;
    }

    @Override
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        if (!LocationPermissions.checkCallingOrSelfLocationPermission(mContext,
                PERMISSION_COARSE)) {
            return Collections.emptyList();
        }

        synchronized (mLock) {
            ArrayList<String> providers = new ArrayList<>(mProviderManagers.size());
            for (LocationProviderManager manager : mProviderManagers) {
                String name = manager.getName();
                if (enabledOnly && !manager.isEnabled(UserHandle.getCallingUserId())) {
                    continue;
                }
                if (criteria != null && !LocationProvider.propertiesMeetCriteria(name,
                        manager.getProperties(), criteria)) {
                    continue;
                }
                providers.add(name);
            }
            return providers;
        }
    }

    @Override
    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers;
        synchronized (mLock) {
            providers = getProviders(criteria, enabledOnly);
            if (providers.isEmpty()) {
                providers = getProviders(null, enabledOnly);
            }
        }

        if (!providers.isEmpty()) {
            if (providers.contains(FUSED_PROVIDER)) {
                return FUSED_PROVIDER;
            } else if (providers.contains(GPS_PROVIDER)) {
                return GPS_PROVIDER;
            } else if (providers.contains(NETWORK_PROVIDER)) {
                return NETWORK_PROVIDER;
            } else {
                return providers.get(0);
            }
        }

        return null;
    }

    @Override
    public String[] getBackgroundThrottlingWhitelist() {
        return mInjector.getSettingsHelper().getBackgroundThrottlePackageWhitelist().toArray(
                new String[0]);
    }

    @Override
    public String[] getIgnoreSettingsWhitelist() {
        return mInjector.getSettingsHelper().getIgnoreSettingsPackageWhitelist().toArray(
                new String[0]);
    }

    @Nullable
    @Override
    public ICancellationSignal getCurrentLocation(String provider, LocationRequest request,
            ILocationCallback consumer, String packageName, String attributionTag,
            String listenerId) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkState(identity.getPid() != Process.myPid() || attributionTag != null);

        request = validateLocationRequest(request, identity);

        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");

        return manager.getCurrentLocation(request, identity, permissionLevel, consumer);
    }

    @Override
    public void registerLocationListener(String provider, LocationRequest request,
            ILocationListener listener, String packageName, @Nullable String attributionTag,
            @Nullable String listenerId) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process should have an attribution tag set
        if (identity.getPid() == Process.myPid() && attributionTag == null) {
            Log.w(TAG, "system location request with no attribution tag",
                    new IllegalArgumentException());
        }

        request = validateLocationRequest(request, identity);

        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");

        manager.registerLocationRequest(request, identity, permissionLevel, listener);
    }

    @Override
    public void registerLocationPendingIntent(String provider, LocationRequest request,
            PendingIntent pendingIntent, String packageName, @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                AppOpsManager.toReceiverId(pendingIntent));
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkArgument(identity.getPid() != Process.myPid() || attributionTag != null);

        // pending intents requests may not use system apis because we do not keep track if clients
        // lose the relevant permissions, and thus should not get the benefit of those apis. its
        // simplest to ensure these apis are simply never set for pending intent requests. the same
        // does not apply for listener requests since those will have the process (including the
        // listener) killed on permission removal
        if (isChangeEnabled(BLOCK_PENDING_INTENT_SYSTEM_API_USAGE, identity.getUid())) {
            boolean usesSystemApi = request.isLowPower()
                    || request.isHiddenFromAppOps()
                    || request.isLocationSettingsIgnored()
                    || !request.getWorkSource().isEmpty();
            if (usesSystemApi) {
                throw new SecurityException(
                        "PendingIntent location requests may not use system APIs: " + request);
            }
        }

        request = validateLocationRequest(request, identity);

        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");

        manager.registerLocationRequest(request, identity, permissionLevel, pendingIntent);
    }

    private LocationRequest validateLocationRequest(LocationRequest request,
            CallerIdentity identity) {
        if (!request.getWorkSource().isEmpty()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.UPDATE_DEVICE_STATS,
                    "setting a work source requires " + permission.UPDATE_DEVICE_STATS);
        }
        if (request.isHiddenFromAppOps()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.UPDATE_APP_OPS_STATS,
                    "hiding from app ops requires " + permission.UPDATE_APP_OPS_STATS);
        }
        if (request.isLocationSettingsIgnored()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.WRITE_SECURE_SETTINGS,
                    "ignoring location settings requires " + permission.WRITE_SECURE_SETTINGS);
        }

        LocationRequest.Builder sanitized = new LocationRequest.Builder(request);

        if (CompatChanges.isChangeEnabled(LOW_POWER_EXCEPTIONS, Binder.getCallingUid())) {
            if (request.isLowPower()) {
                mContext.enforceCallingOrSelfPermission(
                        permission.LOCATION_HARDWARE,
                        "low power request requires " + permission.LOCATION_HARDWARE);
            }
        } else {
            if (mContext.checkCallingPermission(permission.LOCATION_HARDWARE)
                    != PERMISSION_GRANTED) {
                sanitized.setLowPower(false);
            }
        }

        WorkSource workSource = new WorkSource(request.getWorkSource());
        if (workSource.size() > 0 && workSource.getPackageName(0) == null) {
            Log.w(TAG, "received (and ignoring) illegal worksource with no package name");
            workSource.clear();
        } else {
            List<WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null && !workChains.isEmpty()
                    && workChains.get(0).getAttributionTag() == null) {
                Log.w(TAG,
                        "received (and ignoring) illegal worksource with no attribution tag");
                workSource.clear();
            }
        }

        if (workSource.isEmpty()) {
            identity.addToWorkSource(workSource);
        }
        sanitized.setWorkSource(workSource);

        return sanitized.build();
    }

    @Override
    public void requestListenerFlush(String provider, ILocationListener listener, int requestCode) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");

        manager.flush(Objects.requireNonNull(listener), requestCode);
    }

    @Override
    public void requestPendingIntentFlush(String provider, PendingIntent pendingIntent,
            int requestCode) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");

        manager.flush(Objects.requireNonNull(pendingIntent), requestCode);
    }

    @Override
    public void unregisterLocationListener(ILocationListener listener) {
        for (LocationProviderManager manager : mProviderManagers) {
            manager.unregisterLocationRequest(listener);
        }
    }

    @Override
    public void unregisterLocationPendingIntent(PendingIntent pendingIntent) {
        for (LocationProviderManager manager : mProviderManagers) {
            manager.unregisterLocationRequest(pendingIntent);
        }
    }

    @Override
    public Location getLastLocation(String provider, LastLocationRequest request,
            String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        int permissionLevel = LocationPermissions.getPermissionLevel(mContext, identity.getUid(),
                identity.getPid());
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel,
                PERMISSION_COARSE);

        // clients in the system process must have an attribution tag set
        Preconditions.checkArgument(identity.getPid() != Process.myPid() || attributionTag != null);

        request = validateLastLocationRequest(request);

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            return null;
        }

        return manager.getLastLocation(request, identity, permissionLevel);
    }

    private LastLocationRequest validateLastLocationRequest(LastLocationRequest request) {
        if (request.isHiddenFromAppOps()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.UPDATE_APP_OPS_STATS,
                    "hiding from app ops requires " + permission.UPDATE_APP_OPS_STATS);
        }
        if (request.isLocationSettingsIgnored()) {
            mContext.enforceCallingOrSelfPermission(
                    permission.WRITE_SECURE_SETTINGS,
                    "ignoring location settings requires " + permission.WRITE_SECURE_SETTINGS);
        }

        return request;
    }

    @Override
    public LocationTime getGnssTimeMillis() {
        synchronized (mLock) {
            LocationProviderManager gpsManager = getLocationProviderManager(GPS_PROVIDER);
            if (gpsManager == null) {
                return null;
            }

            Location location = gpsManager.getLastLocationUnsafe(UserHandle.USER_ALL,
                    PERMISSION_FINE, false, Long.MAX_VALUE);
            if (location == null) {
                return null;
            }

            long currentNanos = SystemClock.elapsedRealtimeNanos();
            long deltaMs = NANOSECONDS.toMillis(location.getElapsedRealtimeAgeNanos(currentNanos));
            return new LocationTime(location.getTime() + deltaMs, currentNanos);
        }
    }

    @Override
    public void injectLocation(Location location) {
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(ACCESS_FINE_LOCATION, null);

        Preconditions.checkArgument(location.isComplete());

        int userId = UserHandle.getCallingUserId();
        LocationProviderManager manager = getLocationProviderManager(location.getProvider());
        if (manager != null && manager.isEnabled(userId)) {
            manager.injectLastLocation(Objects.requireNonNull(location), userId);
        }
    }

    @Override
    public void requestGeofence(Geofence geofence, PendingIntent intent, String packageName,
            String attributionTag) {
        mGeofenceManager.addGeofence(geofence, intent, packageName, attributionTag);
    }

    @Override
    public void removeGeofence(PendingIntent pendingIntent) {
        mGeofenceManager.removeGeofence(pendingIntent);
    }

    @Override
    public void registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.registerGnssStatusCallback(listener, packageName, attributionTag);
        }
    }

    @Override
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.unregisterGnssStatusCallback(listener);
        }
    }

    @Override
    public void registerGnssNmeaCallback(IGnssNmeaListener listener, String packageName,
            String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.registerGnssNmeaCallback(listener, packageName, attributionTag);
        }
    }

    @Override
    public void unregisterGnssNmeaCallback(IGnssNmeaListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.unregisterGnssNmeaCallback(listener);
        }
    }

    @Override
    public void addGnssMeasurementsListener(@Nullable GnssMeasurementRequest request,
            IGnssMeasurementsListener listener, String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssMeasurementsListener(request, listener, packageName,
                    attributionTag);
        }
    }

    @Override
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssMeasurementsListener(
                    listener);
        }
    }

    @Override
    public void injectGnssMeasurementCorrections(GnssMeasurementCorrections corrections) {
        if (mGnssManagerService != null) {
            mGnssManagerService.injectGnssMeasurementCorrections(corrections);
        }
    }

    @Override
    public GnssCapabilities getGnssCapabilities() {
        return mGnssManagerService == null ? new GnssCapabilities.Builder().build()
                : mGnssManagerService.getGnssCapabilities();
    }

    @Override
    public void addGnssAntennaInfoListener(IGnssAntennaInfoListener listener,
            String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssAntennaInfoListener(listener, packageName, attributionTag);
        }
    }

    @Override
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssAntennaInfoListener(listener);
        }
    }

    @Override
    public void addGnssNavigationMessageListener(IGnssNavigationMessageListener listener,
            String packageName, String attributionTag) {
        if (mGnssManagerService != null) {
            mGnssManagerService.addGnssNavigationMessageListener(listener, packageName,
                    attributionTag);
        }
    }

    @Override
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        if (mGnssManagerService != null) {
            mGnssManagerService.removeGnssNavigationMessageListener(
                    listener);
        }
    }

    @Override
    public void sendExtraCommand(String provider, String command, Bundle extras) {
        LocationPermissions.enforceCallingOrSelfLocationPermission(mContext, PERMISSION_COARSE);
        mContext.enforceCallingOrSelfPermission(
                permission.ACCESS_LOCATION_EXTRA_COMMANDS, null);

        LocationProviderManager manager = getLocationProviderManager(
                Objects.requireNonNull(provider));
        if (manager != null) {
            manager.sendExtraCommand(Binder.getCallingUid(), Binder.getCallingPid(),
                    Objects.requireNonNull(command), extras);
        }

        mInjector.getLocationUsageLogger().logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                provider);
        mInjector.getLocationUsageLogger().logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_SEND_EXTRA_COMMAND,
                provider);
    }

    @Override
    public ProviderProperties getProviderProperties(String provider) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null,
                "provider \"" + provider + "\" does not exist");
        return manager.getProperties();
    }

    @Override
    public boolean isProviderPackage(String provider, String packageName) {
        mContext.enforceCallingOrSelfPermission(permission.READ_DEVICE_CONFIG, null);

        for (LocationProviderManager manager : mProviderManagers) {
            if (provider != null && !provider.equals(manager.getName())) {
                continue;
            }
            CallerIdentity identity = manager.getIdentity();
            if (identity == null) {
                continue;
            }
            if (identity.getPackageName().equals(packageName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> getProviderPackages(String provider) {
        mContext.enforceCallingOrSelfPermission(permission.READ_DEVICE_CONFIG, null);

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            return Collections.emptyList();
        }

        CallerIdentity identity = manager.getIdentity();
        if (identity == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(identity.getPackageName());
    }

    @Override
    public void setExtraLocationControllerPackage(String packageName) {
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE,
                permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mExtraLocationControllerPackage = packageName;
        }
    }

    @Override
    public String getExtraLocationControllerPackage() {
        synchronized (mLock) {
            return mExtraLocationControllerPackage;
        }
    }

    @Override
    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        mContext.enforceCallingPermission(permission.LOCATION_HARDWARE,
                permission.LOCATION_HARDWARE + " permission required");
        synchronized (mLock) {
            mExtraLocationControllerPackageEnabled = enabled;
        }
    }

    @Override
    public boolean isExtraLocationControllerPackageEnabled() {
        synchronized (mLock) {
            return mExtraLocationControllerPackageEnabled
                    && (mExtraLocationControllerPackage != null);
        }
    }

    @Override
    public void setLocationEnabledForUser(boolean enabled, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "setLocationEnabledForUser", null);

        mContext.enforceCallingOrSelfPermission(permission.WRITE_SECURE_SETTINGS, null);

        LocationManager.invalidateLocalLocationEnabledCaches();
        mInjector.getSettingsHelper().setLocationEnabled(enabled, userId);
    }

    @Override
    public boolean isLocationEnabledForUser(int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false, "isLocationEnabledForUser", null);
        return mInjector.getSettingsHelper().isLocationEnabled(userId);
    }

    @Override
    public boolean isProviderEnabledForUser(String provider, int userId) {
        return mLocalService.isProviderEnabledForUser(provider, userId);
    }

    @Override
    public boolean geocoderIsPresent() {
        return mGeocodeProvider != null;
    }

    @Override
    public void getFromLocation(double latitude, double longitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        // validate identity
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, params.getClientPackage(),
                params.getClientAttributionTag());
        Preconditions.checkArgument(identity.getUid() == params.getClientUid());

        if (mGeocodeProvider != null) {
            mGeocodeProvider.getFromLocation(latitude, longitude, maxResults, params, listener);
        } else {
            try {
                listener.onResults(null, Collections.emptyList());
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    @Override
    public void getFromLocationName(String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude, int maxResults,
            GeocoderParams params, IGeocodeListener listener) {
        // validate identity
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, params.getClientPackage(),
                params.getClientAttributionTag());
        Preconditions.checkArgument(identity.getUid() == params.getClientUid());

        if (mGeocodeProvider != null) {
            mGeocodeProvider.getFromLocationName(locationName, lowerLeftLatitude,
                    lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                    maxResults, params, listener);
        } else {
            try {
                listener.onResults(null, Collections.emptyList());
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    @Override
    public void addTestProvider(String provider, ProviderProperties properties,
            String packageName, String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        getOrAddLocationProviderManager(provider).setMockProvider(
                new MockLocationProvider(properties, identity));
    }

    @Override
    public void removeTestProvider(String provider, String packageName, String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        synchronized (mLock) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return;
            }

            manager.setMockProvider(null);
            if (!manager.hasProvider()) {
                removeLocationProviderManager(manager);
            }
        }
    }

    @Override
    public void setTestProviderLocation(String provider, Location location, String packageName,
            String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName,
                attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        Preconditions.checkArgument(location.isComplete(),
                "incomplete location object, missing timestamp or accuracy?");

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        manager.setMockProviderLocation(location);
    }

    @Override
    public void setTestProviderEnabled(String provider, boolean enabled, String packageName,
            String attributionTag) {
        // unsafe is ok because app ops will verify the package name
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName,
                attributionTag);
        if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
            return;
        }

        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }

        manager.setMockProviderAllowed(enabled);
    }

    @Override
    public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
            ParcelFileDescriptor err, String[] args) {
        return new LocationShellCommand(this).exec(
                this, in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(),
                args);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        if (mGnssManagerService != null && args.length > 0 && args[0].equals("--gnssmetrics")) {
            mGnssManagerService.dump(fd, ipw, args);
            return;
        }

        ipw.println("Location Manager State:");
        ipw.increaseIndent();

        ipw.println("User Info:");
        ipw.increaseIndent();
        mInjector.getUserInfoHelper().dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Location Settings:");
        ipw.increaseIndent();
        mInjector.getSettingsHelper().dump(fd, ipw, args);
        ipw.decreaseIndent();

        synchronized (mLock) {
            if (mExtraLocationControllerPackage != null) {
                ipw.println(
                        "Location Controller Extra Package: " + mExtraLocationControllerPackage
                                + (mExtraLocationControllerPackageEnabled ? " [enabled]"
                                : " [disabled]"));
            }
        }

        ipw.println("Location Providers:");
        ipw.increaseIndent();
        for (LocationProviderManager manager : mProviderManagers) {
            manager.dump(fd, ipw, args);
        }
        ipw.decreaseIndent();

        if (mGnssManagerService != null) {
            ipw.println("GNSS Manager:");
            ipw.increaseIndent();
            mGnssManagerService.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

        ipw.println("Geofence Manager:");
        ipw.increaseIndent();
        mGeofenceManager.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Event Log:");
        ipw.increaseIndent();
        mInjector.getLocationEventLog().iterate(ipw::println);
        ipw.decreaseIndent();
    }

    private class LocalService extends LocationManagerInternal {

        LocalService() {}

        @Override
        public boolean isProviderEnabledForUser(@NonNull String provider, int userId) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, false, "isProviderEnabledForUser", null);

            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return false;
            }

            return manager.isEnabled(userId);
        }

        @Override
        public void addProviderEnabledListener(String provider, ProviderEnabledListener listener) {
            LocationProviderManager manager = Objects.requireNonNull(
                    getLocationProviderManager(provider));
            manager.addEnabledListener(listener);
        }

        @Override
        public void removeProviderEnabledListener(String provider,
                ProviderEnabledListener listener) {
            LocationProviderManager manager = Objects.requireNonNull(
                    getLocationProviderManager(provider));
            manager.removeEnabledListener(listener);
        }

        @Override
        public boolean isProvider(String provider, CallerIdentity identity) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return false;
            } else {
                return identity.equals(manager.getIdentity());
            }
        }

        @Override
        public void sendNiResponse(int notifId, int userResponse) {
            if (mGnssManagerService != null) {
                mGnssManagerService.sendNiResponse(notifId, userResponse);
            }
        }
    }

    private static class SystemInjector implements Injector {

        private final Context mContext;

        private final UserInfoHelper mUserInfoHelper;
        private final LocationEventLog mLocationEventLog;
        private final AlarmHelper mAlarmHelper;
        private final SystemAppOpsHelper mAppOpsHelper;
        private final SystemLocationPermissionsHelper mLocationPermissionsHelper;
        private final SystemSettingsHelper mSettingsHelper;
        private final SystemAppForegroundHelper mAppForegroundHelper;
        private final SystemLocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
        private final SystemScreenInteractiveHelper mScreenInteractiveHelper;
        private final LocationAttributionHelper mLocationAttributionHelper;
        private final LocationUsageLogger mLocationUsageLogger;

        // lazily instantiated since they may not always be used

        @GuardedBy("this")
        private @Nullable SystemEmergencyHelper mEmergencyCallHelper;

        @GuardedBy("this")
        private boolean mSystemReady;

        SystemInjector(Context context, UserInfoHelper userInfoHelper) {
            mContext = context;

            mUserInfoHelper = userInfoHelper;
            mLocationEventLog = new LocationEventLog();
            mAlarmHelper = new SystemAlarmHelper(context);
            mAppOpsHelper = new SystemAppOpsHelper(context);
            mLocationPermissionsHelper = new SystemLocationPermissionsHelper(context,
                    mAppOpsHelper);
            mSettingsHelper = new SystemSettingsHelper(context);
            mAppForegroundHelper = new SystemAppForegroundHelper(context);
            mLocationPowerSaveModeHelper = new SystemLocationPowerSaveModeHelper(context,
                    mLocationEventLog);
            mScreenInteractiveHelper = new SystemScreenInteractiveHelper(context);
            mLocationAttributionHelper = new LocationAttributionHelper(mAppOpsHelper);
            mLocationUsageLogger = new LocationUsageLogger();
        }

        synchronized void onSystemReady() {
            mAppOpsHelper.onSystemReady();
            mLocationPermissionsHelper.onSystemReady();
            mSettingsHelper.onSystemReady();
            mAppForegroundHelper.onSystemReady();
            mLocationPowerSaveModeHelper.onSystemReady();
            mScreenInteractiveHelper.onSystemReady();

            if (mEmergencyCallHelper != null) {
                mEmergencyCallHelper.onSystemReady();
            }

            mSystemReady = true;
        }

        @Override
        public UserInfoHelper getUserInfoHelper() {
            return mUserInfoHelper;
        }

        @Override
        public AlarmHelper getAlarmHelper() {
            return mAlarmHelper;
        }

        @Override
        public AppOpsHelper getAppOpsHelper() {
            return mAppOpsHelper;
        }

        @Override
        public LocationPermissionsHelper getLocationPermissionsHelper() {
            return mLocationPermissionsHelper;
        }

        @Override
        public SettingsHelper getSettingsHelper() {
            return mSettingsHelper;
        }

        @Override
        public AppForegroundHelper getAppForegroundHelper() {
            return mAppForegroundHelper;
        }

        @Override
        public LocationPowerSaveModeHelper getLocationPowerSaveModeHelper() {
            return mLocationPowerSaveModeHelper;
        }

        @Override
        public ScreenInteractiveHelper getScreenInteractiveHelper() {
            return mScreenInteractiveHelper;
        }

        @Override
        public LocationAttributionHelper getLocationAttributionHelper() {
            return mLocationAttributionHelper;
        }

        @Override
        public synchronized EmergencyHelper getEmergencyHelper() {
            if (mEmergencyCallHelper == null) {
                mEmergencyCallHelper = new SystemEmergencyHelper(mContext);
                if (mSystemReady) {
                    mEmergencyCallHelper.onSystemReady();
                }
            }

            return mEmergencyCallHelper;
        }

        @Override
        public LocationEventLog getLocationEventLog() {
            return mLocationEventLog;
        }

        @Override
        public LocationUsageLogger getLocationUsageLogger() {
            return mLocationUsageLogger;
        }
    }
}
