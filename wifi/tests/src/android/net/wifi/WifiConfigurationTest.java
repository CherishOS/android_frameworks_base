/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.os.Parcel;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiConfiguration}.
 */
public class WifiConfigurationTest {

    @Before
    public void setUp() {
    }

    /**
     * Check that parcel marshalling/unmarshalling works
     *
     * Create and populate a WifiConfiguration.
     * Marshall and unmashall it, and expect to recover a copy of the original.
     * Marshall the resulting object, and expect the bytes to match the
     * first marshall result.
     */
    @Test
    public void testWifiConfigurationParcel() {
        String cookie = "C O.o |<IE";
        WifiConfiguration config = new WifiConfiguration();
        config.setPasspointManagementObjectTree(cookie);
        Parcel parcelW = Parcel.obtain();
        config.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiConfiguration reconfig = WifiConfiguration.CREATOR.createFromParcel(parcelR);

        // lacking a useful config.equals, check one field near the end.
        assertEquals(cookie, reconfig.getMoTree());

        Parcel parcelWW = Parcel.obtain();
        reconfig.writeToParcel(parcelWW, 0);
        byte[] rebytes = parcelWW.marshall();
        parcelWW.recycle();

        assertArrayEquals(bytes, rebytes);
    }

    @Test
    public void testNetworkSelectionStatusCopy() {
        NetworkSelectionStatus networkSelectionStatus = new NetworkSelectionStatus();
        networkSelectionStatus.setNotRecommended(true);

        NetworkSelectionStatus copy = new NetworkSelectionStatus();
        copy.copy(networkSelectionStatus);

        assertEquals(networkSelectionStatus.isNotRecommended(), copy.isNotRecommended());
    }

    @Test
    public void testNetworkSelectionStatusParcel() {
        NetworkSelectionStatus networkSelectionStatus = new NetworkSelectionStatus();
        networkSelectionStatus.setNotRecommended(true);

        Parcel parcelW = Parcel.obtain();
        networkSelectionStatus.writeToParcel(parcelW);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);

        NetworkSelectionStatus copy = new NetworkSelectionStatus();
        copy.readFromParcel(parcelR);

        assertEquals(networkSelectionStatus.isNotRecommended(), copy.isNotRecommended());
    }

    @Test
    public void testIsOpenNetwork_IsOpen_NullWepKeys() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = null;

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_IsOpen_ZeroLengthWepKeysArray() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[0];

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_IsOpen_NullWepKeysArray() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[1];

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasWepKeys() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[] {"test"};

        assertFalse(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasNullWepKeyFollowedByNonNullKey() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[] {null, null, "test"};

        assertFalse(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasAuthType() {
        for (int keyMgmt = 0; keyMgmt < WifiConfiguration.KeyMgmt.strings.length; keyMgmt++) {
            if (keyMgmt == WifiConfiguration.KeyMgmt.NONE) continue;
            WifiConfiguration config = new WifiConfiguration();
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(keyMgmt);
            config.wepKeys = null;

            assertFalse("Open network reported when key mgmt was set to "
                            + WifiConfiguration.KeyMgmt.strings[keyMgmt], config.isOpenNetwork());
        }
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasAuthTypeNoneAndMore() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        config.wepKeys = null;

        assertFalse(config.isOpenNetwork());
    }
}
