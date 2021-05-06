/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.service.voice.HotwordDetector.CONFIDENCE_LEVEL_NONE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.MediaSyncEvent;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.internal.util.DataClass;

/**
 * Represents a result supporting the hotword detection.
 *
 * @hide
 */
@DataClass(
        genConstructor = false,
        genBuilder = true,
        genEqualsHashCode = true,
        genHiddenConstDefs = true,
        genParcelable = true,
        genToString = true
)
@SystemApi
public final class HotwordDetectedResult implements Parcelable {

    /** Represents unset value for byte offset. */
    public static final int BYTE_OFFSET_UNSET = -1;

    /** Confidence level in the trigger outcome. */
    @HotwordDetector.HotwordConfidenceLevelValue
    private final int mConfidenceLevel;
    private static int defaultConfidenceLevel() {
        return CONFIDENCE_LEVEL_NONE;
    }

    /**
     * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
     * that contains the hotword trigger. This must be obtained using
     * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
     */
    @Nullable
    private MediaSyncEvent mMediaSyncEvent = null;

    /**
     * Byte offset in the audio stream when the trigger event happened.
     */
    private final int mByteOffset;
    private static int defaultByteOffset() {
        return BYTE_OFFSET_UNSET;
    }

    /**
     * Score for the hotword trigger.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    private final int mScore;
    private static int defaultScore() {
        return 0;
    }

    /**
     * Score for the hotword trigger for device user.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    private final int mPersonalizedScore;
    private static int defaultPersonalizedScore() {
        return 0;
    }

    /**
     * Returns the maximum values of {@link #getScore} and {@link #getPersonalizedScore}.
     * <p>
     * The float value should be calculated as {@code getScore() / getMaxScore()}.
     */
    public static int getMaxScore() {
        return 255;
    }

    /**
     * An ID representing the keyphrase that triggered the successful detection.
     *
     * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
     */
    private final int mHotwordPhraseId;
    private static int defaultHotwordPhraseId() {
        return 0;
    }

    /**
     * Returns the maximum value of {@link #getHotwordPhraseId()}.
     */
    public static int getMaxHotwordPhraseId() {
        return 63;
    }

    /**
     * App-specific extras to support trigger.
     *
     * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
     * bundles will be rejected.
     *
     * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
     * bundle.
     *
     * <p>The use of this method is discouraged, and support for it will be removed in future
     * versions of Android.
     *
     * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
     * that can be used to communicate with other processes.
     */
    @NonNull
    private final PersistableBundle mExtras;
    private static PersistableBundle defaultExtras() {
        return new PersistableBundle();
    }

    /**
     * Returns the maximum byte size of the information contained in the bundle.
     *
     * <p>The total size will be calculated as a sum of byte sizes over all bundle keys.
     *
     * <p>For example, for a bundle containing a single key: {@code "example_key" -> 42.0f}, the
     * bundle size will be {@code 11 + Float.BYTES = 15} bytes.
     */
    public static int getMaxBundleSize() {
        return 50;
    }

    /**
     * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
     * that contains the hotword trigger. This must be obtained using
     * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
     * <p>
     * This can be {@code null} if reprocessing the hotword trigger isn't required.
     */
    // Suppress codegen to make javadoc consistent. Getter returns @Nullable, setter accepts
    // @NonNull only, and by default codegen would use the same javadoc on both.
    public @Nullable MediaSyncEvent getMediaSyncEvent() {
        return mMediaSyncEvent;
    }


    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/service/voice/HotwordDetectedResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ HotwordDetectedResult(
            @HotwordDetector.HotwordConfidenceLevelValue int confidenceLevel,
            @Nullable MediaSyncEvent mediaSyncEvent,
            int byteOffset,
            int score,
            int personalizedScore,
            int hotwordPhraseId,
            @NonNull PersistableBundle extras) {
        this.mConfidenceLevel = confidenceLevel;
        com.android.internal.util.AnnotationValidations.validate(
                HotwordDetector.HotwordConfidenceLevelValue.class, null, mConfidenceLevel);
        this.mMediaSyncEvent = mediaSyncEvent;
        this.mByteOffset = byteOffset;
        this.mScore = score;
        this.mPersonalizedScore = personalizedScore;
        this.mHotwordPhraseId = hotwordPhraseId;
        this.mExtras = extras;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mExtras);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Confidence level in the trigger outcome.
     */
    @DataClass.Generated.Member
    public @HotwordDetector.HotwordConfidenceLevelValue int getConfidenceLevel() {
        return mConfidenceLevel;
    }

