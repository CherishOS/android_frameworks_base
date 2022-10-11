/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.timedetector;

import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.util.IndentingPrintWriter;

/**
 * A fake implementation of {@link com.android.server.timedetector.TimeDetectorStrategy} for use
 * in tests.
 */
public class FakeTimeDetectorStrategy implements TimeDetectorStrategy {
    // State
    private TimeState mTimeState;

    @Override
    public TimeState getTimeState() {
        return mTimeState;
    }

    @Override
    public void setTimeState(TimeState timeState) {
        mTimeState = timeState;
    }

    @Override
    public boolean confirmTime(UnixEpochTime confirmationTime) {
        return false;
    }

    @Override
    public void suggestTelephonyTime(TelephonyTimeSuggestion timeSuggestion) {
    }

    @Override
    public boolean suggestManualTime(@UserIdInt int userId, ManualTimeSuggestion timeSuggestion,
            boolean bypassUserPolicyChecks) {
        return true;
    }

    @Override
    public void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion) {
    }

    @Override
    public void suggestGnssTime(GnssTimeSuggestion timeSuggestion) {
    }

    @Override
    public void suggestExternalTime(ExternalTimeSuggestion timeSuggestion) {
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
    }
}
