/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch.exceptions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;

/**
 * An exception thrown by {@link android.app.appsearch.AppSearchSession} or a subcomponent.
 *
 * <p>These exceptions can be converted into a failed {@link AppSearchResult}
 * for propagating to the client.
 * @hide
 */
public class AppSearchException extends Exception {
    private final @AppSearchResult.ResultCode int mResultCode;

    /**
     * Initializes an {@link AppSearchException} with no message.
     * @hide
     */
    public AppSearchException(@AppSearchResult.ResultCode int resultCode) {
        this(resultCode, /*message=*/ null);
    }

    /** @hide */
    public AppSearchException(
            @AppSearchResult.ResultCode int resultCode, @Nullable String message) {
        this(resultCode, message, /*cause=*/ null);
    }

    /** @hide */
    public AppSearchException(
            @AppSearchResult.ResultCode int resultCode,
            @Nullable String message,
            @Nullable Throwable cause) {
        super(message, cause);
        mResultCode = resultCode;
    }

    /** Returns the result code this exception was constructed with. */
    public @AppSearchResult.ResultCode int getResultCode() {
        return mResultCode;
    }

    /**
     * Converts this {@link java.lang.Exception} into a failed {@link AppSearchResult}
     */
    @NonNull
    public <T> AppSearchResult<T> toAppSearchResult() {
        return AppSearchResult.newFailedResult(mResultCode, getMessage());
    }
}
