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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.Dumpable;

/**
 * The interface for the class that implements the time detection algorithm used by the
 * {@link TimeDetectorService}.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(IndentingPrintWriter, String[])}) may be called on a different thread so
 * implementations must handle thread safety.
 *
 * @hide
 */
public interface TimeDetectorStrategy extends Dumpable {

    /** Process the suggested time from telephony sources. */
    void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion);

    /**
     * Process the suggested manually entered time. Returns {@code false} if the suggestion was
     * invalid, or the device configuration prevented the suggestion being used, {@code true} if the
     * suggestion was accepted. A suggestion that is valid but does not change the time because it
     * matches the current device time is considered accepted.
     */
    boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSuggestion);

    /** Process the suggested time from network sources. */
    void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSuggestion);

    /** Handle the auto-time setting being toggled on or off. */
    void handleAutoTimeDetectionChanged();

    // Utility methods below are to be moved to a better home when one becomes more obvious.

    /**
     * Adjusts the supplied time value by applying the difference between the reference time
     * supplied and the reference time associated with the time.
     */
    static long getTimeAt(@NonNull TimestampedValue<Long> timeValue, long referenceClockMillisNow) {
        return (referenceClockMillisNow - timeValue.getReferenceTimeMillis())
                + timeValue.getValue();
    }
}
