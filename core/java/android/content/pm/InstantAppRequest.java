/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.content.Intent;
import android.os.Bundle;

/**
 * Information needed to make an instant application resolution request.
 * @hide
 */
public final class InstantAppRequest {
    /** Response from the first phase of instant application resolution */
    public final AuxiliaryResolveInfo responseObj;
    /** The original intent that triggered instant application resolution */
    public final Intent origIntent;
    /** Resolved type of the intent */
    public final String resolvedType;
    /** The name of the package requesting the instant application */
    public final String callingPackage;
    /** ID of the user requesting the instant application */
    public final int userId;
    /**
     * Optional extra bundle provided by the source application to the installer for additional
     * verification. */
    public final Bundle verificationBundle;

    public InstantAppRequest(AuxiliaryResolveInfo responseObj, Intent origIntent,
            String resolvedType, String callingPackage, int userId, Bundle verificationBundle) {
        this.responseObj = responseObj;
        this.origIntent = origIntent;
        this.resolvedType = resolvedType;
        this.callingPackage = callingPackage;
        this.userId = userId;
        this.verificationBundle = verificationBundle;
    }
}
