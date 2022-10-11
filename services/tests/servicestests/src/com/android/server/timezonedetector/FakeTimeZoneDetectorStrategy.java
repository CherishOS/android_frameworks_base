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
package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.util.IndentingPrintWriter;

public class FakeTimeZoneDetectorStrategy implements TimeZoneDetectorStrategy {

    private TimeZoneState mTimeZoneState;

    @Override
    public boolean confirmTimeZone(String timeZoneId) {
        return false;
    }

    @Override
    public TimeZoneState getTimeZoneState() {
        return mTimeZoneState;
    }

    @Override
    public void setTimeZoneState(TimeZoneState timeZoneState) {
        mTimeZoneState = timeZoneState;
    }

    @Override
    public void suggestGeolocationTimeZone(GeolocationTimeZoneSuggestion timeZoneSuggestion) {
    }

    @Override
    public boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion timeZoneSuggestion,
            boolean bypassUserPolicyChecks) {
        return true;
    }

    @Override
    public void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion) {
    }

    @Override
    public void enableTelephonyTimeZoneFallback() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MetricsTimeZoneDetectorState generateMetricsState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isTelephonyTimeZoneDetectionSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isGeoTimeZoneDetectionSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
    }
}
