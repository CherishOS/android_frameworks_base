/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.power;

import static android.provider.Settings.Global.SHOW_TEMPERATURE_WARNING;
import static android.provider.Settings.Global.SHOW_USB_TEMPERATURE_ALARM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.BatteryManager;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Temperature;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.power.PowerUI.WarningsUI;
import com.android.systemui.statusbar.phone.StatusBar;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class PowerUITest extends SysuiTestCase {

    private static final boolean UNPLUGGED = false;
    private static final boolean POWER_SAVER_OFF = false;
    private static final int ABOVE_WARNING_BUCKET = 1;
    private static final long ONE_HOUR_MILLIS = Duration.ofHours(1).toMillis();
    public static final int BELOW_WARNING_BUCKET = -1;
    public static final long BELOW_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(2);
    public static final long BELOW_SEVERE_HYBRID_THRESHOLD = TimeUnit.MINUTES.toMillis(30);
    public static final long ABOVE_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(4);
    private static final long ABOVE_CHARGE_CYCLE_THRESHOLD = Duration.ofHours(8).toMillis();
    private static final int OLD_BATTERY_LEVEL_NINE = 9;
    private static final int OLD_BATTERY_LEVEL_10 = 10;
    private static final long VERY_BELOW_SEVERE_HYBRID_THRESHOLD = TimeUnit.MINUTES.toMillis(15);
    public static final int BATTERY_LEVEL_10 = 10;
    private WarningsUI mMockWarnings;
    private PowerUI mPowerUI;
    private EnhancedEstimates mEnhancedEstimates;
    @Mock private PowerManager mPowerManager;
    @Mock private IThermalService mThermalServiceMock;
    private IThermalEventListener mThermalEventUsbListener;
    private IThermalEventListener mThermalEventSkinListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMockWarnings = mDependency.injectMockDependency(WarningsUI.class);
        mEnhancedEstimates = mDependency.injectMockDependency(EnhancedEstimates.class);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.addMockSystemService(Context.POWER_SERVICE, mPowerManager);

        createPowerUi();
        mThermalEventSkinListener = mPowerUI.new ThermalEventSkinListener();
        mThermalEventUsbListener = mPowerUI.new ThermalEventUsbListener();
    }

    @Test
    public void testSkinWarning_throttlingCritical() throws Exception {
        mPowerUI.start();

        final Temperature temp = getCriticalStatusTemp(Temperature.TYPE_SKIN, "skin1");
        mThermalEventSkinListener.notifyThrottling(temp);

        // dismiss skin high temperature warning when throttling status is critical
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
        verify(mMockWarnings, times(1)).dismissHighTemperatureWarning();
    }

    @Test
    public void testSkinWarning_throttlingEmergency() throws Exception {
        mPowerUI.start();

        final Temperature temp = getEmergencyStatusTemp(Temperature.TYPE_SKIN, "skin2");
        mThermalEventSkinListener.notifyThrottling(temp);

        // show skin high temperature warning when throttling status is emergency
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, times(1)).showHighTemperatureWarning();
        verify(mMockWarnings, never()).dismissHighTemperatureWarning();
    }

    @Test
    public void testUsbAlarm_throttlingCritical() throws Exception {
        mPowerUI.start();

        final Temperature temp = getCriticalStatusTemp(Temperature.TYPE_USB_PORT, "usb1");
        mThermalEventUsbListener.notifyThrottling(temp);

        // not show usb high temperature alarm when throttling status is critical
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, never()).showUsbHighTemperatureAlarm();
    }

    @Test
    public void testUsbAlarm_throttlingEmergency() throws Exception {
        mPowerUI.start();

        final Temperature temp = getEmergencyStatusTemp(Temperature.TYPE_USB_PORT, "usb2");
        mThermalEventUsbListener.notifyThrottling(temp);

        // show usb high temperature alarm when throttling status is emergency
        TestableLooper.get(this).processAllMessages();
        verify(mMockWarnings, times(1)).showUsbHighTemperatureAlarm();
    }

    @Test
    public void testSettingOverrideConfig_enableSkinTemperatureWarning() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);

        mPowerUI.start();
        mPowerUI.registerThermalEventListener();

        TestableLooper.get(this).processAllMessages();
        verify(mThermalServiceMock, times(1))
                .registerThermalEventListenerWithType(anyObject(), eq(Temperature.TYPE_SKIN));
    }

    @Test
    public void testSettingOverrideConfig_enableUsbTemperatureAlarm() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_USB_TEMPERATURE_ALARM, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showUsbPortAlarm, 0);

        mPowerUI.start();
        mPowerUI.registerThermalEventListener();

        TestableLooper.get(this).processAllMessages();
        verify(mThermalServiceMock, times(1))
                .registerThermalEventListenerWithType(anyObject(), eq(Temperature.TYPE_USB_PORT));
    }

    @Test
    public void testMaybeShowHybridWarning() {
        mPowerUI.start();

        // verify low warning shown this cycle noticed
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();
        BatteryStateSnapshot lastState = state.get();
        state.mTimeRemainingMillis = Duration.ofHours(2).toMillis();
        state.mBatteryLevel = 15;

        mPowerUI.maybeShowHybridWarning(state.get(), lastState);

        assertThat(mPowerUI.mLowWarningShownThisChargeCycle).isTrue();
        assertThat(mPowerUI.mSevereWarningShownThisChargeCycle).isFalse();

        // verify severe warning noticed this cycle
        lastState = state.get();
        state.mBatteryLevel = 1;
        state.mTimeRemainingMillis = Duration.ofMinutes(10).toMillis();

        mPowerUI.maybeShowHybridWarning(state.get(), lastState);

        assertThat(mPowerUI.mLowWarningShownThisChargeCycle).isTrue();
        assertThat(mPowerUI.mSevereWarningShownThisChargeCycle).isTrue();

        // verify getting past threshold resets values
        lastState = state.get();
        state.mBatteryLevel = 100;
        state.mTimeRemainingMillis = Duration.ofDays(1).toMillis();

        mPowerUI.maybeShowHybridWarning(state.get(), lastState);

        assertThat(mPowerUI.mLowWarningShownThisChargeCycle).isFalse();
        assertThat(mPowerUI.mSevereWarningShownThisChargeCycle).isFalse();
    }

    @Test
    public void testShouldShowHybridWarning_lowLevelWarning() {
        mPowerUI.start();
        mPowerUI.mLowWarningShownThisChargeCycle = false;
        mPowerUI.mSevereWarningShownThisChargeCycle = false;
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();

        // sanity check to make sure we can show for a valid config
        state.mBatteryLevel = 10;
        state.mTimeRemainingMillis = Duration.ofHours(2).toMillis();
        boolean shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Shouldn't show if plugged in
        state.mPlugged = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Shouldn't show if battery is unknown
        state.mPlugged = false;
        state.mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        state.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;
        // Already shown both warnings
        mPowerUI.mLowWarningShownThisChargeCycle = true;
        mPowerUI.mSevereWarningShownThisChargeCycle = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Can show low warning
        mPowerUI.mLowWarningShownThisChargeCycle = false;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Can't show if above the threshold for time & battery
        state.mTimeRemainingMillis = Duration.ofHours(1000).toMillis();
        state.mBatteryLevel = 100;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Battery under low percentage threshold but not time
        state.mBatteryLevel = 10;
        state.mLowLevelThreshold = 50;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Should also trigger if both level and time remaining under low threshold
        state.mTimeRemainingMillis = Duration.ofHours(2).toMillis();
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // battery saver should block the low level warning though
        state.mIsPowerSaver = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        state.mIsPowerSaver = false;
        // if disabled we should not show the low warning.
        state.mIsLowLevelWarningEnabled = false;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();
    }

    @Test
    public void testShouldShowHybridWarning_severeLevelWarning() {
        mPowerUI.start();
        mPowerUI.mLowWarningShownThisChargeCycle = false;
        mPowerUI.mSevereWarningShownThisChargeCycle = false;
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();

        // sanity check to make sure we can show for a valid config
        state.mBatteryLevel = 1;
        state.mTimeRemainingMillis = Duration.ofMinutes(1).toMillis();
        boolean shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Shouldn't show if plugged in
        state.mPlugged = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Shouldn't show if battery is unknown
        state.mPlugged = false;
        state.mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        state.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;
        // Already shown both warnings
        mPowerUI.mLowWarningShownThisChargeCycle = true;
        mPowerUI.mSevereWarningShownThisChargeCycle = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Can show severe warning
        mPowerUI.mSevereWarningShownThisChargeCycle = false;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Can't show if above the threshold for time & battery
        state.mTimeRemainingMillis = Duration.ofHours(1000).toMillis();
        state.mBatteryLevel = 100;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isFalse();

        // Battery under low percentage threshold but not time
        state.mBatteryLevel = 1;
        state.mSevereLevelThreshold = 5;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // Should also trigger if both level and time remaining under low threshold
        state.mTimeRemainingMillis = Duration.ofHours(2).toMillis();
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();

        // battery saver should not block the severe level warning though
        state.mIsPowerSaver = true;
        shouldShow = mPowerUI.shouldShowHybridWarning(state.get());
        assertThat(shouldShow).isTrue();
    }

    @Test
    public void testShouldDismissHybridWarning() {
        mPowerUI.start();
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();

        // We should dismiss if the device is plugged in
        state.mPlugged = true;
        state.mTimeRemainingMillis = Duration.ofHours(1).toMillis();
        state.mLowThresholdMillis = Duration.ofHours(2).toMillis();
        boolean shouldDismiss = mPowerUI.shouldDismissHybridWarning(state.get());
        assertThat(shouldDismiss).isTrue();

        // If not plugged in and below the threshold we should not dismiss
        state.mPlugged = false;
        shouldDismiss = mPowerUI.shouldDismissHybridWarning(state.get());
        assertThat(shouldDismiss).isFalse();

        // If we go over the low warning threshold we should dismiss
        state.mTimeRemainingMillis = Duration.ofHours(3).toMillis();
        shouldDismiss = mPowerUI.shouldDismissHybridWarning(state.get());
        assertThat(shouldDismiss).isTrue();
    }

    @Test
    public void testRefreshEstimateIfNeeded_onlyQueriesEstimateOnBatteryLevelChangeOrNull() {
        mPowerUI.start();
        Estimate estimate = new Estimate(BELOW_HYBRID_THRESHOLD, true);
        when(mEnhancedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        when(mEnhancedEstimates.getLowWarningThreshold()).thenReturn(PowerUI.THREE_HOURS_IN_MILLIS);
        when(mEnhancedEstimates.getSevereWarningThreshold()).thenReturn(ONE_HOUR_MILLIS);
        when(mEnhancedEstimates.getEstimate()).thenReturn(estimate);
        mPowerUI.mBatteryLevel = 10;

        // we expect that the first time it will query since there is no last battery snapshot.
        // However an invalid estimate (-1) is returned.
        Estimate refreshedEstimate = mPowerUI.refreshEstimateIfNeeded();
        assertThat(refreshedEstimate.getEstimateMillis()).isEqualTo(BELOW_HYBRID_THRESHOLD);
        BatteryStateSnapshot snapshot = new BatteryStateSnapshot(
                BATTERY_LEVEL_10, false, false, 0, BatteryManager.BATTERY_HEALTH_GOOD,
                0, 0, -1, 0, 0, false, true);
        mPowerUI.mLastBatteryStateSnapshot = snapshot;

        // query again since the estimate was -1
        estimate = new Estimate(BELOW_SEVERE_HYBRID_THRESHOLD, true);
        when(mEnhancedEstimates.getEstimate()).thenReturn(estimate);
        refreshedEstimate = mPowerUI.refreshEstimateIfNeeded();
        assertThat(refreshedEstimate.getEstimateMillis()).isEqualTo(BELOW_SEVERE_HYBRID_THRESHOLD);
        snapshot = new BatteryStateSnapshot(
                BATTERY_LEVEL_10, false, false, 0, BatteryManager.BATTERY_HEALTH_GOOD, 0,
                0, BELOW_SEVERE_HYBRID_THRESHOLD, 0, 0, false, true);
        mPowerUI.mLastBatteryStateSnapshot = snapshot;

        // Battery level hasn't changed, so we don't query again
        estimate = new Estimate(BELOW_HYBRID_THRESHOLD, true);
        when(mEnhancedEstimates.getEstimate()).thenReturn(estimate);
        refreshedEstimate = mPowerUI.refreshEstimateIfNeeded();
        assertThat(refreshedEstimate.getEstimateMillis()).isEqualTo(BELOW_SEVERE_HYBRID_THRESHOLD);

        // Battery level changes so we update again
        mPowerUI.mBatteryLevel = 9;
        refreshedEstimate = mPowerUI.refreshEstimateIfNeeded();
        assertThat(refreshedEstimate.getEstimateMillis()).isEqualTo(BELOW_HYBRID_THRESHOLD);
    }

    @Test
    public void testShouldShowStandardWarning() {
        mPowerUI.start();
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();
        state.mIsHybrid = false;
        BatteryStateSnapshot lastState = state.get();

        // sanity check to make sure we can show for a valid config
        state.mBatteryLevel = 10;
        state.mBucket = -1;
        boolean shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isTrue();
        lastState = state.get();

        // Shouldn't show if plugged in
        state.mPlugged = true;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isFalse();

        state.mPlugged = false;
        // Shouldn't show if battery saver
        state.mIsPowerSaver = true;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isFalse();

        state.mIsPowerSaver = false;
        // Shouldn't show if battery is unknown
        state.mPlugged = false;
        state.mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isFalse();

        state.mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;
        // show if plugged -> unplugged, bucket -1 -> -1
        state.mPlugged = true;
        state.mBucket = -1;
        lastState = state.get();
        state.mPlugged = false;
        state.mBucket = -1;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isTrue();

        // don't show if plugged -> unplugged, bucket 0 -> 0
        state.mPlugged = true;
        state.mBucket = 0;
        lastState = state.get();
        state.mPlugged = false;
        state.mBucket = 0;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isFalse();

        // show if unplugged -> unplugged, bucket 0 -> -1
        state.mPlugged = false;
        state.mBucket = 0;
        lastState = state.get();
        state.mPlugged = false;
        state.mBucket = -1;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isTrue();

        // don't show if unplugged -> unplugged, bucket -1 -> 1
        state.mPlugged = false;
        state.mBucket = -1;
        lastState = state.get();
        state.mPlugged = false;
        state.mBucket = 1;
        shouldShow = mPowerUI.shouldShowLowBatteryWarning(state.get(), lastState);
        assertThat(shouldShow).isFalse();
    }

    @Test
    public void testShouldDismissStandardWarning() {
        mPowerUI.start();
        BatteryStateSnapshotWrapper state = new BatteryStateSnapshotWrapper();
        state.mIsHybrid = false;
        BatteryStateSnapshot lastState = state.get();

        // should dismiss if battery saver
        state.mIsPowerSaver = true;
        boolean shouldDismiss = mPowerUI.shouldDismissLowBatteryWarning(state.get(), lastState);
        assertThat(shouldDismiss).isTrue();

        state.mIsPowerSaver = false;
        // should dismiss if plugged
        state.mPlugged = true;
        shouldDismiss = mPowerUI.shouldDismissLowBatteryWarning(state.get(), lastState);
        assertThat(shouldDismiss).isTrue();

        state.mPlugged = false;
        // should dismiss if bucket 0 -> 1
        state.mBucket = 0;
        lastState = state.get();
        state.mBucket = 1;
        shouldDismiss = mPowerUI.shouldDismissLowBatteryWarning(state.get(), lastState);
        assertThat(shouldDismiss).isTrue();

        // shouldn't dismiss if bucket -1 -> 0
        state.mBucket = -1;
        lastState = state.get();
        state.mBucket = 0;
        shouldDismiss = mPowerUI.shouldDismissLowBatteryWarning(state.get(), lastState);
        assertThat(shouldDismiss).isFalse();

        // should dismiss if powersaver & bucket 0 -> 1
        state.mIsPowerSaver = true;
        state.mBucket = 0;
        lastState = state.get();
        state.mBucket = 1;
        shouldDismiss = mPowerUI.shouldDismissLowBatteryWarning(state.get(), lastState);
        assertThat(shouldDismiss).isTrue();
    }

    private Temperature getEmergencyStatusTemp(int type, String name) {
        final float value = 65;
        return new Temperature(value, type, name, Temperature.THROTTLING_EMERGENCY);
    }

    private Temperature getCriticalStatusTemp(int type, String name) {
        final float value = 60;
        return new Temperature(value, type, name, Temperature.THROTTLING_CRITICAL);
    }

    private void createPowerUi() {
        mPowerUI = new PowerUI();
        mPowerUI.mContext = mContext;
        mPowerUI.mComponents = mContext.getComponents();
        mPowerUI.mThermalService = mThermalServiceMock;
    }

    /**
     * A simple wrapper class that sets values by default and makes them not final to improve
     * test clarity.
     */
    private class BatteryStateSnapshotWrapper {
        public int mBatteryLevel = 100;
        public boolean mIsPowerSaver = false;
        public boolean mPlugged = false;
        public long mSevereThresholdMillis = Duration.ofHours(1).toMillis();
        public long mLowThresholdMillis = Duration.ofHours(3).toMillis();
        public int mSevereLevelThreshold = 5;
        public int mLowLevelThreshold = 15;
        public int mBucket = 1;
        public int mBatteryStatus = BatteryManager.BATTERY_HEALTH_GOOD;
        public long mTimeRemainingMillis = Duration.ofHours(24).toMillis();
        public boolean mIsBasedOnUsage = true;
        public boolean mIsHybrid = true;
        public boolean mIsLowLevelWarningEnabled = true;

        public BatteryStateSnapshot get() {
            if (mIsHybrid) {
                return new BatteryStateSnapshot(mBatteryLevel, mIsPowerSaver, mPlugged, mBucket,
                        mBatteryStatus, mSevereLevelThreshold, mLowLevelThreshold,
                        mTimeRemainingMillis, mSevereThresholdMillis, mLowThresholdMillis,
                        mIsBasedOnUsage, mIsLowLevelWarningEnabled);
            } else {
                return new BatteryStateSnapshot(mBatteryLevel, mIsPowerSaver, mPlugged, mBucket,
                        mBatteryStatus, mSevereLevelThreshold, mLowLevelThreshold);
            }
        }
    }
}
