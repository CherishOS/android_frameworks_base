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

package android.net.wifi;

import static org.junit.Assert.*;

import android.net.MacAddress;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.PasspointTestUtils;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkSuggestion}.
 */
@SmallTest
public class WifiNetworkSuggestionTest {
    private static final int TEST_UID = 45677;
    private static final int TEST_UID_OTHER = 45673;
    private static final String TEST_SSID = "\"Test123\"";
    private static final String TEST_BSSID = "12:12:12:12:12:12";
    private static final String TEST_SSID_1 = "\"Test1234\"";
    private static final String TEST_PRESHARED_KEY = "Test123";
    private static final String TEST_FQDN = "fqdn";
    private static final String TEST_WAPI_CERT_SUITE = "suite";

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for Open network which requires
     * app interaction.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForOpenNetworkWithReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setIsAppInteractionRequired(true)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.NONE));
        assertTrue(suggestion.isAppInteractionRequired);
        assertFalse(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_NONE,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(-1, suggestion.wifiConfiguration.priority);
        assertEquals(false, suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WPA_EAP network which requires
     * app interaction, not share credential and has a priority of zero set.
     */
    @Test
    public void
            testWifiNetworkSuggestionBuilderForWpa2EapNetworkWithPriorityAndReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setIsAppInteractionRequired(true)
                .setIsUserAllowedToManuallyConnect(false)
                .setPriority(0)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.isAppInteractionRequired);
        assertFalse(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_NONE,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(0, suggestion.wifiConfiguration.priority);
        assertEquals(false, suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WPA_PSK network which requires
     * user interaction and is metered.
     */
    @Test
    public void
            testWifiNetworkSuggestionBuilderForWpa2PskNetworkWithMeteredAndReqUserInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setIsUserInteractionRequired(true)
                .setIsMetered(true)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertFalse(suggestion.isAppInteractionRequired);
        assertTrue(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_METERED,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(-1, suggestion.wifiConfiguration.priority);
        assertTrue(suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for OWE network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForEnhancedOpenNetworkWithBssid() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.fromString(TEST_BSSID))
                .setIsEnhancedOpen(true)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertEquals(TEST_BSSID, suggestion.wifiConfiguration.BSSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.OWE));
        assertNull(suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.wifiConfiguration.requirePMF);
        assertFalse(suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for SAE network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpa3PskNetwork() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setIsUserAllowedToManuallyConnect(true)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.SAE));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.wifiConfiguration.requirePMF);
        assertTrue(suggestion.isUserAllowedToManuallyConnect);
    }


    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for SuiteB network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpa3EapNetwork() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);

        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3EnterpriseConfig(enterpriseConfig)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.SUITE_B_192));
        assertTrue(suggestion.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.GCMP_256));
        assertTrue(suggestion.wifiConfiguration.allowedGroupManagementCiphers
                .get(WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256));
        assertTrue(suggestion.wifiConfiguration.requirePMF);
        assertNull(suggestion.wifiConfiguration.preSharedKey);
        // allowedSuiteBCiphers are set according to the loaded certificate and cannot be tested
        // here.
        assertTrue(suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WAPI-PSK network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWapiPskNetwork() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWapiPassphrase(TEST_PRESHARED_KEY)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WAPI_PSK));
        assertTrue(suggestion.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.SMS4));
        assertTrue(suggestion.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.SMS4));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
    }


    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WAPI-CERT network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWapiCertNetwork() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.WAPI_CERT);
        enterpriseConfig.setWapiCertSuite(TEST_WAPI_CERT_SUITE);
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWapiEnterpriseConfig(enterpriseConfig)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WAPI_CERT));
        assertTrue(suggestion.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.SMS4));
        assertTrue(suggestion.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.SMS4));
        assertNull(suggestion.wifiConfiguration.preSharedKey);
        assertNotNull(suggestion.wifiConfiguration.enterpriseConfig);
        assertEquals(WifiEnterpriseConfig.Eap.WAPI_CERT,
                suggestion.wifiConfiguration.enterpriseConfig.getEapMethod());
        assertEquals(TEST_WAPI_CERT_SUITE,
                suggestion.wifiConfiguration.enterpriseConfig.getWapiCertSuite());
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WAPI-CERT network
     * which selects the certificate suite automatically.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWapiCertAutoNetwork() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.WAPI_CERT);
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWapiEnterpriseConfig(enterpriseConfig)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WAPI_CERT));
        assertTrue(suggestion.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.SMS4));
        assertTrue(suggestion.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.SMS4));
        assertNull(suggestion.wifiConfiguration.preSharedKey);
        assertNotNull(suggestion.wifiConfiguration.enterpriseConfig);
        assertEquals(WifiEnterpriseConfig.Eap.WAPI_CERT,
                suggestion.wifiConfiguration.enterpriseConfig.getEapMethod());
        assertEquals("",
                suggestion.wifiConfiguration.enterpriseConfig.getWapiCertSuite());
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for Passpoint network which requires
     *  app interaction and metered.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForPasspointNetworkWithReqAppInteractionMetered() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration)
                .setIsAppInteractionRequired(true)
                .setIsMetered(true)
                .build();
        assertEquals(TEST_FQDN, suggestion.wifiConfiguration.FQDN);
        assertTrue(suggestion.isAppInteractionRequired);
        assertEquals(suggestion.wifiConfiguration.meteredOverride,
                WifiConfiguration.METERED_OVERRIDE_METERED);
        assertTrue(suggestion.isUserAllowedToManuallyConnect);
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setSsid(String)} throws an exception
     * when the string is not Unicode.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderSetSsidWithNonUnicodeString() {
        new WifiNetworkSuggestion.Builder()
                .setSsid("\ud800")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setWpa2Passphrase(String)} throws an exception
     * when the string is not ASCII encodable.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderSetWpa2PasphraseWithNonAsciiString() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase("salvē")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)}}
     * throws an exception when the PasspointConfiguration is not valid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderSetPasspointConfigWithNonValid() {
        PasspointConfiguration passpointConfiguration = new PasspointConfiguration();
        new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setSsid(String)} is not set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithNoSsid() {
        new WifiNetworkSuggestion.Builder()
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setSsid(String)} is invoked with an invalid value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidSsid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid("")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setBssid(MacAddress)} is invoked with an invalid
     * value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidBroadcastBssid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.BROADCAST_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setBssid(MacAddress)} is invoked with an invalid
     * value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidAllZeroBssid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(WifiManager.ALL_ZEROS_MAC_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setPriority(int)} throws an exception
     * when the value is negative.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidPriority() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setPriority(-2)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa2Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa2PasphraseAndWpa3Passphrase() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setWpa3EnterpriseConfig(WifiEnterpriseConfig)} are
     * invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa3PasphraseAndEnterprise() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setWpa3EnterpriseConfig(new WifiEnterpriseConfig())
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setIsEnhancedOpen(boolean)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa3PasphraseAndEnhancedOpen() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setIsEnhancedOpen(true)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setSsid(String)} and
     * {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothSsidAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa2Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa2PassphraseAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa3PassphraseAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3EnterpriseConfig(WifiEnterpriseConfig)}
     * and {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are
     * invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothEnterpriseAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setWpa3EnterpriseConfig(new WifiEnterpriseConfig())
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setIsEnhancedOpen(boolean)} and
     * {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothEnhancedOpenAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setIsEnhancedOpen(true)
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setIsHiddenSsid(boolean)} and
     * {@link WifiNetworkSuggestion.Builder#setPasspointConfig(PasspointConfiguration)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothHiddenSsidAndPasspointConfig() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        new WifiNetworkSuggestion.Builder()
                .setIsHiddenSsid(true)
                .setPasspointConfig(passpointConfiguration)
                .build();
    }

    /**
     * Check that parcel marshalling/unmarshalling works
     */
    @Test
    public void testWifiNetworkSuggestionParcel() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion(
                configuration, null, false, true, true);

        Parcel parcelW = Parcel.obtain();
        suggestion.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkSuggestion parcelSuggestion =
                WifiNetworkSuggestion.CREATOR.createFromParcel(parcelR);

        // Two suggestion objects are considered equal if they point to the same network (i.e same
        // SSID + keyMgmt + same UID). |isAppInteractionRequired| & |isUserInteractionRequired| are
        // not considered for equality and hence needs to be checked for explicitly below.
        assertEquals(suggestion, parcelSuggestion);
        assertEquals(suggestion.hashCode(), parcelSuggestion.hashCode());
        assertEquals(suggestion.isAppInteractionRequired,
                parcelSuggestion.isAppInteractionRequired);
        assertEquals(suggestion.isUserInteractionRequired,
                parcelSuggestion.isUserInteractionRequired);
    }

    /**
     * Check that parcel marshalling/unmarshalling works
     */
    @Test
    public void testPasspointNetworkSuggestionParcel() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration)
                .build();

        Parcel parcelW = Parcel.obtain();
        suggestion.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkSuggestion parcelSuggestion =
                WifiNetworkSuggestion.CREATOR.createFromParcel(parcelR);

        // Two suggestion objects are considered equal if they point to the same network (i.e same
        // SSID + keyMgmt + same UID). |isAppInteractionRequired| & |isUserInteractionRequired| are
        // not considered for equality and hence needs to be checked for explicitly below.
        assertEquals(suggestion, parcelSuggestion);
        assertEquals(suggestion.hashCode(), parcelSuggestion.hashCode());
        assertEquals(suggestion.isAppInteractionRequired,
                parcelSuggestion.isAppInteractionRequired);
        assertEquals(suggestion.isUserInteractionRequired,
                parcelSuggestion.isUserInteractionRequired);
    }

    /**
     * Check NetworkSuggestion equals returns {@code true} for 2 network suggestions with the same
     * SSID, BSSID, key mgmt and UID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsSame() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, null, true, false, true);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.BSSID = TEST_BSSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, null, false, true, true);

        assertEquals(suggestion, suggestion1);
        assertEquals(suggestion.hashCode(), suggestion1.hashCode());
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * BSSID, key mgmt and UID, but different SSID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenSsidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, null, false, false, true);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID_1;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, null, false, false, true);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, key mgmt and UID, but different BSSID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenBssidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, null,  false, false, true);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, null, false, false, true);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, BSSID and UID, but different key mgmt.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenKeyMgmtIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, null, false, false, true);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, null, false, false, true);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code true} for 2 Passpoint network suggestions with
     * same FQDN.
     */
    @Test
    public void testPasspointNetworkSuggestionEqualsSameWithSameFQDN() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        PasspointConfiguration passpointConfiguration1 = PasspointTestUtils.createConfig();
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration)
                .build();
        WifiNetworkSuggestion suggestion1 = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration1)
                .build();
        assertEquals(suggestion, suggestion1);
        assertEquals(suggestion.hashCode(), suggestion1.hashCode());
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 Passpoint network suggestions with
     * different FQDN.
     */
    @Test
    public void testPasspointNetworkSuggestionNotEqualsSameWithDifferentFQDN() {
        PasspointConfiguration passpointConfiguration = PasspointTestUtils.createConfig();
        PasspointConfiguration passpointConfiguration1 = PasspointTestUtils.createConfig();
        passpointConfiguration1.getHomeSp().setFqdn(TEST_FQDN + 1);

        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration)
                .build();
        WifiNetworkSuggestion suggestion1 = new WifiNetworkSuggestion.Builder()
                .setPasspointConfig(passpointConfiguration1)
                .build();
        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setIsUserAllowedToManuallyConnect(boolean)} to
     * true on a open network suggestion.
     */
    @Test(expected = IllegalStateException.class)
    public void testSetIsUserAllowedToManuallyConnectToWithOpenNetwork() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setIsUserAllowedToManuallyConnect(true)
                .build();
    }
}
