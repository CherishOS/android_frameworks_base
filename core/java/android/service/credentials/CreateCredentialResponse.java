/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Response to a {@link CreateCredentialRequest}.
 *
 * @hide
 */
public final class CreateCredentialResponse implements Parcelable {
    private final @NonNull List<SaveEntry> mSaveEntries;
    private final @Nullable Action mRemoteSaveEntry;
    //TODO : Add actions if needed

    private CreateCredentialResponse(@NonNull Parcel in) {
        List<SaveEntry> saveEntries = new ArrayList<>();
        in.readTypedList(saveEntries, SaveEntry.CREATOR);
        mSaveEntries = saveEntries;
        mRemoteSaveEntry = in.readTypedObject(Action.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mSaveEntries);
        dest.writeTypedObject(mRemoteSaveEntry, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<CreateCredentialResponse> CREATOR =
            new Creator<CreateCredentialResponse>() {
                @Override
                public CreateCredentialResponse createFromParcel(@NonNull Parcel in) {
                    return new CreateCredentialResponse(in);
                }

                @Override
                public CreateCredentialResponse[] newArray(int size) {
                    return new CreateCredentialResponse[size];
                }
            };

    /* package-private */ CreateCredentialResponse(
            @NonNull List<SaveEntry> saveEntries,
            @Nullable Action remoteSaveEntry) {
        this.mSaveEntries = saveEntries;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSaveEntries);
        this.mRemoteSaveEntry = remoteSaveEntry;
    }

    /** Returns the list of save entries to be displayed on the UI. */
    public @NonNull List<SaveEntry> getSaveEntries() {
        return mSaveEntries;
    }

    /** Returns the remote save entry to be displayed on the UI. */
    public @NonNull Action getRemoteSaveEntry() {
        return mRemoteSaveEntry;
    }

    /**
     * A builder for {@link CreateCredentialResponse}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private @NonNull List<SaveEntry> mSaveEntries = new ArrayList<>();
        private @Nullable Action mRemoteSaveEntry;

        /**
         * Sets the list of save entries to be shown on the UI.
         *
         * @throws IllegalArgumentException If {@code saveEntries} is empty.
         * @throws NullPointerException If {@code saveEntries} is null, or any of its elements
         * are null.
         */
        public @NonNull Builder setSaveEntries(@NonNull List<SaveEntry> saveEntries) {
            Preconditions.checkCollectionNotEmpty(saveEntries, "saveEntries");
            mSaveEntries = Preconditions.checkCollectionElementsNotNull(
                    saveEntries, "saveEntries");
            return this;
        }

        /**
         * Adds an entry to the list of save entries to be shown on the UI.
         *
         * @throws NullPointerException If {@code saveEntry} is null.
         */
        public @NonNull Builder addSaveEntry(@NonNull SaveEntry saveEntry) {
            mSaveEntries.add(Objects.requireNonNull(saveEntry));
            return this;
        }

        /**
         * Sets a remote save entry to be shown on the UI.
         */
        public @NonNull Builder setRemoteSaveEntry(@Nullable Action remoteSaveEntry) {
            mRemoteSaveEntry = remoteSaveEntry;
            return this;
        }

        /**
         * Builds the instance.
         *
         * @throws IllegalArgumentException If {@code saveEntries} is empty.
         */
        public @NonNull CreateCredentialResponse build() {
            Preconditions.checkCollectionNotEmpty(mSaveEntries, "saveEntries must "
                    + "not be empty");
            return new CreateCredentialResponse(
                    mSaveEntries,
                    mRemoteSaveEntry);
        }
    }
}
