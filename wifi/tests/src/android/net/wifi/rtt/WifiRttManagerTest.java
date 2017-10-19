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

package android.net.wifi.rtt;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.aware.PeerHandle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test harness for WifiRttManager class.
 */
@SmallTest
public class WifiRttManagerTest {
    private WifiRttManager mDut;
    private TestLooper mMockLooper;
    private Handler mMockLooperHandler;

    private final String packageName = "some.package.name.for.rtt.app";

    @Mock
    public Context mockContext;

    @Mock
    public IWifiRttManager mockRttService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiRttManager(mockContext, mockRttService);
        mMockLooper = new TestLooper();
        mMockLooperHandler = new Handler(mMockLooper.getLooper());

        when(mockContext.getOpPackageName()).thenReturn(packageName);
    }

    /**
     * Validate ranging call flow with succesful results.
     */
    @Test
    public void testRangeSuccess() throws Exception {
        RangingRequest request = new RangingRequest.Builder().build();
        List<RangingResult> results = new ArrayList<>();
        results.add(
                new RangingResult(RangingResultCallback.STATUS_SUCCESS, (byte[]) null, 15, 5, 10,
                        666));
        RangingResultCallback callbackMock = mock(RangingResultCallback.class);
        ArgumentCaptor<IRttCallback> callbackCaptor = ArgumentCaptor.forClass(IRttCallback.class);

        // verify ranging request passed to service
        mDut.startRanging(request, callbackMock, mMockLooperHandler);
        verify(mockRttService).startRanging(any(IBinder.class), eq(packageName), eq(request),
                callbackCaptor.capture());

        // service calls back with success
        callbackCaptor.getValue().onRangingResults(RangingResultCallback.STATUS_SUCCESS, results);
        mMockLooper.dispatchAll();
        verify(callbackMock).onRangingResults(results);

        verifyNoMoreInteractions(mockRttService, callbackMock);
    }

    /**
     * Validate ranging call flow which failed.
     */
    @Test
    public void testRangeFail() throws Exception {
        RangingRequest request = new RangingRequest.Builder().build();
        RangingResultCallback callbackMock = mock(RangingResultCallback.class);
        ArgumentCaptor<IRttCallback> callbackCaptor = ArgumentCaptor.forClass(IRttCallback.class);

        // verify ranging request passed to service
        mDut.startRanging(request, callbackMock, mMockLooperHandler);
        verify(mockRttService).startRanging(any(IBinder.class), eq(packageName), eq(request),
                callbackCaptor.capture());

        // service calls back with failure code
        callbackCaptor.getValue().onRangingResults(RangingResultCallback.STATUS_FAIL, null);
        mMockLooper.dispatchAll();
        verify(callbackMock).onRangingFailure();

        verifyNoMoreInteractions(mockRttService, callbackMock);
    }

    /**
     * Validate that RangingRequest parcel works (produces same object on write/read).
     */
    @Test
    public void testRangingRequestParcel() {
        // Note: not validating parcel code of ScanResult (assumed to work)
        ScanResult scanResult1 = new ScanResult();
        scanResult1.BSSID = "00:01:02:03:04:05";
        ScanResult scanResult2 = new ScanResult();
        scanResult2.BSSID = "06:07:08:09:0A:0B";
        ScanResult scanResult3 = new ScanResult();
        scanResult3.BSSID = "AA:BB:CC:DD:EE:FF";
        List<ScanResult> scanResults2and3 = new ArrayList<>(2);
        scanResults2and3.add(scanResult2);
        scanResults2and3.add(scanResult3);
        final byte[] mac1 = HexEncoding.decode("000102030405".toCharArray(), false);
        PeerHandle peerHandle1 = new PeerHandle(12);

        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAp(scanResult1);
        builder.addAps(scanResults2and3);
        builder.addWifiAwarePeer(mac1);
        builder.addWifiAwarePeer(peerHandle1);
        RangingRequest request = builder.build();

        Parcel parcelW = Parcel.obtain();
        request.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        RangingRequest rereadRequest = RangingRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(request, rereadRequest);
    }

    /**
     * Validate that can request as many range operation as the upper limit on number of requests.
     */
    @Test
    public void testRangingRequestAtLimit() {
        ScanResult scanResult = new ScanResult();
        scanResult.BSSID = "AA:BB:CC:DD:EE:FF";
        List<ScanResult> scanResultList = new ArrayList<>();
        for (int i = 0; i < RangingRequest.getMaxPeers() - 3; ++i) {
            scanResultList.add(scanResult);
        }
        final byte[] mac1 = HexEncoding.decode("000102030405".toCharArray(), false);

        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAp(scanResult);
        builder.addAps(scanResultList);
        builder.addAp(scanResult);
        builder.addWifiAwarePeer(mac1);
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(true);
    }

    /**
     * Validate that limit on number of requests is applied.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRangingRequestPastLimit() {
        ScanResult scanResult = new ScanResult();
        List<ScanResult> scanResultList = new ArrayList<>();
        for (int i = 0; i < RangingRequest.getMaxPeers() - 2; ++i) {
            scanResultList.add(scanResult);
        }
        final byte[] mac1 = HexEncoding.decode("000102030405".toCharArray(), false);

        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addAp(scanResult);
        builder.addAps(scanResultList);
        builder.addAp(scanResult);
        builder.addWifiAwarePeer(mac1);
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(true);
    }

    /**
     * Validate that Aware requests are invalid on devices which do not support Aware
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRangingRequestWithAwareWithNoAwareSupport() {
        // create request
        RangingRequest.Builder builder = new RangingRequest.Builder();
        builder.addWifiAwarePeer(new PeerHandle(10));
        RangingRequest request = builder.build();

        // verify request
        request.enforceValidity(false);
    }

    /**
     * Validate that RangingResults parcel works (produces same object on write/read).
     */
    @Test
    public void testRangingResultsParcel() {
        // Note: not validating parcel code of ScanResult (assumed to work)
        int status = RangingResultCallback.STATUS_SUCCESS;
        final byte[] mac = HexEncoding.decode("000102030405".toCharArray(), false);
        PeerHandle peerHandle = new PeerHandle(10);
        int distanceCm = 105;
        int distanceStdDevCm = 10;
        int rssi = 5;
        long timestamp = System.currentTimeMillis();

        // RangingResults constructed with a MAC address
        RangingResult result = new RangingResult(status, mac, distanceCm, distanceStdDevCm, rssi,
                timestamp);

        Parcel parcelW = Parcel.obtain();
        result.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        RangingResult rereadResult = RangingResult.CREATOR.createFromParcel(parcelR);

        assertEquals(result, rereadResult);

        // RangingResults constructed with a PeerHandle
        result = new RangingResult(status, peerHandle, distanceCm, distanceStdDevCm, rssi,
                timestamp);

        parcelW = Parcel.obtain();
        result.writeToParcel(parcelW, 0);
        bytes = parcelW.marshall();
        parcelW.recycle();

        parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        rereadResult = RangingResult.CREATOR.createFromParcel(parcelR);

        assertEquals(result, rereadResult);
    }
}
