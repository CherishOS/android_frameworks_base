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
import android.hardware.radio.RadioTuner;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

class TunerCallback implements ITunerCallback {
    // TODO(b/36863239): rename to RadioService.TunerCallback when native service goes away
    private static final String TAG = "RadioServiceJava.TunerCallback";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    @NonNull private final Tuner mTuner;
    @NonNull private final ITunerCallback mClientCallback;

    TunerCallback(@NonNull Tuner tuner, @NonNull ITunerCallback clientCallback, int halRev) {
        mTuner = tuner;
        mClientCallback = clientCallback;
        mNativeContext = nativeInit(tuner, halRev);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(@NonNull Tuner tuner, int halRev);
    private native void nativeFinalize(long nativeContext);
    private native void nativeDetach(long nativeContext);

    public void detach() {
        nativeDetach(mNativeContext);
    }

    // called from native side
    private void handleHwFailure() {
        onError(RadioTuner.ERROR_HARDWARE_FAILURE);
        mTuner.close();
    }

    @Override
    public void onError(int status) {
        try {
            mClientCallback.onError(status);
        } catch (RemoteException e) {
            Slog.e(TAG, "client died", e);
        }
    }

    @Override
    public void onConfigurationChanged(RadioManager.BandConfig config) {
        try {
            mClientCallback.onConfigurationChanged(config);
        } catch (RemoteException e) {
            Slog.e(TAG, "client died", e);
        }
    }

    @Override
    public void onProgramInfoChanged(RadioManager.ProgramInfo info) {
        try {
            mClientCallback.onProgramInfoChanged(info);
        } catch (RemoteException e) {
            Slog.e(TAG, "client died", e);
        }
    }

    @Override
    public IBinder asBinder() {
        throw new RuntimeException("Not a binder");
    }
}
