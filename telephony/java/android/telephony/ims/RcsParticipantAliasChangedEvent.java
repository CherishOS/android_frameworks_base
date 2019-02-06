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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;

/**
 * An event that indicates an {@link RcsParticipant}'s alias was changed. Please see US18-2 - GSMA
 * RCC.71 (RCS Universal Profile Service Definition Document)
 *
 * @hide - TODO(109759350) make this public
 */
public class RcsParticipantAliasChangedEvent extends RcsEvent {
    // The ID of the participant that changed their alias
    private int mParticipantId;
    // The new alias of the above participant
    private String mNewAlias;

    /**
     * Creates a new {@link RcsParticipantAliasChangedEvent}. This event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp The timestamp of when this event happened, in milliseconds passed after
     *                  midnight, January 1st, 1970 UTC
     * @param participant The {@link RcsParticipant} that got their alias changed
     * @param newAlias The new alias the {@link RcsParticipant} has.
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsParticipantAliasChangedEvent(long timestamp, @NonNull RcsParticipant participant,
            @Nullable String newAlias) {
        super(timestamp);
        mParticipantId = participant.getId();
        mNewAlias = newAlias;
    }

    /**
     * @hide - internal constructor for queries
     */
    public RcsParticipantAliasChangedEvent(long timestamp, int participantId,
            @Nullable String newAlias) {
        super(timestamp);
        mParticipantId = participantId;
        mNewAlias = newAlias;
    }

    /**
     * @return Returns the {@link RcsParticipant} whose alias was changed.
     */
    @NonNull
    public RcsParticipant getParticipantId() {
        return new RcsParticipant(mParticipantId);
    }

    /**
     * @return Returns the alias of the associated {@link RcsParticipant} after this event happened
     */
    @Nullable
    public String getNewAlias() {
        return mNewAlias;
    }

    /**
     * Persists the event to the data store.
     *
     * @hide - not meant for public use.
     */
    @Override
    public void persist() throws RcsMessageStoreException {
        RcsControllerCall.call(iRcs -> iRcs.createParticipantAliasChangedEvent(
                getTimestamp(), getParticipantId().getId(), getNewAlias()));
    }

    public static final Creator<RcsParticipantAliasChangedEvent> CREATOR =
            new Creator<RcsParticipantAliasChangedEvent>() {
                @Override
                public RcsParticipantAliasChangedEvent createFromParcel(Parcel in) {
                    return new RcsParticipantAliasChangedEvent(in);
                }

                @Override
                public RcsParticipantAliasChangedEvent[] newArray(int size) {
                    return new RcsParticipantAliasChangedEvent[size];
                }
            };

    protected RcsParticipantAliasChangedEvent(Parcel in) {
        super(in);
        mNewAlias = in.readString();
        mParticipantId = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mNewAlias);
        dest.writeInt(mParticipantId);
    }
}
