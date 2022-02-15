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

package com.android.server.power;

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;
import static android.os.PowerManagerInternal.isInteractive;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManager;
import android.os.Trace;
import android.util.Slog;
import android.view.Display;

/**
 * Used to store power related requests to every display in a
 * {@link com.android.server.display.DisplayGroup}.
 * For each {@link com.android.server.display.DisplayGroup} there exists a {@link PowerGroup}.
 * The mapping is tracked in {@link PowerManagerService}.
 * <p><b>Note:</b> Methods with the {@code *Locked} suffix require the
 * {@code PowerManagerService#mLock} to be held by the caller.
 */
public class PowerGroup {
    private static final String TAG = PowerGroup.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final DisplayPowerRequest mDisplayPowerRequest;
    private final PowerGroupListener mWakefulnessListener;
    private final boolean mSupportsSandman;
    private final int mGroupId;
    /** True if DisplayManagerService has applied all the latest display states that were requested
     *  for this group. */
    private boolean mReady;
    /** True if this group is in the process of powering on */
    private boolean mPoweringOn;
    /** True if this group is about to dream */
    private boolean mIsSandmanSummoned;
    private int mUserActivitySummary;
    /** The current wakefulness of this group */
    private int mWakefulness;
    private int mWakeLockSummary;
    private long mLastPowerOnTime;
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;
    /** Timestamp (milliseconds since boot) of the last time the power group was awoken.*/
    private long mLastWakeTime;
    /** Timestamp (milliseconds since boot) of the last time the power group was put to sleep. */
    private long mLastSleepTime;

    PowerGroup(int groupId, PowerGroupListener wakefulnessListener,
            DisplayPowerRequest displayPowerRequest, int wakefulness, boolean ready,
            boolean supportsSandman, long eventTime) {
        mGroupId = groupId;
        mWakefulnessListener = wakefulnessListener;
        mDisplayPowerRequest = displayPowerRequest;
        mWakefulness = wakefulness;
        mReady = ready;
        mSupportsSandman = supportsSandman;
        mLastWakeTime = eventTime;
        mLastSleepTime = eventTime;
    }

    PowerGroup(int wakefulness, PowerGroupListener wakefulnessListener, long eventTime) {
        mGroupId = Display.DEFAULT_DISPLAY_GROUP;
        mWakefulnessListener = wakefulnessListener;
        mDisplayPowerRequest = new DisplayPowerRequest();
        mWakefulness = wakefulness;
        mReady = false;
        mSupportsSandman = true;
        mLastWakeTime = eventTime;
        mLastSleepTime = eventTime;    }

    DisplayPowerRequest getDisplayPowerRequestLocked() {
        return mDisplayPowerRequest;
    }

    long getLastWakeTimeLocked() {
        return mLastWakeTime;
    }

    long getLastSleepTimeLocked() {
        return mLastSleepTime;
    }

    int getWakefulnessLocked() {
        return mWakefulness;
    }

    int getGroupId() {
        return mGroupId;
    }

