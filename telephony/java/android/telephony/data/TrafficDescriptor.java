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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2. It is used for URSP traffic
 * matching as described in 3GPP TS 24.526 Section 4.2.2. It includes an optional DNN, which,
 * if present, must be used for traffic matching; it does not specify the end point to be used for
 * the data call.
 * @hide
 */
@SystemApi
public final class TrafficDescriptor implements Parcelable {
    private final String mDnn;
    private final String mOsAppId;

    private TrafficDescriptor(@NonNull Parcel in) {
        mDnn = in.readString();
        mOsAppId = in.readString();
    }

    /**
     * Create a traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2
     * @param dnn optional DNN, which must be used for traffic matching, if present
     * @param osAppId OsId + osAppId of the traffic descriptor
     */
    public TrafficDescriptor(@Nullable String dnn, @Nullable String osAppId) {
        mDnn = dnn;
        mOsAppId = osAppId;
    }

    /**
     * DNN stands for Data Network Name and represents an APN as defined in 3GPP TS 23.003.
     * @return the DNN of this traffic descriptor.
     */
    public @Nullable String getDnn() {
        return mDnn;
    }

    /**
     * OsAppId represents the OsId + OsAppId as defined in 3GPP TS 24.526 Section 5.2.
     * @return the OS App ID of this traffic descriptor.
     */
    public @Nullable String getOsAppId() {
        return mOsAppId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull @Override
    public String toString() {
        return "TrafficDescriptor={mDnn=" + mDnn + ", mOsAppId=" + mOsAppId + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDnn);
        dest.writeString(mOsAppId);
    }

    public static final @NonNull Parcelable.Creator<TrafficDescriptor> CREATOR =
            new Parcelable.Creator<TrafficDescriptor>() {
                @Override
                public @NonNull TrafficDescriptor createFromParcel(@NonNull Parcel source) {
                    return new TrafficDescriptor(source);
                }

                @Override
                public @NonNull TrafficDescriptor[] newArray(int size) {
                    return new TrafficDescriptor[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrafficDescriptor that = (TrafficDescriptor) o;
        return Objects.equals(mDnn, that.mDnn) && Objects.equals(mOsAppId, that.mOsAppId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDnn, mOsAppId);
    }
}
