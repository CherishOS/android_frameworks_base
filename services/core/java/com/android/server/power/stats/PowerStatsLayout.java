/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats;

import android.os.PersistableBundle;
import android.util.Slog;

import com.android.internal.os.PowerStats;

/**
 * Captures the positions and lengths of sections of the stats array, such as usage duration,
 * power usage estimates etc.
 */
public class PowerStatsLayout {
    private static final String TAG = "PowerStatsLayout";
    private static final String EXTRA_DEVICE_POWER_POSITION = "dp";
    private static final String EXTRA_DEVICE_DURATION_POSITION = "dd";
    private static final String EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION = "de";
    private static final String EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT = "dec";
    private static final String EXTRA_UID_POWER_POSITION = "up";

    protected static final double MILLI_TO_NANO_MULTIPLIER = 1000000.0;
    protected static final int UNSUPPORTED = -1;

    private int mDeviceStatsArrayLength;
    private int mStateStatsArrayLength;
    private int mUidStatsArrayLength;

    protected int mDeviceDurationPosition = UNSUPPORTED;
    private int mDeviceEnergyConsumerPosition;
    private int mDeviceEnergyConsumerCount;
    private int mDevicePowerEstimatePosition = UNSUPPORTED;
    private int mUidPowerEstimatePosition = UNSUPPORTED;

    public PowerStatsLayout() {
    }

    public PowerStatsLayout(PowerStats.Descriptor descriptor) {
        fromExtras(descriptor.extras);
    }

    public int getDeviceStatsArrayLength() {
        return mDeviceStatsArrayLength;
    }

    public int getStateStatsArrayLength() {
        return mStateStatsArrayLength;
    }

    public int getUidStatsArrayLength() {
        return mUidStatsArrayLength;
    }

    protected int addDeviceSection(int length) {
        int position = mDeviceStatsArrayLength;
        mDeviceStatsArrayLength += length;
        return position;
    }

    protected int addStateSection(int length) {
        int position = mStateStatsArrayLength;
        mStateStatsArrayLength += length;
        return position;
    }

    protected int addUidSection(int length) {
        int position = mUidStatsArrayLength;
        mUidStatsArrayLength += length;
        return position;
    }

    /**
     * Declare that the stats array has a section capturing usage duration
     */
    public void addDeviceSectionUsageDuration() {
        mDeviceDurationPosition = addDeviceSection(1);
    }

    /**
     * Saves the usage duration in the corresponding <code>stats</code> element.
     */
    public void setUsageDuration(long[] stats, long value) {
        stats[mDeviceDurationPosition] = value;
    }

    /**
     * Extracts the usage duration from the corresponding <code>stats</code> element.
     */
    public long getUsageDuration(long[] stats) {
        return stats[mDeviceDurationPosition];
    }

    /**
     * Declares that the stats array has a section capturing EnergyConsumer data from
     * PowerStatsService.
     */
    public void addDeviceSectionEnergyConsumers(int energyConsumerCount) {
        mDeviceEnergyConsumerPosition = addDeviceSection(energyConsumerCount);
        mDeviceEnergyConsumerCount = energyConsumerCount;
    }

    public int getEnergyConsumerCount() {
        return mDeviceEnergyConsumerCount;
    }

    /**
     * Saves the accumulated energy for the specified rail the corresponding
     * <code>stats</code> element.
     */
    public void setConsumedEnergy(long[] stats, int index, long energy) {
        stats[mDeviceEnergyConsumerPosition + index] = energy;
    }

    /**
     * Extracts the EnergyConsumer data from a device stats array for the specified
     * EnergyConsumer.
     */
    public long getConsumedEnergy(long[] stats, int index) {
        return stats[mDeviceEnergyConsumerPosition + index];
    }

    /**
     * Declare that the stats array has a section capturing a power estimate
     */
    public void addDeviceSectionPowerEstimate() {
        mDevicePowerEstimatePosition = addDeviceSection(1);
    }

    /**
     * Converts the supplied mAh power estimate to a long and saves it in the corresponding
     * element of <code>stats</code>.
     */
    public void setDevicePowerEstimate(long[] stats, double power) {
        stats[mDevicePowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
    }

    /**
     * Extracts the power estimate from a device stats array and converts it to mAh.
     */
    public double getDevicePowerEstimate(long[] stats) {
        return stats[mDevicePowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
    }

    /**
     * Declare that the UID stats array has a section capturing a power estimate
     */
    public void addUidSectionPowerEstimate() {
        mUidPowerEstimatePosition = addUidSection(1);
    }

    /**
     * Returns true if power for this component is attributed to UIDs (apps).
     */
    public boolean isUidPowerAttributionSupported() {
        return mUidPowerEstimatePosition != UNSUPPORTED;
    }

    /**
     * Converts the supplied mAh power estimate to a long and saves it in the corresponding
     * element of <code>stats</code>.
     */
    public void setUidPowerEstimate(long[] stats, double power) {
        stats[mUidPowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
    }

    /**
     * Extracts the power estimate from a UID stats array and converts it to mAh.
     */
    public double getUidPowerEstimate(long[] stats) {
        return stats[mUidPowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        extras.putInt(EXTRA_DEVICE_DURATION_POSITION, mDeviceDurationPosition);
        extras.putInt(EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION,
                mDeviceEnergyConsumerPosition);
        extras.putInt(EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT,
                mDeviceEnergyConsumerCount);
        extras.putInt(EXTRA_DEVICE_POWER_POSITION, mDevicePowerEstimatePosition);
        extras.putInt(EXTRA_UID_POWER_POSITION, mUidPowerEstimatePosition);
    }

    /**
     * Retrieves elements of the stats array layout from <code>extras</code>
     */
    public void fromExtras(PersistableBundle extras) {
        mDeviceDurationPosition = extras.getInt(EXTRA_DEVICE_DURATION_POSITION);
        mDeviceEnergyConsumerPosition = extras.getInt(EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION);
        mDeviceEnergyConsumerCount = extras.getInt(EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT);
        mDevicePowerEstimatePosition = extras.getInt(EXTRA_DEVICE_POWER_POSITION);
        mUidPowerEstimatePosition = extras.getInt(EXTRA_UID_POWER_POSITION);
    }

    protected void putIntArray(PersistableBundle extras, String key, int[] array) {
        if (array == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int value : array) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(value);
        }
        extras.putString(key, sb.toString());
    }

    protected int[] getIntArray(PersistableBundle extras, String key) {
        String string = extras.getString(key);
        if (string == null) {
            return null;
        }
        String[] values = string.trim().split(",");
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                result[i] = Integer.parseInt(values[i]);
            } catch (NumberFormatException e) {
                Slog.wtf(TAG, "Invalid CSV format: " + string);
                return null;
            }
        }
        return result;
    }
}
