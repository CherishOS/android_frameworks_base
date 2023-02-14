/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.hdmi;

import android.hardware.tv.hdmi.earc.IEArcStatus;

final class FakeEarcNativeWrapper implements HdmiEarcController.EarcNativeWrapper {
    private static final String TAG = "FakeEarcNativeWrapper";

    private boolean mIsEarcEnabled = true;

    @Override
    public boolean nativeInit() {
        return true;
    }

    @Override
    public void nativeSetEarcEnabled(boolean enabled) {
        mIsEarcEnabled = enabled;
    }

    @Override
    public boolean nativeIsEarcEnabled() {
        return mIsEarcEnabled;
    }

    @Override
    public void nativeSetCallback(HdmiEarcController.EarcAidlCallback callback) {
    }

    @Override
    public byte nativeGetState(int portId) {
        return IEArcStatus.IDLE;
    }

    @Override
    public byte[] nativeGetLastReportedAudioCapabilities(int portId) {
        return new byte[] {};
    }
}
