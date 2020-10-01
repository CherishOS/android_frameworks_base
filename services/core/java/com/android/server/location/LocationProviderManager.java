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

import static android.app.compat.CompatChanges.isChangeEnabled;
import static android.location.LocationManager.DELIVER_HISTORICAL_LOCATIONS;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.KEY_LOCATION_CHANGED;
import static android.location.LocationManager.KEY_PROVIDER_ENABLED;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.location.LocationRequest.PASSIVE_INTERVAL;
import static android.os.IPowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static com.android.internal.location.ProviderRequest.EMPTY_REQUEST;
import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationPermissions.PERMISSION_NONE;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.Nullable;
import android.app.AlarmManager.OnAlarmListener;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.LocationRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.PowerManager.LocationPowerSaveMode;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.stats.location.LocationStatsEnums;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.listeners.ListenerExecutor.ListenerOperation;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.PendingIntentUtils;
import com.android.server.location.LocationPermissions.PermissionLevel;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.RemoteListenerRegistration;
import com.android.server.location.util.AlarmHelper;
import com.android.server.location.util.AppForegroundHelper;
import com.android.server.location.util.AppForegroundHelper.AppForegroundListener;
import com.android.server.location.util.AppOpsHelper;
import com.android.server.location.util.Injector;
import com.android.server.location.util.LocationAttributionHelper;
import com.android.server.location.util.LocationEventLog;
import com.android.server.location.util.LocationPermissionsHelper;
import com.android.server.location.util.LocationPermissionsHelper.LocationPermissionsListener;
import com.android.server.location.util.LocationPowerSaveModeHelper;
import com.android.server.location.util.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener;
import com.android.server.location.util.LocationUsageLogger;
import com.android.server.location.util.ScreenInteractiveHelper;
import com.android.server.location.util.ScreenInteractiveHelper.ScreenInteractiveChangedListener;
import com.android.server.location.util.SettingsHelper;
import com.android.server.location.util.SettingsHelper.GlobalSettingChangedListener;
import com.android.server.location.util.SettingsHelper.UserSettingChangedListener;
import com.android.server.location.util.UserInfoHelper;
import com.android.server.location.util.UserInfoHelper.UserListener;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

