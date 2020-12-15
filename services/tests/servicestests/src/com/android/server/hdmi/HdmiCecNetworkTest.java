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


import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link HdmiCecNetwork} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiCecNetworkTest {

    private HdmiCecNetwork mHdmiCecNetwork;

    private Context mContext;

    private HdmiControlService mHdmiControlService;
    private HdmiMhlControllerStub mHdmiMhlControllerStub;

    private HdmiCecController mHdmiCecController;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private HdmiPortInfo[] mHdmiPortInfo;
    private List<Integer> mDeviceEventListenerStatuses = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mHdmiControlService = new HdmiControlService(mContext) {
            @Override
            void invokeDeviceEventListeners(HdmiDeviceInfo device, int status) {
                mDeviceEventListenerStatuses.add(status);
            }
        };

        mMyLooper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(mMyLooper);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(mHdmiControlService,
                mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiMhlControllerStub = HdmiMhlControllerStub.create(mHdmiControlService);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(mHdmiMhlControllerStub);
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mHdmiCecNetwork = new HdmiCecNetwork(mHdmiControlService,
                mHdmiCecController, mHdmiMhlControllerStub);

        mHdmiControlService.setHdmiCecNetwork(mHdmiCecNetwork);

        mHdmiPortInfo = new HdmiPortInfo[5];
        mHdmiPortInfo[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x2100, true, false, false);
        mHdmiPortInfo[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2200, true, false, false);
        mHdmiPortInfo[2] =
                new HdmiPortInfo(3, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, false);
        mHdmiPortInfo[3] =
                new HdmiPortInfo(4, HdmiPortInfo.PORT_INPUT, 0x3000, true, false, false);
        mHdmiPortInfo[4] =
                new HdmiPortInfo(5, HdmiPortInfo.PORT_OUTPUT, 0x0000, true, false, false);
        mNativeWrapper.setPortInfo(mHdmiPortInfo);
        mHdmiCecNetwork.initPortInfo();
    }

    @Test
    public void initializeNetwork_verifyPortInfo() {
        mHdmiCecNetwork.initPortInfo();
        assertThat(mHdmiCecNetwork.getPortInfo()).hasSize(mHdmiPortInfo.length);
    }

    @Test
    public void physicalAddressToPort_pathExists_weAreNonTv() {
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiCecNetwork.initPortInfo();
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x2120)).isEqualTo(1);
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x2234)).isEqualTo(2);
    }

    @Test
    public void physicalAddressToPort_pathExists_weAreSourceDevice() {
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiCecNetwork.initPortInfo();
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x0000)).isEqualTo(5);
    }

    @Test
    public void physicalAddressToPort_pathExists_weAreTv() {
        mNativeWrapper.setPhysicalAddress(0x0000);
        mHdmiCecNetwork.initPortInfo();
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x2120)).isEqualTo(3);
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x3234)).isEqualTo(4);
    }

    @Test
    public void physicalAddressToPort_pathInvalid() {
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiCecNetwork.initPortInfo();
        assertThat(mHdmiCecNetwork.physicalAddressToPortId(0x1000)).isEqualTo(
                Constants.INVALID_PORT_ID);
    }

    @Test
    public void localDevices_verifyOne_tv() {
        mHdmiCecNetwork.addLocalDevice(HdmiDeviceInfo.DEVICE_TV,
                new HdmiCecLocalDeviceTv(mHdmiControlService));

        assertThat(mHdmiCecNetwork.getLocalDeviceList()).hasSize(1);
        assertThat(mHdmiCecNetwork.getLocalDeviceList().get(0)).isInstanceOf(
                HdmiCecLocalDeviceTv.class);
        assertThat(mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_TV)).isNotNull();
        assertThat(mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK)).isNull();
    }

    @Test
    public void localDevices_verifyOne_playback() {
        mHdmiCecNetwork.addLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                new HdmiCecLocalDevicePlayback(mHdmiControlService));

        assertThat(mHdmiCecNetwork.getLocalDeviceList()).hasSize(1);
        assertThat(mHdmiCecNetwork.getLocalDeviceList().get(0)).isInstanceOf(
                HdmiCecLocalDevicePlayback.class);
        assertThat(mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK)).isNotNull();
        assertThat(mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_TV)).isNull();
    }

    @Test
    public void cecDevices_tracking_logicalAddressOnly() throws Exception {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildActiveSource(logicalAddress, 0x1000));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(Constants.UNKNOWN_VENDOR_ID);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_UNKNOWN);

        assertThat(mDeviceEventListenerStatuses).containsExactly(
                HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
    }

    @Test
    public void cecDevices_tracking_logicalAddressOnly_doesntNotifyAgain() throws Exception {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildActiveSource(logicalAddress, 0x1000));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildActiveSource(logicalAddress, 0x1000));

        assertThat(mDeviceEventListenerStatuses).containsExactly(
                HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
    }

    @Test
    public void cecDevices_tracking_reportPhysicalAddress() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int physicalAddress = 0x1000;
        int type = HdmiDeviceInfo.DEVICE_PLAYBACK;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        physicalAddress, type));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                physicalAddress);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(type);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(Constants.UNKNOWN_VENDOR_ID);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_UNKNOWN);
    }

    @Test
    public void cecDevices_tracking_updateDeviceInfo_sameDoesntNotify() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int physicalAddress = 0x1000;
        int type = HdmiDeviceInfo.DEVICE_PLAYBACK;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildActiveSource(logicalAddress, 0x1000));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        physicalAddress, type));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        physicalAddress, type));


        // ADD for logical address first detected
        // UPDATE for updating device with physical address
        assertThat(mDeviceEventListenerStatuses).containsExactly(
                HdmiControlManager.DEVICE_EVENT_ADD_DEVICE,
                HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
    }

    @Test
    public void cecDevices_tracking_reportPowerStatus() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int powerStatus = HdmiControlManager.POWER_STATUS_ON;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPowerStatus(logicalAddress,
                        Constants.ADDR_BROADCAST, powerStatus));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(Constants.UNKNOWN_VENDOR_ID);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(powerStatus);
    }

    @Test
    public void cecDevices_tracking_reportOsdName() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        String osdName = "Test Device";
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildSetOsdNameCommand(logicalAddress,
                        Constants.ADDR_BROADCAST, osdName));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(Constants.UNKNOWN_VENDOR_ID);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(osdName);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_UNKNOWN);
    }

    @Test
    public void cecDevices_tracking_reportVendorId() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int vendorId = 1234;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(logicalAddress, vendorId));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(vendorId);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_UNKNOWN);
    }

    @Test
    public void cecDevices_tracking_updatesDeviceInfo() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int physicalAddress = 0x1000;
        int type = HdmiDeviceInfo.DEVICE_PLAYBACK;
        int powerStatus = HdmiControlManager.POWER_STATUS_ON;
        String osdName = "Test Device";
        int vendorId = 1234;
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        physicalAddress, type));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPowerStatus(logicalAddress,
                        Constants.ADDR_BROADCAST, powerStatus));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildSetOsdNameCommand(logicalAddress,
                        Constants.ADDR_BROADCAST, osdName));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(logicalAddress, vendorId));
        mHdmiCecNetwork.handleCecMessage(HdmiCecMessageBuilder.buildCecVersion(logicalAddress,
                Constants.ADDR_BROADCAST, cecVersion));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(physicalAddress);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(type);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(osdName);
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(vendorId);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(powerStatus);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(cecVersion);
    }

    @Test
    public void cecDevices_tracking_updatesPhysicalAddress() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int initialPhysicalAddress = 0x1000;
        int updatedPhysicalAddress = 0x2000;
        int type = HdmiDeviceInfo.DEVICE_PLAYBACK;

        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        initialPhysicalAddress, type));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(logicalAddress,
                        updatedPhysicalAddress, type));
        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(updatedPhysicalAddress);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(type);

        // ADD for logical address first detected
        // UPDATE for updating device with physical address
        // UPDATE for updating device with new physical address
        assertThat(mDeviceEventListenerStatuses).containsExactly(
                HdmiControlManager.DEVICE_EVENT_ADD_DEVICE,
                HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE,
                HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
    }

    @Test
    public void cecDevices_tracking_updatesPowerStatus() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int powerStatus = HdmiControlManager.POWER_STATUS_ON;
        int updatedPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;

        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPowerStatus(logicalAddress,
                        Constants.ADDR_TV, powerStatus));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPowerStatus(logicalAddress,
                        Constants.ADDR_TV, updatedPowerStatus));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(updatedPowerStatus);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void cecDevices_tracking_updatesOsdName() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        String osdName = "Test Device";
        String updatedOsdName = "Different";

        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildSetOsdNameCommand(logicalAddress,
                        Constants.ADDR_BROADCAST, osdName));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildSetOsdNameCommand(logicalAddress,
                        Constants.ADDR_BROADCAST, updatedOsdName));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(updatedOsdName);
    }

    @Test
    public void cecDevices_tracking_updatesVendorId() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int vendorId = 1234;
        int updatedVendorId = 12345;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(logicalAddress, vendorId));
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(logicalAddress, updatedVendorId));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(updatedVendorId);
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_UNKNOWN);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void cecDevices_tracking_clearDevices() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildActiveSource(logicalAddress, 0x1000));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        mHdmiCecNetwork.clearDeviceList();

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).isEmpty();

        assertThat(mDeviceEventListenerStatuses).containsExactly(
                HdmiControlManager.DEVICE_EVENT_ADD_DEVICE,
                HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
    }

    @Test
    public void cecDevices_tracking_reportPowerStatus_broadcast_infersCec2() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int powerStatus = HdmiControlManager.POWER_STATUS_ON;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportPowerStatus(logicalAddress,
                        Constants.ADDR_BROADCAST, powerStatus));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getPhysicalAddress()).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(cecDeviceInfo.getDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_RESERVED);
        assertThat(cecDeviceInfo.getVendorId()).isEqualTo(Constants.UNKNOWN_VENDOR_ID);
        assertThat(cecDeviceInfo.getDisplayName()).isEqualTo(
                HdmiUtils.getDefaultDeviceName(logicalAddress));
        assertThat(cecDeviceInfo.getDevicePowerStatus()).isEqualTo(powerStatus);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void cecDevices_tracking_reportCecVersion_tracksCecVersion_cec14() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildCecVersion(logicalAddress, Constants.ADDR_BROADCAST,
                        cecVersion));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(cecVersion);
    }

    @Test
    public void cecDevices_tracking_reportCecVersion_tracksCecVersion_cec20() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildCecVersion(logicalAddress, Constants.ADDR_BROADCAST,
                        cecVersion));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(cecVersion);
    }

    @Test
    public void cecDevices_tracking_reportFeatures_tracksCecVersion_cec14() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportFeatures(logicalAddress,
                        cecVersion, Collections.emptyList(),
                        Constants.RC_PROFILE_SOURCE, Collections.emptyList(),
                        Collections.emptyList()));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(cecVersion);
    }

    @Test
    public void cecDevices_tracking_reportFeatures_tracksCecVersion_cec20() {
        int logicalAddress = Constants.ADDR_PLAYBACK_1;
        int cecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;
        mHdmiCecNetwork.handleCecMessage(
                HdmiCecMessageBuilder.buildReportFeatures(logicalAddress,
                        cecVersion, Collections.emptyList(),
                        Constants.RC_PROFILE_SOURCE, Collections.emptyList(),
                        Collections.emptyList()));

        assertThat(mHdmiCecNetwork.getSafeCecDevicesLocked()).hasSize(1);

        HdmiDeviceInfo cecDeviceInfo = mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
        assertThat(cecDeviceInfo.getLogicalAddress()).isEqualTo(logicalAddress);
        assertThat(cecDeviceInfo.getCecVersion()).isEqualTo(cecVersion);
    }
}
