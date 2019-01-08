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

import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.HdmiControlService.STANDBY_SCREEN_OFF;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;

@SmallTest
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDeviceAudioSystem} class. */
public class HdmiCecLocalDeviceAudioSystemTest {

    private static final HdmiCecMessage MESSAGE_REQUEST_SAD_LCPM =
            HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                    ADDR_TV, ADDR_AUDIO_SYSTEM, new int[] {Constants.AUDIO_CODEC_LPCM});

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mMusicVolume;
    private int mMusicMaxVolume;
    private boolean mMusicMute;
    private int mAvrPhysicalAddress;

    @Before
    public void setUp() {
        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext()) {
                    @Override
                    AudioManager getAudioManager() {
                        return new AudioManager() {
                            @Override
                            public int getStreamVolume(int streamType) {
                                switch (streamType) {
                                    case STREAM_MUSIC:
                                        return mMusicVolume;
                                    default:
                                        return 0;
                                }
                            }

                            @Override
                            public boolean isStreamMute(int streamType) {
                                switch (streamType) {
                                    case STREAM_MUSIC:
                                        return mMusicMute;
                                    default:
                                        return false;
                                }
                            }

                            @Override
                            public int getStreamMaxVolume(int streamType) {
                                switch (streamType) {
                                    case STREAM_MUSIC:
                                        return mMusicMaxVolume;
                                    default:
                                        return 100;
                                }
                            }

                            @Override
                            public void adjustStreamVolume(
                                    int streamType, int direction, int flags) {
                                switch (streamType) {
                                    case STREAM_MUSIC:
                                        if (direction == AudioManager.ADJUST_UNMUTE) {
                                            mMusicMute = false;
                                        } else if (direction == AudioManager.ADJUST_MUTE) {
                                            mMusicMute = true;
                                        }
                                    default:
                                }
                            }

                            @Override
                            public void setWiredDeviceConnectionState(
                                    int type, int state, String address, String name) {
                                // Do nothing.
                            }
                        };
                    }

                    @Override
                    void wakeUp() {}

                    @Override
                    boolean isControlEnabled() {
                        return true;
                    }
                };

        mMyLooper = mTestLooper.getLooper();
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
                HdmiCecController.createWithNativeWrapper(mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        mHdmiControlService.initPortInfo();
        // No TV device interacts with AVR so system audio control won't be turned on here
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mAvrPhysicalAddress  = 0x2000;
        mNativeWrapper.setPhysicalAddress(mAvrPhysicalAddress);
        SystemProperties.set(Constants.PROPERTY_ARC_SUPPORT, "true");
    }

    @Test
    public void handleGiveAudioStatus_volume_10_mute_true() throws Exception {
        mMusicVolume = 10;
        mMusicMute = true;
        mMusicMaxVolume = 20;
        int scaledVolume = VolumeControlAction.scaleToCecVolume(10, mMusicMaxVolume);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportAudioStatus(
                        ADDR_AUDIO_SYSTEM, ADDR_TV, scaledVolume, true);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveAudioStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(messageGive)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleGiveSystemAudioModeStatus_originalOff() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Ignore("b/80297700")
    @Test
    public void handleRequestShortAudioDescriptor_featureDisabled() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                        Constants.ABORT_REFUSED);

        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(false);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                                MESSAGE_REQUEST_SAD_LCPM))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleRequestShortAudioDescriptor_samOff() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                        Constants.ABORT_NOT_IN_CORRECT_MODE);

        mHdmiCecLocalDeviceAudioSystem.setSystemAudioMode(false);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                                MESSAGE_REQUEST_SAD_LCPM))
                .isEqualTo(true);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleRequestShortAudioDescriptor_noAudioDeviceInfo() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                        Constants.ABORT_UNABLE_TO_DETERMINE);

        mHdmiCecLocalDeviceAudioSystem.setSystemAudioMode(true);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                                MESSAGE_REQUEST_SAD_LCPM))
                .isEqualTo(true);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleSetSystemAudioMode_setOn_orignalOff() throws Exception {
        mMusicMute = true;
        HdmiCecMessage messageSet =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(ADDR_TV, ADDR_AUDIO_SYSTEM, true);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        // Check if originally off
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        // Check if correctly turned on
        mNativeWrapper.clearResultMessages();
        expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSetSystemAudioMode(messageSet)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isFalse();
    }

    @Ignore("b/80297700")
    @Test
    public void handleSystemAudioModeRequest_turnOffByTv() throws Exception {
        assertThat(mMusicMute).isFalse();
        // Check if feature correctly turned off
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage messageRequestOff =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        ADDR_TV, ADDR_AUDIO_SYSTEM, 2, false);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(messageRequestOff))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);

        mNativeWrapper.clearResultMessages();
        expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isTrue();
    }

    @Ignore("b/80297700")
    @Test
    public void onStandbyAudioSystem_currentSystemAudioControlOn() throws Exception {
        // Set system audio control on first
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioMode(true);
        // Check if standby correctly turns off the feature
        mHdmiCecLocalDeviceAudioSystem.onStandby(false, STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isTrue();
    }

    @Test
    public void systemAudioControlOnPowerOn_alwaysOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, true);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.getActions(
                                SystemAudioInitiationActionFromAvr.class))
                .isNotEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_neverOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.NEVER_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, false);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.getActions(
                                SystemAudioInitiationActionFromAvr.class))
                .isEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_useLastState_off() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, false);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.getActions(
                                SystemAudioInitiationActionFromAvr.class))
                .isEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_useLastState_on() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, true);
        assertThat(
                        mHdmiCecLocalDeviceAudioSystem.getActions(
                                SystemAudioInitiationActionFromAvr.class))
                .isNotEmpty();
    }

    @Test
    public void handleActiveSource_updateActiveSource() throws Exception {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        ActiveSource expectedActiveSource = new ActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleActiveSource(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().equals(expectedActiveSource))
                .isTrue();
    }

    @Test
    public void terminateSystemAudioMode_systemAudioModeOff() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioMode(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        mMusicMute = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        mHdmiCecLocalDeviceAudioSystem.terminateSystemAudioMode();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mMusicMute).isFalse();
        assertThat(mNativeWrapper.getResultMessages()).isEmpty();
    }

    @Ignore("b/80297700")
    @Test
    public void terminateSystemAudioMode_systemAudioModeOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioMode(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isTrue();
        mMusicMute = false;
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        mHdmiCecLocalDeviceAudioSystem.terminateSystemAudioMode();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mMusicMute).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void pathToPort_isMe() throws Exception {
        int targetPhysicalAddress = 0x1000;
        mNativeWrapper.setPhysicalAddress(0x1000);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(0);
    }

    @Test
    public void pathToPort_isBelow() throws Exception {
        int targetPhysicalAddress = 0x1100;
        mNativeWrapper.setPhysicalAddress(0x1000);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(1);
    }

    @Test
    public void pathToPort_neitherMeNorBelow() throws Exception {
        int targetPhysicalAddress = 0x3000;
        mNativeWrapper.setPhysicalAddress(0x2000);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(-1);

        targetPhysicalAddress = 0x2200;
        mNativeWrapper.setPhysicalAddress(0x3300);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(-1);

        targetPhysicalAddress = 0x2213;
        mNativeWrapper.setPhysicalAddress(0x2212);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(-1);

        targetPhysicalAddress = 0x2340;
        mNativeWrapper.setPhysicalAddress(0x2310);
        assertThat(mHdmiCecLocalDeviceAudioSystem
                .getLocalPortFromPhysicalAddress(targetPhysicalAddress))
                .isEqualTo(-1);
    }

    @Test
    public void handleRequestArcInitiate_isNotDirectConnectedToTv() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_ARC_INITIATION,
                        Constants.ABORT_NOT_IN_CORRECT_MODE);
        mNativeWrapper.setPhysicalAddress(0x1100);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRequestArcInitiate_startArcInitiationActionFromAvr() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mNativeWrapper.setPhysicalAddress(0x1000);
        mHdmiCecLocalDeviceAudioSystem.removeAction(ArcInitiationActionFromAvr.class);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActions(ArcInitiationActionFromAvr.class))
                .isNotEmpty();
    }

    @Test
    public void handleRequestArcTerminate_arcIsOn_startTerminationActionFromAvr() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceAudioSystem.removeAction(ArcTerminationActionFromAvr.class);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActions(ArcTerminationActionFromAvr.class))
                .isNotEmpty();
    }

    @Test
    public void handleRequestArcTerminate_arcIsNotOn() throws Exception {
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_ARC_TERMINATION,
                        Constants.ABORT_NOT_IN_CORRECT_MODE);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRequestArcInit_arcIsNotSupported() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_ARC_INITIATION,
                        Constants.ABORT_UNRECOGNIZED_OPCODE);
        SystemProperties.set(Constants.PROPERTY_ARC_SUPPORT, "false");

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void onStandby_setAutoDeviceOff_true() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildStandby(ADDR_AUDIO_SYSTEM, ADDR_BROADCAST);
        mHdmiCecLocalDeviceAudioSystem.setAutoDeviceOff(true);
        mHdmiCecLocalDeviceAudioSystem.onStandby(false, STANDBY_SCREEN_OFF);

        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleSetStreamPath_underCurrentDevice() {
        assertThat(mHdmiCecLocalDeviceAudioSystem.getLocalActivePath()).isEqualTo(0);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x2100);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSetStreamPath(message)).isTrue();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getLocalActivePath()).isEqualTo(1);
    }
}