class LocationProviderManager extends
        ListenerMultiplexer<Object, LocationProviderManager.LocationTransport,
                LocationProviderManager.LocationListenerOperation,
                LocationProviderManager.Registration, ProviderRequest> implements
        AbstractLocationProvider.Listener {

    private static final String WAKELOCK_TAG = "*location*";
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 1000;

    // fastest interval at which clients may receive coarse locations
    private static final long MIN_COARSE_INTERVAL_MS = 10 * 60 * 1000;

    // max interval to be considered "high power" request
    private static final long MAX_HIGH_POWER_INTERVAL_MS = 5 * 60 * 1000;

    // max age of a location before it is no longer considered "current"
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 10 * 1000;

    // max timeout allowed for getting the current location
    private static final long GET_CURRENT_LOCATION_MAX_TIMEOUT_MS = 30 * 1000;

    // max jitter allowed for min update interval as a percentage of the interval
    private static final float FASTEST_INTERVAL_JITTER_PERCENTAGE = .10f;

    // max absolute jitter allowed for min update interval evaluation
    private static final int MAX_FASTEST_INTERVAL_JITTER_MS = 5 * 1000;

    // minimum amount of request delay in order to respect the delay, below this value the request
    // will just be scheduled immediately
    private static final long MIN_REQUEST_DELAY_MS = 30 * 1000;

    protected interface LocationTransport {

        void deliverOnLocationChanged(Location location, @Nullable Runnable onCompleteCallback)
                throws Exception;
    }

    protected interface ProviderTransport {

        void deliverOnProviderEnabledChanged(String provider, boolean enabled) throws Exception;
    }

    protected static final class LocationListenerTransport implements LocationTransport,
            ProviderTransport {

        private final ILocationListener mListener;

        protected LocationListenerTransport(ILocationListener listener) {
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback) throws RemoteException {
            mListener.onLocationChanged(location, SingleUseCallback.wrap(onCompleteCallback));
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws RemoteException {
            mListener.onProviderEnabledChanged(provider, enabled);
        }
    }

    protected static final class LocationPendingIntentTransport implements LocationTransport,
            ProviderTransport {

        private final Context mContext;
        private final PendingIntent mPendingIntent;

        public LocationPendingIntentTransport(Context context, PendingIntent pendingIntent) {
            mContext = context;
            mPendingIntent = pendingIntent;
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback)
                throws PendingIntent.CanceledException {
            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_LOCATION_CHANGED, location),
                    onCompleteCallback != null ? (pI, i, rC, rD, rE) -> onCompleteCallback.run()
                            : null, null, null,
                    PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
        }

        @Override
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled)
                throws PendingIntent.CanceledException {
            mPendingIntent.send(mContext, 0, new Intent().putExtra(KEY_PROVIDER_ENABLED, enabled),
                    null, null, null,
                    PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
        }
    }

    protected static final class GetCurrentLocationTransport implements LocationTransport {

        private final ILocationCallback mCallback;

        protected GetCurrentLocationTransport(ILocationCallback callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void deliverOnLocationChanged(Location location,
                @Nullable Runnable onCompleteCallback)
                throws RemoteException {
            // ILocationCallback doesn't currently support completion callbacks
            Preconditions.checkState(onCompleteCallback == null);
            mCallback.onLocation(location);
        }
    }

    protected interface LocationListenerOperation extends ListenerOperation<LocationTransport> {
        /**
         * Must be implemented to return the location this operation intends to deliver.
         */
        @Nullable
        Location getLocation();
    }

    protected abstract class Registration extends RemoteListenerRegistration<LocationRequest,
            LocationTransport, LocationListenerOperation> {

        @PermissionLevel protected final int mPermissionLevel;
        private final WorkSource mWorkSource;

        // we cache these values because checking/calculating on the fly is more expensive
        private boolean mPermitted;
        private boolean mForeground;
        private LocationRequest mProviderLocationRequest;
        private boolean mIsUsingHighPower;

        @Nullable private Location mLastLocation = null;

        protected Registration(LocationRequest request, CallerIdentity identity,
                LocationTransport transport, @PermissionLevel int permissionLevel) {
            super(Objects.requireNonNull(request), identity, transport);

            Preconditions.checkArgument(permissionLevel > PERMISSION_NONE);
            mPermissionLevel = permissionLevel;

            if (request.getWorkSource() != null && !request.getWorkSource().isEmpty()) {
                mWorkSource = request.getWorkSource();
            } else {
                mWorkSource = identity.addToWorkSource(null);
            }

            mProviderLocationRequest = super.getRequest();
        }

        @GuardedBy("mLock")
        @Override
        protected final void onRemovableListenerRegister() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (D) {
                Log.d(TAG, mName + " provider added registration from " + getIdentity() + " -> "
                        + getRequest());
            }

            mLocationEventLog.logProviderClientRegistered(mName, getIdentity(), super.getRequest());

            // initialization order is important as there are ordering dependencies
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().getUid());
            mProviderLocationRequest = calculateProviderLocationRequest();
            mIsUsingHighPower = isUsingHighPower();

            onProviderListenerRegister();
        }

        @GuardedBy("mLock")
        @Override
        protected final void onRemovableListenerUnregister() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            onProviderListenerUnregister();

            mLocationEventLog.logProviderClientUnregistered(mName, getIdentity());

            if (D) {
                Log.d(TAG, mName + " provider removed registration from " + getIdentity());
            }
        }

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerRegister()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerRegister() {}

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerUnregister()}.
         */
        @GuardedBy("mLock")
        protected void onProviderListenerUnregister() {}

        @Override
        protected final LocationListenerOperation onActive() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (!getRequest().isHiddenFromAppOps()) {
                mLocationAttributionHelper.reportLocationStart(getIdentity(), getName(), getKey());
            }
            onHighPowerUsageChanged();
            return onProviderListenerActive();
        }

        @Override
        protected final LocationListenerOperation onInactive() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            onHighPowerUsageChanged();
            if (!getRequest().isHiddenFromAppOps()) {
                mLocationAttributionHelper.reportLocationStop(getIdentity(), getName(), getKey());
            }
            return onProviderListenerInactive();
        }

        /**
         * Subclasses may override this instead of {@link #onActive()}.
         */
        @GuardedBy("mLock")
        protected LocationListenerOperation onProviderListenerActive() {
            return null;
        }

        /**
         * Subclasses may override this instead of {@link #onInactive()} ()}.
         */
        @GuardedBy("mLock")
        protected LocationListenerOperation onProviderListenerInactive() {
            return null;
        }

        @Override
        public final LocationRequest getRequest() {
            return mProviderLocationRequest;
        }

        @GuardedBy("mLock")
        final void initializeLastLocation(@Nullable Location location) {
            if (mLastLocation == null) {
                mLastLocation = location;
            }
        }

        @GuardedBy("mLock")
        public final Location getLastDeliveredLocation() {
            return mLastLocation;
        }

        public final boolean isForeground() {
            return mForeground;
        }

        public final boolean isPermitted() {
            return mPermitted;
        }

        @Override
        protected final LocationProviderManager getOwner() {
            return LocationProviderManager.this;
        }

        protected final WorkSource getWorkSource() {
            return mWorkSource;
        }

        @GuardedBy("mLock")
        private void onHighPowerUsageChanged() {
            boolean isUsingHighPower = isUsingHighPower();
            if (isUsingHighPower != mIsUsingHighPower) {
                mIsUsingHighPower = isUsingHighPower;

                if (!getRequest().isHiddenFromAppOps()) {
                    if (mIsUsingHighPower) {
                        mLocationAttributionHelper.reportHighPowerLocationStart(
                                getIdentity(), getName(), getKey());
                    } else {
                        mLocationAttributionHelper.reportHighPowerLocationStop(
                                getIdentity(), getName(), getKey());
                    }
                }
            }
        }

        @GuardedBy("mLock")
        private boolean isUsingHighPower() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            return isActive()
                    && getRequest().getIntervalMillis() < MAX_HIGH_POWER_INTERVAL_MS
                    && getProperties().getPowerRequirement() == Criteria.POWER_HIGH;
        }

        @GuardedBy("mLock")
        final boolean onLocationPermissionsChanged(String packageName) {
            if (getIdentity().getPackageName().equals(packageName)) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onLocationPermissionsChanged(int uid) {
            if (getIdentity().getUid() == uid) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        @GuardedBy("mLock")
        private boolean onLocationPermissionsChanged() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            boolean permitted = mLocationPermissionsHelper.hasLocationPermissions(mPermissionLevel,
                    getIdentity());
            if (permitted != mPermitted) {
                if (D) {
                    Log.v(TAG, mName + " provider package " + getIdentity().getPackageName()
                            + " permitted = " + permitted);
                }

                mPermitted = permitted;
                return true;
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onForegroundChanged(int uid, boolean foreground) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            if (getIdentity().getUid() == uid && foreground != mForeground) {
                if (D) {
                    Log.v(TAG, mName + " provider uid " + uid + " foreground = " + foreground);
                }

                mForeground = foreground;

                // note that onProviderLocationRequestChanged() is always called
                return onProviderLocationRequestChanged()
                        || mLocationPowerSaveModeHelper.getLocationPowerSaveMode()
                        == LOCATION_MODE_FOREGROUND_ONLY;
            }

            return false;
        }

        @GuardedBy("mLock")
        final boolean onProviderLocationRequestChanged() {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            LocationRequest newRequest = calculateProviderLocationRequest();
            if (mProviderLocationRequest.equals(newRequest)) {
                return false;
            }

            LocationRequest oldRequest = mProviderLocationRequest;
            mProviderLocationRequest = newRequest;
            onHighPowerUsageChanged();
            updateService();

            // if location settings ignored has changed then the active state may have changed
            return oldRequest.isLocationSettingsIgnored() != newRequest.isLocationSettingsIgnored();
        }

        private LocationRequest calculateProviderLocationRequest() {
            LocationRequest baseRequest = super.getRequest();
            LocationRequest.Builder builder = new LocationRequest.Builder(baseRequest);

            if (mPermissionLevel < PERMISSION_FINE) {
                switch (baseRequest.getQuality()) {
                    case LocationRequest.ACCURACY_FINE:
                        builder.setQuality(LocationRequest.ACCURACY_BLOCK);
                        break;
                    case LocationRequest.POWER_HIGH:
                        builder.setQuality(LocationRequest.POWER_LOW);
                        break;
                }
                if (baseRequest.getIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.setIntervalMillis(MIN_COARSE_INTERVAL_MS);
                }
                if (baseRequest.getMinUpdateIntervalMillis() < MIN_COARSE_INTERVAL_MS) {
                    builder.clearMinUpdateIntervalMillis();
                }
            }

            if (baseRequest.isLocationSettingsIgnored()) {
                // if we are not currently allowed use location settings ignored, disable it
                if (!mSettingsHelper.getIgnoreSettingsPackageWhitelist().contains(
                        getIdentity().getPackageName()) && !mLocationManagerInternal.isProvider(
                        null, getIdentity())) {
                    builder.setLocationSettingsIgnored(false);
                }
            }

            if (!baseRequest.isLocationSettingsIgnored() && !isThrottlingExempt()) {
                // throttle in the background
                if (!mForeground) {
                    builder.setIntervalMillis(max(baseRequest.getIntervalMillis(),
                            mSettingsHelper.getBackgroundThrottleIntervalMs()));
                }
            }

            return builder.build();
        }

        private boolean isThrottlingExempt() {
            if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                    getIdentity().getPackageName())) {
                return true;
            }

            return mLocationManagerInternal.isProvider(null, getIdentity());
        }

        @GuardedBy("mLock")
        @Override
        protected final LocationListenerOperation onExecuteOperation(
                LocationListenerOperation operation) {
            mLastLocation = operation.getLocation();
            return super.onExecuteOperation(operation);
        }

        @GuardedBy("mLock")
        @Nullable
        abstract LocationListenerOperation acceptLocationChange(Location fineLocation);

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());

            ArraySet<String> flags = new ArraySet<>(2);
            if (!isForeground()) {
                flags.add("bg");
            }
            if (!isPermitted()) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }

            if (mPermissionLevel == PERMISSION_COARSE) {
                builder.append(" (COARSE)");
            }

            builder.append(" ").append(getRequest());
            return builder.toString();
        }
    }

    protected abstract class LocationRegistration extends Registration implements
            OnAlarmListener, ProviderEnabledListener {

        private final PowerManager.WakeLock mWakeLock;

        private volatile ProviderTransport mProviderTransport;
        private int mNumLocationsDelivered = 0;
        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected <TTransport extends LocationTransport & ProviderTransport> LocationRegistration(
                LocationRequest request, CallerIdentity identity, TTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
            mProviderTransport = transport;
            mWakeLock = Objects.requireNonNull(mContext.getSystemService(PowerManager.class))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            mWakeLock.setReferenceCounted(true);
            mWakeLock.setWorkSource(getWorkSource());
        }

        @Override
        protected void onListenerUnregister() {
            mProviderTransport = null;
        }

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerRegister() {
            long registerTimeMs = SystemClock.elapsedRealtime();
            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);

            // add alarm for expiration
            if (mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.setDelayedAlarm(mExpirationRealtimeMs - registerTimeMs, this,
                        getWorkSource());
            }

            // start listening for provider enabled/disabled events
            addEnabledListener(this);

            onLocationListenerRegister();

            // if the provider is currently disabled, let the client know immediately
            int userId = getIdentity().getUserId();
            if (!isEnabled(userId)) {
                onProviderEnabledChanged(mName, userId, false);
            }
        }

        @GuardedBy("mLock")
        @Override
        protected final void onProviderListenerUnregister() {
            // stop listening for provider enabled/disabled events
            removeEnabledListener(this);

            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.cancel(this);
            }

            onLocationListenerUnregister();
        }

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerRegister()}.
         */
        @GuardedBy("mLock")
        protected void onLocationListenerRegister() {}

        /**
         * Subclasses may override this instead of {@link #onRemovableListenerUnregister()}.
         */
        @GuardedBy("mLock")
        protected void onLocationListenerUnregister() {}

        @GuardedBy("mLock")
        @Override
        protected final LocationListenerOperation onProviderListenerActive() {
            // a new registration may not get a location immediately, the provider request may be
            // delayed. therefore we deliver a historical location if available. since delivering an
            // older location could be considered a breaking change for some applications, we only
            // do so for apps targeting S+.
            if (isChangeEnabled(DELIVER_HISTORICAL_LOCATIONS, getIdentity().getUid())) {
                long maxLocationAgeMs = getRequest().getIntervalMillis();
                Location lastDeliveredLocation = getLastDeliveredLocation();
                if (lastDeliveredLocation != null) {
                    // ensure that location is fresher than the last delivered location
                    maxLocationAgeMs = min(maxLocationAgeMs,
                            lastDeliveredLocation.getElapsedRealtimeAgeMillis() - 1);
                }

                // requests are never delayed less than MIN_REQUEST_DELAY_MS, so it only makes sense
                // to deliver historical locations to clients with a last location older than that
                if (maxLocationAgeMs > MIN_REQUEST_DELAY_MS) {
                    Location lastLocation = getLastLocationUnsafe(
                            getIdentity().getUserId(),
                            PERMISSION_FINE, // acceptLocationChange() handles coarsening this
                            getRequest().isLocationSettingsIgnored(),
                            maxLocationAgeMs);
                    if (lastLocation != null) {
                        return acceptLocationChange(lastLocation);
                    }
                }
            }

            return null;
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                        + " provider due to expiration at " + TimeUtils.formatRealtime(
                        mExpirationRealtimeMs));
            }

            synchronized (mLock) {
                remove();
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
            }
        }

        @GuardedBy("mLock")
        @Nullable
        @Override
        LocationListenerOperation acceptLocationChange(Location fineLocation) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            // check expiration time - alarm is not guaranteed to go off at the right time,
            // especially for short intervals
            if (SystemClock.elapsedRealtime() >= mExpirationRealtimeMs) {
                remove();
                return null;
            }

            Location location;
            switch (mPermissionLevel) {
                case PERMISSION_FINE:
                    location = fineLocation;
                    break;
                case PERMISSION_COARSE:
                    location = mLocationFudger.createCoarse(fineLocation);
                    break;
                default:
                    // shouldn't be possible to have a client added without location permissions
                    throw new AssertionError();
            }

            Location lastDeliveredLocation = getLastDeliveredLocation();
            if (lastDeliveredLocation != null) {
                // check fastest interval
                long deltaMs = location.getElapsedRealtimeMillis()
                        - lastDeliveredLocation.getElapsedRealtimeMillis();
                long maxJitterMs = min((long) (FASTEST_INTERVAL_JITTER_PERCENTAGE
                        * getRequest().getIntervalMillis()), MAX_FASTEST_INTERVAL_JITTER_MS);
                if (deltaMs < getRequest().getMinUpdateIntervalMillis() - maxJitterMs) {
                    return null;
                }

                // check smallest displacement
                double smallestDisplacementM = getRequest().getMinUpdateDistanceMeters();
                if (smallestDisplacementM > 0.0 && location.distanceTo(lastDeliveredLocation)
                        <= smallestDisplacementM) {
                    return null;
                }
            }

            // note app ops
            if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(mPermissionLevel),
                    getIdentity())) {
                if (D) {
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                return null;
            }

            return new LocationListenerOperation() {
                @Override
                public Location getLocation() {
                    return location;
                }

                @Override
                public void onPreExecute() {
                    // don't acquire a wakelock for mock locations to prevent abuse
                    if (!location.isFromMockProvider()) {
                        mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    }
                }

                @Override
                public void operate(LocationTransport listener) throws Exception {
                    // if delivering to the same process, make a copy of the location first (since
                    // location is mutable)
                    Location deliveryLocation;
                    if (getIdentity().getPid() == Process.myPid()) {
                        deliveryLocation = new Location(location);
                    } else {
                        deliveryLocation = location;
                    }

                    listener.deliverOnLocationChanged(deliveryLocation,
                            location.isFromMockProvider() ? null : mWakeLock::release);
                    mLocationEventLog.logProviderDeliveredLocation(mName, getIdentity());
                }

                @Override
                public void onPostExecute(boolean success) {
                    if (!success && !location.isFromMockProvider()) {
                        mWakeLock.release();
                    }

                    if (success) {
                        // check num updates - if successful then this function will always be run
                        // from the same thread, and no additional synchronization is necessary
                        boolean remove = ++mNumLocationsDelivered >= getRequest().getMaxUpdates();
                        if (remove) {
                            if (D) {
                                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                                        + " provider due to number of updates");
                            }

                            synchronized (mLock) {
                                remove();
                            }
                        }
                    }
                }
            };
        }

        @Override
        public void onProviderEnabledChanged(String provider, int userId, boolean enabled) {
            Preconditions.checkState(mName.equals(provider));

            if (userId != getIdentity().getUserId()) {
                return;
            }

            // we choose not to hold a wakelock for provider enabled changed events
            executeSafely(getExecutor(), () -> mProviderTransport,
                    listener -> listener.deliverOnProviderEnabledChanged(mName, enabled),
                    this::onProviderOperationFailure);
        }

        protected abstract void onProviderOperationFailure(
                ListenerOperation<ProviderTransport> operation, Exception exception);
    }

    protected final class LocationListenerRegistration extends LocationRegistration implements
            IBinder.DeathRecipient {

        protected LocationListenerRegistration(LocationRequest request, CallerIdentity identity,
                LocationListenerTransport transport, @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerRegister() {
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerUnregister() {
            ((IBinder) getKey()).unlinkToDeath(this, 0);
        }

        @Override
        protected void onProviderOperationFailure(ListenerOperation<ProviderTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        @Override
        public void onOperationFailure(LocationListenerOperation operation, Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception exception) {
            if (exception instanceof RemoteException) {
                Log.w(TAG, "registration " + this + " removed", exception);
                synchronized (mLock) {
                    remove();
                }
            } else {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider client died: " + getIdentity());
                }

                synchronized (mLock) {
                    remove();
                }
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final class LocationPendingIntentRegistration extends LocationRegistration implements
            PendingIntent.CancelListener {

        protected LocationPendingIntentRegistration(LocationRequest request,
                CallerIdentity identity, LocationPendingIntentTransport transport,
                @PermissionLevel int permissionLevel) {
            super(request, identity, transport, permissionLevel);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerRegister() {
            ((PendingIntent) getKey()).registerCancelListener(this);
        }

        @GuardedBy("mLock")
        @Override
        protected void onLocationListenerUnregister() {
            ((PendingIntent) getKey()).unregisterCancelListener(this);
        }

        @Override
        protected void onProviderOperationFailure(ListenerOperation<ProviderTransport> operation,
                Exception exception) {
            onTransportFailure(exception);
        }

        @Override
        public void onOperationFailure(LocationListenerOperation operation, Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception exception) {
            if (exception instanceof PendingIntent.CanceledException) {
                Log.w(TAG, "registration " + this + " removed", exception);
                synchronized (mLock) {
                    remove();
                }
            } else {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void onCancelled(PendingIntent intent) {
            synchronized (mLock) {
                remove();
            }
        }
    }

    protected final class GetCurrentLocationListenerRegistration extends Registration implements
            IBinder.DeathRecipient, ProviderEnabledListener, OnAlarmListener {

        private volatile LocationTransport mTransport;

        private long mExpirationRealtimeMs = Long.MAX_VALUE;

        protected GetCurrentLocationListenerRegistration(LocationRequest request,
                CallerIdentity identity, LocationTransport transport, int permissionLevel) {
            super(request, identity, transport, permissionLevel);
            mTransport = transport;
        }

        @GuardedBy("mLock")
        void deliverLocation(@Nullable Location location) {
            executeSafely(getExecutor(), () -> mTransport, acceptLocationChange(location));
        }

        @Override
        protected void onListenerUnregister() {
            mTransport = null;
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerRegister() {
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }

            long registerTimeMs = SystemClock.elapsedRealtime();
            mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);

            // add alarm for expiration
            if (mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.setDelayedAlarm(mExpirationRealtimeMs - registerTimeMs, this,
                        getWorkSource());
            }

            // if this request is ignoring location settings, then we don't want to immediately fail
            // it if the provider is disabled or becomes disabled.
            if (!getRequest().isLocationSettingsIgnored()) {
                // start listening for provider enabled/disabled events
                addEnabledListener(this);

                // if the provider is currently disabled fail immediately
                int userId = getIdentity().getUserId();
                if (!getRequest().isLocationSettingsIgnored() && !isEnabled(userId)) {
                    deliverLocation(null);
                }
            }
        }

        @GuardedBy("mLock")
        @Override
        protected void onProviderListenerUnregister() {
            // stop listening for provider enabled/disabled events
            removeEnabledListener(this);

            // remove alarm for expiration
            if (mExpirationRealtimeMs < Long.MAX_VALUE) {
                mAlarmHelper.cancel(this);
            }

            ((IBinder) getKey()).unlinkToDeath(this, 0);
        }

        @Override
        public void onAlarm() {
            if (D) {
                Log.d(TAG, "removing " + getIdentity() + " from " + mName
                        + " provider due to expiration at " + TimeUtils.formatRealtime(
                        mExpirationRealtimeMs));
            }

            synchronized (mLock) {
                deliverLocation(null);
                // no need to remove alarm after it's fired
                mExpirationRealtimeMs = Long.MAX_VALUE;
            }
        }

        @GuardedBy("mLock")
        @Nullable
        @Override
        LocationListenerOperation acceptLocationChange(@Nullable Location fineLocation) {
            if (Build.IS_DEBUGGABLE) {
                Preconditions.checkState(Thread.holdsLock(mLock));
            }

            // check expiration time - alarm is not guaranteed to go off at the right time,
            // especially for short intervals
            if (SystemClock.elapsedRealtime() >= mExpirationRealtimeMs) {
                fineLocation = null;
            }

            // lastly - note app ops
            Location location;
            if (fineLocation == null) {
                location = null;
            } else if (!mAppOpsHelper.noteOpNoThrow(LocationPermissions.asAppOp(mPermissionLevel),
                    getIdentity())) {
                if (D) {
                    Log.w(TAG, "noteOp denied for " + getIdentity());
                }
                location = null;
            } else {
                switch (mPermissionLevel) {
                    case PERMISSION_FINE:
                        location = fineLocation;
                        break;
                    case PERMISSION_COARSE:
                        location = mLocationFudger.createCoarse(fineLocation);
                        break;
                    default:
                        // shouldn't be possible to have a client added without location permissions
                        throw new AssertionError();
                }
            }

            return new LocationListenerOperation() {
                @Override
                public Location getLocation() {
                    return location;
                }

                @Override
                public void operate(LocationTransport listener) {
                    // if delivering to the same process, make a copy of the location first (since
                    // location is mutable)
                    Location deliveryLocation = location;
                    if (getIdentity().getPid() == Process.myPid() && location != null) {
                        deliveryLocation = new Location(location);
                    }

                    // we currently don't hold a wakelock for getCurrentLocation deliveries
                    try {
                        listener.deliverOnLocationChanged(deliveryLocation, null);
                        mLocationEventLog.logProviderDeliveredLocation(mName, getIdentity());
                    } catch (Exception exception) {
                        if (exception instanceof RemoteException) {
                            Log.w(TAG, "registration " + this + " failed", exception);
                        } else {
                            throw new AssertionError(exception);
                        }
                    }

                    synchronized (mLock) {
                        remove();
                    }
                }
            };
        }

        @Override
        public void onProviderEnabledChanged(String provider, int userId, boolean enabled) {
            Preconditions.checkState(mName.equals(provider));

            if (userId != getIdentity().getUserId()) {
                return;
            }

            // if the provider is disabled we give up on current location immediately
            if (!getRequest().isLocationSettingsIgnored() && !enabled) {
                synchronized (mLock) {
                    deliverLocation(null);
                }
            }
        }

        @Override
        public void binderDied() {
            try {
                if (D) {
                    Log.d(TAG, mName + " provider client died: " + getIdentity());
                }

                synchronized (mLock) {
                    remove();
                }
            } catch (RuntimeException e) {
                // the caller may swallow runtime exceptions, so we rethrow as assertion errors to
                // ensure the crash is seen
                throw new AssertionError(e);
            }
        }
    }

    protected final Object mLock = new Object();

    protected final String mName;
    @Nullable private final PassiveLocationProviderManager mPassiveManager;

    protected final Context mContext;

    @GuardedBy("mLock")
    private boolean mStarted;

    // maps of user id to value
    @GuardedBy("mLock")
    private final SparseBooleanArray mEnabled; // null or not present means unknown
    @GuardedBy("mLock")
    private final SparseArray<LastLocation> mLastLocations;

    @GuardedBy("mLock")
    private final ArrayList<ProviderEnabledListener> mEnabledListeners;

    protected final LocationManagerInternal mLocationManagerInternal;
    protected final SettingsHelper mSettingsHelper;
    protected final UserInfoHelper mUserInfoHelper;
    protected final AlarmHelper mAlarmHelper;
    protected final AppOpsHelper mAppOpsHelper;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final LocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    protected final ScreenInteractiveHelper mScreenInteractiveHelper;
    protected final LocationAttributionHelper mLocationAttributionHelper;
    protected final LocationUsageLogger mLocationUsageLogger;
    protected final LocationFudger mLocationFudger;
    protected final LocationEventLog mLocationEventLog;

    private final UserListener mUserChangedListener = this::onUserChanged;
    private final UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final GlobalSettingChangedListener mBackgroundThrottlePackageWhitelistChangedListener =
            this::onBackgroundThrottlePackageWhitelistChanged;
    private final UserSettingChangedListener mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final LocationPermissionsListener mLocationPermissionsListener =
            new LocationPermissionsListener() {
                @Override
                public void onLocationPermissionsChanged(String packageName) {
                    LocationProviderManager.this.onLocationPermissionsChanged(packageName);
                }

                @Override
                public void onLocationPermissionsChanged(int uid) {
                    LocationProviderManager.this.onLocationPermissionsChanged(uid);
                }
            };
    private final AppForegroundListener mAppForegroundChangedListener =
            this::onAppForegroundChanged;
    private final GlobalSettingChangedListener mBackgroundThrottleIntervalChangedListener =
            this::onBackgroundThrottleIntervalChanged;
    private final GlobalSettingChangedListener mIgnoreSettingsPackageWhitelistChangedListener =
            this::onIgnoreSettingsWhitelistChanged;
    private final LocationPowerSaveModeChangedListener mLocationPowerSaveModeChangedListener =
            this::onLocationPowerSaveModeChanged;
    private final ScreenInteractiveChangedListener mScreenInteractiveChangedListener =
            this::onScreenInteractiveChanged;

    // acquiring mLock makes operations on mProvider atomic, but is otherwise unnecessary
    protected final MockableLocationProvider mProvider;

    @GuardedBy("mLock")
    @Nullable private OnAlarmListener mDelayedRegister;

    LocationProviderManager(Context context, Injector injector, String name,
            @Nullable PassiveLocationProviderManager passiveManager) {
        mContext = context;
        mName = Objects.requireNonNull(name);
        mPassiveManager = passiveManager;
        mStarted = false;
        mEnabled = new SparseBooleanArray(2);
        mLastLocations = new SparseArray<>(2);

        mEnabledListeners = new ArrayList<>();

        mLocationManagerInternal = Objects.requireNonNull(
                LocalServices.getService(LocationManagerInternal.class));
        mSettingsHelper = injector.getSettingsHelper();
        mUserInfoHelper = injector.getUserInfoHelper();
        mAlarmHelper = injector.getAlarmHelper();
        mAppOpsHelper = injector.getAppOpsHelper();
        mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        mAppForegroundHelper = injector.getAppForegroundHelper();
        mLocationPowerSaveModeHelper = injector.getLocationPowerSaveModeHelper();
        mScreenInteractiveHelper = injector.getScreenInteractiveHelper();
        mLocationAttributionHelper = injector.getLocationAttributionHelper();
        mLocationUsageLogger = injector.getLocationUsageLogger();
        mLocationEventLog = injector.getLocationEventLog();
        mLocationFudger = new LocationFudger(mSettingsHelper.getCoarseLocationAccuracyM());

        // initialize last since this lets our reference escape
        mProvider = new MockableLocationProvider(mLock, this);
    }

    @Override
    public String getTag() {
        return TAG;
    }

    public void startManager() {
        synchronized (mLock) {
            mStarted = true;

            mUserInfoHelper.addListener(mUserChangedListener);
            mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            long identity = Binder.clearCallingIdentity();
            try {
                // initialize enabled state
                onUserStarted(UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void stopManager() {
        synchronized (mLock) {
            mUserInfoHelper.removeListener(mUserChangedListener);
            mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);

            // notify and remove all listeners
            long identity = Binder.clearCallingIdentity();
            try {
                onUserStopped(UserHandle.USER_ALL);
                removeRegistrationIf(key -> true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            mEnabledListeners.clear();
            mStarted = false;
        }
    }

    public String getName() {
        return mName;
    }

    @Nullable
    public CallerIdentity getIdentity() {
        return mProvider.getState().identity;
    }

    @Nullable
    public ProviderProperties getProperties() {
        return mProvider.getState().properties;
    }

    public boolean hasProvider() {
        return mProvider.getProvider() != null;
    }

    public boolean isEnabled(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return false;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
            int index = mEnabled.indexOfKey(userId);
            if (index < 0) {
                // this generally shouldn't occur, but might be possible due to race conditions
                // on when we are notified of new users
                Log.w(TAG, mName + " provider saw user " + userId + " unexpectedly");
                onEnabledChanged(userId);
                index = mEnabled.indexOfKey(userId);
            }

            return mEnabled.valueAt(index);
        }
    }

    public void addEnabledListener(ProviderEnabledListener listener) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);
            mEnabledListeners.add(listener);
        }
    }

    public void removeEnabledListener(ProviderEnabledListener listener) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);
            mEnabledListeners.remove(listener);
        }
    }

    public void setRealProvider(AbstractLocationProvider provider) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setRealProvider(provider);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProvider(@Nullable MockProvider provider) {
        synchronized (mLock) {
            Preconditions.checkState(mStarted);

            mLocationEventLog.logProviderMocked(mName, provider != null);

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProvider(provider);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            // when removing a mock provider, also clear any mock last locations and reset the
            // location fudger. the mock provider could have been used to infer the current
            // location fudger offsets.
            if (provider == null) {
                final int lastLocationSize = mLastLocations.size();
                for (int i = 0; i < lastLocationSize; i++) {
                    mLastLocations.valueAt(i).clearMock();
                }

                mLocationFudger.resetOffsets();
            }
        }
    }

    public void setMockProviderAllowed(boolean enabled) {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            long identity = Binder.clearCallingIdentity();
            try {
                mProvider.setMockProviderAllowed(enabled);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void setMockProviderLocation(Location location) {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            long identity = Binder.clearCallingIdentity();
            try {
                String locationProvider = location.getProvider();
                if (!TextUtils.isEmpty(locationProvider) && !mName.equals(locationProvider)) {
                    // The location has an explicit provider that is different from the mock
                    // provider name. The caller may be trying to fool us via b/33091107.
                    EventLog.writeEvent(0x534e4554, "33091107", Binder.getCallingUid(),
                            mName + "!=" + locationProvider);
                }

                mProvider.setMockProviderLocation(location);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public List<LocationRequest> getMockProviderRequests() {
        synchronized (mLock) {
            if (!mProvider.isMock()) {
                throw new IllegalArgumentException(mName + " provider is not a test provider");
            }

            return mProvider.getCurrentRequest().getLocationRequests();
        }
    }

    @Nullable
    public Location getLastLocation(CallerIdentity identity, @PermissionLevel int permissionLevel,
            boolean ignoreLocationSettings) {
        if (mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                identity.getPackageName())) {
            return null;
        }
        if (!mUserInfoHelper.isCurrentUserId(identity.getUserId())) {
            return null;
        }
        if (!ignoreLocationSettings && !isEnabled(identity.getUserId())) {
            return null;
        }

        Location location = getLastLocationUnsafe(identity.getUserId(), permissionLevel,
                ignoreLocationSettings, Long.MAX_VALUE);

        // we don't note op here because we don't know what the client intends to do with the
        // location, the client is responsible for noting if necessary

        if (identity.getPid() == Process.myPid() && location != null) {
            // if delivering to the same process, make a copy of the location first (since
            // location is mutable)
            return new Location(location);
        } else {
            return location;
        }
    }

    /**
     * This function does not perform any permissions or safety checks, by calling it you are
     * committing to performing all applicable checks yourself.
     */
    @Nullable
    public Location getLastLocationUnsafe(int userId, @PermissionLevel int permissionLevel,
            boolean ignoreLocationSettings, long maximumAgeMs) {
        if (userId == UserHandle.USER_ALL) {
            // find the most recent location across all users
            Location lastLocation = null;
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                Location next = getLastLocationUnsafe(runningUserIds[i], permissionLevel,
                        ignoreLocationSettings, maximumAgeMs);
                if (lastLocation == null || (next != null && next.getElapsedRealtimeNanos()
                        > lastLocation.getElapsedRealtimeNanos())) {
                    lastLocation = next;
                }
            }
            return lastLocation;
        }

        Preconditions.checkArgument(userId >= 0);

        Location location;
        synchronized (mLock) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                location = null;
            } else {
                location = lastLocation.get(permissionLevel, ignoreLocationSettings);
            }
        }

        if (location == null) {
            return null;
        }

        if (location.getElapsedRealtimeAgeMillis() > maximumAgeMs) {
            return null;
        }

        return location;
    }

    public void injectLastLocation(Location location, int userId) {
        synchronized (mLock) {
            if (getLastLocationUnsafe(userId, PERMISSION_FINE, false, Long.MAX_VALUE) == null) {
                setLastLocation(location, userId);
            }
        }
    }

    private void setLastLocation(Location location, int userId) {
        if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                setLastLocation(location, runningUserIds[i]);
            }
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        synchronized (mLock) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation == null) {
                lastLocation = new LastLocation();
                mLastLocations.put(userId, lastLocation);
            }

            Location coarseLocation = mLocationFudger.createCoarse(location);
            if (isEnabled(userId)) {
                lastLocation.set(location, coarseLocation);
            }
            lastLocation.setBypass(location, coarseLocation);
        }
    }

    @Nullable
    public ICancellationSignal getCurrentLocation(LocationRequest request,
            CallerIdentity callerIdentity, int permissionLevel, ILocationCallback callback) {
        if (request.getDurationMillis() > GET_CURRENT_LOCATION_MAX_TIMEOUT_MS) {
            request = new LocationRequest.Builder(request)
                    .setDurationMillis(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS)
                    .build();
        }

        GetCurrentLocationListenerRegistration registration =
                new GetCurrentLocationListenerRegistration(
                        request,
                        callerIdentity,
                        new GetCurrentLocationTransport(callback),
                        permissionLevel);

        synchronized (mLock) {
            if (mSettingsHelper.isLocationPackageBlacklisted(callerIdentity.getUserId(),
                    callerIdentity.getPackageName())) {
                registration.deliverLocation(null);
                return null;
            }
            if (!mUserInfoHelper.isCurrentUserId(callerIdentity.getUserId())) {
                registration.deliverLocation(null);
                return null;
            }
            if (!request.isLocationSettingsIgnored() && !isEnabled(callerIdentity.getUserId())) {
                registration.deliverLocation(null);
                return null;
            }

            Location lastLocation = getLastLocationUnsafe(
                    callerIdentity.getUserId(),
                    permissionLevel,
                    request.isLocationSettingsIgnored(),
                    MAX_CURRENT_LOCATION_AGE_MS);
            if (lastLocation != null) {
                registration.deliverLocation(lastLocation);
                return null;
            }

            // if last location isn't good enough then we add a location request
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(callback.asBinder(), registration);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal cancellationSignal = CancellationSignal.fromTransport(cancelTransport);
        cancellationSignal.setOnCancelListener(SingleUseCallback.wrap(
                () -> {
                    synchronized (mLock) {
                        removeRegistration(callback.asBinder(), registration);
                    }
                }));
        return cancelTransport;
    }

    public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            mProvider.sendExtraCommand(uid, pid, command, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel, ILocationListener listener) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(
                        listener.asBinder(),
                        new LocationListenerRegistration(
                                request,
                                callerIdentity,
                                new LocationListenerTransport(listener),
                                permissionLevel));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel, PendingIntent pendingIntent) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                addRegistration(
                        pendingIntent,
                        new LocationPendingIntentRegistration(
                                request,
                                callerIdentity,
                                new LocationPendingIntentTransport(mContext, pendingIntent),
                                permissionLevel));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void unregisterLocationRequest(ILocationListener listener) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(listener.asBinder());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void unregisterLocationRequest(PendingIntent pendingIntent) {
        synchronized (mLock) {
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(pendingIntent);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegister() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mSettingsHelper.addOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.addOnIgnoreSettingsPackageWhitelistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.addListener(mLocationPermissionsListener);
        mAppForegroundHelper.addListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.addListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.addListener(mScreenInteractiveChangedListener);
    }

    @GuardedBy("mLock")
    @Override
    protected void onUnregister() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mSettingsHelper.removeOnBackgroundThrottleIntervalChangedListener(
                mBackgroundThrottleIntervalChangedListener);
        mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mSettingsHelper.removeOnIgnoreSettingsPackageWhitelistChangedListener(
                mIgnoreSettingsPackageWhitelistChangedListener);
        mLocationPermissionsHelper.removeListener(mLocationPermissionsListener);
        mAppForegroundHelper.removeListener(mAppForegroundChangedListener);
        mLocationPowerSaveModeHelper.removeListener(mLocationPowerSaveModeChangedListener);
        mScreenInteractiveHelper.removeListener(mScreenInteractiveChangedListener);
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationAdded(Object key, Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                mName,
                registration.getRequest(),
                key instanceof PendingIntent,
                /* geofence= */ key instanceof IBinder,
                null, registration.isForeground());
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationReplaced(Object key, Registration oldRegistration,
            Registration newRegistration) {
        // by saving the last delivered location state we are able to potentially delay the
        // resulting provider request longer and save additional power
        newRegistration.initializeLastLocation(oldRegistration.getLastDeliveredLocation());
        super.onRegistrationReplaced(key, oldRegistration, newRegistration);
    }

    @GuardedBy("mLock")
    @Override
    protected void onRegistrationRemoved(Object key, Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mLocationUsageLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REQUEST_LOCATION_UPDATES,
                registration.getIdentity().getPackageName(),
                mName,
                registration.getRequest(),
                key instanceof PendingIntent,
                /* geofence= */ key instanceof IBinder,
                null, registration.isForeground());
    }

    @GuardedBy("mLock")
    @Override
    protected boolean registerWithService(ProviderRequest request,
            Collection<Registration> registrations) {
        return reregisterWithService(EMPTY_REQUEST, request, registrations);
    }

    @GuardedBy("mLock")
    @Override
    protected boolean reregisterWithService(ProviderRequest oldRequest,
            ProviderRequest newRequest, Collection<Registration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (mDelayedRegister != null) {
            mAlarmHelper.cancel(mDelayedRegister);
            mDelayedRegister = null;
        }

        // calculate how long the new request should be delayed before sending it off to the
        // provider, under the assumption that once we send the request off, the provider will
        // immediately attempt to deliver a new location satisfying that request.
        long delayMs;
        if (!oldRequest.isLocationSettingsIgnored() && newRequest.isLocationSettingsIgnored()) {
            delayMs = 0;
        } else if (newRequest.getIntervalMillis() > oldRequest.getIntervalMillis()) {
            // if the interval has increased, tell the provider immediately, so it can save power
            // (even though technically this could burn extra power in the short term by producing
            // an extra location - the provider itself is free to detect an increasing interval and
            // delay its own location)
            delayMs = 0;
        } else {
            delayMs = calculateRequestDelayMillis(newRequest.getIntervalMillis(), registrations);
        }

        // the delay should never exceed the new interval
        Preconditions.checkState(delayMs >= 0 && delayMs <= newRequest.getIntervalMillis());

        if (delayMs < MIN_REQUEST_DELAY_MS) {
            mLocationEventLog.logProviderUpdateRequest(mName, newRequest);
            mProvider.setRequest(newRequest);
        } else {
            if (D) {
                Log.d(TAG, mName + " provider delaying request update " + newRequest + " by "
                        + TimeUtils.formatDuration(delayMs));
            }

            mDelayedRegister = new OnAlarmListener() {
                @Override
                public void onAlarm() {
                    synchronized (mLock) {
                        if (mDelayedRegister == this) {
                            mLocationEventLog.logProviderUpdateRequest(mName, newRequest);
                            mProvider.setRequest(newRequest);
                            mDelayedRegister = null;
                        }
                    }
                }
            };
            mAlarmHelper.setDelayedAlarm(delayMs, mDelayedRegister, newRequest.getWorkSource());
        }

        return true;
    }

    @GuardedBy("mLock")
    @Override
    protected void unregisterWithService() {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        mLocationEventLog.logProviderUpdateRequest(mName, EMPTY_REQUEST);
        mProvider.setRequest(EMPTY_REQUEST);
    }

    @GuardedBy("mLock")
    @Override
    protected boolean isActive(Registration registration) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        CallerIdentity identity = registration.getIdentity();

        if (!registration.isPermitted()) {
            return false;
        }

        if (!registration.getRequest().isLocationSettingsIgnored()) {
            if (!isEnabled(identity.getUserId())) {
                return false;
            }

            switch (mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
                case LOCATION_MODE_FOREGROUND_ONLY:
                    if (!registration.isForeground()) {
                        return false;
                    }
                    break;
                case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                    if (!GPS_PROVIDER.equals(mName)) {
                        break;
                    }
                    // fall through
                case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                    // fall through
                case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                    if (!mScreenInteractiveHelper.isInteractive()) {
                        return false;
                    }
                    break;
                case LOCATION_MODE_NO_CHANGE:
                    // fall through
                default:
                    break;
            }
        }

        return !mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                identity.getPackageName());
    }

    @GuardedBy("mLock")
    @Override
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        long intervalMs = Long.MAX_VALUE;
        boolean locationSettingsIgnored = false;
        boolean lowPower = true;
        ArrayList<LocationRequest> locationRequests = new ArrayList<>(registrations.size());

        for (Registration registration : registrations) {
            LocationRequest request = registration.getRequest();

            // passive requests do not contribute to the provider request
            if (request.getIntervalMillis() == PASSIVE_INTERVAL) {
                continue;
            }

            intervalMs = min(request.getIntervalMillis(), intervalMs);
            locationSettingsIgnored |= request.isLocationSettingsIgnored();
            lowPower &= request.isLowPower();
            locationRequests.add(request);
        }

        // calculate who to blame for power in a somewhat arbitrary fashion. we pick a threshold
        // interval slightly higher that the minimum interval, and spread the blame across all
        // contributing registrations under that threshold (since worksource does not allow us to
        // represent differing power blame ratios).
        long thresholdIntervalMs = (intervalMs + 1000) * 3 / 2;
        if (thresholdIntervalMs < 0 || thresholdIntervalMs >= PASSIVE_INTERVAL) {
            // check for and handle overflow by setting to one below the passive interval so passive
            // requests are automatically skipped
            thresholdIntervalMs = PASSIVE_INTERVAL - 1;
        }

        WorkSource workSource = new WorkSource();
        for (Registration registration : registrations) {
            if (registration.getRequest().getIntervalMillis() <= thresholdIntervalMs) {
                workSource.add(registration.getWorkSource());
            }
        }

        return new ProviderRequest.Builder()
                .setIntervalMillis(intervalMs)
                .setLocationSettingsIgnored(locationSettingsIgnored)
                .setLowPower(lowPower)
                .setLocationRequests(locationRequests)
                .setWorkSource(workSource)
                .build();
    }

    @GuardedBy("mLock")
    protected long calculateRequestDelayMillis(long newIntervalMs,
            Collection<Registration> registrations) {
        // calculate the minimum delay across all registrations, ensuring that it is not more than
        // the requested interval
        long delayMs = newIntervalMs;
        for (Registration registration : registrations) {
            if (delayMs == 0) {
                break;
            }

            LocationRequest locationRequest = registration.getRequest();
            Location last = registration.getLastDeliveredLocation();

            if (last == null && !locationRequest.isLocationSettingsIgnored()) {
                // if this request has never gotten any location and it's not ignoring location
                // settings, then we pretend that this request has gotten the last applicable cached
                // location for our calculations instead. this prevents spammy add/remove behavior
                last = getLastLocationUnsafe(
                        registration.getIdentity().getUserId(),
                        PERMISSION_FINE,
                        false,
                        locationRequest.getIntervalMillis());
            }

            long registrationDelayMs;
            if (last == null) {
                // if this request has never gotten any location then there's no delay
                registrationDelayMs = 0;
            } else {
                // otherwise the delay is the amount of time until the next location is expected
                registrationDelayMs = max(0,
                        locationRequest.getIntervalMillis() - last.getElapsedRealtimeAgeMillis());
            }

            delayMs = min(delayMs, registrationDelayMs);
        }

        return delayMs;
    }

    private void onUserChanged(int userId, int change) {
        synchronized (mLock) {
            switch (change) {
                case UserListener.CURRENT_USER_CHANGED:
                    onEnabledChanged(userId);
                    break;
                case UserListener.USER_STARTED:
                    onUserStarted(userId);
                    break;
                case UserListener.USER_STOPPED:
                    onUserStopped(userId);
                    break;
            }
        }
    }

    private void onLocationEnabledChanged(int userId) {
        synchronized (mLock) {
            onEnabledChanged(userId);
        }
    }

    private void onScreenInteractiveChanged(boolean screenInteractive) {
        synchronized (mLock) {
            switch (mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
                case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                    if (!GPS_PROVIDER.equals(mName)) {
                        break;
                    }
                    // fall through
                case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                    // fall through
                case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                    updateRegistrations(registration -> true);
                    break;
                default:
                    break;
            }
        }
    }

    private void onBackgroundThrottlePackageWhitelistChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onBackgroundThrottleIntervalChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onLocationPowerSaveModeChanged(@LocationPowerSaveMode int locationPowerSaveMode) {
        synchronized (mLock) {
            // this is rare, just assume everything has changed to keep it simple
            updateRegistrations(registration -> true);
        }
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.onForegroundChanged(uid, foreground));
        }
    }

    private void onIgnoreSettingsWhitelistChanged() {
        synchronized (mLock) {
            updateRegistrations(Registration::onProviderLocationRequestChanged);
        }
    }

    private void onLocationPackageBlacklistChanged(int userId) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
        }
    }

    private void onLocationPermissionsChanged(String packageName) {
        synchronized (mLock) {
            updateRegistrations(
                    registration -> registration.onLocationPermissionsChanged(packageName));
        }
    }

    private void onLocationPermissionsChanged(int uid) {
        synchronized (mLock) {
            updateRegistrations(registration -> registration.onLocationPermissionsChanged(uid));
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onStateChanged(
            AbstractLocationProvider.State oldState, AbstractLocationProvider.State newState) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (oldState.allowed != newState.allowed) {
            onEnabledChanged(UserHandle.USER_ALL);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onReportLocation(Location location) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        // don't validate mock locations
        if (!location.isFromMockProvider()) {
            if (location.getLatitude() == 0 && location.getLongitude() == 0) {
                Log.w(TAG, "blocking 0,0 location from " + mName + " provider");
                return;
            }
        }

        if (!location.isComplete()) {
            Log.w(TAG, "blocking incomplete location from " + mName + " provider");
            return;
        }

        if (mPassiveManager != null) {
            // don't log location received for passive provider because it's spammy
            mLocationEventLog.logProviderReceivedLocation(mName);
        }

        // update last location
        setLastLocation(location, UserHandle.USER_ALL);

        // attempt listener delivery
        deliverToListeners(registration -> registration.acceptLocationChange(location));

        // notify passive provider
        if (mPassiveManager != null) {
            mPassiveManager.updateLocation(location);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void onReportLocation(List<Location> locations) {
        if (!GPS_PROVIDER.equals(mName)) {
            return;
        }

        mLocationManagerInternal.reportGnssBatchLocations(locations);
    }

    @GuardedBy("mLock")
    private void onUserStarted(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            // clear the user's prior enabled state to prevent broadcast of enabled state change
            mEnabled.clear();
            onEnabledChanged(UserHandle.USER_ALL);
        } else {
            Preconditions.checkArgument(userId >= 0);

            // clear the user's prior enabled state to prevent broadcast of enabled state change
            mEnabled.delete(userId);
            onEnabledChanged(userId);
        }
    }

    @GuardedBy("mLock")
    private void onUserStopped(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            return;
        }

        if (userId == UserHandle.USER_ALL) {
            onEnabledChanged(UserHandle.USER_ALL);
            mEnabled.clear();
            mLastLocations.clear();
        } else {
            Preconditions.checkArgument(userId >= 0);

            onEnabledChanged(userId);
            mEnabled.delete(userId);
            mLastLocations.remove(userId);
        }
    }

    @GuardedBy("mLock")
    private void onEnabledChanged(int userId) {
        if (Build.IS_DEBUGGABLE) {
            Preconditions.checkState(Thread.holdsLock(mLock));
        }

        if (userId == UserHandle.USER_NULL) {
            // used during initialization - ignore since many lower level operations (checking
            // settings for instance) do not support the null user
            return;
        } else if (userId == UserHandle.USER_ALL) {
            final int[] runningUserIds = mUserInfoHelper.getRunningUserIds();
            for (int i = 0; i < runningUserIds.length; i++) {
                onEnabledChanged(runningUserIds[i]);
            }
            return;
        }

        Preconditions.checkArgument(userId >= 0);

        boolean enabled = mStarted
                && mProvider.getState().allowed
                && mUserInfoHelper.isCurrentUserId(userId)
                && mSettingsHelper.isLocationEnabled(userId);

        int index = mEnabled.indexOfKey(userId);
        Boolean wasEnabled = index < 0 ? null : mEnabled.valueAt(index);
        if (wasEnabled != null && wasEnabled == enabled) {
            return;
        }

        mEnabled.put(userId, enabled);

        // don't log unknown -> false transitions for brevity
        if (wasEnabled != null || enabled) {
            if (D) {
                Log.d(TAG, "[u" + userId + "] " + mName + " provider enabled = " + enabled);
            }
            mLocationEventLog.logProviderEnabled(mName, userId, enabled);
        }

        // clear last locations if we become disabled
        if (!enabled) {
            LastLocation lastLocation = mLastLocations.get(userId);
            if (lastLocation != null) {
                lastLocation.clearLocations();
            }
        }

        // do not send change notifications if we just saw this user for the first time
        if (wasEnabled != null) {
            // fused and passive provider never get public updates for legacy reasons
            if (!FUSED_PROVIDER.equals(mName) && !PASSIVE_PROVIDER.equals(mName)) {
                Intent intent = new Intent(LocationManager.PROVIDERS_CHANGED_ACTION)
                        .putExtra(LocationManager.EXTRA_PROVIDER_NAME, mName)
                        .putExtra(LocationManager.EXTRA_PROVIDER_ENABLED, enabled)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            }

            // send updates to internal listeners - since we expect listener changes to be more
            // frequent than enabled changes, we use copy-on-read instead of copy-on-write
            if (!mEnabledListeners.isEmpty()) {
                ProviderEnabledListener[] listeners = mEnabledListeners.toArray(
                        new ProviderEnabledListener[0]);
                FgThread.getHandler().post(() -> {
                    for (int i = 0; i < listeners.length; i++) {
                        listeners[i].onProviderEnabledChanged(mName, userId, enabled);
                    }
                });
            }
        }

        // update active state of affected registrations
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        synchronized (mLock) {
            ipw.print(mName);
            ipw.print(" provider");
            if (mProvider.isMock()) {
                ipw.print(" [mock]");
            }
            ipw.println(":");
            ipw.increaseIndent();

            super.dump(fd, ipw, args);

            int[] userIds = mUserInfoHelper.getRunningUserIds();
            for (int userId : userIds) {
                if (userIds.length != 1) {
                    ipw.print("user ");
                    ipw.print(userId);
                    ipw.println(":");
                    ipw.increaseIndent();
                }
                ipw.print("last location=");
                ipw.println(getLastLocationUnsafe(userId, PERMISSION_FINE, false, Long.MAX_VALUE));
                ipw.print("enabled=");
                ipw.println(isEnabled(userId));
                if (userIds.length != 1) {
                    ipw.decreaseIndent();
                }
            }
        }

        mProvider.dump(fd, ipw, args);

        ipw.decreaseIndent();
    }

    @Override
    protected String getServiceState() {
        return mProvider.getCurrentRequest().toString();
    }

    private static class LastLocation {

        @Nullable private Location mFineLocation;
        @Nullable private Location mCoarseLocation;
        @Nullable private Location mFineBypassLocation;
        @Nullable private Location mCoarseBypassLocation;

        public void clearMock() {
            if (mFineLocation != null && mFineLocation.isFromMockProvider()) {
                mFineLocation = null;
                mCoarseLocation = null;
            }
            if (mFineBypassLocation != null && mFineBypassLocation.isFromMockProvider()) {
                mFineBypassLocation = null;
                mCoarseBypassLocation = null;
            }
        }

        public void clearLocations() {
            mFineLocation = null;
            mCoarseLocation = null;
        }

        @Nullable
        public Location get(@PermissionLevel int permissionLevel, boolean ignoreLocationSettings) {
            switch (permissionLevel) {
                case PERMISSION_FINE:
                    if (ignoreLocationSettings) {
                        return mFineBypassLocation;
                    } else {
                        return mFineLocation;
                    }
                case PERMISSION_COARSE:
                    if (ignoreLocationSettings) {
                        return mCoarseBypassLocation;
                    } else {
                        return mCoarseLocation;
                    }
                default:
                    // shouldn't be possible to have a client added without location permissions
                    throw new AssertionError();
            }
        }

        public void set(Location fineLocation, Location coarseLocation) {
            mFineLocation = calculateNextFine(mFineLocation, fineLocation);
            mCoarseLocation = calculateNextCoarse(mCoarseLocation, coarseLocation);
        }

        public void setBypass(Location fineLocation, Location coarseLocation) {
            mFineBypassLocation = calculateNextFine(mFineBypassLocation, fineLocation);
            mCoarseBypassLocation = calculateNextCoarse(mCoarseBypassLocation, coarseLocation);
        }

        private Location calculateNextFine(@Nullable Location oldFine, Location newFine) {
            if (oldFine == null) {
                return newFine;
            }

            // update last fine interval only if more recent
            if (newFine.getElapsedRealtimeNanos() > oldFine.getElapsedRealtimeNanos()) {
                return newFine;
            } else {
                return oldFine;
            }
        }

        private Location calculateNextCoarse(@Nullable Location oldCoarse, Location newCoarse) {
            if (oldCoarse == null) {
                return newCoarse;
            }

            // update last coarse interval only if enough time has passed
            if (newCoarse.getElapsedRealtimeNanos() - MIN_COARSE_INTERVAL_MS
                    > oldCoarse.getElapsedRealtimeNanos()) {
                return newCoarse;
            } else {
                return oldCoarse;
            }
        }
    }

    private static class SingleUseCallback extends IRemoteCallback.Stub implements Runnable,
            CancellationSignal.OnCancelListener {

        @Nullable
        public static SingleUseCallback wrap(@Nullable Runnable callback) {
            return callback == null ? null : new SingleUseCallback(callback);
        }

        @GuardedBy("this")
        @Nullable private Runnable mCallback;

        private SingleUseCallback(Runnable callback) {
            mCallback = Objects.requireNonNull(callback);
        }

        @Override
        public void sendResult(Bundle data) {
            run();
        }

        @Override
        public void onCancel() {
            run();
        }

        @Override
        public void run() {
            Runnable callback;
            synchronized (this) {
                callback = mCallback;
                mCallback = null;
            }

            // prevent this callback from being run more than once - otherwise this could provide an
            // attack vector for a malicious app to break assumptions on how many times a callback
            // may be invoked, and thus crash system server.
            if (callback == null) {
                return;
            }

            long identity = Binder.clearCallingIdentity();
            try {
                callback.run();
            } catch (RuntimeException e) {
                // since this is within a oneway binder transaction there is nowhere
                // for exceptions to go - move onto another thread to crash system
                // server so we find out about it
                FgThread.getExecutor().execute(() -> {
                    throw new AssertionError(e);
                });
                throw e;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
