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

package com.android.systemui.idle;

import static com.android.systemui.communal.dagger.CommunalModule.IDLE_VIEW;

import android.annotation.IntDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.dreams.Sandman;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * {@link IdleHostViewController} processes signals to control the lifecycle of the idle screen.
 */
public class IdleHostViewController extends ViewController<IdleHostView> implements
        SensorEventListener {
    private static final String INPUT_MONITOR_IDENTIFIER = "IdleHostViewController";
    private static final String TAG = "IdleHostViewController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({STATE_IDLE_MODE_ENABLED, STATE_DOZING, STATE_KEYGUARD_SHOWING, STATE_IDLING})
    public @interface State {}

    // Set at construction to indicate idle mode is available.
    private static final int STATE_IDLE_MODE_ENABLED = 1 << 0;

    // Set when the device has entered a system level dream.
    private static final int STATE_DOZING = 1 << 1;

    // Set when the keyguard is showing.
    private static final int STATE_KEYGUARD_SHOWING = 1 << 2;

    // Set when input monitoring has established the device is now idling.
    private static final int STATE_IDLING = 1 << 3;

    // Set when the device is in a low light environment.
    private static final int STATE_LOW_LIGHT = 1 << 4;

    // The state the controller must be in to start recognizing idleness (lack of input
    // interaction).
    private static final int CONDITIONS_IDLE_MONITORING =
            STATE_IDLE_MODE_ENABLED | STATE_KEYGUARD_SHOWING;

    // The state the controller must be in before entering idle mode.
    private static final int CONDITIONS_IDLING = CONDITIONS_IDLE_MONITORING | STATE_IDLING;

    // The state the controller must be in to start listening for low light signals.
    private static final int CONDITIONS_LOW_LIGHT_MONITORING =
            STATE_IDLE_MODE_ENABLED | STATE_KEYGUARD_SHOWING;

    // The aggregate current state.
    private int mState;
    private boolean mIdleModeActive;
    private boolean mLowLightModeActive;
    private boolean mIsMonitoringLowLight;

    private final Context mContext;

    // Timeout to idle in milliseconds.
    private final int mIdleTimeout;

    // Factory for generating input listeners.
    private final InputMonitorFactory mInputMonitorFactory;

    // Delayable executor.
    private final DelayableExecutor mDelayableExecutor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final PowerManager mPowerManager;

    private final AsyncSensorManager mSensorManager;

    // Light sensor used to detect low light condition.
    private final Sensor mSensor;

    // Runnable for canceling enabling idle.
    private Runnable mCancelEnableIdling;

    // Keyguard state controller for monitoring keyguard show state.
    private final KeyguardStateController mKeyguardStateController;

    // Status bar state controller for monitoring when the device is dozing.
    private final StatusBarStateController mStatusBarStateController;

    // Looper to use for monitoring input.
    private final Looper mLooper;

    // Choreographer to use for monitoring input.
    private final Choreographer mChoreographer;

    // Monitor for tracking touches for activity.
    private InputMonitorCompat mInputMonitor;

    // Delayed callback for enabling idle mode.
    private final Runnable mEnableIdlingCallback = () -> {
        if (DEBUG) {
            Log.d(TAG, "enabling idle");
        }
        setState(STATE_IDLING, true);
    };

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
                }
            };

    private final StatusBarStateController.StateListener mStatusBarCallback =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    setState(STATE_DOZING, isDozing);
                }
            };

    private final BroadcastReceiver mDreamEndedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DREAMING_STOPPED.equals(intent.getAction())) {
                setState(STATE_IDLING, false);
            }
        }
    };

    final Provider<View> mIdleViewProvider;

    @Inject
    protected IdleHostViewController(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            PowerManager powerManager,
            AsyncSensorManager sensorManager,
            IdleHostView view, InputMonitorFactory factory,
            @Main DelayableExecutor delayableExecutor,
            @Main Resources resources,
            @Main Looper looper,
            @Named(IDLE_VIEW) Provider<View> idleViewProvider,
            Choreographer choreographer,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController) {
        super(view);
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mPowerManager = powerManager;
        mSensorManager = sensorManager;
        mIdleViewProvider = idleViewProvider;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mLooper = looper;
        mChoreographer = choreographer;

        mState = STATE_KEYGUARD_SHOWING;

        final boolean enabled = resources.getBoolean(R.bool.config_enableIdleMode);
        if (enabled) {
            mState |= STATE_IDLE_MODE_ENABLED;
        }

        setState(mState, true);

        mIdleTimeout = resources.getInteger(R.integer.config_idleModeTimeout);
        mInputMonitorFactory = factory;
        mDelayableExecutor = delayableExecutor;
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (DEBUG) {
            Log.d(TAG, "initial state:" + mState + " enabled:" + enabled
                    + " timeout:" + mIdleTimeout);
        }
    }

    @Override
    public void init() {
        super.init();

        setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
        setState(STATE_DOZING, mStatusBarStateController.isDozing());
    }

    private void setState(@State int state, boolean active) {
        final int oldState = mState;

        if (active) {
            mState |= state;
        } else {
            mState &= ~state;
        }

        // If we have entered doze or no longer match the preconditions for idling, remove idling.
        if ((mState & STATE_DOZING) == STATE_DOZING
                || (mState & CONDITIONS_IDLE_MONITORING) != CONDITIONS_IDLE_MONITORING) {
            mState &= ~STATE_IDLING;
        }

        if (oldState == mState) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "state changed from " + oldState + " to " + mState);
        }

        enableIdleMonitoring(mState == CONDITIONS_IDLE_MONITORING);
        enableIdleMode(mState == CONDITIONS_IDLING);
        // Loose matching. Doesn't need to be the exact state to monitor low light, but only
        // the specified states need to match.
        enableLowLightMonitoring(
                (mState & CONDITIONS_LOW_LIGHT_MONITORING) == CONDITIONS_LOW_LIGHT_MONITORING);
        enableLowLightMode((mState & STATE_LOW_LIGHT) == STATE_LOW_LIGHT);
    }

    private void enableIdleMonitoring(boolean enable) {
        if (enable && mInputMonitor == null) {
            if (DEBUG) {
                Log.d(TAG, "enable idle monitoring");
            }
            // Set initial timeout to idle.
            mCancelEnableIdling = mDelayableExecutor.executeDelayed(mEnableIdlingCallback,
                    mIdleTimeout);

            // Monitor - any input should reset timer
            mInputMonitor = mInputMonitorFactory.getInputMonitor(INPUT_MONITOR_IDENTIFIER);
            mInputMonitor.getInputReceiver(mLooper, mChoreographer,
                    v -> {
                        if (DEBUG) {
                            Log.d(TAG, "touch detected, resetting timeout");
                        }
                        // When input is received, reset timeout.
                        if (mCancelEnableIdling != null) {
                            mCancelEnableIdling.run();
                            mCancelEnableIdling = null;
                        }
                        mCancelEnableIdling = mDelayableExecutor.executeDelayed(
                                mEnableIdlingCallback, mIdleTimeout);
                    });
        } else if (!enable && mInputMonitor != null) {
            if (DEBUG) {
                Log.d(TAG, "disable idle monitoring");
            }
            // Clean up idle callback and touch monitoring.
            if (mCancelEnableIdling != null) {
                mCancelEnableIdling.run();
                mCancelEnableIdling = null;
            }

            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void enableIdleMode(boolean enable) {
        if (mIdleModeActive == enable) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "enable idle mode:" + enable);
        }

        mIdleModeActive = enable;

        if (mIdleModeActive) {
            // Track when the dream ends to cancel any timeouts.
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            mBroadcastDispatcher.registerReceiver(mDreamEndedReceiver, filter);

            // Start dream.
            Sandman.startDreamByUserRequest(mContext);
        } else {
            // Stop tracking dream end.
            mBroadcastDispatcher.unregisterReceiver(mDreamEndedReceiver);
        }
    }

    private void enableLowLightMonitoring(boolean enable) {
        if (enable == mIsMonitoringLowLight) {
            return;
        }

        mIsMonitoringLowLight = enable;

        if (mIsMonitoringLowLight) {
            if (DEBUG) Log.d(TAG, "Enabling low light monitoring.");
            mSensorManager.registerListener(this /*listener*/, mSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            if (DEBUG) Log.d(TAG, "Disabling low light monitoring.");
            mSensorManager.unregisterListener(this);
        }
    }

    private void enableLowLightMode(boolean enable) {
        if (mLowLightModeActive == enable) {
            return;
        }

        mLowLightModeActive = enable;

        if (mLowLightModeActive) {
            if (DEBUG) Log.d(TAG, "Entering low light, start dozing.");

            mPowerManager.goToSleep(
                    SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        } else {
            if (DEBUG) Log.d(TAG, "Exiting low light, stop dozing.");
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION, "Exit low light condition");
        }
    }

    @Override
    protected void onViewAttached() {
        if (DEBUG) {
            Log.d(TAG, "onViewAttached");
        }

        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.addCallback(mStatusBarCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardStateController.removeCallback(mKeyguardCallback);
        mStatusBarStateController.removeCallback(mStatusBarCallback);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) {
            if (DEBUG) Log.w(TAG, "SensorEvent doesn't have value");
            return;
        }

        final boolean isLowLight = event.values[0] < 10;
        setState(STATE_LOW_LIGHT, isLowLight);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (DEBUG) {
            Log.d(TAG, "onAccuracyChanged accuracy=" + accuracy);
        }
    }
}
