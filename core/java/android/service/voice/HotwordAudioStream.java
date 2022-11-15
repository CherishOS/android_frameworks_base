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

package android.service.voice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.Objects;

/**
 * Represents an audio stream supporting the hotword detection.
 *
 * @hide
 */
public final class HotwordAudioStream implements Parcelable {

    /**
     * The {@link AudioFormat} of the audio stream.
     */
    @NonNull
    @UnsupportedAppUsage
    private final AudioFormat mAudioFormat;

    /**
     * This stream starts with the audio bytes used for hotword detection, but continues streaming
     * the audio until the stream is shutdown by the {@link HotwordDetectionService}.
     */
    @NonNull
    @UnsupportedAppUsage
    private final ParcelFileDescriptor mAudioStreamParcelFileDescriptor;

    /**
     * The timestamp when the audio stream was captured by the Audio platform.
     *
     * <p>
     * The {@link HotwordDetectionService} egressing the audio is the owner of the underlying
     * AudioRecord. The {@link HotwordDetectionService} is expected to optionally populate this
     * field by {@link AudioRecord#getTimestamp}.
     * </p>
     *
     * <p>
     * This timestamp can be used in conjunction with the
     * {@link HotwordDetectedResult#getHotwordOffsetMillis()} and
     * {@link HotwordDetectedResult#getHotwordDurationMillis()} to translate these durations to
     * timestamps.
     * </p>
     *
     * @see #getAudioStreamParcelFileDescriptor()
     */
    @Nullable
    @UnsupportedAppUsage
    private final AudioTimestamp mTimestamp;

    private static AudioTimestamp defaultTimestamp() {
        return null;
    }

    /**
     * The metadata associated with the audio stream.
     */
    @NonNull
    @UnsupportedAppUsage
    private final PersistableBundle mMetadata;

    private static PersistableBundle defaultMetadata() {
        return new PersistableBundle();
    }

    private String timestampToString() {
        if (mTimestamp == null) {
            return "";
        }
        return "TimeStamp:"
                + " framePos=" + mTimestamp.framePosition
                + " nanoTime=" + mTimestamp.nanoTime;
    }

    private void parcelTimestamp(Parcel dest, int flags) {
        if (mTimestamp != null) {
            // mTimestamp is not null, we write it to the parcel, set true.
            dest.writeBoolean(true);
            dest.writeLong(mTimestamp.framePosition);
            dest.writeLong(mTimestamp.nanoTime);
        } else {
            // mTimestamp is null, we don't write any value out, set false.
            dest.writeBoolean(false);
        }
    }

    @Nullable
    private static AudioTimestamp unparcelTimestamp(Parcel in) {
        // If it is true, it means we wrote the value to the parcel before, parse it.
        // Otherwise, return null.
        if (in.readBoolean()) {
            final AudioTimestamp timeStamp = new AudioTimestamp();
            timeStamp.framePosition = in.readLong();
            timeStamp.nanoTime = in.readLong();
            return timeStamp;
        } else {
            return null;
        }
    }

