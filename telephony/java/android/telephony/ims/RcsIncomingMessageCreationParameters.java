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

package android.telephony.ims;

import android.annotation.CheckResult;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@link RcsIncomingMessageCreationParameters} is a collection of parameters that should be passed
 * into {@link RcsThread#addIncomingMessage(RcsIncomingMessageCreationParameters)} to generate an
 * {@link RcsIncomingMessage} on that {@link RcsThread}
 *
 * @hide TODO:make public
 */
public class RcsIncomingMessageCreationParameters extends RcsMessageCreationParameters implements
        Parcelable {
    // The arrival timestamp for the RcsIncomingMessage to be created
    private final long mArrivalTimestamp;
    // The seen timestamp for the RcsIncomingMessage to be created
    private final long mSeenTimestamp;
    // The participant that sent this incoming message
    private final int mSenderParticipantId;

    /**
     * Builder to help create an {@link RcsIncomingMessageCreationParameters}
     *
     * @see RcsThread#addIncomingMessage(RcsIncomingMessageCreationParameters)
     */
    public static class Builder extends RcsMessageCreationParameters.Builder {
        private RcsParticipant mSenderParticipant;
        private long mArrivalTimestamp;
        private long mSeenTimestamp;

        /**
         * Creates a {@link Builder} to create an instance of
         * {@link RcsIncomingMessageCreationParameters}
         *
         * @param originationTimestamp The timestamp of {@link RcsMessage} creation. The origination
         *                             timestamp value in milliseconds passed after midnight,
         *                             January 1, 1970 UTC
         * @param arrivalTimestamp The timestamp of arrival, defined as milliseconds passed after
         *                         midnight, January 1, 1970 UTC
         * @param subscriptionId The subscription ID that was used to send or receive this
         *                       {@link RcsMessage}
         */
        public Builder(long originationTimestamp, long arrivalTimestamp, int subscriptionId) {
            super(originationTimestamp, subscriptionId);
            mArrivalTimestamp = arrivalTimestamp;
        }

        /**
         * Sets the {@link RcsParticipant} that send this {@link RcsIncomingMessage}
         *
         * @param senderParticipant The {@link RcsParticipant} that sent this
         * {@link RcsIncomingMessage}
         * @return The same instance of {@link Builder} to chain methods.
         */
        @CheckResult
        public Builder setSenderParticipant(RcsParticipant senderParticipant) {
            mSenderParticipant = senderParticipant;
            return this;
        }

        /**
         * Sets the time of the arrival of this {@link RcsIncomingMessage}

         * @return The same instance of {@link Builder} to chain methods.
         * @see RcsIncomingMessage#setArrivalTimestamp(long)
         */
        @CheckResult
        public Builder setArrivalTimestamp(long arrivalTimestamp) {
            mArrivalTimestamp = arrivalTimestamp;
            return this;
        }

        /**
         * Sets the time of the when this user saw the {@link RcsIncomingMessage}
         * @param seenTimestamp The seen timestamp , defined as milliseconds passed after midnight,
         *                      January 1, 1970 UTC
         * @return The same instance of {@link Builder} to chain methods.
         * @see RcsIncomingMessage#setSeenTimestamp(long)
         */
        @CheckResult
        public Builder setSeenTimestamp(long seenTimestamp) {
            mSeenTimestamp = seenTimestamp;
            return this;
        }

        /**
         * Creates parameters for creating a new incoming message.
         * @return A new instance of {@link RcsIncomingMessageCreationParameters} to create a new
         * {@link RcsIncomingMessage}
         */
        public RcsIncomingMessageCreationParameters build() {
            return new RcsIncomingMessageCreationParameters(this);
        }
    }

    private RcsIncomingMessageCreationParameters(Builder builder) {
        super(builder);
        mArrivalTimestamp = builder.mArrivalTimestamp;
        mSeenTimestamp = builder.mSeenTimestamp;
        mSenderParticipantId = builder.mSenderParticipant.getId();
    }

    protected RcsIncomingMessageCreationParameters(Parcel in) {
        super(in);
        mArrivalTimestamp = in.readLong();
        mSeenTimestamp = in.readLong();
        mSenderParticipantId = in.readInt();
    }

    /**
     * @return Returns the arrival timestamp for the {@link RcsIncomingMessage} to be created.
     * Timestamp is defined as milliseconds passed after midnight, January 1, 1970 UTC
     */
    public long getArrivalTimestamp() {
        return mArrivalTimestamp;
    }

    /**
     * @return Returns the seen timestamp for the {@link RcsIncomingMessage} to be created.
     * Timestamp is defined as milliseconds passed after midnight, January 1, 1970 UTC
     */
    public long getSeenTimestamp() {
        return mSeenTimestamp;
    }

    /**
     * Helper getter for {@link com.android.internal.telephony.ims.RcsMessageStoreController} to
     * create {@link RcsIncomingMessage}s
     *
     * Since the API doesn't expose any ID's to API users, this should be hidden.
     * @hide
     */
    public int getSenderParticipantId() {
        return mSenderParticipantId;
    }

    public static final Creator<RcsIncomingMessageCreationParameters> CREATOR =
            new Creator<RcsIncomingMessageCreationParameters>() {
                @Override
                public RcsIncomingMessageCreationParameters createFromParcel(Parcel in) {
                    return new RcsIncomingMessageCreationParameters(in);
                }

                @Override
                public RcsIncomingMessageCreationParameters[] newArray(int size) {
                    return new RcsIncomingMessageCreationParameters[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeLong(mArrivalTimestamp);
        dest.writeLong(mSeenTimestamp);
        dest.writeInt(mSenderParticipantId);
    }
}
