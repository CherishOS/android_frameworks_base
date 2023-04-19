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

package com.android.settingslib.fuelgauge;

import static com.android.settingslib.fuelgauge.BatterySaverLogging.SAVER_ENABLED_UNKNOWN;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.KEY_NO_SCHEDULE;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.KEY_PERCENTAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BatterySaverUtilsTest {
    private static final int BATTERY_SAVER_THRESHOLD_1 = 15;
    private static final int BATTERY_SAVER_THRESHOLD_2 = 20;

    @Mock
    private Context mMockContext;

    @Mock
    private ContentResolver mMockResolver;

    @Mock
    private PowerManager mMockPowerManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        when(mMockContext.getSystemService(eq(PowerManager.class))).thenReturn(mMockPowerManager);
        when(mMockPowerManager.setPowerSaveModeEnabled(anyBoolean())).thenReturn(true);
    }

    @Test
    public void testSetPowerSaveMode_enable_firstCall_needWarning() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isFalse();

        verify(mMockContext, times(1)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(0)).setPowerSaveModeEnabled(anyBoolean());

        // They shouldn't have changed.
        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_secondCall_needWarning() {
        // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_thridCall_needWarning() {
        // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 1);

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_firstCall_noWarning() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, false,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_disable_firstCall_noWarning() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        // When disabling, needFirstTimeWarning doesn't matter.
        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, false, false,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(false));

        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_disable_firstCall_needWarning() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        // When disabling, needFirstTimeWarning doesn't matter.
        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, false, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(false));

        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testEnsureAutoBatterysaver_setNewPositiveValue_doNotOverwrite() {
        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);

        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_1);

        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);

        // Once a positive number is set, ensureAutoBatterySaver() won't overwrite it.
        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_2);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
    }

    @Test
    public void testSetAutoBatterySaverTriggerLevel_setSuppressSuggestion() {
        Global.putString(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, "null");
        Secure.putString(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, "null");

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, 0);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(0);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(-1); // not set.

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, BATTERY_SAVER_THRESHOLD_1);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(1);
    }

    @Test
    public void testGetBatterySaverScheduleKey_returnExpectedKey() {
        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_NO_SCHEDULE);

        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 20);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_PERCENTAGE);

        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 20);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_DYNAMIC);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_NO_SCHEDULE);
    }

    @Test
    public void testSetBatterySaverScheduleMode_setSchedule() {
        BatterySaverUtils.setBatterySaverScheduleMode(mMockContext, KEY_NO_SCHEDULE, -1);

        assertThat(Global.getInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE, -1))
                .isEqualTo(PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(0);

        BatterySaverUtils.setBatterySaverScheduleMode(mMockContext, KEY_PERCENTAGE, 20);

        assertThat(Global.getInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE, -1))
                .isEqualTo(PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(20);

    }
}
