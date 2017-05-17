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

import java.util.List;

class Tuner extends ITuner.Stub {
    // TODO(b/36863239): rename to RadioService.Tuner when native service goes away
    private static final String TAG = "RadioServiceJava.Tuner";

    /**
     * This field is used by native code, do not access or modify.
     */
    private final long mNativeContext;

    @NonNull private final TunerCallback mTunerCallback;
    private final Object mLock = new Object();
    private boolean mIsClosed = false;
    private boolean mIsMuted = false;
    private int mRegion;  // TODO(b/36863239): find better solution to manage regions
    private final boolean mWithAudio;

    Tuner(@NonNull ITunerCallback clientCallback, int halRev, int region, boolean withAudio) {
        mTunerCallback = new TunerCallback(this, clientCallback, halRev);
        mRegion = region;
        mWithAudio = withAudio;
        mNativeContext = nativeInit(halRev);
    }

    @Override
    protected void finalize() throws Throwable {
        nativeFinalize(mNativeContext);
        super.finalize();
    }

    private native long nativeInit(int halRev);
    private native void nativeFinalize(long nativeContext);
    private native void nativeClose(long nativeContext);

    private native void nativeSetConfiguration(long nativeContext,
            @NonNull RadioManager.BandConfig config);
    private native RadioManager.BandConfig nativeGetConfiguration(long nativeContext, int region);

    private native void nativeStep(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeScan(long nativeContext, boolean directionDown, boolean skipSubChannel);
    private native void nativeTune(long nativeContext, int channel, int subChannel);
    private native void nativeCancel(long nativeContext);

    private native RadioManager.ProgramInfo nativeGetProgramInformation(long nativeContext);
    private native boolean nativeStartBackgroundScan(long nativeContext);
    private native List<RadioManager.ProgramInfo> nativeGetProgramList(long nativeContext,
            String filter);

    private native boolean nativeIsAnalogForced(long nativeContext);
    private native void nativeSetAnalogForced(long nativeContext, boolean isForced);

    private native boolean nativeIsAntennaConnected(long nativeContext);

    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) return;
            mTunerCallback.detach();
            nativeClose(mNativeContext);
            mIsClosed = true;
        }
    }

    private void checkNotClosedLocked() {
        if (mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("The argument must not be a null pointer");
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeSetConfiguration(mNativeContext, config);
            mRegion = config.getRegion();
        }
    }

    @Override
    public RadioManager.BandConfig getConfiguration() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeGetConfiguration(mNativeContext, mRegion);
        }
    }

    @Override
    public void setMuted(boolean mute) {
        if (!mWithAudio) {
            throw new IllegalStateException("Can't operate on mute - no audio requested");
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            if (mIsMuted == mute) return;
            mIsMuted = mute;

            // TODO(b/34348946): notifify audio policy manager of media activity on radio audio
            // device. This task is pulled directly from previous implementation of native service.
        }
    }

    @Override
    public boolean isMuted() {
        if (!mWithAudio) {
            Slog.w(TAG, "Tuner did not request audio, pretending it was muted");
            return true;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            return mIsMuted;
        }
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeStep(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void scan(boolean directionDown, boolean skipSubChannel) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeScan(mNativeContext, directionDown, skipSubChannel);
        }
    }

    @Override
    public void tune(int channel, int subChannel) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeTune(mNativeContext, channel, subChannel);
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeCancel(mNativeContext);
        }
    }

    @Override
    public RadioManager.ProgramInfo getProgramInformation() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeGetProgramInformation(mNativeContext);
        }
    }

    @Override
    public boolean startBackgroundScan() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeStartBackgroundScan(mNativeContext);
        }
    }

    @Override
    public List<RadioManager.ProgramInfo> getProgramList(String filter) {
        synchronized (mLock) {
            checkNotClosedLocked();
            List<RadioManager.ProgramInfo> list = nativeGetProgramList(mNativeContext, filter);
            if (list == null) {
                throw new IllegalStateException("Program list is not ready");
            }
            return list;
        }
    }

    @Override
    public boolean isAnalogForced() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeIsAnalogForced(mNativeContext);
        }
    }

    @Override
    public void setAnalogForced(boolean isForced) {
        synchronized (mLock) {
            checkNotClosedLocked();
            nativeSetAnalogForced(mNativeContext, isForced);
        }
    }

    @Override
    public boolean isAntennaConnected() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return nativeIsAntennaConnected(mNativeContext);
        }
    }
}