    /* package-private */
    HotwordAudioStream(
            @NonNull AudioFormat audioFormat,
            @NonNull ParcelFileDescriptor audioStreamParcelFileDescriptor,
            @Nullable AudioTimestamp timestamp,
            @NonNull PersistableBundle metadata) {
        this.mAudioFormat = audioFormat;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioFormat);
        this.mAudioStreamParcelFileDescriptor = audioStreamParcelFileDescriptor;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioStreamParcelFileDescriptor);
        this.mTimestamp = timestamp;
        this.mMetadata = metadata;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mMetadata);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The {@link AudioFormat} of the audio stream.
     */
    @UnsupportedAppUsage
    @NonNull
    public AudioFormat getAudioFormat() {
        return mAudioFormat;
    }

    /**
     * This stream starts with the audio bytes used for hotword detection, but continues streaming
     * the audio until the stream is shutdown by the {@link HotwordDetectionService}.
     */
    @UnsupportedAppUsage
    @NonNull
    public ParcelFileDescriptor getAudioStreamParcelFileDescriptor() {
        return mAudioStreamParcelFileDescriptor;
    }

    /**
     * The timestamp when the audio stream was captured by the Audio platform.
     *
     * <p>
     * The {@link HotwordDetectionService} egressing the audio is the owner of the underlying
     * AudioRecord. The {@link HotwordDetectionService} is expected to optionally populate this
     * field by {@link AudioRecord#getTimestamp}.
     * </p>
     *
     * <p>
     * This timestamp can be used in conjunction with the
     * {@link HotwordDetectedResult#getHotwordOffsetMillis()} and
     * {@link HotwordDetectedResult#getHotwordDurationMillis()} to translate these durations to
     * timestamps.
     * </p>
     *
     * @see #getAudioStreamParcelFileDescriptor()
     */
    @UnsupportedAppUsage
    @Nullable
    public AudioTimestamp getTimestamp() {
        return mTimestamp;
    }

    /**
     * The metadata associated with the audio stream.
     */
    @UnsupportedAppUsage
    @NonNull
    public PersistableBundle getMetadata() {
        return mMetadata;
    }

    @Override
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "HotwordAudioStream { "
                + "audioFormat = " + mAudioFormat + ", "
                + "audioStreamParcelFileDescriptor = " + mAudioStreamParcelFileDescriptor + ", "
                + "timestamp = " + timestampToString() + ", "
                + "metadata = " + mMetadata + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(HotwordAudioStream other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        HotwordAudioStream that = (HotwordAudioStream) o;
        //noinspection PointlessBooleanExpression
        return Objects.equals(mAudioFormat, that.mAudioFormat)
                && Objects.equals(mAudioStreamParcelFileDescriptor,
                that.mAudioStreamParcelFileDescriptor)
                && Objects.equals(mTimestamp, that.mTimestamp)
                && Objects.equals(mMetadata, that.mMetadata);
    }

    @Override
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mAudioFormat);
        _hash = 31 * _hash + Objects.hashCode(mAudioStreamParcelFileDescriptor);
        _hash = 31 * _hash + Objects.hashCode(mTimestamp);
        _hash = 31 * _hash + Objects.hashCode(mMetadata);
        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mTimestamp != null) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeTypedObject(mAudioFormat, flags);
        dest.writeTypedObject(mAudioStreamParcelFileDescriptor, flags);
        parcelTimestamp(dest, flags);
        dest.writeTypedObject(mMetadata, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */
    HotwordAudioStream(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        AudioFormat audioFormat = (AudioFormat) in.readTypedObject(AudioFormat.CREATOR);
        ParcelFileDescriptor audioStreamParcelFileDescriptor =
                (ParcelFileDescriptor) in.readTypedObject(ParcelFileDescriptor.CREATOR);
        AudioTimestamp timestamp = unparcelTimestamp(in);
        PersistableBundle metadata = (PersistableBundle) in.readTypedObject(
                PersistableBundle.CREATOR);

        this.mAudioFormat = audioFormat;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioFormat);
        this.mAudioStreamParcelFileDescriptor = audioStreamParcelFileDescriptor;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioStreamParcelFileDescriptor);
        this.mTimestamp = timestamp;
        this.mMetadata = metadata;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mMetadata);

        // onConstructed(); // You can define this method to get a callback
    }

    @NonNull
    public static final Parcelable.Creator<HotwordAudioStream> CREATOR =
            new Parcelable.Creator<HotwordAudioStream>() {
                @Override
                public HotwordAudioStream[] newArray(int size) {
                    return new HotwordAudioStream[size];
                }

                @Override
                public HotwordAudioStream createFromParcel(@NonNull Parcel in) {
                    return new HotwordAudioStream(in);
                }
            };

    /**
     * A builder for {@link HotwordAudioStream}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        @NonNull
        private AudioFormat mAudioFormat;
        @NonNull
        private ParcelFileDescriptor mAudioStreamParcelFileDescriptor;
        @Nullable
        private AudioTimestamp mTimestamp;
        @NonNull
        private PersistableBundle mMetadata;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param audioFormat                     The {@link AudioFormat} of the audio stream.
         * @param audioStreamParcelFileDescriptor This stream starts with the audio bytes used for
         *                                        hotword detection, but continues streaming
         *                                        the audio until the stream is shutdown by the
         *                                        {@link HotwordDetectionService}.
         */
        @UnsupportedAppUsage
        public Builder(
                @NonNull AudioFormat audioFormat,
                @NonNull ParcelFileDescriptor audioStreamParcelFileDescriptor) {
            mAudioFormat = audioFormat;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAudioFormat);
            mAudioStreamParcelFileDescriptor = audioStreamParcelFileDescriptor;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAudioStreamParcelFileDescriptor);
        }

        /**
         * The {@link AudioFormat} of the audio stream.
         */
        @UnsupportedAppUsage
        @NonNull
        public Builder setAudioFormat(@NonNull AudioFormat value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mAudioFormat = value;
            return this;
        }

        /**
         * This stream starts with the audio bytes used for hotword detection, but continues
         * streaming
         * the audio until the stream is shutdown by the {@link HotwordDetectionService}.
         */
        @UnsupportedAppUsage
        @NonNull
        public Builder setAudioStreamParcelFileDescriptor(@NonNull ParcelFileDescriptor value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mAudioStreamParcelFileDescriptor = value;
            return this;
        }

        /**
         * The timestamp when the audio stream was captured by the Audio platform.
         *
         * <p>
         * The {@link HotwordDetectionService} egressing the audio is the owner of the underlying
         * AudioRecord. The {@link HotwordDetectionService} is expected to optionally populate this
         * field by {@link AudioRecord#getTimestamp}.
         * </p>
         *
         * <p>
         * This timestamp can be used in conjunction with the
         * {@link HotwordDetectedResult#getHotwordOffsetMillis()} and
         * {@link HotwordDetectedResult#getHotwordDurationMillis()} to translate these durations to
         * timestamps.
         * </p>
         *
         * @see #getAudioStreamParcelFileDescriptor()
         */
        @UnsupportedAppUsage
        @NonNull
        public Builder setTimestamp(@NonNull AudioTimestamp value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mTimestamp = value;
            return this;
        }

        /**
         * The metadata associated with the audio stream.
         */
        @UnsupportedAppUsage
        @NonNull
        public Builder setMetadata(@NonNull PersistableBundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mMetadata = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        @UnsupportedAppUsage
        @NonNull
        public HotwordAudioStream build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10; // Mark builder used

            if ((mBuilderFieldsSet & 0x4) == 0) {
                mTimestamp = defaultTimestamp();
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mMetadata = defaultMetadata();
            }
            HotwordAudioStream o = new HotwordAudioStream(
                    mAudioFormat,
                    mAudioStreamParcelFileDescriptor,
                    mTimestamp,
                    mMetadata);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x10) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
