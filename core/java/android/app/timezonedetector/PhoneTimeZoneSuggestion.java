/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.timezonedetector;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time zone suggestion from an identified telephony source, e.g. from MCC and NITZ information
 * associated with a specific radio.
 *
 * <p>The time zone ID can be {@code null} to indicate that the telephony source has entered an
 * "un-opinionated" state and any previous suggestions from that source are being withdrawn.
 * When not {@code null}, the value consists of a suggested time zone ID and metadata that can be
 * used to judge quality / certainty of the suggestion.
 *
 * <p>{@code matchType} must be set to {@link #MATCH_TYPE_NA} when {@code zoneId} is {@code null},
 * and one of the other {@code MATCH_TYPE_} values when it is not {@code null}.
 *
 * <p>{@code quality} must be set to {@link #QUALITY_NA} when {@code zoneId} is {@code null},
 * and one of the other {@code QUALITY_} values when it is not {@code null}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class PhoneTimeZoneSuggestion implements Parcelable {

    /** @hide */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public static final Creator<PhoneTimeZoneSuggestion> CREATOR =
            new Creator<PhoneTimeZoneSuggestion>() {
                public PhoneTimeZoneSuggestion createFromParcel(Parcel in) {
                    return PhoneTimeZoneSuggestion.createFromParcel(in);
                }

                public PhoneTimeZoneSuggestion[] newArray(int size) {
                    return new PhoneTimeZoneSuggestion[size];
                }
            };

    /**
     * Creates an empty time zone suggestion, i.e. one that will cancel previous suggestions with
     * the same {@code phoneId}.
     */
    @NonNull
    public static PhoneTimeZoneSuggestion createEmptySuggestion(
            int phoneId, @NonNull String debugInfo) {
        return new Builder(phoneId).addDebugInfo(debugInfo).build();
    }

    /** @hide */
    @IntDef({ MATCH_TYPE_NA, MATCH_TYPE_NETWORK_COUNTRY_ONLY, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
            MATCH_TYPE_EMULATOR_ZONE_ID, MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MatchType {}

    /** Used when match type is not applicable. */
    public static final int MATCH_TYPE_NA = 0;

    /**
     * Only the network country is known.
     */
    public static final int MATCH_TYPE_NETWORK_COUNTRY_ONLY = 2;

    /**
     * Both the network county and offset were known.
     */
    public static final int MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET = 3;

    /**
     * The device is running in an emulator and an NITZ signal was simulated containing an
     * Android extension with an explicit Olson ID.
     */
    public static final int MATCH_TYPE_EMULATOR_ZONE_ID = 4;

    /**
     * The phone is most likely running in a test network not associated with a country (this is
     * distinct from the country just not being known yet).
     * Historically, Android has just picked an arbitrary time zone with the correct offset when
     * on a test network.
     */
    public static final int MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY = 5;

    /** @hide */
    @IntDef({ QUALITY_NA, QUALITY_SINGLE_ZONE, QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET,
            QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Quality {}

    /** Used when quality is not applicable. */
    public static final int QUALITY_NA = 0;

    /** There is only one answer */
    public static final int QUALITY_SINGLE_ZONE = 1;

    /**
     * There are multiple answers, but they all shared the same offset / DST state at the time
     * the suggestion was created. i.e. it might be the wrong zone but the user won't notice
     * immediately if it is wrong.
     */
    public static final int QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET = 2;

    /**
     * There are multiple answers with different offsets. The one given is just one possible.
     */
    public static final int QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS = 3;

    /**
     * The ID of the phone this suggestion is associated with. For multiple-sim devices this
     * helps to establish source so filtering / stickiness can be implemented.
     */
    private final int mPhoneId;

    /**
     * The suggestion. {@code null} means there is no current suggestion and any previous suggestion
     * should be forgotten.
     */
    @Nullable
    private final String mZoneId;

    /**
     * The type of "match" used to establish the time zone.
     */
    @MatchType
    private final int mMatchType;

    /**
     * A measure of the quality of the time zone suggestion, i.e. how confident one could be in
     * it.
     */
    @Quality
    private final int mQuality;

    /**
     * Free-form debug information about how the suggestion was derived. Used for debug only,
     * intentionally not used in equals(), etc.
     */
    @Nullable
    private List<String> mDebugInfo;

    private PhoneTimeZoneSuggestion(Builder builder) {
        mPhoneId = builder.mPhoneId;
        mZoneId = builder.mZoneId;
        mMatchType = builder.mMatchType;
        mQuality = builder.mQuality;
        mDebugInfo = builder.mDebugInfo != null ? new ArrayList<>(builder.mDebugInfo) : null;
    }

    @SuppressWarnings("unchecked")
    private static PhoneTimeZoneSuggestion createFromParcel(Parcel in) {
        // Use the Builder so we get validation during build().
        int phoneId = in.readInt();
        PhoneTimeZoneSuggestion suggestion = new Builder(phoneId)
                .setZoneId(in.readString())
                .setMatchType(in.readInt())
                .setQuality(in.readInt())
                .build();
        List<String> debugInfo = in.readArrayList(PhoneTimeZoneSuggestion.class.getClassLoader());
        if (debugInfo != null) {
            suggestion.addDebugInfo(debugInfo);
        }
        return suggestion;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPhoneId);
        dest.writeString(mZoneId);
        dest.writeInt(mMatchType);
        dest.writeInt(mQuality);
        dest.writeList(mDebugInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns an identifier for the source of this suggestion. When a device has several "phones",
     * i.e. sim slots or equivalent, it is used to identify which one.
     */
    public int getPhoneId() {
        return mPhoneId;
    }

    /**
     * Returns the suggested time zone Olson ID, e.g. "America/Los_Angeles". {@code null} means that
     * the caller is no longer sure what the current time zone is. See
     * {@link PhoneTimeZoneSuggestion} for the associated {@code matchType} / {@code quality} rules.
     */
    @Nullable
    public String getZoneId() {
        return mZoneId;
    }

    /**
     * Returns information about how the suggestion was determined which could be used to rank
     * suggestions when several are available from different sources. See
     * {@link PhoneTimeZoneSuggestion} for the associated rules.
     */
    @MatchType
    public int getMatchType() {
        return mMatchType;
    }

    /**
     * Returns information about the likelihood of the suggested zone being correct.  See
     * {@link PhoneTimeZoneSuggestion} for the associated rules.
     */
    @Quality
    public int getQuality() {
        return mQuality;
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
        PhoneTimeZoneSuggestion that = (PhoneTimeZoneSuggestion) o;
        return mPhoneId == that.mPhoneId
                && mMatchType == that.mMatchType
                && mQuality == that.mQuality
                && Objects.equals(mZoneId, that.mZoneId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPhoneId, mZoneId, mMatchType, mQuality);
    }

    @Override
    public String toString() {
        return "PhoneTimeZoneSuggestion{"
                + "mPhoneId=" + mPhoneId
                + ", mZoneId='" + mZoneId + '\''
                + ", mMatchType=" + mMatchType
                + ", mQuality=" + mQuality
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /**
     * Builds {@link PhoneTimeZoneSuggestion} instances.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final class Builder {
        private final int mPhoneId;
        @Nullable private String mZoneId;
        @MatchType private int mMatchType;
        @Quality private int mQuality;
        @Nullable private List<String> mDebugInfo;

        public Builder(int phoneId) {
            mPhoneId = phoneId;
        }

        /**
         * Returns the builder for call chaining.
         */
        @NonNull
        public Builder setZoneId(@Nullable String zoneId) {
            mZoneId = zoneId;
            return this;
        }

        /** Returns the builder for call chaining. */
        @NonNull
        public Builder setMatchType(@MatchType int matchType) {
            mMatchType = matchType;
            return this;
        }

        /** Returns the builder for call chaining. */
        @NonNull
        public Builder setQuality(@Quality int quality) {
            mQuality = quality;
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

        /**
         * Performs basic structural validation of this instance. e.g. Are all the fields populated
         * that must be? Are the enum ints set to valid values?
         */
        void validate() {
            int quality = mQuality;
            int matchType = mMatchType;
            if (mZoneId == null) {
                if (quality != QUALITY_NA || matchType != MATCH_TYPE_NA) {
                    throw new RuntimeException("Invalid quality or match type for null zone ID."
                            + " quality=" + quality + ", matchType=" + matchType);
                }
            } else {
                boolean qualityValid = (quality == QUALITY_SINGLE_ZONE
                        || quality == QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET
                        || quality == QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
                boolean matchTypeValid = (matchType == MATCH_TYPE_NETWORK_COUNTRY_ONLY
                        || matchType == MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET
                        || matchType == MATCH_TYPE_EMULATOR_ZONE_ID
                        || matchType == MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY);
                if (!qualityValid || !matchTypeValid) {
                    throw new RuntimeException("Invalid quality or match type with zone ID."
                            + " quality=" + quality + ", matchType=" + matchType);
                }
            }
        }

        /** Returns the {@link PhoneTimeZoneSuggestion}. */
        @NonNull
        public PhoneTimeZoneSuggestion build() {
            validate();
            return new PhoneTimeZoneSuggestion(this);
        }
    }
}
