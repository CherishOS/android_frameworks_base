/*
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
package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.R;
import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION, resourceDir =
        "../../res")
public class CachedBluetoothDeviceTest {
    private final static String DEVICE_NAME = "TestName";
    private final static String DEVICE_ALIAS = "TestAlias";
    private final static String DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF";
    private final static String DEVICE_ALIAS_NEW = "TestAliasNew";
    @Mock
    private LocalBluetoothAdapter mAdapter;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HeadsetProfile mHfpProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private HidProfile mHidProfile;
    @Mock
    private BluetoothDevice mDevice;
    private CachedBluetoothDevice mCachedDevice;
    private Context mContext;
    private int mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        when(mDevice.getAddress()).thenReturn(DEVICE_ADDRESS);
        when(mAdapter.getBluetoothState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mHfpProfile.isProfileReady()).thenReturn(true);
        when(mA2dpProfile.isProfileReady()).thenReturn(true);
        when(mHidProfile.isProfileReady()).thenReturn(true);
        mCachedDevice = spy(
                new CachedBluetoothDevice(mContext, mAdapter, mProfileManager, mDevice));
        doAnswer((invocation) -> mBatteryLevel).when(mCachedDevice).getBatteryLevel();
    }

    /**
     * Test to verify the current test context object works so that we are not checking null
     * against null
     */
    @Test
    public void testContextMock() {
        assertThat(mContext.getString(R.string.bluetooth_connected)).isEqualTo("Connected");
    }

    @Test
    public void testGetConnectionSummary_testSingleProfileConnectDisconnect() {
        // Test without battery level
        // Set HID profile to be connected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected));

        // Set HID profile to be disconnected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with battery level
        mBatteryLevel = 10;
        // Set HID profile to be connected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected_battery_level,
                com.android.settingslib.Utils.formatPercentage(mBatteryLevel)));

        // Set HID profile to be disconnected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();

        // Test with BluetoothDevice.BATTERY_LEVEL_UNKNOWN battery level
        mBatteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Set HID profile to be connected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected));

        // Set HID profile to be disconnected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void testGetConnectionSummary_testMultipleProfileConnectDisconnect() {
        mBatteryLevel = 10;

        // Set HFP, A2DP and HID profile to be connected and test connection state summary
        mCachedDevice.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_CONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected_battery_level,
                com.android.settingslib.Utils.formatPercentage(mBatteryLevel)));

        // Disconnect HFP only and test connection state summary
        mCachedDevice.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected_no_headset_battery_level,
                com.android.settingslib.Utils.formatPercentage(mBatteryLevel)));

        // Disconnect A2DP only and test connection state summary
        mCachedDevice.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_CONNECTED);
        mCachedDevice.onProfileStateChanged(mA2dpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected_no_a2dp_battery_level,
                com.android.settingslib.Utils.formatPercentage(mBatteryLevel)));

        // Disconnect both HFP and A2DP and test connection state summary
        mCachedDevice.onProfileStateChanged(mHfpProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isEqualTo(mContext.getString(
                R.string.bluetooth_connected_no_headset_no_a2dp_battery_level,
                com.android.settingslib.Utils.formatPercentage(mBatteryLevel)));

        // Disconnect all profiles and test connection state summary
        mCachedDevice.onProfileStateChanged(mHidProfile, BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mCachedDevice.getConnectionSummary()).isNull();
    }

    @Test
    public void testDeviceName_testAliasNameAvailable() {
        when(mDevice.getAliasName()).thenReturn(DEVICE_ALIAS);
        when(mDevice.getName()).thenReturn(DEVICE_NAME);
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mAdapter, mProfileManager, mDevice);
        // Verify alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS);
        // Verify device is visible
        assertThat(cachedBluetoothDevice.hasHumanReadableName()).isTrue();
    }

    @Test
    public void testDeviceName_testNameNotAvailable() {
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mAdapter, mProfileManager, mDevice);
        // Verify device address is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ADDRESS);
        // Verify device is not visible
        assertThat(cachedBluetoothDevice.hasHumanReadableName()).isFalse();
    }

    @Test
    public void testDeviceName_testRenameDevice() {
        final String[] alias = {DEVICE_ALIAS};
        doAnswer(invocation -> alias[0]).when(mDevice).getAliasName();
        doAnswer(invocation -> {
            alias[0] = (String) invocation.getArguments()[0];
            return true;
        }).when(mDevice).setAlias(anyString());
        when(mDevice.getName()).thenReturn(DEVICE_NAME);
        CachedBluetoothDevice cachedBluetoothDevice =
                new CachedBluetoothDevice(mContext, mAdapter, mProfileManager, mDevice);
        // Verify alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS);
        // Verify null name does not get set
        cachedBluetoothDevice.setName(null);
        verify(mDevice, never()).setAlias(any());
        // Verify new name is set properly
        cachedBluetoothDevice.setName(DEVICE_ALIAS_NEW);
        verify(mDevice).setAlias(DEVICE_ALIAS_NEW);
        // Verify new alias is returned on getName
        assertThat(cachedBluetoothDevice.getName()).isEqualTo(DEVICE_ALIAS_NEW);
    }
}
