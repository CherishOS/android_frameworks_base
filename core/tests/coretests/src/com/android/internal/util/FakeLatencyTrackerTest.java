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

package com.android.internal.util;

import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_VOICE_INTERACTION;
import static com.android.internal.util.FrameworkStatsLog.UI_ACTION_LATENCY_REPORTED;
import static com.android.internal.util.LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * This test class verifies the additional methods which {@link FakeLatencyTracker} exposes.
 *
 * <p>The typical {@link LatencyTracker} behavior test coverage is present in
 * {@link LatencyTrackerTest}
 */
@RunWith(AndroidJUnit4.class)
public class FakeLatencyTrackerTest {

    private FakeLatencyTracker mFakeLatencyTracker;

    @Before
    public void setUp() throws Exception {
        mFakeLatencyTracker = FakeLatencyTracker.create();
    }

    @Test
    public void testForceEnabled() throws Exception {
        mFakeLatencyTracker.logAction(ACTION_SHOW_VOICE_INTERACTION, 1234);

        assertThat(mFakeLatencyTracker.getEventsWrittenToFrameworkStats(
                ACTION_SHOW_VOICE_INTERACTION)).isEmpty();

        mFakeLatencyTracker.forceEnabled(ACTION_SHOW_VOICE_INTERACTION, 1000);
        mFakeLatencyTracker.logAction(ACTION_SHOW_VOICE_INTERACTION, 1234);
        List<LatencyTracker.FrameworkStatsLogEvent> events =
                mFakeLatencyTracker.getEventsWrittenToFrameworkStats(
                        ACTION_SHOW_VOICE_INTERACTION);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).logCode).isEqualTo(UI_ACTION_LATENCY_REPORTED);
        assertThat(events.get(0).statsdAction).isEqualTo(
                UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_VOICE_INTERACTION);
        assertThat(events.get(0).durationMillis).isEqualTo(1234);
    }
}
