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

import android.annotation.Nullable;
import android.annotation.WorkerThread;

/**
 * RcsParticipant is an RCS capable contact that can participate in {@link RcsThread}s.
 *
 * @hide - TODO(109759350) make this public
 */
public class RcsParticipant {
    // The row ID of this participant in the database
    private int mId;

    /**
     * Constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create instances of participants. This is not meant to be part of the SDK.
     *
     * @hide
     */
    public RcsParticipant(int id) {
        mId = id;
    }

    /**
     * @return Returns the canonical address (i.e. normalized phone number) for this
     * {@link RcsParticipant}
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public String getCanonicalAddress() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getRcsParticipantCanonicalAddress(mId));
    }

    /**
     * @return Returns the alias for this {@link RcsParticipant}. Alias is usually the real name of
     * the person themselves. Please see US5-15 - GSMA RCC.71 (RCS Universal Profile Service
     * Definition Document)
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public String getAlias() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getRcsParticipantAlias(mId));
    }

    /**
     * Sets the alias for this {@link RcsParticipant} and persists it in storage. Alias is usually
     * the real name of the person themselves. Please see US5-15 - GSMA RCC.71 (RCS Universal
     * Profile Service Definition Document)
     *
     * @param alias The alias to set to.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setAlias(String alias) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setRcsParticipantAlias(mId, alias));
    }

    /**
     * @return Returns the contact ID for this {@link RcsParticipant}. Contact ID is a unique ID for
     * an {@link RcsParticipant} that is RCS provisioned. Please see 4.4.5 - GSMA RCC.53 (RCS Device
     * API 1.6 Specification)
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public String getContactId() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getRcsParticipantContactId(mId));
    }

    /**
     * Sets the contact ID for this {@link RcsParticipant}. Contact ID is a unique ID for
     * an {@link RcsParticipant} that is RCS provisioned. Please see 4.4.5 - GSMA RCC.53 (RCS Device
     * API 1.6 Specification)
     *
     * @param contactId The contact ID to set to.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setContactId(String contactId) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setRcsParticipantContactId(mId, contactId));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RcsParticipant)) {
            return false;
        }
        RcsParticipant other = (RcsParticipant) obj;

        return mId == other.mId;
    }

    @Override
    public int hashCode() {
        return mId;
    }

    /**
     * Returns the row id of this participant. This is not meant to be part of the SDK
     *
     * @hide
     */
    public int getId() {
        return mId;
    }
}
