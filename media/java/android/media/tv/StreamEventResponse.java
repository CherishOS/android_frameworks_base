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

package android.media.tv;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class StreamEventResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_STREAM_EVENT;

    public static final @NonNull Parcelable.Creator<StreamEventResponse> CREATOR =
            new Parcelable.Creator<StreamEventResponse>() {
                @Override
                public StreamEventResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public StreamEventResponse[] newArray(int size) {
                    return new StreamEventResponse[size];
                }
            };

    private final int mEventId;
    private final long mNpt;
    private final byte[] mData;

    public static StreamEventResponse createFromParcelBody(Parcel in) {
        return new StreamEventResponse(in);
    }

    public StreamEventResponse(int requestId, int sequence, @ResponseResult int responseResult,
            int eventId, long npt, @NonNull byte[] data) {
        super(responseType, requestId, sequence, responseResult);
        mEventId = eventId;
        mNpt = npt;
        mData = data;
    }

    private StreamEventResponse(@NonNull Parcel source) {
        super(responseType, source);
        mEventId = source.readInt();
        mNpt = source.readLong();
        int dataLength = source.readInt();
        mData = new byte[dataLength];
        source.readByteArray(mData);
    }

    /** Returns the event ID */
    public int getEventId() {
        return mEventId;
    }

    /** Returns the NPT(Normal Play Time) value when the event occurred or will occur */
    public long getNpt() {
        return mNpt;
    }

    /** Returns the application specific data */
    @NonNull
    public byte[] getData() {
        return mData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mEventId);
        dest.writeLong(mNpt);
        dest.writeInt(mData.length);
        dest.writeByteArray(mData);
    }
}
