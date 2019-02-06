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
package android.telephony.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * The base class for events that can happen on {@link RcsParticipant}s and {@link RcsThread}s.
 * @hide - TODO(109759350) make this public
 */
public abstract class RcsEvent implements Parcelable {
    protected long mTimestamp;

    protected RcsEvent(long timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * @return Returns the time of when this event happened. The timestamp is defined as
     * milliseconds passed after midnight, January 1, 1970 UTC
     */
    public long getTimestamp() {
        return mTimestamp;
    }

    /**
     * Persists the event to the data store
     *
     * @hide
     */
    abstract void persist() throws RcsMessageStoreException;

    RcsEvent(Parcel in) {
        mTimestamp = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTimestamp);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
