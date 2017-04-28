/**
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

package com.android.server.radio;

import android.annotation.NonNull;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.util.Slog;

class Tuner extends ITuner.Stub {
    // TODO(b/36863239): rename to RadioService.Tuner when native service goes away
    private static final String TAG = "RadioServiceJava.Tuner";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    private int mRegion;

    Tuner(@NonNull ITunerCallback clientCallback, int region) {
        mRegion = region;
        mNativeContext = nativeInit(clientCallback);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(ITunerCallback clientCallback);
    private native void nativeFinalize(long nativeContext);
    private native void nativeClose(long nativeContext);

    @Override
    public void close() {
        nativeClose(mNativeContext);
    }

    @Override
    public int getProgramInformation(RadioManager.ProgramInfo[] infoOut) {
        Slog.d(TAG, "getProgramInformation()");
        return RadioManager.STATUS_INVALID_OPERATION;
    }
}