    /**
     * Byte offset in the audio stream when the trigger event happened.
     */
    @DataClass.Generated.Member
    public int getByteOffset() {
        return mByteOffset;
    }

    /**
     * Score for the hotword trigger.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getScore() {
        return mScore;
    }

    /**
     * Score for the hotword trigger for device user.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getPersonalizedScore() {
        return mPersonalizedScore;
    }

    /**
     * An ID representing the keyphrase that triggered the successful detection.
     *
     * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getHotwordPhraseId() {
        return mHotwordPhraseId;
    }

    /**
     * App-specific extras to support trigger.
     *
     * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
     * bundles will be rejected.
     *
     * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
     * bundle.
     *
     * <p>The use of this method is discouraged, and support for it will be removed in future
     * versions of Android.
     *
     * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
     * that can be used to communicate with other processes.
     */
    @DataClass.Generated.Member
    public @NonNull PersistableBundle getExtras() {
        return mExtras;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "HotwordDetectedResult { " +
                "confidenceLevel = " + mConfidenceLevel + ", " +
                "mediaSyncEvent = " + mMediaSyncEvent + ", " +
                "byteOffset = " + mByteOffset + ", " +
                "score = " + mScore + ", " +
                "personalizedScore = " + mPersonalizedScore + ", " +
                "hotwordPhraseId = " + mHotwordPhraseId + ", " +
                "extras = " + mExtras +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(HotwordDetectedResult other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        HotwordDetectedResult that = (HotwordDetectedResult) o;
        //noinspection PointlessBooleanExpression
        return true
                && mConfidenceLevel == that.mConfidenceLevel
                && java.util.Objects.equals(mMediaSyncEvent, that.mMediaSyncEvent)
                && mByteOffset == that.mByteOffset
                && mScore == that.mScore
                && mPersonalizedScore == that.mPersonalizedScore
                && mHotwordPhraseId == that.mHotwordPhraseId
                && java.util.Objects.equals(mExtras, that.mExtras);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mConfidenceLevel;
        _hash = 31 * _hash + java.util.Objects.hashCode(mMediaSyncEvent);
        _hash = 31 * _hash + mByteOffset;
        _hash = 31 * _hash + mScore;
        _hash = 31 * _hash + mPersonalizedScore;
        _hash = 31 * _hash + mHotwordPhraseId;
        _hash = 31 * _hash + java.util.Objects.hashCode(mExtras);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mMediaSyncEvent != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeInt(mConfidenceLevel);
        if (mMediaSyncEvent != null) dest.writeTypedObject(mMediaSyncEvent, flags);
        dest.writeInt(mByteOffset);
        dest.writeInt(mScore);
        dest.writeInt(mPersonalizedScore);
        dest.writeInt(mHotwordPhraseId);
        dest.writeTypedObject(mExtras, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ HotwordDetectedResult(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int confidenceLevel = in.readInt();
        MediaSyncEvent mediaSyncEvent = (flg & 0x2) == 0 ? null : (MediaSyncEvent) in.readTypedObject(MediaSyncEvent.CREATOR);
        int byteOffset = in.readInt();
        int score = in.readInt();
        int personalizedScore = in.readInt();
        int hotwordPhraseId = in.readInt();
        PersistableBundle extras = (PersistableBundle) in.readTypedObject(PersistableBundle.CREATOR);

        this.mConfidenceLevel = confidenceLevel;
        com.android.internal.util.AnnotationValidations.validate(
                HotwordDetector.HotwordConfidenceLevelValue.class, null, mConfidenceLevel);
        this.mMediaSyncEvent = mediaSyncEvent;
        this.mByteOffset = byteOffset;
        this.mScore = score;
        this.mPersonalizedScore = personalizedScore;
        this.mHotwordPhraseId = hotwordPhraseId;
        this.mExtras = extras;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mExtras);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<HotwordDetectedResult> CREATOR
            = new Parcelable.Creator<HotwordDetectedResult>() {
        @Override
        public HotwordDetectedResult[] newArray(int size) {
            return new HotwordDetectedResult[size];
        }

        @Override
        public HotwordDetectedResult createFromParcel(@NonNull android.os.Parcel in) {
            return new HotwordDetectedResult(in);
        }
    };

    /**
     * A builder for {@link HotwordDetectedResult}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private @HotwordDetector.HotwordConfidenceLevelValue int mConfidenceLevel;
        private @Nullable MediaSyncEvent mMediaSyncEvent;
        private int mByteOffset;
        private int mScore;
        private int mPersonalizedScore;
        private int mHotwordPhraseId;
        private @NonNull PersistableBundle mExtras;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Confidence level in the trigger outcome.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setConfidenceLevel(@HotwordDetector.HotwordConfidenceLevelValue int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mConfidenceLevel = value;
            return this;
        }

        /**
         * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
         * that contains the hotword trigger. This must be obtained using
         * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setMediaSyncEvent(@NonNull MediaSyncEvent value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mMediaSyncEvent = value;
            return this;
        }

        /**
         * Byte offset in the audio stream when the trigger event happened.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setByteOffset(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mByteOffset = value;
            return this;
        }

        /**
         * Score for the hotword trigger.
         *
         * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setScore(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mScore = value;
            return this;
        }

        /**
         * Score for the hotword trigger for device user.
         *
         * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setPersonalizedScore(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mPersonalizedScore = value;
            return this;
        }

        /**
         * An ID representing the keyphrase that triggered the successful detection.
         *
         * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setHotwordPhraseId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mHotwordPhraseId = value;
            return this;
        }

        /**
         * App-specific extras to support trigger.
         *
         * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
         * bundles will be rejected.
         *
         * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
         * bundle.
         *
         * <p>The use of this method is discouraged, and support for it will be removed in future
         * versions of Android.
         *
         * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
         * that can be used to communicate with other processes.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setExtras(@NonNull PersistableBundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mExtras = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull HotwordDetectedResult build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mConfidenceLevel = defaultConfidenceLevel();
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mMediaSyncEvent = null;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mByteOffset = defaultByteOffset();
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mScore = defaultScore();
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mPersonalizedScore = defaultPersonalizedScore();
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mHotwordPhraseId = defaultHotwordPhraseId();
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mExtras = defaultExtras();
            }
            HotwordDetectedResult o = new HotwordDetectedResult(
                    mConfidenceLevel,
                    mMediaSyncEvent,
                    mByteOffset,
                    mScore,
                    mPersonalizedScore,
                    mHotwordPhraseId,
                    mExtras);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x80) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1620133603958L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/service/voice/HotwordDetectedResult.java",
            inputSignatures = "public static final  int BYTE_OFFSET_UNSET\nprivate final @android.service.voice.HotwordDetector.HotwordConfidenceLevelValue int mConfidenceLevel\nprivate @android.annotation.Nullable android.media.MediaSyncEvent mMediaSyncEvent\nprivate final  int mByteOffset\nprivate final  int mScore\nprivate final  int mPersonalizedScore\nprivate final  int mHotwordPhraseId\nprivate final @android.annotation.NonNull android.os.PersistableBundle mExtras\nprivate static  int defaultConfidenceLevel()\nprivate static  int defaultByteOffset()\nprivate static  int defaultScore()\nprivate static  int defaultPersonalizedScore()\npublic static  int getMaxScore()\nprivate static  int defaultHotwordPhraseId()\npublic static  int getMaxHotwordPhraseId()\nprivate static  android.os.PersistableBundle defaultExtras()\npublic static  int getMaxBundleSize()\npublic @android.annotation.Nullable android.media.MediaSyncEvent getMediaSyncEvent()\nclass HotwordDetectedResult extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=true, genEqualsHashCode=true, genHiddenConstDefs=true, genParcelable=true, genToString=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
