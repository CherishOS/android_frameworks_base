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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
public abstract class BroadcastInfoRequest implements Parcelable {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({REQUEST_OPTION_REPEAT, REQUEST_OPTION_AUTO_UPDATE})
    public @interface RequestOption {}

    public static final int REQUEST_OPTION_REPEAT = 0;
    public static final int REQUEST_OPTION_AUTO_UPDATE = 1;

    public static final @NonNull Parcelable.Creator<BroadcastInfoRequest> CREATOR =
            new Parcelable.Creator<BroadcastInfoRequest>() {
                @Override
                public BroadcastInfoRequest createFromParcel(Parcel source) {
                    @TvInputManager.BroadcastInfoType int type = source.readInt();
                    switch (type) {
                        case TvInputManager.BROADCAST_INFO_TYPE_TS:
                            return TsRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_SECTION:
                            return SectionRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_PES:
                            return PesRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_STREAM_EVENT:
                            return StreamEventRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_DSMCC:
                            return DsmccRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_COMMAND:
                            return CommandRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TIMELINE:
                            return TimelineRequest.createFromParcelBody(source);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected broadcast info request type (value "
                                            + type + ") in parcel.");
                    }
                }

                @Override
                public BroadcastInfoRequest[] newArray(int size) {
                    return new BroadcastInfoRequest[size];
                }
            };

    protected final @TvInputManager.BroadcastInfoType int mType;
    protected final int mRequestId;
    protected final @RequestOption int mOption;

    protected BroadcastInfoRequest(@TvInputManager.BroadcastInfoType int type,
            int requestId, @RequestOption int option) {
        mType = type;
        mRequestId = requestId;
        mOption = option;
    }

    protected BroadcastInfoRequest(@TvInputManager.BroadcastInfoType int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mOption = source.readInt();
    }

    public @TvInputManager.BroadcastInfoType int getType() {
        return mType;
    }

    public int getRequestId() {
        return mRequestId;
    }

    public @RequestOption int getOption() {
        return mOption;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mRequestId);
        dest.writeInt(mOption);
    }
}
