/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.doze;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.Formatter;
import android.util.Log;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.util.Preconditions;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.Assert;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;

/**
 * Handles triggers for ambient state changes.
 */
public class DozeTriggers implements DozeMachine.Part {

    private static final String TAG = "DozeTriggers";
    private static final boolean DEBUG = DozeService.DEBUG;

    /** adb shell am broadcast -a com.android.systemui.doze.pulse com.android.systemui */
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";

    private final Context mContext;
    private final DozeMachine mMachine;
    private final DozeSensors mDozeSensors;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeParameters mDozeParameters;
    private final SensorManager mSensorManager;
    private final Handler mHandler;
    private final WakeLock mWakeLock;
    private final boolean mAllowPulseTriggers;
    private final UiModeManager mUiModeManager;
    private final TriggerReceiver mBroadcastReceiver = new TriggerReceiver();

    private long mNotificationPulseTime;
    private boolean mPulsePending;


    public DozeTriggers(Context context, DozeMachine machine, DozeHost dozeHost,
            AmbientDisplayConfiguration config,
            DozeParameters dozeParameters, SensorManager sensorManager, Handler handler,
            WakeLock wakeLock, boolean allowPulseTriggers) {
        mContext = context;
        mMachine = machine;
        mDozeHost = dozeHost;
        mConfig = config;
        mDozeParameters = dozeParameters;
        mSensorManager = sensorManager;
        mHandler = handler;
        mWakeLock = wakeLock;
        mAllowPulseTriggers = allowPulseTriggers;
        mDozeSensors = new DozeSensors(context, mSensorManager, dozeParameters, config,
                wakeLock, this::onSensor, this::onProximityFar);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
    }

