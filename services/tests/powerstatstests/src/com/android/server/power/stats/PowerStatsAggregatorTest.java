/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.ParseException;
import java.text.SimpleDateFormat;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerStatsAggregatorTest {
    private static final int TEST_POWER_COMPONENT = 77;
    private static final int TEST_UID = 42;

    private final MockClock mClock = new MockClock();
    private long mStartTime;
    private BatteryStatsHistory mHistory;
    private PowerStatsAggregator mAggregator;
    private int mAggregatedStatsCount;

    @Before
    public void setup() throws ParseException {
        mHistory = new BatteryStatsHistory(32, 1024,
                mock(BatteryStatsHistory.HistoryStepDetailsCalculator.class), mClock);
        mStartTime = new SimpleDateFormat("yyyy-MM-dd HH:mm")
                .parse("2008-09-23 08:00").getTime();
        mClock.currentTime = mStartTime;

        PowerStatsAggregator.Builder builder = new PowerStatsAggregator.Builder(mHistory);
        builder.trackPowerComponent(TEST_POWER_COMPONENT)
                .trackDeviceStates(
                        PowerStatsAggregator.STATE_POWER,
                        PowerStatsAggregator.STATE_SCREEN)
                .trackUidStates(
                        PowerStatsAggregator.STATE_POWER,
                        PowerStatsAggregator.STATE_SCREEN,
                        PowerStatsAggregator.STATE_PROCESS_STATE);
        mAggregator = builder.build();
    }

    @Test
    public void stateUpdates() {
        mHistory.forceRecordAllHistory();
        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 10, /* plugged */ true);
        mHistory.recordStateStartEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);

        advance(1000);

        PowerStats.Descriptor descriptor =
                new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                        new PersistableBundle());
        PowerStats powerStats = new PowerStats(descriptor);
        powerStats.stats = new long[]{10000};
        powerStats.uidStats.put(TEST_UID, new long[]{1234});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 90, /* plugged */ false);
        mHistory.recordStateStopEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);

        advance(1000);

        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);

        advance(3000);

        powerStats.stats = new long[]{20000};
        powerStats.uidStats.put(TEST_UID, new long[]{4444});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mAggregator.aggregateBatteryStats(0, 0, stats -> {
            assertThat(mAggregatedStatsCount++).isEqualTo(0);
            assertThat(stats.getStartTime()).isEqualTo(mStartTime);
            assertThat(stats.getDuration()).isEqualTo(5000);

            long[] values = new long[1];

            PowerComponentAggregatedPowerStats powerComponentStats = stats.getPowerComponentStats(
                    TEST_POWER_COMPONENT);

            assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                    PowerStatsAggregator.POWER_STATE_OTHER,
                    PowerStatsAggregator.SCREEN_STATE_ON}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{10000});

            assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                    PowerStatsAggregator.POWER_STATE_BATTERY,
                    PowerStatsAggregator.SCREEN_STATE_OTHER}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{20000});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    PowerStatsAggregator.POWER_STATE_OTHER,
                    PowerStatsAggregator.SCREEN_STATE_ON,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{1234});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    PowerStatsAggregator.POWER_STATE_BATTERY,
                    PowerStatsAggregator.SCREEN_STATE_OTHER,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{1111});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    PowerStatsAggregator.POWER_STATE_BATTERY,
                    PowerStatsAggregator.SCREEN_STATE_OTHER,
                    BatteryConsumer.PROCESS_STATE_BACKGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{3333});
        });
    }

    @Test
    public void incompatiblePowerStats() {
        mHistory.forceRecordAllHistory();
        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 10, /* plugged */ true);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);

        advance(1000);

        PowerStats.Descriptor descriptor =
                new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                        new PersistableBundle());
        PowerStats powerStats = new PowerStats(descriptor);
        powerStats.stats = new long[]{10000};
        powerStats.uidStats.put(TEST_UID, new long[]{1234});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 90, /* plugged */ false);

        advance(1000);

        descriptor = new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                PersistableBundle.forPair("something", "changed"));
        powerStats = new PowerStats(descriptor);
        powerStats.stats = new long[]{20000};
        powerStats.uidStats.put(TEST_UID, new long[]{4444});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        advance(1000);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 50, /* plugged */ true);

        mAggregator.aggregateBatteryStats(0, 0, stats -> {
            long[] values = new long[1];

            PowerComponentAggregatedPowerStats powerComponentStats =
                    stats.getPowerComponentStats(TEST_POWER_COMPONENT);

            if (mAggregatedStatsCount == 0) {
                assertThat(stats.getStartTime()).isEqualTo(mStartTime);
                assertThat(stats.getDuration()).isEqualTo(2000);

                assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                        PowerStatsAggregator.POWER_STATE_OTHER,
                        PowerStatsAggregator.SCREEN_STATE_ON}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{10000});
                assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                        PowerStatsAggregator.POWER_STATE_OTHER,
                        PowerStatsAggregator.SCREEN_STATE_ON,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{1234});
            } else if (mAggregatedStatsCount == 1) {
                assertThat(stats.getStartTime()).isEqualTo(mStartTime + 2000);
                assertThat(stats.getDuration()).isEqualTo(1000);

                assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                        PowerStatsAggregator.POWER_STATE_BATTERY,
                        PowerStatsAggregator.SCREEN_STATE_ON}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{20000});
                assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                        PowerStatsAggregator.POWER_STATE_BATTERY,
                        PowerStatsAggregator.SCREEN_STATE_ON,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{4444});
            } else {
                fail();
            }
            mAggregatedStatsCount++;
        });
    }

    private void advance(long durationMs) {
        mClock.realtime += durationMs;
        mClock.uptime += durationMs;
        mClock.currentTime += durationMs;
    }
}
