/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;

/**
 * A {@link PowerCalculator} to calculate power consumed by audio hardware.
 *
 * Also see {@link PowerProfile#POWER_AUDIO}.
 */
public class AudioPowerCalculator extends PowerCalculator {
    // Calculate audio power usage, an estimate based on the average power routed to different
    // components like speaker, bluetooth, usb-c, earphone, etc.
    // TODO(b/175344313): improve the model by taking into account different audio routes
    private final UsageBasedPowerEstimator mPowerEstimator;

    public AudioPowerCalculator(PowerProfile powerProfile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_AUDIO));
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long durationMs = mPowerEstimator.calculateDuration(u.getAudioTurnedOnTimer(),
                rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_AUDIO, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_AUDIO, powerMah);
    }
}
