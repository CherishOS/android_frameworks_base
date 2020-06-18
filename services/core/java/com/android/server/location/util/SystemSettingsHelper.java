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

package com.android.server.location.util;

import static android.provider.Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST;
import static android.provider.Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS;
import static android.provider.Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST;
import static android.provider.Settings.Secure.LOCATION_COARSE_ACCURACY_M;
import static android.provider.Settings.Secure.LOCATION_MODE;
import static android.provider.Settings.Secure.LOCATION_MODE_OFF;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.SystemConfig;

import java.io.FileDescriptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Provides accessors and listeners for all location related settings.
 */
public class SystemSettingsHelper extends SettingsHelper {

    private static final String LOCATION_PACKAGE_BLACKLIST = "locationPackagePrefixBlacklist";
    private static final String LOCATION_PACKAGE_WHITELIST = "locationPackagePrefixWhitelist";

    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;
    private static final long DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS =
            30 * 60 * 1000;
    private static final float DEFAULT_COARSE_LOCATION_ACCURACY_M = 2000.0f;

    private final Context mContext;

    private final IntegerSecureSetting mLocationMode;
    private final LongGlobalSetting mBackgroundThrottleIntervalMs;
    private final BooleanGlobalSetting mGnssMeasurementFullTracking;
    private final StringListCachedSecureSetting mLocationPackageBlacklist;
    private final StringListCachedSecureSetting mLocationPackageWhitelist;
    private final StringSetCachedGlobalSetting mBackgroundThrottlePackageWhitelist;
    private final StringSetCachedGlobalSetting mIgnoreSettingsPackageWhitelist;

