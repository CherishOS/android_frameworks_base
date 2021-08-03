/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.timedetector.ServerFlags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A singleton that provides access to service configuration for time zone detection. This hides how
 * configuration is split between static, compile-time config and dynamic, server-pushed flags. It
 * provides a rudimentary mechanism to signal when values have changed.
 */
public final class ServiceConfigAccessor {

    @StringDef(prefix = "PROVIDER_MODE_",
            value = { PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface ProviderMode {}

    /**
     * The "disabled" provider mode. For use with {@link #getPrimaryLocationTimeZoneProviderMode()}
     * and {@link #getSecondaryLocationTimeZoneProviderMode()}.
     */
    public static final @ProviderMode String PROVIDER_MODE_DISABLED = "disabled";

    /**
     * The "enabled" provider mode. For use with {@link #getPrimaryLocationTimeZoneProviderMode()}
     * and {@link #getSecondaryLocationTimeZoneProviderMode()}.
     */
    public static final @ProviderMode String PROVIDER_MODE_ENABLED = "enabled";

    /**
     * Device config keys that affect the {@link TimeZoneDetectorService} service and {@link
     * com.android.server.timezonedetector.location.LocationTimeZoneManagerService}.
     */
    private static final Set<String> SERVER_FLAGS_KEYS_TO_WATCH = Collections.unmodifiableSet(
            new ArraySet<>(new String[] {
                    ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
                    ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT,
                    ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE,
                    ServerFlags.KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE,
                    ServerFlags.KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE,
                    ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS,
                    ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
                    ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS
            }));

    private static final Duration DEFAULT_PROVIDER_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ =
            Duration.ofMinutes(1);
    private static final Duration DEFAULT_PROVIDER_UNCERTAINTY_DELAY = Duration.ofMinutes(5);

    private static final Object SLOCK = new Object();

    /** The singleton instance. Initialized once in {@link #getInstance(Context)}. */
    @GuardedBy("SLOCK")
    @Nullable
    private static ServiceConfigAccessor sInstance;

    @NonNull private final Context mContext;

    /**
     * An ultimate "feature switch" for location-based time zone detection. If this is
     * {@code false}, the device cannot support the feature without a config change or a reboot:
     * This affects what services are started on boot to minimize expense when the feature is not
     * wanted.
     */
    private final boolean mGeoDetectionFeatureSupportedInConfig;

    @NonNull private final ServerFlags mServerFlags;

    /**
     * The mode to use for the primary location time zone provider in a test. Setting this
     * disables some permission checks.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test provider accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    @Nullable
    private String mTestPrimaryLocationTimeZoneProviderMode;

    /**
     * The package name to use for the primary location time zone provider in a test.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test provider accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    @Nullable
    private String mTestPrimaryLocationTimeZoneProviderPackageName;

    /**
     * See {@link #mTestPrimaryLocationTimeZoneProviderMode}; this is the equivalent for the
     * secondary provider.
     */
    @Nullable
    private String mTestSecondaryLocationTimeZoneProviderMode;

    /**
     * See {@link #mTestPrimaryLocationTimeZoneProviderPackageName}; this is the equivalent for the
     * secondary provider.
     */
    @Nullable
    private String mTestSecondaryLocationTimeZoneProviderPackageName;

    /**
     * Whether to record state changes for tests.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test state accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    private boolean mRecordProviderStateChanges;

    private ServiceConfigAccessor(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);

        // The config value is expected to be the main feature flag. Platform developers can also
        // force enable the feature using a persistent system property. Because system properties
        // can change, this value is cached and only changes on reboot.
        mGeoDetectionFeatureSupportedInConfig = context.getResources().getBoolean(
                com.android.internal.R.bool.config_enableGeolocationTimeZoneDetection)
                || SystemProperties.getBoolean(
                "persist.sys.location_time_zone_detection_feature_supported", false);

        mServerFlags = ServerFlags.getInstance(mContext);
    }

    /** Returns the singleton instance. */
    public static ServiceConfigAccessor getInstance(Context context) {
        synchronized (SLOCK) {
            if (sInstance == null) {
                sInstance = new ServiceConfigAccessor(context);
            }
            return sInstance;
        }
    }

    /**
     * Adds a listener that will be called when server flags related to this class change. The
     * callbacks are delivered on the main looper thread.
     *
     * <p>Note: Only for use by long-lived objects. There is deliberately no associated remove
     * method.
     */
    public void addListener(@NonNull ConfigurationChangeListener listener) {
        mServerFlags.addListener(listener, SERVER_FLAGS_KEYS_TO_WATCH);
    }

    /** Returns {@code true} if any form of automatic time zone detection is supported. */
    public boolean isAutoDetectionFeatureSupported() {
        return isTelephonyTimeZoneDetectionFeatureSupported()
                || isGeoTimeZoneDetectionFeatureSupported();
    }

    /**
     * Returns {@code true} if the telephony-based time zone detection feature is supported on the
     * device.
     */
    public boolean isTelephonyTimeZoneDetectionFeatureSupported() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /**
     * Returns {@code true} if the location-based time zone detection feature can be supported on
     * this device at all according to config. When {@code false}, implies that various other
     * location-based settings will be turned off or rendered meaningless. Typically {@link
     * #isGeoTimeZoneDetectionFeatureSupported()} should be used instead.
     */
    public boolean isGeoTimeZoneDetectionFeatureSupportedInConfig() {
        return mGeoDetectionFeatureSupportedInConfig;
    }

    /**
     * Returns {@code true} if the location-based time zone detection feature is supported on the
     * device.
     */
    public boolean isGeoTimeZoneDetectionFeatureSupported() {
        // For the feature to be enabled it must:
        // 1) Be turned on in config.
        // 2) Not be turned off via a server flag.
        // 3) There must be at least one location time zone provider enabled / configured.
        return mGeoDetectionFeatureSupportedInConfig
                && isGeoTimeZoneDetectionFeatureSupportedInternal()
                && atLeastOneProviderIsEnabled();
    }

    private boolean atLeastOneProviderIsEnabled() {
        return !(Objects.equals(getPrimaryLocationTimeZoneProviderMode(), PROVIDER_MODE_DISABLED)
                && Objects.equals(getSecondaryLocationTimeZoneProviderMode(),
                PROVIDER_MODE_DISABLED));
    }

    /**
     * Returns {@code true} if the location-based time zone detection feature is not explicitly
     * disabled by a server flag.
     */
    private boolean isGeoTimeZoneDetectionFeatureSupportedInternal() {
        final boolean defaultEnabled = true;
        return mServerFlags.getBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
                defaultEnabled);
    }

    /** Returns the package name of the app hosting the primary location time zone provider. */
    @NonNull
    public String getPrimaryLocationTimeZoneProviderPackageName() {
        if (mTestPrimaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestPrimaryLocationTimeZoneProviderPackageName;
        }
        return mContext.getResources().getString(
                R.string.config_primaryLocationTimeZoneProviderPackageName);
    }

    /**
     * Sets the package name of the app hosting the primary location time zone provider for tests.
     * Setting a {@code null} value means the provider is to be disabled.
     * The values are reset with {@link #resetVolatileTestConfig()}.
     */
    public void setTestPrimaryLocationTimeZoneProviderPackageName(
            @Nullable String testPrimaryLocationTimeZoneProviderPackageName) {
        mTestPrimaryLocationTimeZoneProviderPackageName =
                testPrimaryLocationTimeZoneProviderPackageName;
        mTestPrimaryLocationTimeZoneProviderMode =
                mTestPrimaryLocationTimeZoneProviderPackageName == null
                        ? PROVIDER_MODE_DISABLED : PROVIDER_MODE_ENABLED;
    }

    /**
     * Returns {@code true} if the usual permission checks are to be bypassed for the primary
     * provider. Returns {@code true} only if {@link
     * #setTestPrimaryLocationTimeZoneProviderPackageName} has been called.
     */
    public boolean isTestPrimaryLocationTimeZoneProvider() {
        return mTestPrimaryLocationTimeZoneProviderMode != null;
    }

    /** Returns the package name of the app hosting the secondary location time zone provider. */
    @NonNull
    public String getSecondaryLocationTimeZoneProviderPackageName() {
        if (mTestSecondaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestSecondaryLocationTimeZoneProviderPackageName;
        }
        return mContext.getResources().getString(
                R.string.config_secondaryLocationTimeZoneProviderPackageName);
    }

    /**
     * Sets the package name of the app hosting the secondary location time zone provider for tests.
     * Setting a {@code null} value means the provider is to be disabled.
     * The values are reset with {@link #resetVolatileTestConfig()}.
     */
    public void setTestSecondaryLocationTimeZoneProviderPackageName(
            @Nullable String testSecondaryLocationTimeZoneProviderPackageName) {
        mTestSecondaryLocationTimeZoneProviderPackageName =
                testSecondaryLocationTimeZoneProviderPackageName;
        mTestSecondaryLocationTimeZoneProviderMode =
                mTestSecondaryLocationTimeZoneProviderPackageName == null
                        ? PROVIDER_MODE_DISABLED : PROVIDER_MODE_ENABLED;
    }

    /**
     * Returns {@code true} if the usual permission checks are to be bypassed for the secondary
     * provider. Returns {@code true} only if {@link
     * #setTestSecondaryLocationTimeZoneProviderPackageName} has been called.
     */
    public boolean isTestSecondaryLocationTimeZoneProvider() {
        return mTestSecondaryLocationTimeZoneProviderMode != null;
    }

    /**
     * Enables/disables the state recording mode for tests. The value is reset with {@link
     * #resetVolatileTestConfig()}.
     */
    public void setRecordProviderStateChanges(boolean enabled) {
        mRecordProviderStateChanges = enabled;
    }

    /**
     * Returns {@code true} if providers are expected to record their state changes for tests.
     */
    public boolean getRecordProviderStateChanges() {
        return mRecordProviderStateChanges;
    }

    /**
     * Returns the mode for the primary location time zone provider.
     */
    @NonNull
    public @ProviderMode String getPrimaryLocationTimeZoneProviderMode() {
        if (mTestPrimaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestPrimaryLocationTimeZoneProviderMode;
        }
        return mServerFlags.getOptionalString(
                ServerFlags.KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE)
                .orElse(getPrimaryLocationTimeZoneProviderModeFromConfig());
    }

    @NonNull
    private @ProviderMode String getPrimaryLocationTimeZoneProviderModeFromConfig() {
        int providerEnabledConfigId = R.bool.config_enablePrimaryLocationTimeZoneProvider;
        return getConfigBoolean(providerEnabledConfigId)
                ? PROVIDER_MODE_ENABLED : PROVIDER_MODE_DISABLED;
    }

    /**
     * Returns the mode for the secondary location time zone provider.
     */
    public @ProviderMode String getSecondaryLocationTimeZoneProviderMode() {
        if (mTestSecondaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestSecondaryLocationTimeZoneProviderMode;
        }
        return mServerFlags.getOptionalString(
                ServerFlags.KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE)
                .orElse(getSecondaryLocationTimeZoneProviderModeFromConfig());
    }

    @NonNull
    private @ProviderMode String getSecondaryLocationTimeZoneProviderModeFromConfig() {
        int providerEnabledConfigId = R.bool.config_enableSecondaryLocationTimeZoneProvider;
        return getConfigBoolean(providerEnabledConfigId)
                ? PROVIDER_MODE_ENABLED : PROVIDER_MODE_DISABLED;
    }

    /**
     * Returns whether location time zone detection is enabled for users when there's no setting
     * value. Intended for use during feature release testing to "opt-in" users that haven't shown
     * an explicit preference.
     */
    public boolean isGeoDetectionEnabledForUsersByDefault() {
        return mServerFlags.getBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT, false);
    }

