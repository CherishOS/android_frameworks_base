/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.MessageQueue;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiCecController.NativeWrapper;
import java.util.ArrayList;
import java.util.List;

/** Fake {@link NativeWrapper} useful for testing. */
final class FakeNativeWrapper implements NativeWrapper {
    private final int[] mPollAddressResponse =
            new int[] {
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
                SendMessageResult.NACK,
            };

    private final List<HdmiCecMessage> mResultMessages = new ArrayList<>();
    private HdmiCecMessage mResultMessage;
    private int mMyPhysicalAddress = 0;

    @Override
    public long nativeInit(HdmiCecController handler, MessageQueue messageQueue) {
        return 1L;
    }

    @Override
    public int nativeSendCecCommand(
            long controllerPtr, int srcAddress, int dstAddress, byte[] body) {
        if (body.length == 0) {
            return mPollAddressResponse[dstAddress];
        } else {
            mResultMessages.add(HdmiCecMessageBuilder.of(srcAddress, dstAddress, body));
        }
        return SendMessageResult.SUCCESS;
    }

    @Override
    public int nativeAddLogicalAddress(long controllerPtr, int logicalAddress) {
        return 0;
    }

    @Override
    public void nativeClearLogicalAddress(long controllerPtr) {}

    @Override
    public int nativeGetPhysicalAddress(long controllerPtr) {
        return mMyPhysicalAddress;
    }

    @Override
    public int nativeGetVersion(long controllerPtr) {
        return 0;
    }

    @Override
    public int nativeGetVendorId(long controllerPtr) {
        return 0;
    }

    @Override
    public HdmiPortInfo[] nativeGetPortInfos(long controllerPtr) {
        HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[1];
        hdmiPortInfo[0] = new HdmiPortInfo(1, 1, 0x1000, true, true, true);
        return hdmiPortInfo;
    }

    @Override
    public void nativeSetOption(long controllerPtr, int flag, boolean enabled) {}

    @Override
    public void nativeSetLanguage(long controllerPtr, String language) {}

    @Override
    public void nativeEnableAudioReturnChannel(long controllerPtr, int port, boolean flag) {}

    @Override
    public boolean nativeIsConnected(long controllerPtr, int port) {
        return false;
    }

    public List<HdmiCecMessage> getResultMessages() {
        return new ArrayList<>(mResultMessages);
    }

    public HdmiCecMessage getOnlyResultMessage() throws Exception {
        if (mResultMessages.size() != 1) {
            throw new Exception("There is not exactly one message");
        }
        return mResultMessages.get(0);
    }

    public void setPollAddressResponse(int logicalAddress, int response) {
        mPollAddressResponse[logicalAddress] = response;
    }

    @VisibleForTesting
    protected void setPhysicalAddress(int physicalAddress) {
        mMyPhysicalAddress = physicalAddress;
    }
}
