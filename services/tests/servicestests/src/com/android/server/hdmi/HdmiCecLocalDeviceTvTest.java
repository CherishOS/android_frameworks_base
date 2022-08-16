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
package com.android.server.hdmi;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ABORT_UNRECOGNIZED_OPCODE;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.Constants.ADDR_RECORDER_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDeviceTv} class. */
public class HdmiCecLocalDeviceTvTest {
    private static final int TIMEOUT_MS = HdmiConfig.TIMEOUT_MS + 1;
    private static final int PORT_1 = 1;

    private static final String[] SADS_NOT_TO_QUERY = new String[]{
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_MPEG1,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_AAC,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_DTS,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_ATRAC,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_DDP,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_DTSHD,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_TRUEHD,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_DST,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_WMAPRO,
            HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_MAX};
    private static final HdmiCecMessage SAD_QUERY =
            HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(ADDR_TV, ADDR_AUDIO_SYSTEM,
                    new int[]{Constants.AUDIO_CODEC_LPCM, Constants.AUDIO_CODEC_DD,
                            Constants.AUDIO_CODEC_MP3, Constants.AUDIO_CODEC_MPEG2});

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mTvPhysicalAddress;
    private int mTvLogicalAddress;
    private boolean mWokenUp;
    private List<DeviceEventListener> mDeviceEventListeners = new ArrayList<>();

    private class DeviceEventListener {
        private HdmiDeviceInfo mDevice;
        private int mStatus;

        DeviceEventListener(HdmiDeviceInfo device, int status) {
            this.mDevice = device;
            this.mStatus = status;
        }

        int getStatus() {
            return mStatus;
        }

        HdmiDeviceInfo getDeviceInfo() {
            return mDevice;
        }
    }