    /**
     * Sets the {@code wakefulness} value for this {@link PowerGroup}.
     *
     * @return {@code true} if the wakefulness value was changed; {@code false} otherwise.
     */
    boolean setWakefulnessLocked(int newWakefulness, long eventTime, int uid, int reason, int opUid,
            String opPackageName, String details) {
        if (mWakefulness != newWakefulness) {
            if (newWakefulness == WAKEFULNESS_AWAKE) {
                setLastPowerOnTimeLocked(eventTime);
                setIsPoweringOnLocked(true);
                mLastWakeTime = eventTime;
            } else if (isInteractive(mWakefulness) && !isInteractive(newWakefulness)) {
                mLastSleepTime = eventTime;
            }
            mWakefulness = newWakefulness;
            mWakefulnessListener.onWakefulnessChangedLocked(mGroupId, mWakefulness, eventTime,
                    reason, uid, opUid, opPackageName, details);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if every display in this group has its requested state matching
     * its actual state.
     */
    boolean isReadyLocked() {
        return mReady;
    }

    /**
     * Sets whether the displays of this group are all ready.
     *
     * <p>A display is ready if its reported
     * {@link android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks#onStateChanged()
     * actual state} matches its
     * {@link android.hardware.display.DisplayManagerInternal#requestPowerState requested state}.
     *
     * @param isReady {@code true} if every display in the group is ready; otherwise {@code false}.
     * @return {@code true} if the ready state changed; otherwise {@code false}.
     */
    boolean setReadyLocked(boolean isReady) {
        if (mReady != isReady) {
            mReady = isReady;
            return true;
        }
        return false;
    }

    long getLastPowerOnTimeLocked() {
        return mLastPowerOnTime;
    }

    void setLastPowerOnTimeLocked(long time) {
        mLastPowerOnTime = time;
    }

    boolean isPoweringOnLocked() {
        return mPoweringOn;
    }

    void setIsPoweringOnLocked(boolean isPoweringOnNew) {
        mPoweringOn = isPoweringOnNew;
    }

    boolean isSandmanSummonedLocked() {
        return mIsSandmanSummoned;
    }

    /**
     * Sets whether or not the sandman is summoned for this {@link PowerGroup}.
     *
     * @param isSandmanSummoned {@code true} to summon the sandman; {@code false} to unsummon.
     */
    void setSandmanSummonedLocked(boolean isSandmanSummoned) {
        mIsSandmanSummoned = isSandmanSummoned;
    }

    boolean dreamLocked(long eventTime, int uid) {
        if (eventTime < mLastWakeTime || mWakefulness != WAKEFULNESS_AWAKE) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "dreamPowerGroup" + getGroupId());
        try {
            Slog.i(TAG, "Napping power group (groupId=" + getGroupId() + ", uid=" + uid + ")...");
            setSandmanSummonedLocked(true);
            setWakefulnessLocked(WAKEFULNESS_DREAMING, eventTime, uid, /* reason= */0,
                    /* opUid= */ 0, /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    boolean dozeLocked(long eventTime, int uid, int reason) {
        if (eventTime < getLastWakeTimeLocked() || !isInteractive(mWakefulness)) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "powerOffDisplay");
        try {
            reason = Math.min(PowerManager.GO_TO_SLEEP_REASON_MAX,
                    Math.max(reason, PowerManager.GO_TO_SLEEP_REASON_MIN));
            Slog.i(TAG, "Powering off display group due to "
                    + PowerManager.sleepReasonToString(reason)  + " (groupId= " + getGroupId()
                    + ", uid= " + uid + ")...");

            setSandmanSummonedLocked(/* isSandmanSummoned= */ true);
            setWakefulnessLocked(WAKEFULNESS_DOZING, eventTime, uid, reason, /* opUid= */ 0,
                    /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    boolean sleepLocked(long eventTime, int uid, int reason) {
        if (eventTime < mLastWakeTime || getWakefulnessLocked() == WAKEFULNESS_ASLEEP) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "sleepPowerGroup");
        try {
            Slog.i(TAG, "Sleeping power group (groupId=" + getGroupId() + ", uid=" + uid + ")...");
            setSandmanSummonedLocked(/* isSandmanSummoned= */ true);
            setWakefulnessLocked(WAKEFULNESS_ASLEEP, eventTime, uid, reason, /* opUid= */0,
                    /* opPackageName= */ null, /* details= */ null);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    long getLastUserActivityTimeLocked() {
        return mLastUserActivityTime;
    }

    void setLastUserActivityTimeLocked(long lastUserActivityTime) {
        mLastUserActivityTime = lastUserActivityTime;
    }

    public long getLastUserActivityTimeNoChangeLightsLocked() {
        return mLastUserActivityTimeNoChangeLights;
    }

    public void setLastUserActivityTimeNoChangeLightsLocked(long time) {
        mLastUserActivityTimeNoChangeLights = time;
    }

    public int getUserActivitySummaryLocked() {
        return mUserActivitySummary;
    }

    public void setUserActivitySummaryLocked(int summary) {
        mUserActivitySummary = summary;
    }

    public int getWakeLockSummaryLocked() {
        return mWakeLockSummary;
    }

    public void setWakeLockSummaryLocked(int summary) {
        mWakeLockSummary = summary;
    }

    /**
     * Whether or not this DisplayGroup supports dreaming.
     * @return {@code true} if this DisplayGroup supports dreaming; otherwise {@code false}.
     */
    public boolean supportsSandmanLocked() {
        return mSupportsSandman;
    }

    protected interface PowerGroupListener {
        /**
         * Informs the recipient about a wakefulness change of a {@link PowerGroup}.
         *
         * @param groupId The PowerGroup's id for which the wakefulness has changed.
         * @param wakefulness The new wakefulness.
         * @param eventTime The time of the event.
         * @param reason The reason, any of {@link android.os.PowerManager.WakeReason} or
         *               {@link android.os.PowerManager.GoToSleepReason}.
         * @param uid The uid which caused the wakefulness change.
         * @param opUid The uid used for AppOps.
         * @param opPackageName The Package name used for AppOps.
         * @param details Details about the event.
         */
        void onWakefulnessChangedLocked(int groupId, int wakefulness, long eventTime, int reason,
                int uid, int opUid, String opPackageName, String details);
    }
}
