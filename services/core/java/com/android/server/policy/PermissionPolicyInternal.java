/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;

/**
 * Internal calls into {@link PermissionPolicyService}.
 */
public abstract class PermissionPolicyInternal {

    /**
     * Check whether an activity should be started.
     *
     * @param intent the {@link Intent} for the activity start
     * @param callingUid the calling uid starting the activity
     * @param callingPackage the calling package starting the activity
     *
     * @return whether the activity should be started
     */
    public abstract boolean checkStartActivity(@NonNull Intent intent, int callingUid,
            @Nullable String callingPackage);
}
