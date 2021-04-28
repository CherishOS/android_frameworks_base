/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.util.SparseArray;

import java.util.List;

public class SensorPowerCalculator extends PowerCalculator {
    private final SparseArray<Sensor> mSensors;

    public SensorPowerCalculator(SensorManager sensorManager) {
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        mSensors = new SparseArray<>(sensors.size());
        for (int i = 0; i < sensors.size(); i++) {
            Sensor sensor = sensors.get(i);
            mSensors.put(sensor.getHandle(), sensor);
        }
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_SENSORS,
                        calculateDuration(u, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED))
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_SENSORS,
                        calculatePowerMah(u, rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED));
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        app.sensorPowerMah = calculatePowerMah(u, rawRealtimeUs, statsType);
    }

    private long calculateDuration(BatteryStats.Uid u, long rawRealtimeUs, int statsType) {
        long durationMs = 0;
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final int NSE = sensorStats.size();
        for (int ise = 0; ise < NSE; ise++) {
            final int sensorHandle = sensorStats.keyAt(ise);
            if (sensorHandle == BatteryStats.Uid.Sensor.GPS) {
                continue;
            }

            final BatteryStats.Uid.Sensor sensor = sensorStats.valueAt(ise);
            final BatteryStats.Timer timer = sensor.getSensorTime();
            durationMs += timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
        }
        return durationMs;
    }

    private double calculatePowerMah(BatteryStats.Uid u, long rawRealtimeUs, int statsType) {
        double powerMah = 0;
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final int count = sensorStats.size();
        for (int ise = 0; ise < count; ise++) {
            final int sensorHandle = sensorStats.keyAt(ise);
            // TODO(b/178127364): remove BatteryStats.Uid.Sensor.GPS and references to it.
            if (sensorHandle == BatteryStats.Uid.Sensor.GPS) {
                continue;
            }

            final BatteryStats.Uid.Sensor sensor = sensorStats.valueAt(ise);
            final BatteryStats.Timer timer = sensor.getSensorTime();
            final long sensorTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            if (sensorTime != 0) {
                Sensor s = mSensors.get(sensorHandle);
                if (s != null) {
                    powerMah += (sensorTime * s.getPower()) / (1000 * 60 * 60);
                }
            }
        }
        return powerMah;
    }
}
