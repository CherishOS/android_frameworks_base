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

import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings.Global;
import android.sysprop.HdmiProperties;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDevicePlayback} class. */
public class HdmiCecLocalDevicePlaybackTest {

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPlaybackPhysicalAddress;
    private int mPlaybackLogicalAddress;
    private boolean mWokenUp;
    private boolean mStandby;

    @Mock private IPowerManager mIPowerManagerMock;
    @Mock private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();
        PowerManager powerManager = new PowerManager(context, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mMyLooper));
        mHdmiControlService =
            new HdmiControlService(InstrumentationRegistry.getTargetContext()) {
                @Override
                void wakeUp() {
                    mWokenUp = true;
                }

                @Override
                void standby() {
                    mStandby = true;
                }

                @Override
                boolean isControlEnabled() {
                    return true;
                }

                @Override
                boolean isPlaybackDevice() {
                    return true;
                }

                @Override
                void writeStringSystemProperty(String key, String value) {
                    // do nothing
                }

                @Override
                boolean isPowerStandby() {
                    return false;
                }

                @Override
                PowerManager getPowerManager() {
                    return powerManager;
                }
            };

        mHdmiCecLocalDevicePlayback = new HdmiCecLocalDevicePlayback(mHdmiControlService);
        mHdmiCecLocalDevicePlayback.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
            HdmiCecController.createWithNativeWrapper(mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDevicePlayback);
        mHdmiControlService.initPortInfo();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mPlaybackPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPlaybackPhysicalAddress);
        mTestLooper.dispatchAll();
        mPlaybackLogicalAddress = mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void handleRoutingChange_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                    .playback_device_action_on_routing_control_values
                    .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingInformation_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                    .playback_device_action_on_routing_control_values
                    .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingChange_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.NONE;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isTrue();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.NONE;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isTrue();
        assertThat(mStandby).isFalse();
    }

    // Playback device does not handle routing control related feature right now
    @Ignore("b/120845532")
    @Test
    public void handleSetStreamPath_underCurrentDevice() {
        assertThat(mHdmiCecLocalDevicePlayback.getLocalActivePath()).isEqualTo(0);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x2100);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message)).isTrue();
        // TODO(amyjojo): Move set and get LocalActivePath to Control Service.
        assertThat(mHdmiCecLocalDevicePlayback.getLocalActivePath()).isEqualTo(1);
    }

    @Test
    public void handleSetSystemAudioModeOn_audioSystemBroadcast() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_BROADCAST, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void handleSetSystemAudioModeOff_audioSystemToPlayback() {
        mHdmiCecLocalDevicePlayback.mService.setSystemAudioActivated(true);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
        // This direct message to Playback device is invalid.
        // Test should ignore it and still keep the system audio mode on.
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, mHdmiCecLocalDevicePlayback.mAddress, false);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void handleSystemAudioModeStatusOn_DirectlyToLocalDeviceFromAudioSystem() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, mHdmiCecLocalDevicePlayback.mAddress, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSystemAudioModeStatus(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugIn() {
        mWokenUp = false;
        mHdmiCecLocalDevicePlayback.onHotplug(0, true);
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugOut() {
        mWokenUp = false;
        mHdmiCecLocalDevicePlayback.onHotplug(0, false);
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_NONE);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.writeStringSetting(
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.SEND_STANDBY_ON_SLEEP_NONE);
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mHdmiCecLocalDevicePlayback.setAutoDeviceOff(true);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleActiveSource_ActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.NONE;
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                                         mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleActiveSource_notActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.NONE;
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleActiveSource_ActiveSource_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                                         mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleActiveSource_notActiveSource_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message)).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void sendVolumeKeyEvent_up_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_down_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_mute_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_MUTE);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_up_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_down_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_mute_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void handleSetStreamPath_broadcastsActiveSource() {
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.mAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
    }

    @Test
    public void handleSetStreamPath_afterHotplug_broadcastsActiveSource() {
        mHdmiControlService.onHotplug(1, false);
        mHdmiControlService.onHotplug(1, true);

        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
    }

    @Test
    public void handleSetStreamPath_afterHotplug_hasCorrectActiveSource() {
        mHdmiControlService.onHotplug(1, false);
        mHdmiControlService.onHotplug(1, true);

        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress());
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.NONE;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(true);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mPowerStateChangeOnActiveSourceLost =
            HdmiProperties.power_state_change_on_active_source_lost_values.STANDBY_NOW;
        mHdmiCecLocalDevicePlayback.setIsActiveSource(false);
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mIsActiveSource).isFalse();
        assertThat(mStandby).isFalse();
    }
}