    public SystemSettingsHelper(Context context) {
        mContext = context;

        mLocationMode = new IntegerSecureSetting(context, LOCATION_MODE, FgThread.getHandler());
        mBackgroundThrottleIntervalMs = new LongGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS, FgThread.getHandler());
        mGnssMeasurementFullTracking = new BooleanGlobalSetting(context,
                ENABLE_GNSS_RAW_MEAS_FULL_TRACKING, FgThread.getHandler());
        mLocationPackageBlacklist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_BLACKLIST, FgThread.getHandler());
        mLocationPackageWhitelist = new StringListCachedSecureSetting(context,
                LOCATION_PACKAGE_WHITELIST, FgThread.getHandler());
        mBackgroundThrottlePackageWhitelist = new StringSetCachedGlobalSetting(context,
                LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                () -> SystemConfig.getInstance().getAllowUnthrottledLocation(),
                FgThread.getHandler());
        mIgnoreSettingsPackageWhitelist = new StringSetCachedGlobalSetting(context,
                LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST,
                () -> SystemConfig.getInstance().getAllowIgnoreLocationSettings(),
                FgThread.getHandler());
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        mLocationMode.register();
        mBackgroundThrottleIntervalMs.register();
        mLocationPackageBlacklist.register();
        mLocationPackageWhitelist.register();
        mBackgroundThrottlePackageWhitelist.register();
        mIgnoreSettingsPackageWhitelist.register();
    }

    /**
     * Retrieve if location is enabled or not.
     */
    @Override
    public boolean isLocationEnabled(int userId) {
        return mLocationMode.getValueForUser(LOCATION_MODE_OFF, userId) != LOCATION_MODE_OFF;
    }

    /**
     * Set location enabled for a user.
     */
    @Override
    public void setLocationEnabled(boolean enabled, int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCATION_MODE,
                    enabled
                            ? Settings.Secure.LOCATION_MODE_ON
                            : Settings.Secure.LOCATION_MODE_OFF,
                    userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Add a listener for changes to the location enabled setting. Callbacks occur on an unspecified
     * thread.
     */
    @Override
    public void addOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.addListener(listener);
    }

    /**
     * Remove a listener for changes to the location enabled setting.
     */
    @Override
    public void removeOnLocationEnabledChangedListener(UserSettingChangedListener listener) {
        mLocationMode.removeListener(listener);
    }

    /**
     * Retrieve the background throttle interval.
     */
    @Override
    public long getBackgroundThrottleIntervalMs() {
        return mBackgroundThrottleIntervalMs.getValue(DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS);
    }

    /**
     * Add a listener for changes to the background throttle interval. Callbacks occur on an
     * unspecified thread.
     */
    @Override
    public void addOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle interval.
     */
    @Override
    public void removeOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottleIntervalMs.removeListener(listener);
    }

    /**
     * Check if the given package is blacklisted for location access.
     */
    @Override
    public boolean isLocationPackageBlacklisted(int userId, String packageName) {
        List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(userId);
        if (locationPackageBlacklist.isEmpty()) {
            return false;
        }

        List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(userId);
        for (String locationWhitelistPackage : locationPackageWhitelist) {
            if (packageName.startsWith(locationWhitelistPackage)) {
                return false;
            }
        }

        for (String locationBlacklistPackage : locationPackageBlacklist) {
            if (packageName.startsWith(locationBlacklistPackage)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add a listener for changes to the location package blacklist. Callbacks occur on an
     * unspecified thread.
     */
    @Override
    public void addOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener) {
        mLocationPackageBlacklist.addListener(listener);
        mLocationPackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the location package blacklist.
     */
    @Override
    public void removeOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener) {
        mLocationPackageBlacklist.removeListener(listener);
        mLocationPackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the background throttle package whitelist.
     */
    @Override
    public Set<String> getBackgroundThrottlePackageWhitelist() {
        return mBackgroundThrottlePackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the background throttle package whitelist. Callbacks occur on
     * an unspecified thread.
     */
    @Override
    public void addOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    @Override
    public void removeOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mBackgroundThrottlePackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the gnss measurements full tracking enabled setting.
     */
    @Override
    public boolean isGnssMeasurementsFullTrackingEnabled() {
        return mGnssMeasurementFullTracking.getValue(false);
    }

    /**
     * Add a listener for changes to the background throttle package whitelist. Callbacks occur on
     * an unspecified thread.
     */
    @Override
    public void addOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementFullTracking.addListener(listener);
    }

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    @Override
    public void removeOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener) {
        mGnssMeasurementFullTracking.removeListener(listener);
    }

    /**
     * Retrieve the ignore settings package whitelist.
     */
    @Override
    public Set<String> getIgnoreSettingsPackageWhitelist() {
        return mIgnoreSettingsPackageWhitelist.getValue();
    }

    /**
     * Add a listener for changes to the ignore settings package whitelist. Callbacks occur on an
     * unspecified thread.
     */
    @Override
    public void addOnIgnoreSettingsPackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageWhitelist.addListener(listener);
    }

    /**
     * Remove a listener for changes to the ignore settings package whitelist.
     */
    @Override
    public void removeOnIgnoreSettingsPackageWhitelistChangedListener(
            GlobalSettingChangedListener listener) {
        mIgnoreSettingsPackageWhitelist.removeListener(listener);
    }

    /**
     * Retrieve the background throttling proximity alert interval.
     */
    @Override
    public long getBackgroundThrottleProximityAlertIntervalMs() {
        long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Global.getLong(mContext.getContentResolver(),
                    LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                    DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Retrieve the accuracy for coarsening location, ie, the grid size used for snap-to-grid
     * coarsening.
     */
    @Override
    public float getCoarseLocationAccuracyM() {
        long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getFloat(
                    mContext.getContentResolver(),
                    LOCATION_COARSE_ACCURACY_M,
                    DEFAULT_COARSE_LOCATION_ACCURACY_M);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Dump info for debugging.
     */
    @Override
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        int userId = ActivityManager.getCurrentUser();

        ipw.print("Location Enabled: ");
        ipw.println(isLocationEnabled(userId));

        List<String> locationPackageBlacklist = mLocationPackageBlacklist.getValueForUser(userId);
        if (!locationPackageBlacklist.isEmpty()) {
            ipw.println("Location Deny Packages:");
            ipw.increaseIndent();
            for (String packageName : locationPackageBlacklist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();

            List<String> locationPackageWhitelist = mLocationPackageWhitelist.getValueForUser(
                    userId);
            if (!locationPackageWhitelist.isEmpty()) {
                ipw.println("Location Allow Packages:");
                ipw.increaseIndent();
                for (String packageName : locationPackageWhitelist) {
                    ipw.println(packageName);
                }
                ipw.decreaseIndent();
            }
        }

        Set<String> backgroundThrottlePackageWhitelist =
                mBackgroundThrottlePackageWhitelist.getValue();
        if (!backgroundThrottlePackageWhitelist.isEmpty()) {
            ipw.println("Throttling Allow Packages:");
            ipw.increaseIndent();
            for (String packageName : backgroundThrottlePackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }

        Set<String> ignoreSettingsPackageWhitelist = mIgnoreSettingsPackageWhitelist.getValue();
        if (!ignoreSettingsPackageWhitelist.isEmpty()) {
            ipw.println("Bypass Allow Packages:");
            ipw.increaseIndent();
            for (String packageName : ignoreSettingsPackageWhitelist) {
                ipw.println(packageName);
            }
            ipw.decreaseIndent();
        }
    }

    private abstract static class ObservingSetting extends ContentObserver {

        private final CopyOnWriteArrayList<UserSettingChangedListener> mListeners;

        @GuardedBy("this")
        private boolean mRegistered;

        ObservingSetting(Handler handler) {
            super(handler);
            mListeners = new CopyOnWriteArrayList<>();
        }

        protected synchronized boolean isRegistered() {
            return mRegistered;
        }

        protected synchronized void register(Context context, Uri uri) {
            if (mRegistered) {
                return;
            }

            context.getContentResolver().registerContentObserver(
                    uri, false, this, UserHandle.USER_ALL);
            mRegistered = true;
        }

        public void addListener(UserSettingChangedListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(UserSettingChangedListener listener) {
            mListeners.remove(listener);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (D) {
                Log.d(TAG, "location setting changed [u" + userId + "]: " + uri);
            }

            for (UserSettingChangedListener listener : mListeners) {
                listener.onSettingChanged(userId);
            }
        }
    }

    private static class IntegerSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        IntegerSecureSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        void register() {
            register(mContext, Settings.Secure.getUriFor(mSettingName));
        }

        public int getValueForUser(int defaultValue, int userId) {
            long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Secure.getIntForUser(mContext.getContentResolver(), mSettingName,
                        defaultValue, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class StringListCachedSecureSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        @GuardedBy("this")
        private int mCachedUserId;
        @GuardedBy("this")
        private List<String> mCachedValue;

        StringListCachedSecureSetting(Context context, String settingName,
                Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;

            mCachedUserId = UserHandle.USER_NULL;
        }

        public void register() {
            register(mContext, Settings.Secure.getUriFor(mSettingName));
        }

        public synchronized List<String> getValueForUser(int userId) {
            Preconditions.checkArgument(userId != UserHandle.USER_NULL);

            List<String> value = mCachedValue;
            if (userId != mCachedUserId) {
                long identity = Binder.clearCallingIdentity();
                try {
                    String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            mSettingName, userId);
                    if (TextUtils.isEmpty(setting)) {
                        value = Collections.emptyList();
                    } else {
                        value = Arrays.asList(setting.split(","));
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                if (isRegistered()) {
                    mCachedUserId = userId;
                    mCachedValue = value;
                }
            }

            return value;
        }

        public synchronized void invalidateForUser(int userId) {
            if (mCachedUserId == userId) {
                mCachedUserId = UserHandle.USER_NULL;
                mCachedValue = null;
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            invalidateForUser(userId);
            super.onChange(selfChange, uri, userId);
        }
    }

    private static class BooleanGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        BooleanGlobalSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public boolean getValue(boolean defaultValue) {
            long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Global.getInt(mContext.getContentResolver(), mSettingName,
                        defaultValue ? 1 : 0) != 0;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class LongGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;

        LongGlobalSetting(Context context, String settingName, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public long getValue(long defaultValue) {
            long identity = Binder.clearCallingIdentity();
            try {
                return Settings.Global.getLong(mContext.getContentResolver(), mSettingName,
                        defaultValue);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class StringSetCachedGlobalSetting extends ObservingSetting {

        private final Context mContext;
        private final String mSettingName;
        private final Supplier<ArraySet<String>> mBaseValuesSupplier;

        @GuardedBy("this")
        private boolean mValid;
        @GuardedBy("this")
        private ArraySet<String> mCachedValue;

        StringSetCachedGlobalSetting(Context context, String settingName,
                Supplier<ArraySet<String>> baseValuesSupplier, Handler handler) {
            super(handler);
            mContext = context;
            mSettingName = settingName;
            mBaseValuesSupplier = baseValuesSupplier;

            mValid = false;
        }

        public void register() {
            register(mContext, Settings.Global.getUriFor(mSettingName));
        }

        public synchronized Set<String> getValue() {
            ArraySet<String> value = mCachedValue;
            if (!mValid) {
                long identity = Binder.clearCallingIdentity();
                try {
                    value = new ArraySet<>(mBaseValuesSupplier.get());
                    String setting = Settings.Global.getString(mContext.getContentResolver(),
                            mSettingName);
                    if (!TextUtils.isEmpty(setting)) {
                        value.addAll(Arrays.asList(setting.split(",")));
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                if (isRegistered()) {
                    mValid = true;
                    mCachedValue = value;
                }
            }

            return value;
        }

        public synchronized void invalidate() {
            mValid = false;
            mCachedValue = null;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            invalidate();
            super.onChange(selfChange, uri, userId);
        }
    }
}
