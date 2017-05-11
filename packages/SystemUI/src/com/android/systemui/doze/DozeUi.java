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

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.Log;

import com.android.systemui.util.wakelock.WakeLock;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * The policy controlling doze.
 */
public class DozeUi implements DozeMachine.Part {

    private static final long TIME_TICK_DEADLINE_MILLIS = 90 * 1000; // 1.5min
    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final DozeHost mHost;
    private final Handler mHandler;
    private final WakeLock mWakeLock;
    private final DozeMachine mMachine;
    private final AlarmManager.OnAlarmListener mTimeTick;

    private boolean mTimeTickScheduled = false;
    private long mLastTimeTickElapsed = 0;

    public DozeUi(Context context, AlarmManager alarmManager, DozeMachine machine,
            WakeLock wakeLock, DozeHost host, Handler handler) {
        mContext = context;
        mAlarmManager = alarmManager;
        mMachine = machine;
        mWakeLock = wakeLock;
        mHost = host;
        mHandler = handler;

        mTimeTick = this::onTimeTick;
    }

    private void pulseWhileDozing(int reason) {
        mHost.pulseWhileDozing(
                new DozeHost.PulseCallback() {
                    @Override
                    public void onPulseStarted() {
                        mMachine.requestState(DozeMachine.State.DOZE_PULSING);
                    }

                    @Override
                    public void onPulseFinished() {
                        mMachine.requestState(DozeMachine.State.DOZE_PULSE_DONE);
                    }
                }, reason);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case DOZE_AOD:
                scheduleTimeTick();
                break;
            case DOZE:
            case DOZE_AOD_PAUSED:
                unscheduleTimeTick();
                break;
            case DOZE_REQUEST_PULSE:
                pulseWhileDozing(mMachine.getPulseReason());
                break;
            case DOZE_PULSE_DONE:
                mHost.abortPulsing();
            case INITIALIZED:
                mHost.startDozing();
                break;
            case FINISH:
                mHost.stopDozing();
                unscheduleTimeTick();
                break;
        }
        mHost.setAnimateWakeup(shouldAnimateWakeup(newState));
    }

    private boolean shouldAnimateWakeup(DozeMachine.State state) {
        switch (state) {
            case DOZE_AOD:
            case DOZE_REQUEST_PULSE:
            case DOZE_PULSING:
            case DOZE_PULSE_DONE:
                return true;
            default:
                return false;
        }
    }

    private void scheduleTimeTick() {
        if (mTimeTickScheduled) {
            return;
        }

        long delta = roundToNextMinute(System.currentTimeMillis()) - System.currentTimeMillis();
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delta, "doze_time_tick", mTimeTick, mHandler);

        mTimeTickScheduled = true;
        mLastTimeTickElapsed = SystemClock.elapsedRealtime();
    }

    private void unscheduleTimeTick() {
        if (!mTimeTickScheduled) {
            return;
        }
        verifyLastTimeTick();
        mAlarmManager.cancel(mTimeTick);
    }

    private void verifyLastTimeTick() {
        long millisSinceLastTick = SystemClock.elapsedRealtime() - mLastTimeTickElapsed;
        if (millisSinceLastTick > TIME_TICK_DEADLINE_MILLIS) {
            String delay = Formatter.formatShortElapsedTime(mContext, millisSinceLastTick);
            DozeLog.traceMissedTick(delay);
            Log.e(DozeMachine.TAG, "Missed AOD time tick by " + delay);
        }
    }

    private long roundToNextMinute(long timeInMillis) {
        Calendar calendar = GregorianCalendar.getInstance();
        calendar.setTimeInMillis(timeInMillis);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.add(Calendar.MINUTE, 1);

        return calendar.getTimeInMillis();
    }

    private void onTimeTick() {
        if (!mTimeTickScheduled) {
            // Alarm was canceled, but we still got the callback. Ignore.
            return;
        }
        verifyLastTimeTick();

        mHost.dozeTimeTick();

        // Keep wakelock until a frame has been pushed.
        mHandler.post(mWakeLock.wrap(() -> {}));

        mTimeTickScheduled = false;
        scheduleTimeTick();
    }
}
