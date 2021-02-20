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
package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;

/**
 * Calculates the amount of power consumed by custom energy consumers (i.e. consumers of type
 * {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
 */
public class CustomMeasuredPowerCalculator extends PowerCalculator {
    public CustomMeasuredPowerCalculator(PowerProfile powerProfile) {
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        super.calculate(builder, batteryStats, rawRealtimeUs, rawUptimeUs, query);
        final double[] customMeasuredPowerMah = calculateMeasuredEnergiesMah(
                batteryStats.getCustomMeasuredEnergiesMicroJoules());
        if (customMeasuredPowerMah != null) {
            final SystemBatteryConsumer.Builder systemBatteryConsumerBuilder =
                    builder.getOrCreateSystemBatteryConsumerBuilder(
                            SystemBatteryConsumer.DRAIN_TYPE_CUSTOM);
            for (int i = 0; i < customMeasuredPowerMah.length; i++) {
                systemBatteryConsumerBuilder.setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customMeasuredPowerMah[i]);
            }
        }
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final double[] customMeasuredPowerMah = calculateMeasuredEnergiesMah(
                u.getCustomMeasuredEnergiesMicroJoules());
        if (customMeasuredPowerMah != null) {
            for (int i = 0; i < customMeasuredPowerMah.length; i++) {
                app.setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + i,
                        customMeasuredPowerMah[i]);
            }
        }
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        updateCustomMeasuredPowerMah(app, u.getCustomMeasuredEnergiesMicroJoules());
    }

    private void updateCustomMeasuredPowerMah(BatterySipper sipper, long[] measuredEnergiesUJ) {
        sipper.customMeasuredPowerMah = calculateMeasuredEnergiesMah(measuredEnergiesUJ);
    }

    private double[] calculateMeasuredEnergiesMah(long[] measuredEnergiesUJ) {
        if (measuredEnergiesUJ == null) {
            return null;
        }
        final double[] measuredEnergiesMah = new double[measuredEnergiesUJ.length];
        for (int i = 0; i < measuredEnergiesUJ.length; i++) {
            measuredEnergiesMah[i] = uJtoMah(measuredEnergiesUJ[i]);
        }
        return measuredEnergiesMah;
    }
}