    @Mock
    private AudioManager mAudioManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.emptyList(), new FakeAudioDeviceVolumeManagerWrapper()) {
                    @Override
                    void wakeUp() {
                        mWokenUp = true;
                        super.wakeUp();
                    }
                    @Override
                    boolean isControlEnabled() {
                        return true;
                    }

                    @Override
                    boolean isTvDevice() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
                    }

                    @Override
                    void invokeDeviceEventListeners(HdmiDeviceInfo device, int status) {
                        mDeviceEventListeners.add(new DeviceEventListener(device, status));
                    }
                };

        mHdmiCecLocalDeviceTv = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mHdmiCecLocalDeviceTv.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceTv);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[2];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x1000, true, false, false);
        hdmiPortInfos[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, true);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTvPhysicalAddress = 0x0000;
        mNativeWrapper.setPhysicalAddress(mTvPhysicalAddress);
        mTestLooper.dispatchAll();
        mTvLogicalAddress = mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress();
        for (String sad : SADS_NOT_TO_QUERY) {
            mHdmiControlService.getHdmiCecConfig().setIntValue(
                    sad, HdmiControlManager.QUERY_SAD_DISABLED);
        }
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void initialPowerStateIsStandby() {
        assertThat(mHdmiCecLocalDeviceTv.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void onAddressAllocated_invokesDeviceDiscovery() {
        mHdmiControlService.getHdmiCecNetwork().clearLocalDevices();
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();

        // Check for for <Give Physical Address> being sent to available device (ADDR_PLAYBACK_1).
        // This message is sent as part of the DeviceDiscoveryAction to available devices.
        HdmiCecMessage givePhysicalAddress = HdmiCecMessageBuilder.buildGivePhysicalAddress(ADDR_TV,
                ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(givePhysicalAddress);
    }

    @Test
    public void getActiveSource_noActiveSource() {
        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                Constants.INVALID_PHYSICAL_ADDRESS, "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(HdmiDeviceInfo.PATH_INVALID);

        assertThat(mHdmiControlService.getActiveSource()).isNull();
    }

    @Test
    public void getActiveSource_deviceInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x3000)
                .setPortId(0)
                .setDeviceType(Constants.ADDR_PLAYBACK_1)
                .setVendorId(0)
                .setDisplayName("Test Device")
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(externalDevice);
        mTestLooper.dispatchAll();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(externalDevice);
    }

    @Test
    public void getActiveSource_unknownLogicalAddressInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = HdmiDeviceInfo.hardwarePort(0x1000, 1);

        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(0x1000);

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(
                externalDevice);
    }

    @Test
    public void getActiveSource_unknownDeviceIsActiveSource() {
        HdmiDeviceInfo externalDevice = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x0000)
                .setPortId(0)
                .setDeviceType(ADDR_PLAYBACK_1)
                .setVendorId(0)
                .setDisplayName("Test Device")
                .build();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(0x1000);

        assertThat(mHdmiControlService.getActiveSource().getPhysicalAddress()).isEqualTo(
                externalDevice.getPhysicalAddress());
    }

    @Test
    public void shouldHandleTvPowerKey_CecEnabled_PowerControlModeTv() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void tvWakeOnOneTouchPlay_TextViewOn_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED);
        mTestLooper.dispatchAll();
        mPowerManager.setInteractive(false);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(ADDR_PLAYBACK_1,
                mTvLogicalAddress);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(textViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void tvWakeOnOneTouchPlay_ImageViewOn_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED);
        mTestLooper.dispatchAll();
        mPowerManager.setInteractive(false);
        HdmiCecMessage imageViewOn = HdmiCecMessage.build(ADDR_PLAYBACK_1, mTvLogicalAddress,
                Constants.MESSAGE_IMAGE_VIEW_ON, HdmiCecMessage.EMPTY_PARAM);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(imageViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void tvWakeOnOneTouchPlay_TextViewOn_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
        mTestLooper.dispatchAll();
        mPowerManager.setInteractive(false);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(ADDR_PLAYBACK_1,
                mTvLogicalAddress);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(textViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void tvWakeOnOneTouchPlay_ImageViewOn_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
        mTestLooper.dispatchAll();
        mPowerManager.setInteractive(false);
        HdmiCecMessage imageViewOn = HdmiCecMessage.build(ADDR_PLAYBACK_1, mTvLogicalAddress,
                Constants.MESSAGE_IMAGE_VIEW_ON, HdmiCecMessage.EMPTY_PARAM);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(imageViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleTextViewOn_Dreaming() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED);
        mTestLooper.dispatchAll();
        mPowerManager.setInteractive(true);
        mWokenUp = false;
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(ADDR_PLAYBACK_1,
                mTvLogicalAddress);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(textViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mWokenUp).isTrue();
    }

    @Test
    public void tvSendStandbyOnSleep_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mTestLooper.dispatchAll();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standby = HdmiCecMessageBuilder.buildStandby(ADDR_TV, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(standby);
    }

    @Test
    public void tvSendStandbyOnSleep_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_DISABLED);
        mTestLooper.dispatchAll();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standby = HdmiCecMessageBuilder.buildStandby(ADDR_TV, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standby);
    }

    @Test
    public void getRcFeatures() {
        ArrayList<Integer> features = new ArrayList<>(mHdmiCecLocalDeviceTv.getRcFeatures());
        assertThat(features.contains(Constants.RC_PROFILE_TV_NONE)).isTrue();
        assertThat(features.contains(Constants.RC_PROFILE_TV_ONE)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_TWO)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_THREE)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_FOUR)).isFalse();
    }

    @Test
    public void startArcAction_enable_noAudioDevice() {
        mHdmiCecLocalDeviceTv.startArcAction(true);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }


    @Test
    public void startArcAction_disable_noAudioDevice() {
        mHdmiCecLocalDeviceTv.startArcAction(false);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_enable_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        mHdmiCecLocalDeviceTv.startArcAction(true);
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_disable_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        mHdmiCecLocalDeviceTv.startArcAction(false);
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_enable_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDeviceTv.startArcAction(true);
        mTestLooper.dispatchAll();
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).contains(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_disable_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDeviceTv.startArcAction(false);
        mTestLooper.dispatchAll();
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).contains(requestArcTermination);
    }

    @Test
    public void handleInitiateArc_noAudioDevice() {
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportArcInitiated);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(SAD_QUERY);
    }

    @Test
    public void handleInitiateArc_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportArcInitiated);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(SAD_QUERY);
    }

    @Test
    public void handleInitiateArc_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        // <Report ARC Initiated> should only be sent after SAD querying is done
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportArcInitiated);

        // Finish querying SADs
        assertThat(mNativeWrapper.getResultMessages()).contains(SAD_QUERY);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SAD_QUERY);
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(reportArcInitiated);
    }

    @Test
    public void supportsRecordTvScreen() {
        HdmiCecMessage recordTvScreen = HdmiCecMessage.build(ADDR_RECORDER_1, mTvLogicalAddress,
                Constants.MESSAGE_RECORD_TV_SCREEN, HdmiCecMessage.EMPTY_PARAM);

        mNativeWrapper.onCecMessage(recordTvScreen);
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mTvLogicalAddress, ADDR_RECORDER_1, Constants.MESSAGE_RECORD_TV_SCREEN,
                ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(featureAbort);
    }

    @Test
    public void handleReportAudioStatus_SamOnArcOff_setStreamVolumeNotCalled() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        HdmiCecFeatureAction systemAudioAutoInitiationAction =
                new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(systemAudioAutoInitiationAction);
        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        true);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);

        mTestLooper.dispatchAll();

        // SAM must be on; ARC must be off
        assertTrue(mHdmiCecLocalDeviceTv.isSystemAudioActivated());
        assertFalse(mHdmiCecLocalDeviceTv.isArcEstablished());

        HdmiCecMessage reportAudioStatus = HdmiCecMessageBuilder.buildReportAudioStatus(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV,
                50, // Volume of incoming message does not affect HDMI-CEC logic
                false);
        mNativeWrapper.onCecMessage(reportAudioStatus);

        mTestLooper.dispatchAll();

        verify(mAudioManager, never()).setStreamVolume(anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that receiving a message from a device does not prevent it from being discovered
     * by HotplugDetectionAction.
     */
    @Test
    public void hotplugDetectionAction_discoversDeviceAfterMessageReceived() {
        // Playback 1 sends a message before ACKing a poll
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.NACK);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildActiveSource(
                ADDR_PLAYBACK_1, ADDR_TV);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        // Playback 1 begins ACKing polls, allowing detection by HotplugDetectionAction
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        for (int pollCount = 0; pollCount < HotplugDetectionAction.TIMEOUT_COUNT; pollCount++) {
            mTestLooper.moveTimeForward(
                    TimeUnit.SECONDS.toMillis(HotplugDetectionAction.POLLING_INTERVAL_MS_FOR_TV));
            mTestLooper.dispatchAll();
        }

        // Device sends <Give Physical Address> to Playback 1 after detecting it
        HdmiCecMessage givePhysicalAddress = HdmiCecMessageBuilder.buildGivePhysicalAddress(
                ADDR_TV, ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(givePhysicalAddress);
    }

    @Test
    public void hotplugDetectionActionClearsDevices() {
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        // Add a device to the network and assert that this device is included in the list of
        // devices.
        HdmiDeviceInfo infoPlayback = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_2)
                .setPhysicalAddress(0x1000)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1000)
                .setDisplayName("Playback 2")
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        mDeviceEventListeners.clear();
        assertThat(mDeviceEventListeners.size()).isEqualTo(0);

        // HAL detects a hotplug out. Assert that this device stays in the list of devices.
        mHdmiControlService.onHotplug(PORT_1, false);
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        assertThat(mDeviceEventListeners).isEmpty();
        mTestLooper.dispatchAll();
        // Make the device not acknowledge the poll message sent by the HotplugDetectionAction.
        // Assert that this device is removed from the list of devices.
        mNativeWrapper.setPollAddressResponse(Constants.ADDR_PLAYBACK_2, SendMessageResult.NACK);
        for (int pollCount = 0; pollCount < HotplugDetectionAction.TIMEOUT_COUNT; pollCount++) {
            mTestLooper.moveTimeForward(HotplugDetectionAction.POLLING_INTERVAL_MS_FOR_TV);
            mTestLooper.dispatchAll();
        }

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        assertThat(mDeviceEventListeners.size()).isEqualTo(1);
        assertThat(mDeviceEventListeners.get(0).getStatus())
                .isEqualTo(HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
        HdmiDeviceInfo removedDeviceInfo = mDeviceEventListeners.get(0).getDeviceInfo();
        assertThat(removedDeviceInfo.getPortId()).isEqualTo(PORT_1);
        assertThat(removedDeviceInfo.getLogicalAddress()).isEqualTo(Constants.ADDR_PLAYBACK_2);
        assertThat(removedDeviceInfo.getPhysicalAddress()).isEqualTo(0x1000);
        assertThat(removedDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    @Test
    public void hotplugDetectionActionClearsDevices_AudioSystem() {
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        // Add a device to the network and assert that this device is included in the list of
        // devices.
        HdmiDeviceInfo infoAudioSystem = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(ADDR_AUDIO_SYSTEM)
                .setPhysicalAddress(0x1000)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)
                .setVendorId(0x1000)
                .setDisplayName("Audio System")
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoAudioSystem);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        mDeviceEventListeners.clear();
        assertThat(mDeviceEventListeners.size()).isEqualTo(0);

        // HAL detects a hotplug out. Assert that this device stays in the list of devices.
        mHdmiControlService.onHotplug(PORT_1, false);
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        assertThat(mDeviceEventListeners).isEmpty();
        mTestLooper.dispatchAll();
        // Make the device not acknowledge the poll message sent by the HotplugDetectionAction.
        // Assert that this device is removed from the list of devices.
        mNativeWrapper.setPollAddressResponse(ADDR_AUDIO_SYSTEM, SendMessageResult.NACK);
        for (int pollCount = 0; pollCount < HotplugDetectionAction.TIMEOUT_COUNT; pollCount++) {
            mTestLooper.moveTimeForward(HotplugDetectionAction.POLLING_INTERVAL_MS_FOR_TV);
            mTestLooper.dispatchAll();
        }

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        assertThat(mDeviceEventListeners.size()).isEqualTo(1);
        assertThat(mDeviceEventListeners.get(0).getStatus())
                .isEqualTo(HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
        HdmiDeviceInfo removedDeviceInfo = mDeviceEventListeners.get(0).getDeviceInfo();
        assertThat(removedDeviceInfo.getPortId()).isEqualTo(PORT_1);
        assertThat(removedDeviceInfo.getLogicalAddress()).isEqualTo(Constants.ADDR_AUDIO_SYSTEM);
        assertThat(removedDeviceInfo.getPhysicalAddress()).isEqualTo(0x1000);
        assertThat(removedDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    @Test
    public void listenerInvokedIfPhysicalAddressReported() {
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        HdmiCecMessage reportPhysicalAddress =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_PLAYBACK_2, 0x1000, HdmiDeviceInfo.DEVICE_PLAYBACK);
        mNativeWrapper.onCecMessage(reportPhysicalAddress);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        assertThat(mDeviceEventListeners.size()).isEqualTo(1);
        assertThat(mDeviceEventListeners.get(0).getStatus())
                .isEqualTo(HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
    }

    @Test
    public void listenerNotInvokedIfPhysicalAddressUnknown() {
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        HdmiCecMessage setOsdName = HdmiCecMessageBuilder.buildSetOsdNameCommand(
                ADDR_PLAYBACK_2, ADDR_TV, "Playback 2");
        mNativeWrapper.onCecMessage(setOsdName);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        assertThat(mDeviceEventListeners).isEmpty();

        // When the device reports its physical address, the listener eventually is invoked.
        HdmiCecMessage reportPhysicalAddress =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        ADDR_PLAYBACK_2, 0x1000, HdmiDeviceInfo.DEVICE_PLAYBACK);
        mNativeWrapper.onCecMessage(reportPhysicalAddress);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);
        assertThat(mDeviceEventListeners.size()).isEqualTo(1);
        assertThat(mDeviceEventListeners.get(0).getStatus())
                .isEqualTo(HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
    }

    @Test
    public void receiveSetAudioVolumeLevel_samNotActivated_noFeatureAbort_volumeChanges() {
        when(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(25);

        // Max volume of STREAM_MUSIC is retrieved on boot
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(SetAudioVolumeLevelMessage.build(
                ADDR_PLAYBACK_1,
                ADDR_TV,
                20));
        mTestLooper.dispatchAll();

        // <Feature Abort>[Not in correct mode] not sent
        HdmiCecMessage featureAbortMessage = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                ADDR_TV,
                ADDR_PLAYBACK_1,
                Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL,
                Constants.ABORT_NOT_IN_CORRECT_MODE);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(featureAbortMessage);

        // <Set Audio Volume Level> uses volume range [0, 100]; STREAM_MUSIC uses range [0, 25]
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(5), anyInt());
    }

    @Test
    public void receiveSetAudioVolumeLevel_samActivated_respondsFeatureAbort_noVolumeChange() {
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildSetSystemAudioMode(
                ADDR_AUDIO_SYSTEM, ADDR_TV, true));
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(SetAudioVolumeLevelMessage.build(
                ADDR_PLAYBACK_1, ADDR_TV, 50));
        mTestLooper.dispatchAll();

        // <Feature Abort>[Not in correct mode] sent
        HdmiCecMessage featureAbortMessage = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                ADDR_TV,
                ADDR_PLAYBACK_1,
                Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL,
                Constants.ABORT_NOT_IN_CORRECT_MODE);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbortMessage);

        // AudioManager not notified of volume change
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(),
                anyInt());
    }
}
