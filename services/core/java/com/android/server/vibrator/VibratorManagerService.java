/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.CombinedVibrationEffect;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorManagerService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";
    private static final boolean DEBUG = false;
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();

    /** Lifecycle responsible for initializing this class at the right system server phases. */
    public static class Lifecycle extends SystemService {
        private VibratorManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new VibratorManagerService(getContext(), new Injector());
            publishBinderService(Context.VIBRATOR_MANAGER_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
                mService.systemReady();
            }
        }
    }

    // Used to generate globally unique vibration ids.
    private final AtomicInteger mNextVibrationId = new AtomicInteger(1); // 0 = no callback

    private final Object mLock = new Object();
    private final Context mContext;
    private final String mSystemUiPackage;
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final Handler mHandler;
    private final AppOpsManager mAppOps;
    private final NativeWrapper mNativeWrapper;
    private final VibratorManagerRecords mVibratorManagerRecords;
    private final long mCapabilities;
    private final int[] mVibratorIds;
    private final SparseArray<VibratorController> mVibrators;
    private final VibrationCallbacks mVibrationCallbacks = new VibrationCallbacks();
    @GuardedBy("mLock")
    private final SparseArray<AlwaysOnVibration> mAlwaysOnEffects = new SparseArray<>();
    @GuardedBy("mLock")
    private VibrationThread mCurrentVibration;
    @GuardedBy("mLock")
    private VibrationThread mNextVibration;
    @GuardedBy("mLock")
    private ExternalVibrationHolder mCurrentExternalVibration;

    private final VibrationSettings mVibrationSettings;
    private final VibrationScaler mVibrationScaler;
    private final InputDeviceDelegate mInputDeviceDelegate;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mLock) {
                    // When the system is entering a non-interactive state, we want
                    // to cancel vibrations in case a misbehaving app has abandoned
                    // them.  However it may happen that the system is currently playing
                    // haptic feedback as part of the transition.  So we don't cancel
                    // system vibrations.
                    if (mCurrentVibration != null
                            && !isSystemHapticFeedback(mCurrentVibration.getVibration())) {
                        mNextVibration = null;
                        mCurrentVibration.cancel();
                    }
                }
            }
        }
    };

    static native long nativeInit(OnSyncedVibrationCompleteListener listener);

    static native long nativeGetFinalizer();

    static native long nativeGetCapabilities(long nativeServicePtr);

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    static native boolean nativePrepareSynced(long nativeServicePtr, int[] vibratorIds);

    static native boolean nativeTriggerSynced(long nativeServicePtr, long vibrationId);

    static native void nativeCancelSynced(long nativeServicePtr);

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mHandler = injector.createHandler(Looper.myLooper());

        mVibrationSettings = new VibrationSettings(mContext, mHandler);
        mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);
        mInputDeviceDelegate = new InputDeviceDelegate(mContext, mHandler);

        VibrationCompleteListener listener = new VibrationCompleteListener(this);
        mNativeWrapper = injector.getNativeWrapper();
        mNativeWrapper.init(listener);

        int dumpLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpLimit);
        mVibratorManagerRecords = new VibratorManagerRecords(dumpLimit);

        mSystemUiPackage = LocalServices.getService(PackageManagerInternal.class)
                .getSystemUiServiceComponent().getPackageName();

        mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        mAppOps = mContext.getSystemService(AppOpsManager.class);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);

        mCapabilities = mNativeWrapper.getCapabilities();
        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        if (vibratorIds == null) {
            mVibratorIds = new int[0];
            mVibrators = new SparseArray<>(0);
        } else {
            // Keep original vibrator id order, which might be meaningful.
            mVibratorIds = vibratorIds;
            mVibrators = new SparseArray<>(mVibratorIds.length);
            for (int vibratorId : vibratorIds) {
                mVibrators.put(vibratorId, injector.createVibratorController(vibratorId, listener));
            }
        }

        // Reset the hardware to a default state, in case this is a runtime restart instead of a
        // fresh boot.
        mNativeWrapper.cancelSynced();
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).off();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
    }

    /** Finish initialization at boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}. */
    @VisibleForTesting
    void systemReady() {
        Slog.v(TAG, "Initializing VibratorManager service...");
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "systemReady");
        try {
            mVibrationSettings.onSystemReady();
            mInputDeviceDelegate.onSystemReady();

            mVibrationSettings.addListener(this::updateServiceState);

            // Will update settings and input devices.
            updateServiceState();
        } finally {
            Slog.v(TAG, "VibratorManager service initialized");
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    @Nullable
    public VibratorInfo getVibratorInfo(int vibratorId) {
        VibratorController controller = mVibrators.get(vibratorId);
        return controller == null ? null : controller.getVibratorInfo();
    }

    @Override // Binder call
    public boolean isVibrating(int vibratorId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "isVibrating");
        VibratorController controller = mVibrators.get(vibratorId);
        return controller != null && controller.isVibrating();
    }

    @Override // Binder call
    public boolean registerVibratorStateListener(int vibratorId, IVibratorStateListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "registerVibratorStateListener");
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.registerVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean unregisterVibratorStateListener(int vibratorId,
            IVibratorStateListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "unregisterVibratorStateListener");
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.unregisterVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibrationEffect effect, @Nullable VibrationAttributes attrs) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "setAlwaysOnEffect");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE_ALWAYS_ON,
                    "setAlwaysOnEffect");

            if (effect == null) {
                synchronized (mLock) {
                    mAlwaysOnEffects.delete(alwaysOnId);
                    onAllVibratorsLocked(v -> {
                        if (v.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                            v.updateAlwaysOn(alwaysOnId, /* effect= */ null);
                        }
                    });
                }
                return true;
            }
            if (!isEffectValid(effect)) {
                return false;
            }
            attrs = fixupVibrationAttributes(attrs);
            synchronized (mLock) {
                SparseArray<VibrationEffect.Prebaked> effects = fixupAlwaysOnEffectsLocked(effect);
                if (effects == null) {
                    // Invalid effects set in CombinedVibrationEffect, or always-on capability is
                    // missing on individual vibrators.
                    return false;
                }
                AlwaysOnVibration alwaysOnVibration = new AlwaysOnVibration(
                        alwaysOnId, uid, opPkg, attrs, effects);
                mAlwaysOnEffects.put(alwaysOnId, alwaysOnVibration);
                updateAlwaysOnLocked(alwaysOnVibration);
            }
            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void vibrate(int uid, String opPkg, @NonNull CombinedVibrationEffect effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate, reason = " + reason);
        try {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.VIBRATE, "vibrate");

            if (token == null) {
                Slog.e(TAG, "token must not be null");
                return;
            }
            enforceUpdateAppOpsStatsPermission(uid);
            if (!isEffectValid(effect)) {
                return;
            }
            effect = fixupVibrationEffect(effect);
            attrs = fixupVibrationAttributes(attrs);
            Vibration vib = new Vibration(token, mNextVibrationId.getAndIncrement(), effect, attrs,
                    uid, opPkg, reason);

            synchronized (mLock) {
                Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(vib);
                if (ignoreStatus != null) {
                    endVibrationLocked(vib, ignoreStatus);
                    return;
                }

                ignoreStatus = shouldIgnoreVibrationForCurrentLocked(vib);
                if (ignoreStatus != null) {
                    endVibrationLocked(vib, ignoreStatus);
                    return;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mCurrentVibration != null) {
                        mCurrentVibration.cancel();
                    }
                    Vibration.Status status = startVibrationLocked(vib);
                    if (status != Vibration.Status.RUNNING) {
                        endVibrationLocked(vib, status);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void cancelVibrate(IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "cancelVibrate");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE,
                    "cancelVibrate");

            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration.");
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mNextVibration = null;
                    if (mCurrentVibration != null
                            && mCurrentVibration.getVibration().token == token) {
                        mCurrentVibration.cancel();
                    }
                    if (mCurrentExternalVibration != null) {
                        mCurrentExternalVibration.end(Vibration.Status.CANCELLED);
                        mVibratorManagerRecords.record(mCurrentExternalVibration);
                        mCurrentExternalVibration.externalVibration.mute();
                        mCurrentExternalVibration = null;
                        setExternalControl(false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        final long ident = Binder.clearCallingIdentity();

        boolean isDumpProto = false;
        for (String arg : args) {
            if (arg.equals("--proto")) {
                isDumpProto = true;
            }
        }
        try {
            if (isDumpProto) {
                mVibratorManagerRecords.dumpProto(fd);
            } else {
                mVibratorManagerRecords.dumpText(pw);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(this).exec(this, in, out, err, args, cb, resultReceiver);
    }

    @VisibleForTesting
    void updateServiceState() {
        synchronized (mLock) {
            boolean inputDevicesChanged = mInputDeviceDelegate.updateInputDeviceVibrators(
                    mVibrationSettings.shouldVibrateInputDevices());

            for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
                updateAlwaysOnLocked(mAlwaysOnEffects.valueAt(i));
            }

            if (mCurrentVibration == null) {
                return;
            }

            if (inputDevicesChanged || !mVibrationSettings.shouldVibrateForPowerMode(
                    mCurrentVibration.getVibration().attrs.getUsage())) {
                mCurrentVibration.cancel();
            }
        }
    }

    private void setExternalControl(boolean externalControl) {
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).setExternalControl(externalControl);
        }
    }

    @GuardedBy("mLock")
    private void updateAlwaysOnLocked(AlwaysOnVibration vib) {
        for (int i = 0; i < vib.effects.size(); i++) {
            VibratorController vibrator = mVibrators.get(vib.effects.keyAt(i));
            VibrationEffect.Prebaked effect = vib.effects.valueAt(i);
            if (vibrator == null) {
                continue;
            }
            Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(
                    vib.uid, vib.opPkg, vib.attrs);
            if (ignoreStatus == null) {
                effect = mVibrationScaler.scale(effect, vib.attrs.getUsage());
            } else {
                // Vibration should not run, use null effect to remove registered effect.
                effect = null;
            }
            vibrator.updateAlwaysOn(vib.alwaysOnId, effect);
        }
    }

    @GuardedBy("mLock")
    private Vibration.Status startVibrationLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            vib.updateEffect(mVibrationScaler.scale(vib.getEffect(), vib.attrs.getUsage()));
            boolean inputDevicesAvailable = mInputDeviceDelegate.vibrateIfAvailable(
                    vib.uid, vib.opPkg, vib.getEffect(), vib.reason, vib.attrs);
            if (inputDevicesAvailable) {
                return Vibration.Status.FORWARDED_TO_INPUT_DEVICES;
            }

            VibrationThread vibThread = new VibrationThread(vib, mVibrators, mWakeLock,
                    mBatteryStatsService, mVibrationCallbacks);

            if (mCurrentVibration == null) {
                return startVibrationThreadLocked(vibThread);
            }

            mNextVibration = vibThread;
            return Vibration.Status.RUNNING;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private Vibration.Status startVibrationThreadLocked(VibrationThread vibThread) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationThreadLocked");
        try {
            Vibration vib = vibThread.getVibration();
            int mode = startAppOpModeLocked(vib.uid, vib.opPkg, vib.attrs);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                    Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                    mCurrentVibration = vibThread;
                    mCurrentVibration.start();
                    return Vibration.Status.RUNNING;
                case AppOpsManager.MODE_ERRORED:
                    Slog.w(TAG, "Start AppOpsManager operation errored for uid " + vib.uid);
                    return Vibration.Status.IGNORED_ERROR_APP_OPS;
                default:
                    return Vibration.Status.IGNORED_APP_OPS;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void endVibrationLocked(Vibration vib, Vibration.Status status) {
        vib.end(status);
        mVibratorManagerRecords.record(vib);
    }

    @GuardedBy("mLock")
    private void endVibrationLocked(ExternalVibrationHolder vib, Vibration.Status status) {
        vib.end(status);
        mVibratorManagerRecords.record(vib);
    }

    @GuardedBy("mLock")
    private void reportFinishedVibrationLocked(Vibration.Status status) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        try {
            Vibration vib = mCurrentVibration.getVibration();
            mCurrentVibration = null;
            endVibrationLocked(vib, status);
            finishAppOpModeLocked(vib.uid, vib.opPkg);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void onSyncedVibrationComplete(long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Synced vibration " + vibrationId + " complete, notifying thread");
                }
                mCurrentVibration.syncedVibrationComplete();
            }
        }
    }

    private void onVibrationComplete(int vibratorId, long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration " + vibrationId + " on vibrator " + vibratorId
                            + " complete, notifying thread");
                }
                mCurrentVibration.vibratorComplete(vibratorId);
            }
        }
    }

    /**
     * Check if given vibration should be ignored in favour of one of the vibrations currently
     * running on the same vibrators.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationForCurrentLocked(Vibration vibration) {
        if (vibration.isRepeating()) {
            // Repeating vibrations always take precedence.
            return null;
        }
        if (mCurrentVibration != null && mCurrentVibration.getVibration().isRepeating()) {
            if (DEBUG) {
                Slog.d(TAG, "Ignoring incoming vibration in favor of previous alarm vibration");
            }
            return Vibration.Status.IGNORED_FOR_ALARM;
        }
        return null;
    }

    /**
     * Check if given vibration should be ignored by this service.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored.
     * @see #shouldIgnoreVibrationLocked(int, String, VibrationAttributes)
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationLocked(Vibration vib) {
        // If something has external control of the vibrator, assume that it's more important.
        if (mCurrentExternalVibration != null) {
            if (DEBUG) {
                Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
            }
            return Vibration.Status.IGNORED_FOR_EXTERNAL;
        }

        if (!mVibrationSettings.shouldVibrateForUid(vib.uid, vib.attrs.getUsage())) {
            Slog.e(TAG, "Ignoring incoming vibration as process with"
                    + " uid= " + vib.uid + " is background,"
                    + " attrs= " + vib.attrs);
            return Vibration.Status.IGNORED_BACKGROUND;
        }

        return shouldIgnoreVibrationLocked(vib.uid, vib.opPkg, vib.attrs);
    }

    /**
     * Check if a vibration with given {@code uid}, {@code opPkg} and {@code attrs} should be
     * ignored by this service.
     *
     * @param uid   The user id of this vibration
     * @param opPkg The package name of this vibration
     * @param attrs The attributes of this vibration
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationLocked(int uid, String opPkg,
            VibrationAttributes attrs) {
        if (!mVibrationSettings.shouldVibrateForPowerMode(attrs.getUsage())) {
            return Vibration.Status.IGNORED_FOR_POWER;
        }

        int intensity = mVibrationSettings.getCurrentIntensity(attrs.getUsage());
        if (intensity == Vibrator.VIBRATION_INTENSITY_OFF) {
            return Vibration.Status.IGNORED_FOR_SETTINGS;
        }

        if (!mVibrationSettings.shouldVibrateForRingerMode(attrs.getUsage())) {
            if (DEBUG) {
                Slog.e(TAG, "Vibrate ignored, not vibrating for ringtones");
            }
            return Vibration.Status.IGNORED_RINGTONE;
        }

        int mode = checkAppOpModeLocked(uid, opPkg, attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                Slog.w(TAG, "Would be an error: vibrate from uid " + uid);
                return Vibration.Status.IGNORED_ERROR_APP_OPS;
            } else {
                return Vibration.Status.IGNORED_APP_OPS;
            }
        }

        return null;
    }

    /**
     * Check which mode should be set for a vibration with given {@code uid}, {@code opPkg} and
     * {@code attrs}. This will return one of the AppOpsManager.MODE_*.
     */
    @GuardedBy("mLock")
    private int checkAppOpModeLocked(int uid, String opPkg, VibrationAttributes attrs) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                attrs.getAudioUsage(), uid, opPkg);
        int fixedMode = fixupAppOpModeLocked(mode, attrs);
        if (mode != fixedMode && fixedMode == AppOpsManager.MODE_ALLOWED) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + uid);
        }
        return fixedMode;
    }

    /** Start an operation in {@link AppOpsManager}, if allowed. */
    @GuardedBy("mLock")
    private int startAppOpModeLocked(int uid, String opPkg, VibrationAttributes attrs) {
        return fixupAppOpModeLocked(
                mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, uid, opPkg), attrs);
    }

    /**
     * Finish a previously started operation in {@link AppOpsManager}. This will be a noop if no
     * operation with same uid was previously started.
     */
    @GuardedBy("mLock")
    private void finishAppOpModeLocked(int uid, String opPkg) {
        mAppOps.finishOp(AppOpsManager.OP_VIBRATE, uid, opPkg);
    }

    /**
     * Enforces {@link android.Manifest.permission#UPDATE_APP_OPS_STATS} to incoming UID if it's
     * different from the calling UID.
     */
    private void enforceUpdateAppOpsStatsPermission(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    /**
     * Validate the incoming {@link CombinedVibrationEffect}.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the CombinedVibrationEffect is non-null and valid
     */
    private static boolean isEffectValid(@Nullable CombinedVibrationEffect effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying CombinedVibrationEffect.", e);
            return false;
        }
        return true;
    }

    /**
     * Sets fallback effects to all prebaked ones in given combination of effects, based on {@link
     * VibrationSettings#getFallbackEffect}.
     */
    private CombinedVibrationEffect fixupVibrationEffect(CombinedVibrationEffect effect) {
        if (effect instanceof CombinedVibrationEffect.Mono) {
            return CombinedVibrationEffect.createSynced(
                    fixupVibrationEffect(((CombinedVibrationEffect.Mono) effect).getEffect()));
        } else if (effect instanceof CombinedVibrationEffect.Stereo) {
            CombinedVibrationEffect.SyncedCombination combination =
                    CombinedVibrationEffect.startSynced();
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibrationEffect.Stereo) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                combination.addVibrator(effects.keyAt(i), fixupVibrationEffect(effects.valueAt(i)));
            }
            return combination.combine();
        } else if (effect instanceof CombinedVibrationEffect.Sequential) {
            CombinedVibrationEffect.SequentialCombination combination =
                    CombinedVibrationEffect.startSequential();
            List<CombinedVibrationEffect> effects =
                    ((CombinedVibrationEffect.Sequential) effect).getEffects();
            for (CombinedVibrationEffect e : effects) {
                combination.addNext(fixupVibrationEffect(e));
            }
            return combination.combine();
        }
        return effect;
    }

    private VibrationEffect fixupVibrationEffect(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Prebaked
                && ((VibrationEffect.Prebaked) effect).shouldFallback()) {
            VibrationEffect.Prebaked prebaked = (VibrationEffect.Prebaked) effect;
            VibrationEffect fallback = mVibrationSettings.getFallbackEffect(prebaked.getId());
            if (fallback != null) {
                return new VibrationEffect.Prebaked(prebaked.getId(), prebaked.getEffectStrength(),
                        fallback);
            }
        }
        return effect;
    }

    /**
     * Return new {@link VibrationAttributes} that only applies flags that this user has permissions
     * to use.
     */
    private VibrationAttributes fixupVibrationAttributes(@Nullable VibrationAttributes attrs) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        if (attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            if (!(hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    || hasPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                    || hasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
                final int flags = attrs.getFlags()
                        & ~VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY;
                attrs = new VibrationAttributes.Builder(attrs)
                        .setFlags(flags, attrs.getFlags()).build();
            }
        }
        return attrs;
    }

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<VibrationEffect.Prebaked> fixupAlwaysOnEffectsLocked(
            CombinedVibrationEffect effect) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "fixupAlwaysOnEffectsLocked");
        try {
            SparseArray<VibrationEffect> effects;
            if (effect instanceof CombinedVibrationEffect.Mono) {
                VibrationEffect syncedEffect = ((CombinedVibrationEffect.Mono) effect).getEffect();
                effects = transformAllVibratorsLocked(unused -> syncedEffect);
            } else if (effect instanceof CombinedVibrationEffect.Stereo) {
                effects = ((CombinedVibrationEffect.Stereo) effect).getEffects();
            } else {
                // Only synced combinations can be used for always-on effects.
                return null;
            }
            SparseArray<VibrationEffect.Prebaked> result = new SparseArray<>();
            for (int i = 0; i < effects.size(); i++) {
                VibrationEffect prebaked = effects.valueAt(i);
                if (!(prebaked instanceof VibrationEffect.Prebaked)) {
                    Slog.e(TAG, "Only prebaked effects supported for always-on.");
                    return null;
                }
                int vibratorId = effects.keyAt(i);
                VibratorController vibrator = mVibrators.get(vibratorId);
                if (vibrator != null && vibrator.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    result.put(vibratorId, (VibrationEffect.Prebaked) prebaked);
                }
            }
            if (result.size() == 0) {
                return null;
            }
            return result;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /**
     * Check given mode, one of the AppOpsManager.MODE_*, against {@link VibrationAttributes} to
     * allow bypassing {@link AppOpsManager} checks.
     */
    @GuardedBy("mLock")
    private int fixupAppOpModeLocked(int mode, VibrationAttributes attrs) {
        if (mode == AppOpsManager.MODE_IGNORED
                && attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            return AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemHapticFeedback(Vibration vib) {
        if (vib.attrs.getUsage() != VibrationAttributes.USAGE_TOUCH) {
            return false;
        }
        return vib.uid == Process.SYSTEM_UID || vib.uid == 0 || mSystemUiPackage.equals(vib.opPkg);
    }

    @GuardedBy("mLock")
    private void onAllVibratorsLocked(Consumer<VibratorController> consumer) {
        for (int i = 0; i < mVibrators.size(); i++) {
            consumer.accept(mVibrators.valueAt(i));
        }
    }

    @GuardedBy("mLock")
    private <T> SparseArray<T> transformAllVibratorsLocked(Function<VibratorController, T> fn) {
        SparseArray<T> ret = new SparseArray<>(mVibrators.size());
        for (int i = 0; i < mVibrators.size(); i++) {
            ret.put(mVibrators.keyAt(i), fn.apply(mVibrators.valueAt(i)));
        }
        return ret;
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        NativeWrapper getNativeWrapper() {
            return new NativeWrapper();
        }

        Handler createHandler(Looper looper) {
            return new Handler(looper);
        }

        VibratorController createVibratorController(int vibratorId,
                VibratorController.OnVibrationCompleteListener listener) {
            return new VibratorController(vibratorId, listener);
        }

        void addService(String name, IBinder service) {
            ServiceManager.addService(name, service);
        }
    }

    /**
     * Implementation of {@link VibrationThread.VibrationCallbacks} that controls synced vibrations
     * and reports them when finished.
     */
    private final class VibrationCallbacks implements VibrationThread.VibrationCallbacks {

        @Override
        public boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds) {
            if ((mCapabilities & requiredCapabilities) != requiredCapabilities) {
                // This sync step requires capabilities this device doesn't have, skipping sync...
                return false;
            }
            return mNativeWrapper.prepareSynced(vibratorIds);
        }

        @Override
        public boolean triggerSyncedVibration(long vibrationId) {
            return mNativeWrapper.triggerSynced(vibrationId);
        }

        @Override
        public void cancelSyncedVibration() {
            mNativeWrapper.cancelSynced();
        }

        @Override
        public void onVibrationEnded(long vibrationId, Vibration.Status status) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration " + vibrationId + " thread finished with status " + status);
            }
            synchronized (mLock) {
                if (mCurrentVibration != null
                        && mCurrentVibration.getVibration().id == vibrationId) {
                    reportFinishedVibrationLocked(status);

                    if (mNextVibration != null) {
                        VibrationThread vibThread = mNextVibration;
                        mNextVibration = null;
                        startVibrationThreadLocked(vibThread);
                    }
                }
            }
        }
    }

    /** Listener for synced vibration completion callbacks from native. */
    @VisibleForTesting
    interface OnSyncedVibrationCompleteListener {

        /** Callback triggered when synced vibration is complete. */
        void onComplete(long vibrationId);
    }

    /**
     * Implementation of listeners to native vibrators with a weak reference to this service.
     */
    private static final class VibrationCompleteListener implements
            VibratorController.OnVibrationCompleteListener, OnSyncedVibrationCompleteListener {
        private WeakReference<VibratorManagerService> mServiceRef;

        VibrationCompleteListener(VibratorManagerService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onComplete(long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onSyncedVibrationComplete(vibrationId);
            }
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationComplete(vibratorId, vibrationId);
            }
        }
    }

    /**
     * Combination of prekabed vibrations on multiple vibrators, with the same {@link
     * VibrationAttributes}, that can be set for always-on effects.
     */
    private static final class AlwaysOnVibration {
        public final int alwaysOnId;
        public final int uid;
        public final String opPkg;
        public final VibrationAttributes attrs;
        public final SparseArray<VibrationEffect.Prebaked> effects;

        AlwaysOnVibration(int alwaysOnId, int uid, String opPkg, VibrationAttributes attrs,
                SparseArray<VibrationEffect.Prebaked> effects) {
            this.alwaysOnId = alwaysOnId;
            this.uid = uid;
            this.opPkg = opPkg;
            this.attrs = attrs;
            this.effects = effects;
        }
    }

    /** Holder for a {@link ExternalVibration}. */
    private final class ExternalVibrationHolder {

        public final ExternalVibration externalVibration;
        public int scale;

        private final long mStartTimeDebug;
        private long mEndTimeDebug;
        private Vibration.Status mStatus;

        private ExternalVibrationHolder(ExternalVibration externalVibration) {
            this.externalVibration = externalVibration;
            this.scale = IExternalVibratorService.SCALE_NONE;
            mStartTimeDebug = System.currentTimeMillis();
            mStatus = Vibration.Status.RUNNING;
        }

        public void end(Vibration.Status status) {
            if (mStatus != Vibration.Status.RUNNING) {
                // Vibration already ended, keep first ending status set and ignore this one.
                return;
            }
            mStatus = status;
            mEndTimeDebug = System.currentTimeMillis();
        }

        public Vibration.DebugInfo getDebugInfo() {
            return new Vibration.DebugInfo(
                    mStartTimeDebug, mEndTimeDebug, /* effect= */ null, /* originalEffect= */ null,
                    scale, externalVibration.getVibrationAttributes(),
                    externalVibration.getUid(), externalVibration.getPackage(),
                    /* reason= */ null, mStatus);
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init(OnSyncedVibrationCompleteListener listener) {
            mNativeServicePtr = nativeInit(listener);
            long finalizerPtr = nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns manager capabilities. */
        public long getCapabilities() {
            return nativeGetCapabilities(mNativeServicePtr);
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return nativeGetVibratorIds(mNativeServicePtr);
        }

        /** Prepare vibrators for triggering vibrations in sync. */
        public boolean prepareSynced(@NonNull int[] vibratorIds) {
            return nativePrepareSynced(mNativeServicePtr, vibratorIds);
        }

        /** Trigger prepared synced vibration. */
        public boolean triggerSynced(long vibrationId) {
            return nativeTriggerSynced(mNativeServicePtr, vibrationId);
        }

        /** Cancel prepared synced vibration. */
        public void cancelSynced() {
            nativeCancelSynced(mNativeServicePtr);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private final class VibratorManagerRecords {
        @GuardedBy("mLock")
        private final SparseArray<LinkedList<Vibration.DebugInfo>> mPreviousVibrations =
                new SparseArray<>();
        @GuardedBy("mLock")
        private final LinkedList<Vibration.DebugInfo> mPreviousExternalVibrations =
                new LinkedList<>();
        private final int mPreviousVibrationsLimit;

        private VibratorManagerRecords(int limit) {
            mPreviousVibrationsLimit = limit;
        }

        @GuardedBy("mLock")
        void record(Vibration vib) {
            int usage = vib.attrs.getUsage();
            if (!mPreviousVibrations.contains(usage)) {
                mPreviousVibrations.put(usage, new LinkedList<>());
            }
            record(mPreviousVibrations.get(usage), vib.getDebugInfo());
        }

        @GuardedBy("mLock")
        void record(ExternalVibrationHolder vib) {
            record(mPreviousExternalVibrations, vib.getDebugInfo());
        }

        @GuardedBy("mLock")
        void record(LinkedList<Vibration.DebugInfo> records, Vibration.DebugInfo info) {
            if (records.size() > mPreviousVibrationsLimit) {
                records.removeFirst();
            }
            records.addLast(info);
        }

        void dumpText(PrintWriter pw) {
            pw.println("Vibrator Manager Service:");
            synchronized (mLock) {
                pw.println("  mVibratorControllers:");
                for (int i = 0; i < mVibrators.size(); i++) {
                    pw.println("    " + mVibrators.valueAt(i));
                }
                pw.println();
                pw.println("  mCurrentVibration:");
                pw.println("    " + (mCurrentVibration == null
                        ? null : mCurrentVibration.getVibration().getDebugInfo()));
                pw.println("  mNextVibration:");
                pw.println("    " + (mNextVibration == null
                        ? null : mNextVibration.getVibration().getDebugInfo()));
                pw.println("  mCurrentExternalVibration:");
                pw.println("    " + (mCurrentExternalVibration == null
                        ? null : mCurrentExternalVibration.getDebugInfo()));
                pw.println();
                pw.println("  mVibrationSettings=" + mVibrationSettings);
                for (int i = 0; i < mPreviousVibrations.size(); i++) {
                    pw.println();
                    pw.print("  Previous vibrations for usage ");
                    pw.print(VibrationAttributes.usageToString(mPreviousVibrations.keyAt(i)));
                    pw.println(":");
                    for (Vibration.DebugInfo info : mPreviousVibrations.valueAt(i)) {
                        pw.println("    " + info);
                    }
                }

                pw.println();
                pw.println("  Previous external vibrations:");
                for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                    pw.println("    " + info);
                }
            }
        }

        synchronized void dumpProto(FileDescriptor fd) {
            final ProtoOutputStream proto = new ProtoOutputStream(fd);

            synchronized (mLock) {
                mVibrationSettings.dumpProto(proto);
                if (mCurrentVibration != null) {
                    mCurrentVibration.getVibration().getDebugInfo().dumpProto(proto,
                            VibratorManagerServiceDumpProto.CURRENT_VIBRATION);
                }
                if (mCurrentExternalVibration != null) {
                    mCurrentExternalVibration.getDebugInfo().dumpProto(proto,
                            VibratorManagerServiceDumpProto.CURRENT_EXTERNAL_VIBRATION);
                }

                boolean isVibrating = false;
                boolean isUnderExternalControl = false;
                for (int i = 0; i < mVibrators.size(); i++) {
                    proto.write(VibratorManagerServiceDumpProto.VIBRATOR_IDS, mVibrators.keyAt(i));
                    isVibrating |= mVibrators.valueAt(i).isVibrating();
                    isUnderExternalControl |= mVibrators.valueAt(i).isUnderExternalControl();
                }
                proto.write(VibratorManagerServiceDumpProto.IS_VIBRATING, isVibrating);
                proto.write(VibratorManagerServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                        isUnderExternalControl);

                for (int i = 0; i < mPreviousVibrations.size(); i++) {
                    long fieldId;
                    switch (mPreviousVibrations.keyAt(i)) {
                        case VibrationAttributes.USAGE_RINGTONE:
                            fieldId = VibratorManagerServiceDumpProto.PREVIOUS_RING_VIBRATIONS;
                            break;
                        case VibrationAttributes.USAGE_NOTIFICATION:
                            fieldId = VibratorManagerServiceDumpProto
                                    .PREVIOUS_NOTIFICATION_VIBRATIONS;
                            break;
                        case VibrationAttributes.USAGE_ALARM:
                            fieldId = VibratorManagerServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS;
                            break;
                        default:
                            fieldId = VibratorManagerServiceDumpProto.PREVIOUS_VIBRATIONS;
                    }
                    for (Vibration.DebugInfo info : mPreviousVibrations.valueAt(i)) {
                        info.dumpProto(proto, fieldId);
                    }
                }

                for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                    info.dumpProto(proto,
                            VibratorManagerServiceDumpProto.PREVIOUS_EXTERNAL_VIBRATIONS);
                }
            }
            proto.flush();
        }
    }

    /** Implementation of {@link IExternalVibratorService} to be triggered on external control. */
    private final class ExternalVibratorService extends IExternalVibratorService.Stub {
        ExternalVibrationDeathRecipient mCurrentExternalDeathRecipient;

        @Override
        public int onExternalVibrationStart(ExternalVibration vib) {
            if (!hasExternalControlCapability()) {
                return IExternalVibratorService.SCALE_MUTE;
            }
            if (ActivityManager.checkComponentPermission(android.Manifest.permission.VIBRATE,
                    vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                        + " tried to play externally controlled vibration"
                        + " without VIBRATE permission, ignoring.");
                return IExternalVibratorService.SCALE_MUTE;
            }

            int mode = checkAppOpModeLocked(vib.getUid(), vib.getPackage(),
                    vib.getVibrationAttributes());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                ExternalVibrationHolder vibHolder = new ExternalVibrationHolder(vib);
                vibHolder.scale = IExternalVibratorService.SCALE_MUTE;
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Slog.w(TAG, "Would be an error: external vibrate from uid " + vib.getUid());
                    endVibrationLocked(vibHolder, Vibration.Status.IGNORED_ERROR_APP_OPS);
                } else {
                    endVibrationLocked(vibHolder, Vibration.Status.IGNORED_APP_OPS);
                }
                return vibHolder.scale;
            }

            VibrationThread cancelingVibration = null;
            int scale;
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    // We are already playing this external vibration, so we can return the same
                    // scale calculated in the previous call to this method.
                    return mCurrentExternalVibration.scale;
                }
                if (mCurrentExternalVibration == null) {
                    // If we're not under external control right now, then cancel any normal
                    // vibration that may be playing and ready the vibrator for external control.
                    if (mCurrentVibration != null) {
                        mNextVibration = null;
                        mCurrentVibration.cancel();
                        cancelingVibration = mCurrentVibration;
                    }
                } else {
                    endVibrationLocked(mCurrentExternalVibration, Vibration.Status.CANCELLED);
                }
                // At this point we either have an externally controlled vibration playing, or
                // no vibration playing. Since the interface defines that only one externally
                // controlled vibration can play at a time, by returning something other than
                // SCALE_MUTE from this function we can be assured that if we are currently
                // playing vibration, it will be muted in favor of the new vibration.
                //
                // Note that this doesn't support multiple concurrent external controls, as we
                // would need to mute the old one still if it came from a different controller.
                mCurrentExternalVibration = new ExternalVibrationHolder(vib);
                mCurrentExternalDeathRecipient = new ExternalVibrationDeathRecipient();
                vib.linkToDeath(mCurrentExternalDeathRecipient);
                mCurrentExternalVibration.scale = mVibrationScaler.getExternalVibrationScale(
                        vib.getVibrationAttributes().getUsage());
                scale = mCurrentExternalVibration.scale;
            }

            if (cancelingVibration != null) {
                try {
                    cancelingVibration.join();
                } catch (InterruptedException e) {
                    Slog.w("Interrupted while waiting for vibration to finish before starting "
                            + "external control", e);
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Vibrator going under external control.");
            }
            setExternalControl(true);
            if (DEBUG) {
                Slog.e(TAG, "Playing external vibration: " + vib);
            }
            return scale;
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    if (DEBUG) {
                        Slog.e(TAG, "Stopping external vibration" + vib);
                    }
                    stopExternalVibrateLocked(Vibration.Status.FINISHED);
                }
            }
        }

        private void stopExternalVibrateLocked(Vibration.Status status) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "stopExternalVibrateLocked");
            try {
                if (mCurrentExternalVibration == null) {
                    return;
                }
                endVibrationLocked(mCurrentExternalVibration, status);
                mCurrentExternalVibration.externalVibration.unlinkToDeath(
                        mCurrentExternalDeathRecipient);
                mCurrentExternalDeathRecipient = null;
                mCurrentExternalVibration = null;
                setExternalControl(false);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private boolean hasExternalControlCapability() {
            for (int i = 0; i < mVibrators.size(); i++) {
                if (mVibrators.valueAt(i).hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    return true;
                }
            }
            return false;
        }

        private class ExternalVibrationDeathRecipient implements IBinder.DeathRecipient {
            public void binderDied() {
                synchronized (mLock) {
                    if (mCurrentExternalVibration != null) {
                        if (DEBUG) {
                            Slog.d(TAG, "External vibration finished because binder died");
                        }
                        stopExternalVibrateLocked(Vibration.Status.CANCELLED);
                    }
                }
            }
        }
    }

    /** Provide limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {
        public static final String SHELL_PACKAGE_NAME = "com.android.shell";

        private final class CommonOptions {
            public boolean force = false;
            public String description = "Shell command";

            CommonOptions() {
                String nextArg;
                while ((nextArg = peekNextArg()) != null) {
                    switch (nextArg) {
                        case "-f":
                            getNextArgRequired(); // consume the -f argument;
                            force = true;
                            break;
                        case "-d":
                            getNextArgRequired(); // consume the -d argument;
                            description = getNextArgRequired();
                            break;
                        default:
                            // Not a common option, finish reading.
                            return;
                    }
                }
            }
        }

        private final IBinder mToken;

        private VibratorManagerShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "onCommand " + cmd);
            try {
                if ("list".equals(cmd)) {
                    return runListVibrators();
                }
                if ("synced".equals(cmd)) {
                    return runMono();
                }
                if ("combined".equals(cmd)) {
                    return runStereo();
                }
                if ("sequential".equals(cmd)) {
                    return runSequential();
                }
                if ("cancel".equals(cmd)) {
                    return runCancel();
                }
                return handleDefaultCommands(cmd);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runListVibrators() {
            try (PrintWriter pw = getOutPrintWriter();) {
                if (mVibratorIds.length == 0) {
                    pw.println("No vibrator found");
                } else {
                    for (int id : mVibratorIds) {
                        pw.println(id);
                    }
                }
                pw.println("");
                return 0;
            }
        }

        private int runMono() {
            CommonOptions commonOptions = new CommonOptions();
            VibrationEffect effect = nextEffect();
            if (effect == null) {
                return 0;
            }

            CombinedVibrationEffect combinedEffect = CombinedVibrationEffect.createSynced(effect);
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            vibrate(Binder.getCallingUid(), SHELL_PACKAGE_NAME, combinedEffect, attrs,
                    commonOptions.description, mToken);
            return 0;
        }

        private int runStereo() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibrationEffect.SyncedCombination combination =
                    CombinedVibrationEffect.startSynced();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                VibrationEffect effect = nextEffect();
                if (effect != null) {
                    combination.addVibrator(vibratorId, effect);
                }
            }
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            vibrate(Binder.getCallingUid(), SHELL_PACKAGE_NAME, combination.combine(), attrs,
                    commonOptions.description, mToken);
            return 0;
        }

        private int runSequential() {
            CommonOptions commonOptions = new CommonOptions();

            CombinedVibrationEffect.SequentialCombination combination =
                    CombinedVibrationEffect.startSequential();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                int delay = 0;
                if ("-w".equals(getNextOption())) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
                VibrationEffect effect = nextEffect();
                if (effect != null) {
                    combination.addNext(vibratorId, effect, delay);
                }
            }
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            vibrate(Binder.getCallingUid(), SHELL_PACKAGE_NAME, combination.combine(), attrs,
                    commonOptions.description, mToken);
            return 0;
        }

        private int runCancel() {
            cancelVibrate(mToken);
            return 0;
        }

        @Nullable
        private VibrationEffect nextEffect() {
            String effectType = getNextArgRequired();
            if ("oneshot".equals(effectType)) {
                return nextOneShot();
            }
            if ("waveform".equals(effectType)) {
                return nextWaveform();
            }
            if ("prebaked".equals(effectType)) {
                return nextPrebaked();
            }
            if ("composed".equals(effectType)) {
                return nextComposed();
            }
            return null;
        }

        private VibrationEffect nextOneShot() {
            boolean hasAmplitude = "-a".equals(getNextOption());
            long duration = Long.parseLong(getNextArgRequired());
            int amplitude = hasAmplitude ? Integer.parseInt(getNextArgRequired())
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            return VibrationEffect.createOneShot(duration, amplitude);
        }

        private VibrationEffect nextWaveform() {
            boolean hasAmplitudes = false;
            int repeat = -1;

            String nextOption = getNextOption();
            while (nextOption != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitudes = true;
                } else if ("-r".equals(nextOption)) {
                    repeat = Integer.parseInt(getNextArgRequired());
                }
                nextOption = getNextOption();
            }
            List<Long> durations = new ArrayList<>();
            List<Integer> amplitudes = new ArrayList<>();

            String nextArg;
            while ((nextArg = peekNextArg()) != null && !"-v".equals(nextArg)) {
                durations.add(Long.parseLong(getNextArgRequired()));
                if (hasAmplitudes) {
                    amplitudes.add(Integer.parseInt(getNextArgRequired()));
                }
            }

            long[] durationArray = durations.stream().mapToLong(Long::longValue).toArray();
            if (!hasAmplitudes) {
                return VibrationEffect.createWaveform(durationArray, repeat);
            }

            int[] amplitudeArray = amplitudes.stream().mapToInt(Integer::intValue).toArray();
            return VibrationEffect.createWaveform(durationArray, amplitudeArray, repeat);
        }

        private VibrationEffect nextPrebaked() {
            boolean shouldFallback = "-b".equals(getNextOption());
            int effectId = Integer.parseInt(getNextArgRequired());
            return VibrationEffect.get(effectId, shouldFallback);
        }

        private VibrationEffect nextComposed() {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                int delay = 0;
                if ("-w".equals(nextArg)) {
                    getNextArgRequired(); // consume the -w option
                    delay = Integer.parseInt(getNextArgRequired());
                } else if ("-v".equals(nextArg)) {
                    // Starting next vibrator, this composed effect if finished.
                    break;
                }
                int primitiveId = Integer.parseInt(getNextArgRequired());
                composition.addPrimitive(primitiveId, /* scale= */ 1f, delay);
            }
            return composition.compose();
        }

        private VibrationAttributes createVibrationAttributes(CommonOptions commonOptions) {
            final int flags =
                    commonOptions.force ? VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY : 0;
            return new VibrationAttributes.Builder()
                    .setFlags(flags, VibrationAttributes.FLAG_ALL_SUPPORTED)
                    // Used to apply Settings.System.HAPTIC_FEEDBACK_INTENSITY to scale effects.
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build();
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator Manager commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  list");
                pw.println("    Prints the id of device vibrators. This does not include any ");
                pw.println("    connected input device.");
                pw.println("  synced [options] <effect>");
                pw.println("    Vibrates effect on all vibrators in sync.");
                pw.println("  combined [options] (-v <vibrator-id> <effect>)...");
                pw.println("    Vibrates different effects on each vibrator in sync.");
                pw.println("  sequential [options] (-v <vibrator-id> [-w <delay>] <effect>)...");
                pw.println("    Vibrates different effects on each vibrator in sequence.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("");
                pw.println("Effect commands:");
                pw.println("  oneshot [-a] <duration> [<amplitude>]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on ");
                pw.println("    DND (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -a is provided, the command accepts a second argument for ");
                pw.println("    amplitude, in a scale of 1-255.");
                pw.println("  waveform [-r <index>] [-a] (<duration> [<amplitude>])...");
                pw.println("    Vibrates for durations and amplitudes in list; ignored when ");
                pw.println("    device is on DND (Do Not Disturb) mode; touch feedback strength ");
                pw.println("    user setting will be used to scale amplitude.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command accepts duration-amplitude pairs;");
                pw.println("    otherwise, it accepts durations only and alternates off/on");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255.");
                pw.println("  prebaked [-b] <effect-id>");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -b is provided, the prebaked fallback effect will be played if");
                pw.println("    the device doesn't support the given effect-id.");
                pw.println("  composed [-w <delay>] <primitive-id>...");
                pw.println("    Vibrates with a composed effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale primitive intensities.");
                pw.println("    If -w is provided, the next primitive will be played after the ");
                pw.println("    specified wait time in milliseconds.");
                pw.println("");
                pw.println("Common Options:");
                pw.println("  -f");
                pw.println("    Force. Ignore Do Not Disturb setting.");
                pw.println("  -d <description>");
                pw.println("    Add description to the vibration.");
                pw.println("");
            }
        }
    }
}
