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

import android.hardware.tv.cec.V1_0.SendMessageResult;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Feature action that handles System Audio Mode initiated by AVR devices.
 */
public class SystemAudioInitiationActionFromAvr extends HdmiCecFeatureAction {

    // State that waits for <Active Source> once send <Request Active Source>.
    private static final int STATE_WAITING_FOR_ACTIVE_SOURCE = 1;
    @VisibleForTesting
    static final int MAX_RETRY_COUNT = 5;

    private int mSendRequestActiveSourceRetryCount = 0;
    private int mSendSetSystemAudioModeRetryCount = 0;

    SystemAudioInitiationActionFromAvr(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        if (audioSystem().mActiveSource.physicalAddress == Constants.INVALID_PHYSICAL_ADDRESS) {
            mState = STATE_WAITING_FOR_ACTIVE_SOURCE;
            addTimer(mState, HdmiConfig.TIMEOUT_MS);
            sendRequestActiveSource();
        } else {
            queryTvSystemAudioModeSupport();
        }
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
                // received <Active Source>
                if (mState != STATE_WAITING_FOR_ACTIVE_SOURCE) {
                    return false;
                }
                mActionTimer.clearTimerMessage();
                int physicalAddress = HdmiUtils.twoBytesToInt(cmd.getParams());
                if (physicalAddress != getSourcePath()) {
                    audioSystem().setActiveSource(cmd.getSource(), physicalAddress);
                }
                queryTvSystemAudioModeSupport();
                return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        switch (mState) {
            case STATE_WAITING_FOR_ACTIVE_SOURCE:
                handleActiveSourceTimeout();
                break;
        }
    }

    protected void sendRequestActiveSource() {
        sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()),
                result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        if (mSendRequestActiveSourceRetryCount < MAX_RETRY_COUNT) {
                            mSendRequestActiveSourceRetryCount++;
                            sendRequestActiveSource();
                        } else {
                            audioSystem().setSystemAudioMode(false);
                            finish();
                        }
                    }
                });
    }

    protected void sendSetSystemAudioMode(boolean on, int dest) {
        sendCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(getSourceAddress(),
                dest, on), result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        if (mSendSetSystemAudioModeRetryCount < MAX_RETRY_COUNT) {
                            mSendSetSystemAudioModeRetryCount++;
                            sendSetSystemAudioMode(on, dest);
                        } else {
                            audioSystem().setSystemAudioMode(false);
                            finish();
                        }
                    }
                });
    }

    private void handleActiveSourceTimeout() {
        HdmiLogger.debug("Cannot get active source.");
        audioSystem().setSystemAudioMode(false);
        finish();
    }

    private void queryTvSystemAudioModeSupport() {
        audioSystem().queryTvSystemAudioModeSupport(
                supported -> {
                    if (supported) {
                        if (audioSystem().setSystemAudioMode(true)) {
                            sendSetSystemAudioMode(true, Constants.ADDR_BROADCAST);
                        }
                        finish();
                    } else {
                        audioSystem().setSystemAudioMode(false);
                        finish();
                    }
                });
    }

    private void switchToRelevantInputForDeviceAt(int physicalAddress) {
        // TODO(shubang): implement this method
    }
}
