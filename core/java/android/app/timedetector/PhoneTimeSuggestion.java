/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TimestampedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from a telephony source. The value consists of the number of milliseconds elapsed
 * since 1/1/1970 00:00:00 UTC and the time according to the elapsed realtime clock when that number
 * was established. The elapsed realtime clock is considered accurate but volatile, so time signals
 * must not be persisted across device resets.
 *
 * @hide
 */
public final class PhoneTimeSuggestion implements Parcelable {

    public static final @NonNull Parcelable.Creator<PhoneTimeSuggestion> CREATOR =
            new Parcelable.Creator<PhoneTimeSuggestion>() {
                public PhoneTimeSuggestion createFromParcel(Parcel in) {
                    return PhoneTimeSuggestion.createFromParcel(in);
                }

                public PhoneTimeSuggestion[] newArray(int size) {
                    return new PhoneTimeSuggestion[size];
                }
            };

    private final int mPhoneId;
    @NonNull
    private final TimestampedValue<Long> mUtcTime;
    @Nullable
    private ArrayList<String> mDebugInfo;

    public PhoneTimeSuggestion(int phoneId, @NonNull TimestampedValue<Long> utcTime) {
        mPhoneId = phoneId;
        mUtcTime = Objects.requireNonNull(utcTime);
    }

    private static PhoneTimeSuggestion createFromParcel(Parcel in) {
        int phoneId = in.readInt();
        TimestampedValue<Long> utcTime = in.readParcelable(null /* classLoader */);
        PhoneTimeSuggestion suggestion = new PhoneTimeSuggestion(phoneId, utcTime);
        @SuppressWarnings("unchecked")
        ArrayList<String> debugInfo = (ArrayList<String>) in.readArrayList(null /* classLoader */);
        suggestion.mDebugInfo = debugInfo;
        return suggestion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPhoneId);
        dest.writeParcelable(mUtcTime, 0);
        dest.writeList(mDebugInfo);
    }

    public int getPhoneId() {
        return mPhoneId;
    }

    @NonNull
    public TimestampedValue<Long> getUtcTime() {
        return mUtcTime;
    }

    @NonNull
    public List<String> getDebugInfo() {
        return Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(String... debugInfos) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>();
        }
        mDebugInfo.addAll(Arrays.asList(debugInfos));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PhoneTimeSuggestion that = (PhoneTimeSuggestion) o;
        return mPhoneId == that.mPhoneId
                && Objects.equals(mUtcTime, that.mUtcTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPhoneId, mUtcTime);
    }

    @Override
    public String toString() {
        return "PhoneTimeSuggestion{"
                + "mPhoneId='" + mPhoneId + '\''
                + ", mUtcTime=" + mUtcTime
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }
}
