/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;

import java.util.Objects;

/**
 * A simple class to pass around context information.
 *
 * @hide
 */
public class VcnContext {
    @NonNull private final Context mContext;
    @NonNull private final Looper mLooper;
    @NonNull private final VcnNetworkProvider mVcnNetworkProvider;

    public VcnContext(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull VcnNetworkProvider vcnNetworkProvider) {
        mContext = Objects.requireNonNull(context, "Missing context");
        mLooper = Objects.requireNonNull(looper, "Missing looper");
        mVcnNetworkProvider = Objects.requireNonNull(vcnNetworkProvider, "Missing networkProvider");
    }

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @NonNull
    public Looper getLooper() {
        return mLooper;
    }

    @NonNull
    public VcnNetworkProvider getVcnNetworkProvider() {
        return mVcnNetworkProvider;
    }
}
