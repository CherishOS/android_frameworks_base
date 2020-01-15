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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.TimestampedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time suggestion from an identified telephony source. e.g. from NITZ information from a specific
 * radio.
 *
 * <p>The time value can be {@code null} to indicate that the telephony source has entered an
 * "un-opinionated" state and any previous suggestions from the source are being withdrawn. When not
 * {@code null}, the value consists of the number of milliseconds elapsed since 1/1/1970 00:00:00
 * UTC and the time according to the elapsed realtime clock when that number was established. The
 * elapsed realtime clock is considered accurate but volatile, so time suggestions must not be
 * persisted across device resets.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class PhoneTimeSuggestion implements Parcelable {

    /** @hide */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
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
    @Nullable private final TimestampedValue<Long> mUtcTime;
    @Nullable private ArrayList<String> mDebugInfo;

    private PhoneTimeSuggestion(Builder builder) {
        mPhoneId = builder.mPhoneId;
        mUtcTime = builder.mUtcTime;
        mDebugInfo = builder.mDebugInfo != null ? new ArrayList<>(builder.mDebugInfo) : null;
    }

    private static PhoneTimeSuggestion createFromParcel(Parcel in) {
        int phoneId = in.readInt();
        PhoneTimeSuggestion suggestion = new PhoneTimeSuggestion.Builder(phoneId)
                .setUtcTime(in.readParcelable(null /* classLoader */))
                .build();
        @SuppressWarnings("unchecked")
        ArrayList<String> debugInfo = (ArrayList<String>) in.readArrayList(null /* classLoader */);
        if (debugInfo != null) {
            suggestion.addDebugInfo(debugInfo);
        }
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

    /**
     * Returns an identifier for the source of this suggestion. When a device has several "phones",
     * i.e. sim slots or equivalent, it is used to identify which one.
     */
    public int getPhoneId() {
        return mPhoneId;
    }

    /**
     * Returns the suggestion. {@code null} means that the caller is no longer sure what time it
     * is.
     */
    @Nullable
    public TimestampedValue<Long> getUtcTime() {
        return mUtcTime;
    }

    /**
     * Returns debug metadata for the suggestion. The information is present in {@link #toString()}
     * but is not considered for {@link #equals(Object)} and {@link #hashCode()}.
     */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(@NonNull String debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>();
        }
        mDebugInfo.add(debugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(@NonNull List<String> debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>(debugInfo.size());
        }
        mDebugInfo.addAll(debugInfo);
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

    /**
     * Builds {@link PhoneTimeSuggestion} instances.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final class Builder {
        private final int mPhoneId;
        @Nullable private TimestampedValue<Long> mUtcTime;
        @Nullable private List<String> mDebugInfo;

        /** Creates a builder with the specified {@code phoneId}. */
        public Builder(int phoneId) {
            mPhoneId = phoneId;
        }

        /** Returns the builder for call chaining. */
        @NonNull
        public Builder setUtcTime(@Nullable TimestampedValue<Long> utcTime) {
            if (utcTime != null) {
                // utcTime can be null, but the value it holds cannot.
                Objects.requireNonNull(utcTime.getValue());
            }

            mUtcTime = utcTime;
            return this;
        }

        /** Returns the builder for call chaining. */
        @NonNull
        public Builder addDebugInfo(@NonNull String debugInfo) {
            if (mDebugInfo == null) {
                mDebugInfo = new ArrayList<>();
            }
            mDebugInfo.add(debugInfo);
            return this;
        }

        /** Returns the {@link PhoneTimeSuggestion}. */
        @NonNull
        public PhoneTimeSuggestion build() {
            return new PhoneTimeSuggestion(this);
        }
    }
}
