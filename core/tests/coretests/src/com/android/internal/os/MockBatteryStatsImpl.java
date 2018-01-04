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

package com.android.internal.os;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.concurrent.Future;

/**
 * Mocks a BatteryStatsImpl object.
 */
public class MockBatteryStatsImpl extends BatteryStatsImpl {
    public BatteryStatsImpl.Clocks clocks;
    public boolean mForceOnBattery;

    MockBatteryStatsImpl(Clocks clocks) {
        super(clocks);
        this.clocks = mClocks;
        mScreenOnTimer = new BatteryStatsImpl.StopwatchTimer(clocks, null, -1, null,
                mOnBatteryTimeBase);
        mScreenDozeTimer = new BatteryStatsImpl.StopwatchTimer(clocks, null, -1, null,
                mOnBatteryTimeBase);
        mBluetoothScanTimer = new StopwatchTimer(mClocks, null, -14, null, mOnBatteryTimeBase);
        setExternalStatsSyncLocked(new DummyExternalStatsSync());

        // A no-op handler.
        mHandler = new Handler(Looper.getMainLooper()) {};
    }

    MockBatteryStatsImpl() {
        this(new MockClocks());
    }

    public TimeBase getOnBatteryTimeBase() {
        return mOnBatteryTimeBase;
    }

    public TimeBase getOnBatteryScreenOffTimeBase() {
        return mOnBatteryScreenOffTimeBase;
    }

    public int getScreenState() {
        return mScreenState;
    }

    public boolean isOnBattery() {
        return mForceOnBattery ? true : super.isOnBattery();
    }

    public void forceRecordAllHistory() {
        mHaveBatteryLevel = true;
        mRecordingHistory = true;
        mRecordAllHistory = true;
    }

    public TimeBase getOnBatteryBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryBackgroundTimeBase;
    }

    public TimeBase getOnBatteryScreenOffBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryScreenOffBackgroundTimeBase;
    }

    public MockBatteryStatsImpl setPowerProfile(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
        return this;
    }

    public MockBatteryStatsImpl setKernelUidCpuFreqTimeReader(KernelUidCpuFreqTimeReader reader) {
        mKernelUidCpuFreqTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelUidCpuTimeReader(KernelUidCpuTimeReader reader) {
        mKernelUidCpuTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelSingleUidTimeReader(KernelSingleUidTimeReader reader) {
        mKernelSingleUidTimeReader = reader;
        return this;
    }

    public MockBatteryStatsImpl setKernelCpuSpeedReaders(KernelCpuSpeedReader[] readers) {
        mKernelCpuSpeedReaders = readers;
        return this;
    }

    public MockBatteryStatsImpl setUserInfoProvider(UserInfoProvider provider) {
        mUserInfoProvider = provider;
        return this;
    }

    public MockBatteryStatsImpl setPartialTimers(ArrayList<StopwatchTimer> partialTimers) {
        mPartialTimers = partialTimers;
        return this;
    }

    public MockBatteryStatsImpl setLastPartialTimers(ArrayList<StopwatchTimer> lastPartialTimers) {
        mLastPartialTimers = lastPartialTimers;
        return this;
    }

    public MockBatteryStatsImpl setOnBatteryInternal(boolean onBatteryInternal) {
        mOnBatteryInternal = onBatteryInternal;
        return this;
    }

    public SparseIntArray getPendingUids() {
        return mPendingUids;
    }

    private class DummyExternalStatsSync implements ExternalStatsSync {
        @Override
        public Future<?> scheduleSync(String reason, int flags) {
            return null;
        }

        @Override
        public Future<?> scheduleCpuSyncDueToRemovedUid(int uid) {
            return null;
        }

        @Override
        public Future<?> scheduleReadProcStateCpuTimes() {
            return null;
        }

        @Override
        public Future<?> scheduleCopyFromAllUidsCpuTimes() {
            return null;
        }

    }
}