    /**
     * Returns whether location time zone detection is force enabled/disabled for users. Intended
     * for use during feature release testing to force a given state.
     */
    @NonNull
    public Optional<Boolean> getGeoDetectionSettingEnabledOverride() {
        return mServerFlags.getOptionalBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE);
    }

    /**
     * Returns the time to send to a location time zone provider that informs it how long it has
     * to return its first time zone suggestion.
     */
    @NonNull
    public Duration getLocationTimeZoneProviderInitializationTimeout() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS,
                DEFAULT_PROVIDER_INITIALIZATION_TIMEOUT);
    }

    /**
     * Returns the time added to {@link #getLocationTimeZoneProviderInitializationTimeout()} by the
     * server before unilaterally declaring the provider is uncertain.
     */
    @NonNull
    public Duration getLocationTimeZoneProviderInitializationTimeoutFuzz() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
                DEFAULT_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ);
    }

    /**
     * Returns the time after uncertainty is detected by providers before the location time zone
     * manager makes a suggestion to the time zone detector.
     */
    @NonNull
    public Duration getLocationTimeZoneUncertaintyDelay() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS,
                DEFAULT_PROVIDER_UNCERTAINTY_DELAY);
    }

    /** Clears all in-memory test config. */
    public void resetVolatileTestConfig() {
        mTestPrimaryLocationTimeZoneProviderPackageName = null;
        mTestPrimaryLocationTimeZoneProviderMode = null;
        mTestSecondaryLocationTimeZoneProviderPackageName = null;
        mTestSecondaryLocationTimeZoneProviderMode = null;
        mRecordProviderStateChanges = false;
    }

    private boolean getConfigBoolean(int providerEnabledConfigId) {
        Resources resources = mContext.getResources();
        return resources.getBoolean(providerEnabledConfigId);
    }
}
