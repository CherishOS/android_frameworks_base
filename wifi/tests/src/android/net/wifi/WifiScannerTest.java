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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.PnoSettings.PnoNetwork;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.util.test.BidirectionalAsyncChannelServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/**
 * Unit tests for {@link android.net.wifi.WifiScanner}.
 */
@SmallTest
public class WifiScannerTest {
    @Mock
    private Context mContext;
    @Mock
    private IWifiScanner mService;

    private static final boolean TEST_PNOSETTINGS_IS_CONNECTED = false;
    private static final int TEST_PNOSETTINGS_MIN_5GHZ_RSSI = -60;
    private static final int TEST_PNOSETTINGS_MIN_2GHZ_RSSI = -70;
    private static final int TEST_PNOSETTINGS_INITIAL_SCORE_MAX = 50;
    private static final int TEST_PNOSETTINGS_CURRENT_CONNECTION_BONUS = 10;
    private static final int TEST_PNOSETTINGS_SAME_NETWORK_BONUS = 11;
    private static final int TEST_PNOSETTINGS_SECURE_BONUS = 12;
    private static final int TEST_PNOSETTINGS_BAND_5GHZ_BONUS = 13;
    private static final String TEST_SSID_1 = "TEST1";
    private static final String TEST_SSID_2 = "TEST2";
    private static final int[] TEST_FREQUENCIES_1 = {};
    private static final int[] TEST_FREQUENCIES_2 = {2500, 5124};

    private WifiScanner mWifiScanner;
    private TestLooper mLooper;
    private Handler mHandler;
    private BidirectionalAsyncChannelServer mBidirectionalAsyncChannelServer;

    /**
     * Setup before tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = spy(new Handler(mLooper.getLooper()));
        mBidirectionalAsyncChannelServer = new BidirectionalAsyncChannelServer(
                mContext, mLooper.getLooper(), mHandler);
        when(mService.getMessenger()).thenReturn(mBidirectionalAsyncChannelServer.getMessenger());
        mWifiScanner = new WifiScanner(mContext, mService, mLooper.getLooper());
        mLooper.dispatchAll();
    }

    /**
     * Clean up after tests.
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Verify parcel read/write for ScanSettings.
     */
    @Test
    public void verifyScanSettingsParcelWithBand() throws Exception {
        ScanSettings writeSettings = new ScanSettings();
        writeSettings.type = WifiScanner.TYPE_LOW_POWER;
        writeSettings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;

        ScanSettings readSettings = parcelWriteRead(writeSettings);
        assertEquals(readSettings.type, writeSettings.type);
        assertEquals(readSettings.band, writeSettings.band);
        assertEquals(0, readSettings.channels.length);
    }

    /**
     * Verify parcel read/write for ScanSettings.
     */
    @Test
    public void verifyScanSettingsParcelWithChannels() throws Exception {
        ScanSettings writeSettings = new ScanSettings();
        writeSettings.type = WifiScanner.TYPE_HIGH_ACCURACY;
        writeSettings.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        writeSettings.channels = new WifiScanner.ChannelSpec[] {
                new WifiScanner.ChannelSpec(5),
                new WifiScanner.ChannelSpec(7)
        };

        ScanSettings readSettings = parcelWriteRead(writeSettings);
        assertEquals(writeSettings.type, readSettings.type);
        assertEquals(writeSettings.band, readSettings.band);
        assertEquals(2, readSettings.channels.length);
        assertEquals(5, readSettings.channels[0].frequency);
        assertEquals(7, readSettings.channels[1].frequency);
    }

