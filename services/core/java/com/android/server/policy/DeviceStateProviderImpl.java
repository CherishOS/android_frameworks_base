/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.policy;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManagerInternal;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.devicestate.config.Conditions;
import com.android.server.policy.devicestate.config.DeviceState;
import com.android.server.policy.devicestate.config.DeviceStateConfig;
import com.android.server.policy.devicestate.config.LidSwitchCondition;
import com.android.server.policy.devicestate.config.NumericRange;
import com.android.server.policy.devicestate.config.SensorCondition;
import com.android.server.policy.devicestate.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Implementation of {@link DeviceStateProvider} that reads the set of supported device states
 * from a configuration file provided at either /vendor/etc/devicestate or
 * /data/system/devicestate/. By default, the provider supports {@link #DEFAULT_DEVICE_STATE} when
 * no configuration is provided.
 */
public final class DeviceStateProviderImpl implements DeviceStateProvider,
        InputManagerInternal.LidSwitchCallback, SensorEventListener {
    private static final String TAG = "DeviceStateProviderImpl";

    private static final BooleanSupplier TRUE_BOOLEAN_SUPPLIER = () -> true;

    @VisibleForTesting
    static final int DEFAULT_DEVICE_STATE = 0;

    private static final String VENDOR_CONFIG_FILE_PATH = "etc/devicestate/";
    private static final String DATA_CONFIG_FILE_PATH = "system/devicestate/";
    private static final String CONFIG_FILE_NAME = "device_state_configuration.xml";

    /** Interface that allows reading the device state configuration. */
    interface ReadableConfig {
        @NonNull
        InputStream openRead() throws IOException;
    }

    /**
     * Returns a new {@link DeviceStateProviderImpl} instance.
     *
     * @param context the {@link Context} that should be used to access system services.
     */
    public static DeviceStateProviderImpl create(@NonNull Context context) {
        File configFile = getConfigurationFile();
        if (configFile == null) {
            return createFromConfig(context, null);
        }
        return createFromConfig(context, new ReadableFileConfig(configFile));
    }

    /**
     * Returns a new {@link DeviceStateProviderImpl} instance.
     *
     * @param context the {@link Context} that should be used to access system services.
     * @param readableConfig the config the provider instance should read supported states from.
     */
    @VisibleForTesting
    static DeviceStateProviderImpl createFromConfig(@NonNull Context context,
            @Nullable ReadableConfig readableConfig) {
        SparseArray<Conditions> conditionsForState = new SparseArray<>();
        if (readableConfig != null) {
            DeviceStateConfig config = parseConfig(readableConfig);
            if (config != null) {
                for (DeviceState stateConfig : config.getDeviceState()) {
                    int state = stateConfig.getIdentifier().intValue();
                    Conditions conditions = stateConfig.getConditions();
                    conditionsForState.put(state, conditions);
                }
            }
        }

        if (conditionsForState.size() == 0) {
            conditionsForState.put(DEFAULT_DEVICE_STATE, null);
        }
        return new DeviceStateProviderImpl(context, conditionsForState);
    }

    // Lock for internal state.
    private final Object mLock = new Object();
    private final Context mContext;
    // List of supported states in ascending order.
    private final int[] mOrderedStates;
    // Map of state to a boolean supplier that returns true when all required conditions are met for
    // the device to be in the state.
    private final SparseArray<BooleanSupplier> mStateConditions;

    @Nullable
    @GuardedBy("mLock")
    private Listener mListener = null;
    @GuardedBy("mLock")
    private int mLastReportedState = INVALID_DEVICE_STATE;

    @GuardedBy("mLock")
    private boolean mIsLidOpen;
    @GuardedBy("mLock")
    private final Map<Sensor, SensorEvent> mLatestSensorEvent = new ArrayMap<>();

    private DeviceStateProviderImpl(@NonNull Context context,
            @NonNull SparseArray<Conditions> conditionsForState) {
        mContext = context;
        mOrderedStates = new int[conditionsForState.size()];
        for (int i = 0; i < conditionsForState.size(); i++) {
            mOrderedStates[i] = conditionsForState.keyAt(i);
        }

        // Whether or not this instance should register to receive lid switch notifications from
        // InputManagerInternal. If there are no device state conditions that are based on the lid
        // switch there is no need to register for a callback.
        boolean shouldListenToLidSwitch = false;

        final SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        // The set of Sensor(s) that this instance should register to receive SensorEvent(s) from.
        final ArraySet<Sensor> sensorsToListenTo = new ArraySet<>();

        mStateConditions = new SparseArray<>();
        for (int i = 0; i < mOrderedStates.length; i++) {
            int state = mOrderedStates[i];
            Conditions conditions = conditionsForState.get(state);
            if (conditions == null) {
                mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
                continue;
            }

            List<BooleanSupplier> suppliers = new ArrayList<>();

            LidSwitchCondition lidSwitchCondition = conditions.getLidSwitch();
            if (lidSwitchCondition != null) {
                suppliers.add(new LidSwitchBooleanSupplier(lidSwitchCondition.getOpen()));
                shouldListenToLidSwitch = true;
            }

            List<SensorCondition> sensorConditions = conditions.getSensor();
            for (int j = 0; j < sensorConditions.size(); j++) {
                SensorCondition sensorCondition = sensorConditions.get(j);
                final int expectedSensorType = sensorCondition.getType().intValue();
                final String expectedSensorName = sensorCondition.getName();

                List<Sensor> sensors = sensorManager.getSensorList(expectedSensorType);
                Sensor foundSensor = null;
                for (int sensorIndex = 0; sensorIndex < sensors.size(); sensorIndex++) {
                    Sensor sensor = sensors.get(sensorIndex);
                    if (sensor.getName().equals(expectedSensorName)) {
                        foundSensor = sensor;
                        break;
                    }
                }

                if (foundSensor == null) {
                    throw new IllegalStateException("Failed to find Sensor with type: "
                            + expectedSensorType + " and name: " + expectedSensorName);
                }

                suppliers.add(new SensorBooleanSupplier(foundSensor, sensorCondition.getValue()));
                sensorsToListenTo.add(foundSensor);
            }

            if (suppliers.size() > 1) {
                mStateConditions.put(state, new AndBooleanSupplier(suppliers));
            } else if (suppliers.size() > 0) {
                // No need to wrap with an AND supplier if there is only 1.
                mStateConditions.put(state, suppliers.get(0));
            } else {
                // There are no conditions for this state. Default to always true.
                mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
            }
        }

        if (shouldListenToLidSwitch) {
            InputManagerInternal inputManager = LocalServices.getService(
                    InputManagerInternal.class);
            inputManager.registerLidSwitchCallback(this);
        }

        for (int i = 0; i < sensorsToListenTo.size(); i++) {
            Sensor sensor = sensorsToListenTo.valueAt(i);
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Override
    public void setListener(Listener listener) {
        synchronized (mLock) {
            if (mListener != null) {
                throw new RuntimeException("Provider already has a listener set.");
            }
            mListener = listener;
        }
        notifySupportedStatesChanged();
        notifyDeviceStateChangedIfNeeded();
    }

    /** Notifies the listener that the set of supported device states has changed. */
    private void notifySupportedStatesChanged() {
        int[] supportedStates;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            supportedStates = Arrays.copyOf(mOrderedStates, mOrderedStates.length);
        }

        mListener.onSupportedDeviceStatesChanged(supportedStates);
    }

    /** Computes the current device state and notifies the listener of a change, if needed. */
    void notifyDeviceStateChangedIfNeeded() {
        int stateToReport = INVALID_DEVICE_STATE;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            int newState = mOrderedStates[0];
            for (int i = 1; i < mOrderedStates.length; i++) {
                int state = mOrderedStates[i];
                if (mStateConditions.get(state).getAsBoolean()) {
                    newState = state;
                    break;
                }
            }

            if (newState != mLastReportedState) {
                mLastReportedState = newState;
                stateToReport = newState;
            }
        }

        if (stateToReport != INVALID_DEVICE_STATE) {
            mListener.onStateChanged(stateToReport);
        }
    }

    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        synchronized (mLock) {
            mIsLidOpen = lidOpen;
        }
        notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        synchronized (mLock) {
            mLatestSensorEvent.put(event.sensor, event);
        }
        notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing.
    }

    /**
     * Implementation of {@link BooleanSupplier} that returns {@code true} if the expected lid
     * switch open state matches {@link #mIsLidOpen}.
     */
    private final class LidSwitchBooleanSupplier implements BooleanSupplier {
        private final boolean mExpectedOpen;

        LidSwitchBooleanSupplier(boolean expectedOpen) {
            mExpectedOpen = expectedOpen;
        }

        @Override
        public boolean getAsBoolean() {
            synchronized (mLock) {
                return mIsLidOpen == mExpectedOpen;
            }
        }
    }

    /**
     * Implementation of {@link BooleanSupplier} that returns {@code true} if the latest
     * {@link SensorEvent#values sensor event values} for the specified {@link Sensor} adhere to
     * the supplied {@link NumericRange ranges}.
     */
    private final class SensorBooleanSupplier implements BooleanSupplier {
        @NonNull
        private final Sensor mSensor;
        @NonNull
        private final List<NumericRange> mExpectedValues;

        SensorBooleanSupplier(@NonNull Sensor sensor, @NonNull List<NumericRange> expectedValues) {
            mSensor = sensor;
            mExpectedValues = expectedValues;
        }

        @Override
        public boolean getAsBoolean() {
            synchronized (mLock) {
                SensorEvent latestEvent = mLatestSensorEvent.get(mSensor);
                if (latestEvent == null) {
                    // Default to returning false if we have not yet received a sensor event for the
                    // sensor.
                    return false;
                }

                if (latestEvent.values.length != mExpectedValues.size()) {
                    throw new IllegalStateException("Number of supplied numeric range(s) does not "
                            + "match the number of values in the latest sensor event for sensor: "
                            + mSensor);
                }

                for (int i = 0; i < latestEvent.values.length; i++) {
                    if (!adheresToRange(latestEvent.values[i], mExpectedValues.get(i))) {
                        return false;
                    }
                }
                return true;
            }
        }

        /**
         * Returns {@code true} if the supplied {@code value} adheres to the constraints specified
         * in {@code range}.
         */
        private boolean adheresToRange(float value, @NonNull NumericRange range) {
            final BigDecimal min = range.getMin_optional();
            if (min != null) {
                if (value <= min.floatValue()) {
                    return false;
                }
            }

            final BigDecimal minInclusive = range.getMinInclusive_optional();
            if (minInclusive != null) {
                if (value < minInclusive.floatValue()) {
                    return false;
                }
            }

            final BigDecimal max = range.getMax_optional();
            if (max != null) {
                if (value >= max.floatValue()) {
                    return false;
                }
            }

            final BigDecimal maxInclusive = range.getMaxInclusive_optional();
            if (maxInclusive != null) {
                if (value > maxInclusive.floatValue()) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * Implementation of {@link BooleanSupplier} whose result is the product of an AND operation
     * applied to the result of all child suppliers.
     */
    private static final class AndBooleanSupplier implements BooleanSupplier {
        @NonNull
        List<BooleanSupplier> mBooleanSuppliers;

        AndBooleanSupplier(@NonNull List<BooleanSupplier> booleanSuppliers) {
            mBooleanSuppliers = booleanSuppliers;
        }

        @Override
        public boolean getAsBoolean() {
            for (int i = 0; i < mBooleanSuppliers.size(); i++) {
                if (!mBooleanSuppliers.get(i).getAsBoolean()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Returns the device state configuration file that should be used, or {@code null} if no file
     * is present on the device.
     * <p>
     * Defaults to returning a config file present in the data/ dir at
     * {@link #DATA_CONFIG_FILE_PATH}, and then falls back to the config file in the vendor/ dir
     * at {@link #VENDOR_CONFIG_FILE_PATH} if no config file is found in the data/ dir.
     */
    @Nullable
    private static File getConfigurationFile() {
        final File configFileFromDataDir = Environment.buildPath(Environment.getDataDirectory(),
                DATA_CONFIG_FILE_PATH, CONFIG_FILE_NAME);
        if (configFileFromDataDir.exists()) {
            return configFileFromDataDir;
        }

        final File configFileFromVendorDir = Environment.buildPath(Environment.getVendorDirectory(),
                VENDOR_CONFIG_FILE_PATH, CONFIG_FILE_NAME);
        if (configFileFromVendorDir.exists()) {
            return configFileFromVendorDir;
        }

        return null;
    }

    /**
     * Tries to parse the provided file into a {@link DeviceStateConfig} object. Returns
     * {@code null} if the file could not be successfully parsed.
     */
    @Nullable
    private static DeviceStateConfig parseConfig(@NonNull ReadableConfig readableConfig) {
        try (InputStream in = readableConfig.openRead();
                InputStream bin = new BufferedInputStream(in)) {
            return XmlParser.read(bin);
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading device state config", e);
        }
        return null;
    }

    /** Implementation of {@link ReadableConfig} that reads config data from a file. */
    private static final class ReadableFileConfig implements ReadableConfig {
        @NonNull
        private final File mFile;

        private ReadableFileConfig(@NonNull File file) {
            mFile = file;
        }

        @Override
        public InputStream openRead() throws IOException {
            return new FileInputStream(mFile);
        }
    }
}