    private void onNotification() {
        if (DozeMachine.DEBUG) Log.d(TAG, "requestNotificationPulse");
        mNotificationPulseTime = SystemClock.elapsedRealtime();
        if (!mConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) return;
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION, false /* performedProxCheck */);
        DozeLog.traceNotificationPulse(mContext);
    }

    private void onWhisper() {
        requestPulse(DozeLog.PULSE_REASON_NOTIFICATION, false /* performedProxCheck */);
    }

    private void onSensor(int pulseReason, boolean sensorPerformedProxCheck) {
        requestPulse(pulseReason, sensorPerformedProxCheck);

        if (pulseReason == DozeLog.PULSE_REASON_SENSOR_PICKUP) {
            final long timeSinceNotification =
                    SystemClock.elapsedRealtime() - mNotificationPulseTime;
            final boolean withinVibrationThreshold =
                    timeSinceNotification < mDozeParameters.getPickupVibrationThreshold();
            DozeLog.tracePickupPulse(mContext, withinVibrationThreshold);
        }
    }

    private void onProximityFar(boolean far) {
        final boolean near = !far;
        DozeMachine.State state = mMachine.getState();
        if (near && state == DozeMachine.State.DOZE_PULSING) {
            if (DEBUG) Log.i(TAG, "Prox NEAR, ending pulse");
            DozeLog.tracePulseCanceledByProx(mContext);
            mMachine.requestState(DozeMachine.State.DOZE_PULSE_DONE);
        }
        if (far && state == DozeMachine.State.DOZE_AOD_PAUSED) {
            if (DEBUG) Log.i(TAG, "Prox FAR, unpausing AOD");
            mMachine.requestState(DozeMachine.State.DOZE_AOD);
        } else if (near && state == DozeMachine.State.DOZE_AOD) {
            if (DEBUG) Log.i(TAG, "Prox NEAR, pausing AOD");
            mMachine.requestState(DozeMachine.State.DOZE_AOD_PAUSED);
        }
    }

    private void onCarMode() {
        mMachine.requestState(DozeMachine.State.FINISH);
    }

    private void onPowerSave() {
        mMachine.requestState(DozeMachine.State.FINISH);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mBroadcastReceiver.register(mContext);
                mDozeHost.addCallback(mHostCallback);
                checkTriggersAtInit();
                break;
            case DOZE:
            case DOZE_AOD:
            case DOZE_AOD_PAUSED:
                mDozeSensors.setProxListening(newState != DozeMachine.State.DOZE);
                mDozeSensors.setListening(true);
                if (oldState != DozeMachine.State.INITIALIZED) {
                    mDozeSensors.reregisterAllSensors();
                }
                break;
            case DOZE_PULSING:
                mDozeSensors.setProxListening(true);
                break;
            case FINISH:
                mBroadcastReceiver.unregister(mContext);
                mDozeHost.removeCallback(mHostCallback);
                mDozeSensors.setListening(false);
                mDozeSensors.setProxListening(false);
                break;
            default:
        }
    }

    private void checkTriggersAtInit() {
        if (mUiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR) {
            onCarMode();
        }
        if (mDozeHost.isPowerSaveActive()) {
            onPowerSave();
        }
    }

    private void requestPulse(final int reason, boolean performedProxCheck) {
        Assert.isMainThread();
        mDozeHost.extendPulse();
        if (mPulsePending || !mAllowPulseTriggers || !canPulse()) {
            if (mAllowPulseTriggers) {
                DozeLog.tracePulseDropped(mContext, mPulsePending, mMachine.getState(),
                        mDozeHost.isPulsingBlocked());
            }
            return;
        }

        mPulsePending = true;
        if (!mDozeParameters.getProxCheckBeforePulse() || performedProxCheck) {
            // skip proximity check
            continuePulseRequest(reason);
            return;
        }

        final long start = SystemClock.uptimeMillis();
        new ProximityCheck() {
            @Override
            public void onProximityResult(int result) {
                final long end = SystemClock.uptimeMillis();
                DozeLog.traceProximityResult(mContext, result == RESULT_NEAR,
                        end - start, reason);
                if (performedProxCheck) {
                    // we already continued
                    return;
                }
                // avoid pulsing in pockets
                if (result == RESULT_NEAR) {
                    mPulsePending = false;
                    return;
                }

                // not in-pocket, continue pulsing
                continuePulseRequest(reason);
            }
        }.check();
    }

    private boolean canPulse() {
        return mMachine.getState() == DozeMachine.State.DOZE
                || mMachine.getState() == DozeMachine.State.DOZE_AOD;
    }

    private void continuePulseRequest(int reason) {
        mPulsePending = false;
        if (mDozeHost.isPulsingBlocked() || !canPulse()) {
            DozeLog.tracePulseDropped(mContext, mPulsePending, mMachine.getState(),
                    mDozeHost.isPulsingBlocked());
            return;
        }
        mMachine.requestPulse(reason);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" notificationPulseTime=");
        pw.println(Formatter.formatShortElapsedTime(mContext, mNotificationPulseTime));

        pw.print(" pulsePending="); pw.println(mPulsePending);
        pw.println("DozeSensors:");
        mDozeSensors.dump(pw);
    }

    private abstract class ProximityCheck implements SensorEventListener, Runnable {
        private static final int TIMEOUT_DELAY_MS = 500;

        protected static final int RESULT_UNKNOWN = 0;
        protected static final int RESULT_NEAR = 1;
        protected static final int RESULT_FAR = 2;

        private boolean mRegistered;
        private boolean mFinished;
        private float mMaxRange;

        protected abstract void onProximityResult(int result);

        public void check() {
            Preconditions.checkState(!mFinished && !mRegistered);
            final Sensor sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (sensor == null) {
                if (DozeMachine.DEBUG) Log.d(TAG, "ProxCheck: No sensor found");
                finishWithResult(RESULT_UNKNOWN);
                return;
            }
            mDozeSensors.setDisableSensorsInterferingWithProximity(true);

            mMaxRange = sensor.getMaximumRange();
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0,
                    mHandler);
            mHandler.postDelayed(this, TIMEOUT_DELAY_MS);
            mWakeLock.acquire();
            mRegistered = true;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length == 0) {
                if (DozeMachine.DEBUG) Log.d(TAG, "ProxCheck: Event has no values!");
                finishWithResult(RESULT_UNKNOWN);
            } else {
                if (DozeMachine.DEBUG) {
                    Log.d(TAG, "ProxCheck: Event: value=" + event.values[0] + " max=" + mMaxRange);
                }
                final boolean isNear = event.values[0] < mMaxRange;
                finishWithResult(isNear ? RESULT_NEAR : RESULT_FAR);
            }
        }

        @Override
        public void run() {
            if (DozeMachine.DEBUG) Log.d(TAG, "ProxCheck: No event received before timeout");
            finishWithResult(RESULT_UNKNOWN);
        }

        private void finishWithResult(int result) {
            if (mFinished) return;
            boolean wasRegistered = mRegistered;
            if (mRegistered) {
                mHandler.removeCallbacks(this);
                mSensorManager.unregisterListener(this);
                mDozeSensors.setDisableSensorsInterferingWithProximity(false);
                mRegistered = false;
            }
            onProximityResult(result);
            if (wasRegistered) {
                mWakeLock.release();
            }
            mFinished = true;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // noop
        }
    }

    private class TriggerReceiver extends BroadcastReceiver {
        private boolean mRegistered;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (PULSE_ACTION.equals(intent.getAction())) {
                if (DozeMachine.DEBUG) Log.d(TAG, "Received pulse intent");
                requestPulse(DozeLog.PULSE_REASON_INTENT, false /* performedProxCheck */);
            }
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(intent.getAction())) {
                onCarMode();
            }
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mDozeSensors.onUserSwitched();
            }
        }

        public void register(Context context) {
            if (mRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(PULSE_ACTION);
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            context.registerReceiver(this, filter);
            mRegistered = true;
        }

        public void unregister(Context context) {
            if (!mRegistered) {
                return;
            }
            context.unregisterReceiver(this);
            mRegistered = false;
        }
    }

    private DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onNotificationHeadsUp() {
            onNotification();
        }

        @Override
        public void onPowerSaveChanged(boolean active) {
            if (active) {
                onPowerSave();
            }
        }
    };
}
