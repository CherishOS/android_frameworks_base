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

package android.credentials;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

/**
 * A specific type of credential request.
 */
public final class GetCredentialOption implements Parcelable {

    /**
     * The requested credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The request data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * Determines whether or not the request must only be fulfilled by a system provider.
     */
    private final boolean mRequireSystemProvider;

    /**
     * Returns the requested credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the request data.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    /**
     * Returns true if the request must only be fulfilled by a system provider, and false
     * otherwise.
     */
    public boolean requireSystemProvider() {
        return mRequireSystemProvider;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mData);
        dest.writeBoolean(mRequireSystemProvider);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialOption {"
                + "type=" + mType
                + ", data=" + mData
                + ", requireSystemProvider=" + mRequireSystemProvider
                + "}";
    }

    /**
     * Constructs a {@link GetCredentialOption}.
     *
     * @param type the requested credential type
     * @param data the request data
     * @param requireSystemProvider whether or not the request must only be fulfilled by a system
     *                              provider
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialOption(
            @NonNull String type,
            @NonNull Bundle data,
            boolean requireSystemProvider) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mData = requireNonNull(data, "data must not be null");
        mRequireSystemProvider = requireSystemProvider;
    }

    private GetCredentialOption(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle data = in.readBundle();
        boolean requireSystemProvider = in.readBoolean();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);
        mRequireSystemProvider = requireSystemProvider;
    }

    public static final @NonNull Parcelable.Creator<GetCredentialOption> CREATOR =
            new Parcelable.Creator<GetCredentialOption>() {
        @Override
        public GetCredentialOption[] newArray(int size) {
            return new GetCredentialOption[size];
        }

        @Override
        public GetCredentialOption createFromParcel(@NonNull Parcel in) {
            return new GetCredentialOption(in);
        }
    };
}
