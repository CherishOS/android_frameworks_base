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

package com.android.server.vibrator;

import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Vibrator.VibrationIntensity;
import android.os.vibrator.VibrationConfig;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Controls all the system settings related to vibration. */
final class VibrationSettings {
    private static final String TAG = "VibrationSettings";

    /**
     * Set of usages allowed for vibrations from background processes.
     *
     * <p>Some examples are notification, ringtone or alarm vibrations, that are allowed to vibrate
     * unexpectedly as they are meant to grab the user's attention. Hardware feedback and physical
     * emulation are also supported, as the trigger process might still be in the background when
     * the user interaction wakes the device.
     */
    private static final Set<Integer> BACKGROUND_PROCESS_USAGE_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    USAGE_RINGTONE,
                    USAGE_ALARM,
                    USAGE_NOTIFICATION,
                    USAGE_COMMUNICATION_REQUEST,
                    USAGE_HARDWARE_FEEDBACK,
                    USAGE_PHYSICAL_EMULATION));

    /**
     * Set of usages allowed for vibrations in battery saver mode (low power).
     *
     * <p>Some examples are ringtone or alarm vibrations, that have high priority and should vibrate
     * even when the device is saving battery.
     */
    private static final Set<Integer> BATTERY_SAVER_USAGE_ALLOWLIST = new HashSet<>(
            Arrays.asList(
                    USAGE_RINGTONE,
                    USAGE_ALARM,
                    USAGE_COMMUNICATION_REQUEST));

    /** Listener for changes on vibration settings. */
    interface OnVibratorSettingsChanged {
        /** Callback triggered when any of the vibrator settings change. */
        void onChange();
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final SettingsObserver mSettingObserver;
    @VisibleForTesting
    final UidObserver mUidObserver;
    @VisibleForTesting
    final UserObserver mUserReceiver;

    @GuardedBy("mLock")
    private final List<OnVibratorSettingsChanged> mListeners = new ArrayList<>();
    private final SparseArray<VibrationEffect> mFallbackEffects;

    private final VibrationConfig mVibrationConfig;

    @GuardedBy("mLock")
    @Nullable
    private AudioManager mAudioManager;

    @GuardedBy("mLock")
    private boolean mVibrateInputDevices;
    @GuardedBy("mLock")
    private SparseIntArray mCurrentVibrationIntensities = new SparseIntArray();
    @GuardedBy("mLock")
    private boolean mBatterySaverMode;

    VibrationSettings(Context context, Handler handler) {
        this(context, handler, new VibrationConfig(context.getResources()));
    }

    @VisibleForTesting
    VibrationSettings(Context context, Handler handler, VibrationConfig config) {
        mContext = context;
        mVibrationConfig = config;
        mSettingObserver = new SettingsObserver(handler);
        mUidObserver = new UidObserver();
        mUserReceiver = new UserObserver();

        VibrationEffect clickEffect = createEffectFromResource(
                com.android.internal.R.array.config_virtualKeyVibePattern);
        VibrationEffect doubleClickEffect = createEffectFromResource(
                com.android.internal.R.array.config_doubleClickVibePattern);
        VibrationEffect heavyClickEffect = createEffectFromResource(
                com.android.internal.R.array.config_longPressVibePattern);
        VibrationEffect tickEffect = createEffectFromResource(
                com.android.internal.R.array.config_clockTickVibePattern);

        mFallbackEffects = new SparseArray<>();
        mFallbackEffects.put(VibrationEffect.EFFECT_CLICK, clickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_DOUBLE_CLICK, doubleClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TICK, tickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_HEAVY_CLICK, heavyClickEffect);
        mFallbackEffects.put(VibrationEffect.EFFECT_TEXTURE_TICK,
                VibrationEffect.get(VibrationEffect.EFFECT_TICK, false));

        // Update with current values from settings.
        updateSettings();
    }

    public void onSystemReady() {
        synchronized (mLock) {
            mAudioManager = mContext.getSystemService(AudioManager.class);
        }
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

        PowerManagerInternal pm = LocalServices.getService(PowerManagerInternal.class);
        pm.registerLowPowerModeObserver(
                new PowerManagerInternal.LowPowerModeListener() {
                    @Override
                    public int getServiceType() {
                        return PowerManager.ServiceType.VIBRATION;
                    }

                    @Override
                    public void onLowPowerModeChanged(PowerSaveState result) {
                        boolean shouldNotifyListeners;
                        synchronized (mLock) {
                            shouldNotifyListeners = result.batterySaverEnabled != mBatterySaverMode;
                            mBatterySaverMode = result.batterySaverEnabled;
                        }
                        if (shouldNotifyListeners) {
                            notifyListeners();
                        }
                    }
                });

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mUserReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Listen to all settings that might affect the result of Vibrator.getVibrationIntensity.
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_INPUT_DEVICES));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING));
        registerSettingsObserver(Settings.System.getUriFor(Settings.System.APPLY_RAMPING_RINGER));
        registerSettingsObserver(Settings.System.getUriFor(
                Settings.System.HAPTIC_FEEDBACK_ENABLED));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.ALARM_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.MEDIA_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.NOTIFICATION_VIBRATION_INTENSITY));
        registerSettingsObserver(
                Settings.System.getUriFor(Settings.System.RING_VIBRATION_INTENSITY));

        // Update with newly loaded services.
        updateSettings();
    }

    /**
     * Add listener to vibrator settings changes. This will trigger the listener with current state
     * immediately and every time one of the settings change.
     */
    public void addListener(OnVibratorSettingsChanged listener) {
        synchronized (mLock) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    /** Remove listener to vibrator settings. */
    public void removeListener(OnVibratorSettingsChanged listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    /**
     * The duration, in milliseconds, that should be applied to convert vibration effect's
     * {@link android.os.vibrator.RampSegment} to a {@link android.os.vibrator.StepSegment} on
     * devices without PWLE support.
     */
    public int getRampStepDuration() {
        return mVibrationConfig.getRampStepDurationMs();
    }

    /**
     * The duration, in milliseconds, that should be applied to the ramp to turn off the vibrator
     * when a vibration is cancelled or finished at non-zero amplitude.
     */
    public int getRampDownDuration() {
        return mVibrationConfig.getRampDownDurationMs();
    }

    /**
     * Return default vibration intensity for given usage.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getDefaultIntensity(@VibrationAttributes.Usage int usageHint) {
        return mVibrationConfig.getDefaultVibrationIntensity(usageHint);
    }

    /**
     * Return the current vibration intensity set for given usage at the user settings.
     *
     * @param usageHint one of VibrationAttributes.USAGE_*
     * @return The vibration intensity, one of Vibrator.VIBRATION_INTENSITY_*
     */
    public int getCurrentIntensity(@VibrationAttributes.Usage int usageHint) {
        int defaultIntensity = getDefaultIntensity(usageHint);
        synchronized (mLock) {
            return mCurrentVibrationIntensities.get(usageHint, defaultIntensity);
        }
    }

    /**
     * Return a {@link VibrationEffect} that should be played if the device do not support given
     * {@code effectId}.
     *
     * @param effectId one of VibrationEffect.EFFECT_*
     * @return The effect to be played as a fallback
     */
    public VibrationEffect getFallbackEffect(int effectId) {
        return mFallbackEffects.get(effectId);
    }

    /** Return {@code true} if input devices should vibrate instead of this device. */
    public boolean shouldVibrateInputDevices() {
        return mVibrateInputDevices;
    }

    /**
     * Check if given vibration should be ignored by the service.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored,
     * null otherwise.
     */
    @Nullable
    public Vibration.Status shouldIgnoreVibration(int uid, VibrationAttributes attrs) {
        final int usage = attrs.getUsage();
        synchronized (mLock) {
            if (!mUidObserver.isUidForeground(uid)
                    && !BACKGROUND_PROCESS_USAGE_ALLOWLIST.contains(usage)) {
                return Vibration.Status.IGNORED_BACKGROUND;
            }

            if (mBatterySaverMode && !BATTERY_SAVER_USAGE_ALLOWLIST.contains(usage)) {
                return Vibration.Status.IGNORED_FOR_POWER;
            }

            int intensity = getCurrentIntensity(usage);
            if ((intensity == Vibrator.VIBRATION_INTENSITY_OFF)
                    && !attrs.isFlagSet(
                            VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)) {
                return Vibration.Status.IGNORED_FOR_SETTINGS;
            }

            if (!shouldVibrateForRingerModeLocked(usage)) {
                return Vibration.Status.IGNORED_FOR_RINGER_MODE;
            }
        }
        return null;
    }

    /**
     * Return {@code true} if the device should vibrate for current ringer mode.
     *
     * <p>This checks the current {@link AudioManager#getRingerModeInternal()} against user settings
     * for touch and ringtone usages only. All other usages are allowed by this method.
     */
    @GuardedBy("mLock")
    private boolean shouldVibrateForRingerModeLocked(@VibrationAttributes.Usage int usageHint) {
        // If audio manager was not loaded yet then assume most restrictive mode.
        int ringerMode = (mAudioManager == null)
                ? AudioManager.RINGER_MODE_SILENT
                : mAudioManager.getRingerModeInternal();

        switch (usageHint) {
            case USAGE_TOUCH:
            case USAGE_RINGTONE:
                // Touch feedback and ringtone disabled when phone is on silent mode.
                return ringerMode != AudioManager.RINGER_MODE_SILENT;
            default:
                // All other usages ignore ringer mode settings.
                return true;
        }
    }

    /** Updates all vibration settings and triggers registered listeners. */
    @VisibleForTesting
    void updateSettings() {
        synchronized (mLock) {
            mVibrateInputDevices = loadSystemSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0) > 0;

            int alarmIntensity = toIntensity(
                    loadSystemSetting(Settings.System.ALARM_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_ALARM));
            int defaultHapticFeedbackIntensity = getDefaultIntensity(USAGE_TOUCH);
            int hapticFeedbackIntensity = toIntensity(
                    loadSystemSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, -1),
                    defaultHapticFeedbackIntensity);
            int positiveHapticFeedbackIntensity = toPositiveIntensity(
                    hapticFeedbackIntensity, defaultHapticFeedbackIntensity);
            int hardwareFeedbackIntensity = toIntensity(
                    loadSystemSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, -1),
                    positiveHapticFeedbackIntensity);
            int mediaIntensity = toIntensity(
                    loadSystemSetting(Settings.System.MEDIA_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_MEDIA));
            int defaultNotificationIntensity = getDefaultIntensity(USAGE_NOTIFICATION);
            int notificationIntensity = toIntensity(
                    loadSystemSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, -1),
                    defaultNotificationIntensity);
            int positiveNotificationIntensity = toPositiveIntensity(
                    notificationIntensity, defaultNotificationIntensity);
            int ringIntensity = toIntensity(
                    loadSystemSetting(Settings.System.RING_VIBRATION_INTENSITY, -1),
                    getDefaultIntensity(USAGE_RINGTONE));


            mCurrentVibrationIntensities.clear();
            mCurrentVibrationIntensities.put(USAGE_ALARM, alarmIntensity);
            mCurrentVibrationIntensities.put(USAGE_NOTIFICATION, notificationIntensity);
            mCurrentVibrationIntensities.put(USAGE_MEDIA, mediaIntensity);
            mCurrentVibrationIntensities.put(USAGE_UNKNOWN, mediaIntensity);

            // Communication request is not disabled by the notification setting.
            mCurrentVibrationIntensities.put(USAGE_COMMUNICATION_REQUEST,
                    positiveNotificationIntensity);

            if (!loadBooleanSetting(Settings.System.VIBRATE_WHEN_RINGING)
                    && !loadBooleanSetting(Settings.System.APPLY_RAMPING_RINGER)) {
                // Make sure deprecated boolean setting still disables ringtone vibrations.
                mCurrentVibrationIntensities.put(USAGE_RINGTONE, Vibrator.VIBRATION_INTENSITY_OFF);
            } else {
                mCurrentVibrationIntensities.put(USAGE_RINGTONE, ringIntensity);
            }

            // This should adapt the behavior preceding the introduction of this new setting
            // key, which is to apply HAPTIC_FEEDBACK_INTENSITY, unless it's disabled.
            mCurrentVibrationIntensities.put(USAGE_HARDWARE_FEEDBACK, hardwareFeedbackIntensity);
            mCurrentVibrationIntensities.put(USAGE_PHYSICAL_EMULATION, hardwareFeedbackIntensity);

            if (!loadBooleanSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED)) {
                // Make sure deprecated boolean setting still disables touch vibrations.
                mCurrentVibrationIntensities.put(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_OFF);
            } else {
                mCurrentVibrationIntensities.put(USAGE_TOUCH, hapticFeedbackIntensity);
            }

            // A11y is not disabled by any haptic feedback setting.
            mCurrentVibrationIntensities.put(USAGE_ACCESSIBILITY, positiveHapticFeedbackIntensity);
        }
        notifyListeners();
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            StringBuilder vibrationIntensitiesString = new StringBuilder("{");
            for (int i = 0; i < mCurrentVibrationIntensities.size(); i++) {
                int usage = mCurrentVibrationIntensities.keyAt(i);
                int intensity = mCurrentVibrationIntensities.valueAt(i);
                vibrationIntensitiesString.append(VibrationAttributes.usageToString(usage))
                        .append("=(").append(intensityToString(intensity))
                        .append(",default:").append(intensityToString(getDefaultIntensity(usage)))
                        .append("), ");
            }
            vibrationIntensitiesString.append('}');
            return "VibrationSettings{"
                    + "mVibratorConfig=" + mVibrationConfig
                    + ", mVibrateInputDevices=" + mVibrateInputDevices
                    + ", mBatterySaverMode=" + mBatterySaverMode
                    + ", mProcStatesCache=" + mUidObserver.mProcStatesCache
                    + ", mVibrationIntensities=" + vibrationIntensitiesString
                    + '}';
        }
    }

    /** Write current settings into given {@link ProtoOutputStream}. */
    public void dumpProto(ProtoOutputStream proto) {
        synchronized (mLock) {
            proto.write(VibratorManagerServiceDumpProto.ALARM_INTENSITY,
                    getCurrentIntensity(USAGE_ALARM));
            proto.write(VibratorManagerServiceDumpProto.ALARM_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_ALARM));
            proto.write(VibratorManagerServiceDumpProto.HARDWARE_FEEDBACK_INTENSITY,
                    getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
            proto.write(VibratorManagerServiceDumpProto.HARDWARE_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_HARDWARE_FEEDBACK));
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_INTENSITY,
                    getCurrentIntensity(USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.HAPTIC_FEEDBACK_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_TOUCH));
            proto.write(VibratorManagerServiceDumpProto.MEDIA_INTENSITY,
                    getCurrentIntensity(USAGE_MEDIA));
            proto.write(VibratorManagerServiceDumpProto.MEDIA_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_MEDIA));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_INTENSITY,
                    getCurrentIntensity(USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.NOTIFICATION_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_NOTIFICATION));
            proto.write(VibratorManagerServiceDumpProto.RING_INTENSITY,
                    getCurrentIntensity(USAGE_RINGTONE));
            proto.write(VibratorManagerServiceDumpProto.RING_DEFAULT_INTENSITY,
                    getDefaultIntensity(USAGE_RINGTONE));
        }
    }

    private void notifyListeners() {
        List<OnVibratorSettingsChanged> currentListeners;
        synchronized (mLock) {
            currentListeners = new ArrayList<>(mListeners);
        }
        for (OnVibratorSettingsChanged listener : currentListeners) {
            listener.onChange();
        }
    }

    private static String intensityToString(int intensity) {
        switch (intensity) {
            case Vibrator.VIBRATION_INTENSITY_OFF:
                return "OFF";
            case Vibrator.VIBRATION_INTENSITY_LOW:
                return "LOW";
            case Vibrator.VIBRATION_INTENSITY_MEDIUM:
                return "MEDIUM";
            case Vibrator.VIBRATION_INTENSITY_HIGH:
                return "HIGH";
            default:
                return "UNKNOWN INTENSITY " + intensity;
        }
    }

    @VibrationIntensity
    private int toPositiveIntensity(int value, @VibrationIntensity int defaultValue) {
        if (value == Vibrator.VIBRATION_INTENSITY_OFF) {
            return defaultValue;
        }
        return toIntensity(value, defaultValue);
    }

    @VibrationIntensity
    private int toIntensity(int value, @VibrationIntensity int defaultValue) {
        if ((value < Vibrator.VIBRATION_INTENSITY_OFF)
                || (value > Vibrator.VIBRATION_INTENSITY_HIGH)) {
            return defaultValue;
        }
        return value;
    }

    private boolean loadBooleanSetting(String settingKey) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                settingKey, 0, UserHandle.USER_CURRENT) != 0;
    }

    private int loadSystemSetting(String settingName, int defaultValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, defaultValue, UserHandle.USER_CURRENT);
    }

    private void registerSettingsObserver(Uri settingUri) {
        mContext.getContentResolver().registerContentObserver(
                settingUri, /* notifyForDescendants= */ true, mSettingObserver,
                UserHandle.USER_ALL);
    }

    @Nullable
    private VibrationEffect createEffectFromResource(int resId) {
        long[] timings = getLongIntArray(mContext.getResources(), resId);
        return createEffectFromTimings(timings);
    }

    @Nullable
    private static VibrationEffect createEffectFromTimings(@Nullable long[] timings) {
        if (timings == null || timings.length == 0) {
            return null;
        } else if (timings.length == 1) {
            return VibrationEffect.createOneShot(timings[0], VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            return VibrationEffect.createWaveform(timings, -1);
        }
    }

    private static long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i = 0; i < ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    /** Implementation of {@link ContentObserver} to be registered to a setting {@link Uri}. */
    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /** Implementation of {@link BroadcastReceiver} to update settings on current user change. */
    @VisibleForTesting
    final class UserObserver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                updateSettings();
            }
        }
    }

    /** Implementation of {@link ContentObserver} to be registered to a setting {@link Uri}. */
    @VisibleForTesting
    final class UidObserver extends IUidObserver.Stub {
        private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();

        public boolean isUidForeground(int uid) {
            return mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                    <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mProcStatesCache.delete(uid);
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mProcStatesCache.put(uid, procState);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }
    }
}
