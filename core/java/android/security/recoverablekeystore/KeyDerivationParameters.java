/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.security.recoverablekeystore;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Collection of parameters which define a key derivation function.
 * Supports
 *
 * <ul>
 * <li>SHA256
 * <li>Argon2id
 * </ul>
 * @hide
 */
public final class KeyDerivationParameters implements Parcelable {
    private final int mAlgorithm;
    private byte[] mSalt;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ALGORITHM_SHA256, ALGORITHM_ARGON2ID})
    public @interface KeyDerivationAlgorithm {
    }

    /**
     * Salted SHA256
     */
    public static final int ALGORITHM_SHA256 = 1;

    /**
     * Argon2ID
     */
    // TODO: add Argon2ID support.
    public static final int ALGORITHM_ARGON2ID = 2;

    /**
     * Creates instance of the class to to derive key using salted SHA256 hash.
     */
    public static KeyDerivationParameters createSHA256Parameters(@NonNull byte[] salt) {
        return new KeyDerivationParameters(ALGORITHM_SHA256, salt);
    }

    private KeyDerivationParameters(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt) {
        mAlgorithm = algorithm;
        mSalt = Preconditions.checkNotNull(salt);
    }

    /**
     * Gets algorithm.
     */
    public @KeyDerivationAlgorithm int getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Gets salt.
     */
    public @NonNull byte[] getSalt() {
        return mSalt;
    }

    public static final Parcelable.Creator<KeyDerivationParameters> CREATOR =
            new Parcelable.Creator<KeyDerivationParameters>() {
        public KeyDerivationParameters createFromParcel(Parcel in) {
                return new KeyDerivationParameters(in);
        }

        public KeyDerivationParameters[] newArray(int length) {
            return new KeyDerivationParameters[length];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAlgorithm);
        out.writeByteArray(mSalt);
    }

    protected KeyDerivationParameters(Parcel in) {
        mAlgorithm = in.readInt();
        mSalt = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
