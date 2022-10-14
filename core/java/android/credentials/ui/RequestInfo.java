/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Contains information about the request that initiated this UX flow.
 *
 * @hide
 */
public class RequestInfo implements Parcelable {

    /**
     * The intent extra key for the {@code RequestInfo} object when launching the UX
     * activities.
     */
    public static final @NonNull String EXTRA_REQUEST_INFO =
            "android.credentials.ui.extra.REQUEST_INFO";
    /**
     * The intent extra key for the {@code ResultReceiver} object when launching the UX
     * activities.
     */
    public static final @NonNull String EXTRA_RESULT_RECEIVER =
            "android.credentials.ui.extra.RESULT_RECEIVER";

    /** Type value for an executeGetCredential request. */
    public static final @NonNull String TYPE_GET = "android.credentials.ui.TYPE_GET";
    /** Type value for an executeCreateCredential request. */
    public static final @NonNull String TYPE_CREATE = "android.credentials.ui.TYPE_CREATE";

    @NonNull
    private final IBinder mToken;

    @NonNull
    private final String mType;

    private final boolean mIsFirstUsage;

    public RequestInfo(@NonNull IBinder token, @NonNull String type, boolean isFirstUsage) {
        mToken = token;
        mType = type;
        mIsFirstUsage = isFirstUsage;
    }

    /** Returns the request token matching the user request. */
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    /** Returns the request type. */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns whether this is the first Credential Manager usage for this user on the device.
     *
     * If true, the user will be prompted for a provider-centric dialog first to confirm their
     * provider choices.
     */
    public boolean isFirstUsage() {
        return mIsFirstUsage;
    }

    protected RequestInfo(@NonNull Parcel in) {
        IBinder token = in.readStrongBinder();
        String type = in.readString8();
        boolean isFirstUsage = in.readBoolean();

        mToken = token;
        AnnotationValidations.validate(NonNull.class, null, mToken);
        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mIsFirstUsage = isFirstUsage;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeString8(mType);
        dest.writeBoolean(mIsFirstUsage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<RequestInfo> CREATOR = new Creator<RequestInfo>() {
        @Override
        public RequestInfo createFromParcel(@NonNull Parcel in) {
            return new RequestInfo(in);
        }

        @Override
        public RequestInfo[] newArray(int size) {
            return new RequestInfo[size];
        }
    };
}