    /**
     * Write the provided {@link ScanSettings} to a parcel and deserialize it.
     */
    private static ScanSettings parcelWriteRead(ScanSettings writeSettings) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeSettings.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        return ScanSettings.CREATOR.createFromParcel(parcel);
    }

    /**
     *  PnoSettings object can be serialized and deserialized, while keeping the
     *  values unchanged.
     */
    @Test
    public void canSerializeAndDeserializePnoSettings() throws Exception {

        PnoSettings pnoSettings = new PnoSettings();

        PnoNetwork pnoNetwork1 = new PnoNetwork(TEST_SSID_1);
        PnoNetwork pnoNetwork2 = new PnoNetwork(TEST_SSID_2);
        pnoNetwork1.frequencies = TEST_FREQUENCIES_1;
        pnoNetwork2.frequencies = TEST_FREQUENCIES_2;

        pnoSettings.networkList = new PnoNetwork[]{pnoNetwork1, pnoNetwork2};
        pnoSettings.isConnected = TEST_PNOSETTINGS_IS_CONNECTED;
        pnoSettings.min5GHzRssi = TEST_PNOSETTINGS_MIN_5GHZ_RSSI;
        pnoSettings.min24GHzRssi = TEST_PNOSETTINGS_MIN_2GHZ_RSSI;
        pnoSettings.initialScoreMax = TEST_PNOSETTINGS_INITIAL_SCORE_MAX;
        pnoSettings.currentConnectionBonus = TEST_PNOSETTINGS_CURRENT_CONNECTION_BONUS;
        pnoSettings.sameNetworkBonus = TEST_PNOSETTINGS_SAME_NETWORK_BONUS;
        pnoSettings.secureBonus = TEST_PNOSETTINGS_SECURE_BONUS;
        pnoSettings.band5GHzBonus = TEST_PNOSETTINGS_BAND_5GHZ_BONUS;

        Parcel parcel = Parcel.obtain();
        pnoSettings.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        PnoSettings pnoSettingsDeserialized =
                pnoSettings.CREATOR.createFromParcel(parcel);

        assertNotNull(pnoSettingsDeserialized);
        assertEquals(TEST_PNOSETTINGS_IS_CONNECTED, pnoSettingsDeserialized.isConnected);
        assertEquals(TEST_PNOSETTINGS_MIN_5GHZ_RSSI, pnoSettingsDeserialized.min5GHzRssi);
        assertEquals(TEST_PNOSETTINGS_MIN_2GHZ_RSSI, pnoSettingsDeserialized.min24GHzRssi);
        assertEquals(TEST_PNOSETTINGS_INITIAL_SCORE_MAX, pnoSettingsDeserialized.initialScoreMax);
        assertEquals(TEST_PNOSETTINGS_CURRENT_CONNECTION_BONUS,
                pnoSettingsDeserialized.currentConnectionBonus);
        assertEquals(TEST_PNOSETTINGS_SAME_NETWORK_BONUS,
                pnoSettingsDeserialized.sameNetworkBonus);
        assertEquals(TEST_PNOSETTINGS_SECURE_BONUS, pnoSettingsDeserialized.secureBonus);
        assertEquals(TEST_PNOSETTINGS_BAND_5GHZ_BONUS, pnoSettingsDeserialized.band5GHzBonus);

        // Test parsing of PnoNetwork
        assertEquals(pnoSettings.networkList.length, pnoSettingsDeserialized.networkList.length);
        for (int i = 0; i < pnoSettings.networkList.length; i++) {
            PnoNetwork expected = pnoSettings.networkList[i];
            PnoNetwork actual = pnoSettingsDeserialized.networkList[i];
            assertEquals(expected.ssid, actual.ssid);
            assertEquals(expected.flags, actual.flags);
            assertEquals(expected.authBitField, actual.authBitField);
            assertTrue(Arrays.equals(expected.frequencies, actual.frequencies));
        }
    }

    /**
     *  Make sure that frequencies is not null by default.
     */
    @Test
    public void pnoNetworkFrequencyIsNotNull() throws Exception {
        PnoNetwork pnoNetwork = new PnoNetwork(TEST_SSID_1);
        assertNotNull(pnoNetwork.frequencies);
    }

    /**
     * Verify parcel read/write for ScanData.
     */
    @Test
    public void verifyScanDataParcel() throws Exception {
        ScanData writeScanData = new ScanData(2, 0, 3,
                WifiScanner.WIFI_BAND_BOTH_WITH_DFS, new ScanResult[0]);

        ScanData readScanData = parcelWriteRead(writeScanData);
        assertEquals(writeScanData.getId(), readScanData.getId());
        assertEquals(writeScanData.getFlags(), readScanData.getFlags());
        assertEquals(writeScanData.getBucketsScanned(), readScanData.getBucketsScanned());
        assertEquals(writeScanData.getBandScanned(), readScanData.getBandScanned());
        assertArrayEquals(writeScanData.getResults(), readScanData.getResults());
    }

    /**
     * Write the provided {@link ScanData} to a parcel and deserialize it.
     */
    private static ScanData parcelWriteRead(ScanData writeScanData) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeScanData.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        return ScanData.CREATOR.createFromParcel(parcel);
    }


    /**
     * Test behavior of {@link WifiScanner#startScan(ScanSettings, WifiScanner.ScanListener)}
     * @throws Exception
     */
    @Test
    public void testStartScan() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        WifiScanner.ScanListener scanListener = mock(WifiScanner.ScanListener.class);

        mWifiScanner.startScan(scanSettings, scanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler).handleMessage(messageArgumentCaptor.capture());
        Message message = messageArgumentCaptor.getValue();
        assertNotNull(message);

        assertEquals(WifiScanner.CMD_START_SINGLE_SCAN, message.what);
        assertTrue(message.obj instanceof Bundle);
        Bundle messageBundle = (Bundle) message.obj;
        assertEquals(scanSettings,
                messageBundle.getParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY));
        assertNull(messageBundle.getParcelable(WifiScanner.SCAN_PARAMS_WORK_SOURCE_KEY));
        assertEquals(mContext.getOpPackageName(),
                messageBundle.getParcelable(WifiScanner.REQUEST_PACKAGE_NAME_KEY));

    }

    /**
     * Test behavior of {@link WifiScanner#stopScan(WifiScanner.ScanListener)}
     * @throws Exception
     */
    @Test
    public void testStopScan() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        WifiScanner.ScanListener scanListener = mock(WifiScanner.ScanListener.class);

        mWifiScanner.startScan(scanSettings, scanListener);
        mLooper.dispatchAll();

        mWifiScanner.stopScan(scanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler, times(2)).handleMessage(messageArgumentCaptor.capture());
        Message message = messageArgumentCaptor.getValue();
        assertNotNull(message);

        assertEquals(WifiScanner.CMD_STOP_SINGLE_SCAN, message.what);
        assertTrue(message.obj instanceof Bundle);
        Bundle messageBundle = (Bundle) message.obj;
        assertEquals(mContext.getOpPackageName(),
                messageBundle.getParcelable(WifiScanner.REQUEST_PACKAGE_NAME_KEY));

    }

    /**
     * Test behavior of {@link WifiScanner#startScan(ScanSettings, WifiScanner.ScanListener)}
     * @throws Exception
     */
    @Test
    public void testStartScanListenerOnSuccess() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        WifiScanner.ScanListener scanListener = mock(WifiScanner.ScanListener.class);

        mWifiScanner.startScan(scanSettings, scanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler).handleMessage(messageArgumentCaptor.capture());
        Message sentMessage = messageArgumentCaptor.getValue();
        assertNotNull(sentMessage);

        assertEquals(1, mBidirectionalAsyncChannelServer.getClientMessengers().size());
        Messenger scannerMessenger =
                mBidirectionalAsyncChannelServer.getClientMessengers().iterator().next();

        Message responseMessage = Message.obtain();
        responseMessage.what = WifiScanner.CMD_OP_SUCCEEDED;
        responseMessage.arg2 = sentMessage.arg2;
        scannerMessenger.send(responseMessage);
        mLooper.dispatchAll();

        verify(scanListener).onSuccess();
    }

    /**
     * Test behavior of {@link WifiScanner#startScan(ScanSettings, WifiScanner.ScanListener)}
     * @throws Exception
     */
    @Test
    public void testStartScanListenerOnResults() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        WifiScanner.ScanListener scanListener = mock(WifiScanner.ScanListener.class);

        mWifiScanner.startScan(scanSettings, scanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler).handleMessage(messageArgumentCaptor.capture());
        Message sentMessage = messageArgumentCaptor.getValue();
        assertNotNull(sentMessage);

        assertEquals(1, mBidirectionalAsyncChannelServer.getClientMessengers().size());
        Messenger scannerMessenger =
                mBidirectionalAsyncChannelServer.getClientMessengers().iterator().next();

        ScanResult scanResult = new ScanResult();
        ScanData scanDatas[] = new ScanData[]{new ScanData(0, 0 , new ScanResult[] {scanResult})};
        Message responseMessage = Message.obtain();
        responseMessage.what = WifiScanner.CMD_SCAN_RESULT;
        responseMessage.arg2 = sentMessage.arg2;
        responseMessage.obj = new WifiScanner.ParcelableScanData(scanDatas);
        scannerMessenger.send(responseMessage);
        mLooper.dispatchAll();

        verify(scanListener).onResults(scanDatas);
    }

    /**
     * Test behavior of {@link WifiScanner#startDisconnectedPnoScan(ScanSettings, PnoSettings,
     * WifiScanner.PnoScanListener)}
     * @throws Exception
     */
    @Test
    public void testStartDisconnectedPnoScan() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        PnoSettings pnoSettings = new PnoSettings();
        WifiScanner.PnoScanListener pnoScanListener = mock(WifiScanner.PnoScanListener.class);

        mWifiScanner.startDisconnectedPnoScan(scanSettings, pnoSettings, pnoScanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler).handleMessage(messageArgumentCaptor.capture());
        Message message = messageArgumentCaptor.getValue();
        assertNotNull(message);

        assertEquals(WifiScanner.CMD_START_PNO_SCAN, message.what);
        assertTrue(message.obj instanceof Bundle);
        Bundle messageBundle = (Bundle) message.obj;
        assertEquals(scanSettings,
                messageBundle.getParcelable(WifiScanner.PNO_PARAMS_SCAN_SETTINGS_KEY));
        assertTrue(scanSettings.isPnoScan);
        assertFalse(pnoSettings.isConnected);
        assertEquals(pnoSettings,
                messageBundle.getParcelable(WifiScanner.PNO_PARAMS_PNO_SETTINGS_KEY));
    }

    /**
     * Test behavior of {@link WifiScanner#startConnectedPnoScan(ScanSettings, PnoSettings,
     * WifiScanner.PnoScanListener)}
     * @throws Exception
     */
    @Test
    public void testStartConnectedPnoScan() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        PnoSettings pnoSettings = new PnoSettings();
        WifiScanner.PnoScanListener pnoScanListener = mock(WifiScanner.PnoScanListener.class);

        mWifiScanner.startConnectedPnoScan(scanSettings, pnoSettings, pnoScanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler).handleMessage(messageArgumentCaptor.capture());
        Message message = messageArgumentCaptor.getValue();
        assertNotNull(message);

        assertEquals(WifiScanner.CMD_START_PNO_SCAN, message.what);
        assertTrue(message.obj instanceof Bundle);
        Bundle messageBundle = (Bundle) message.obj;
        assertEquals(scanSettings,
                messageBundle.getParcelable(WifiScanner.PNO_PARAMS_SCAN_SETTINGS_KEY));
        assertTrue(scanSettings.isPnoScan);
        assertTrue(pnoSettings.isConnected);
        assertEquals(pnoSettings,
                messageBundle.getParcelable(WifiScanner.PNO_PARAMS_PNO_SETTINGS_KEY));
    }

    /**
     * Test behavior of {@link WifiScanner#stopPnoScan(WifiScanner.ScanListener)}
     * WifiScanner.PnoScanListener)}
     * @throws Exception
     */
    @Test
    public void testStopPnoScan() throws Exception {
        ScanSettings scanSettings = new ScanSettings();
        PnoSettings pnoSettings = new PnoSettings();
        WifiScanner.PnoScanListener pnoScanListener = mock(WifiScanner.PnoScanListener.class);

        mWifiScanner.startDisconnectedPnoScan(scanSettings, pnoSettings, pnoScanListener);
        mLooper.dispatchAll();
        mWifiScanner.stopPnoScan(pnoScanListener);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mHandler, times(2)).handleMessage(messageArgumentCaptor.capture());
        Message message = messageArgumentCaptor.getValue();
        assertNotNull(message);

        assertEquals(WifiScanner.CMD_STOP_PNO_SCAN, message.what);
    }
}
