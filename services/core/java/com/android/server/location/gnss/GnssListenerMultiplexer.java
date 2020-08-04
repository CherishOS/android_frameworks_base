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

package com.android.server.location.gnss;

import static android.location.LocationManager.GPS_PROVIDER;

import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.annotation.Nullable;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.location.listeners.BinderListenerRegistration;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.util.AppForegroundHelper;
import com.android.server.location.util.Injector;
import com.android.server.location.util.LocationPermissionsHelper;
import com.android.server.location.util.SettingsHelper;
import com.android.server.location.util.UserInfoHelper;
import com.android.server.location.util.UserInfoHelper.UserListener;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Manager for all GNSS related listeners. This class handles deactivating listeners that do not
 * belong to the current user, that do not have the appropriate permissions, or that are not
 * currently in the foreground. It will also disable listeners if the GNSS provider is disabled.
 * Listeners must be registered with the associated IBinder as the key, if the IBinder dies, the
 * registration will automatically be removed.
 *
 * @param <TRequest>       request type
 * @param <TListener>      listener type
 * @param <TMergedRequest> merged request type
 */
public abstract class GnssListenerMultiplexer<TRequest, TListener extends IInterface,
        TMergedRequest> extends
        ListenerMultiplexer<IBinder, TRequest, TListener, GnssListenerMultiplexer<TRequest,
                        TListener, TMergedRequest>.GnssListenerRegistration, TMergedRequest> {

    /**
     * Registration object for GNSS listeners.
     */
    protected final class GnssListenerRegistration extends
            BinderListenerRegistration<TRequest, TListener> {

        // we store these values because we don't trust the listeners not to give us dupes, not to
        // spam us, and because checking the values may be more expensive
        private boolean mForeground;
        private boolean mPermitted;

        protected GnssListenerRegistration(@Nullable TRequest request,
                CallerIdentity callerIdentity, TListener listener) {
            super(TAG, request, callerIdentity, listener);
        }

        @Override
        protected GnssListenerMultiplexer<TRequest, TListener, TMergedRequest> getOwner() {
            return GnssListenerMultiplexer.this;
        }

        /**
         * Returns true if this registration is currently in the foreground.
         */
        public boolean isForeground() {
            return mForeground;
        }

        boolean isPermitted() {
            return mPermitted;
        }

        @Override
        protected void onBinderListenerRegister() {
            mPermitted = mLocationPermissionsHelper.hasLocationPermissions(PERMISSION_FINE,
                    getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().getUid());
        }

        boolean onLocationPermissionsChanged(String packageName) {
            if (getIdentity().getPackageName().equals(packageName)) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        boolean onLocationPermissionsChanged(int uid) {
            if (getIdentity().getUid() == uid) {
                return onLocationPermissionsChanged();
            }

            return false;
        }

        private boolean onLocationPermissionsChanged() {
            boolean permitted = mLocationPermissionsHelper.hasLocationPermissions(PERMISSION_FINE,
                    getIdentity());
            if (permitted != mPermitted) {
                mPermitted = permitted;
                return true;
            }

            return false;
        }

        boolean onForegroundChanged(int uid, boolean foreground) {
            if (getIdentity().getUid() == uid && foreground != mForeground) {
                mForeground = foreground;
                return true;
            }

            return false;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());

            ArraySet<String> flags = new ArraySet<>(2);
            if (!mForeground) {
                flags.add("bg");
            }
            if (!mPermitted) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }

            if (getRequest() != null) {
                builder.append(" ").append(getRequest());
            }
            return builder.toString();
        }
    }

    protected final UserInfoHelper mUserInfoHelper;
    protected final SettingsHelper mSettingsHelper;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final LocationManagerInternal mLocationManagerInternal;

    private final UserListener mUserChangedListener = this::onUserChanged;
    private final ProviderEnabledListener mProviderEnabledChangedListener =
            this::onProviderEnabledChanged;
    private final SettingsHelper.GlobalSettingChangedListener
            mBackgroundThrottlePackageWhitelistChangedListener =
            this::onBackgroundThrottlePackageWhitelistChanged;
    private final SettingsHelper.UserSettingChangedListener
            mLocationPackageBlacklistChangedListener =
            this::onLocationPackageBlacklistChanged;
    private final LocationPermissionsHelper.LocationPermissionsListener
            mLocationPermissionsListener =
            new LocationPermissionsHelper.LocationPermissionsListener() {
                @Override
                public void onLocationPermissionsChanged(String packageName) {
                    GnssListenerMultiplexer.this.onLocationPermissionsChanged(packageName);
                }

                @Override
                public void onLocationPermissionsChanged(int uid) {
                    GnssListenerMultiplexer.this.onLocationPermissionsChanged(uid);
                }
            };
    private final AppForegroundHelper.AppForegroundListener mAppForegroundChangedListener =
            this::onAppForegroundChanged;

    protected GnssListenerMultiplexer(Injector injector) {
        mUserInfoHelper = injector.getUserInfoHelper();
        mSettingsHelper = injector.getSettingsHelper();
        mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        mAppForegroundHelper = injector.getAppForegroundHelper();
        mLocationManagerInternal = Objects.requireNonNull(
                LocalServices.getService(LocationManagerInternal.class));
    }

    /**
     * May be overridden by subclasses to return whether the service is supported or not. This value
     * should never change for the lifetime of the multiplexer. If the service is unsupported, all
     * registrations will be treated as inactive and the backing service will never be registered.
     *
     */
    protected boolean isServiceSupported() {
        return true;
    }

    /**
     * Adds a listener with the given identity.
     */
    protected void addListener(CallerIdentity identity, TListener listener) {
        addListener(null, identity, listener);
    }

    /**
     * Adds a listener with the given identity and request.
     */
    protected void addListener(TRequest request, CallerIdentity identity, TListener listener) {
        addRegistration(listener.asBinder(),
                new GnssListenerRegistration(request, identity, listener));
    }

    /**
     * Removes the given listener.
     */
    public void removeListener(TListener listener) {
        removeRegistration(listener.asBinder());
    }

    @Override
    protected boolean isActive(GnssListenerRegistration registration) {
        if (!isServiceSupported()) {
            return false;
        }

        CallerIdentity identity = registration.getIdentity();
        return registration.isPermitted()
                && (registration.isForeground() || isBackgroundRestrictionExempt(identity))
                && mUserInfoHelper.isCurrentUserId(identity.getUserId())
                && mLocationManagerInternal.isProviderEnabledForUser(GPS_PROVIDER,
                identity.getUserId())
                && !mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(),
                identity.getPackageName());
    }

    private boolean isBackgroundRestrictionExempt(CallerIdentity identity) {
        if (identity.getUid() == Process.SYSTEM_UID) {
            return true;
        }

        if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                identity.getPackageName())) {
            return true;
        }

        return mLocationManagerInternal.isProvider(null, identity);
    }

    @Override
    protected void onRegister() {
        if (!isServiceSupported()) {
            return;
        }

        mUserInfoHelper.addListener(mUserChangedListener);
        mLocationManagerInternal.addProviderEnabledListener(GPS_PROVIDER,
                mProviderEnabledChangedListener);
        mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.addOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mLocationPermissionsHelper.addListener(mLocationPermissionsListener);
        mAppForegroundHelper.addListener(mAppForegroundChangedListener);
    }

    @Override
    protected void onUnregister() {
        if (!isServiceSupported()) {
            return;
        }

        mUserInfoHelper.removeListener(mUserChangedListener);
        mLocationManagerInternal.removeProviderEnabledListener(GPS_PROVIDER,
                mProviderEnabledChangedListener);
        mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(
                mLocationPackageBlacklistChangedListener);
        mLocationPermissionsHelper.removeListener(mLocationPermissionsListener);
        mAppForegroundHelper.removeListener(mAppForegroundChangedListener);
    }

    private void onUserChanged(int userId, int change) {
        if (change == UserListener.CURRENT_USER_CHANGED) {
            updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
        }
    }

    private void onProviderEnabledChanged(String provider, int userId, boolean enabled) {
        Preconditions.checkState(GPS_PROVIDER.equals(provider));
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    private void onBackgroundThrottlePackageWhitelistChanged() {
        updateRegistrations(registration -> true);
    }

    private void onLocationPackageBlacklistChanged(int userId) {
        updateRegistrations(registration -> registration.getIdentity().getUserId() == userId);
    }

    private void onLocationPermissionsChanged(String packageName) {
        updateRegistrations(registration -> registration.onLocationPermissionsChanged(packageName));
    }

    private void onLocationPermissionsChanged(int uid) {
        updateRegistrations(registration -> registration.onLocationPermissionsChanged(uid));
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        updateRegistrations(registration -> registration.onForegroundChanged(uid, foreground));
    }

    @Override
    protected void dumpServiceState(PrintWriter pw) {
        if (!isServiceSupported()) {
            pw.print("unsupported");
        } else {
            super.dumpServiceState(pw);
        }
    }
}
