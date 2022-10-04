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

import android.annotation.Nullable;

/** Exception class for CredentialManager operations. */
public class CredentialManagerException extends Exception {
    /** Indicates that an unknown error was encountered. */
    public static final int ERROR_UNKNOWN = 0;

    /**
     * The given CredentialManager operation is cancelled by the user.
     *
     * @hide
     */
    public static final int ERROR_USER_CANCELLED = 1;

    /**
     * No appropriate provider is found to support the target credential type(s).
     *
     * @hide
     */
    public static final int ERROR_PROVIDER_NOT_FOUND = 2;

    public final int errorCode;

    public CredentialManagerException(int errorCode, @Nullable String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CredentialManagerException(
            int errorCode, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public CredentialManagerException(int errorCode, @Nullable Throwable cause) {
        super(cause);
        this.errorCode = errorCode;
    }

    public CredentialManagerException(int errorCode) {
        super();
        this.errorCode = errorCode;
    }
}
