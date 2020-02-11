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

package com.android.server.location.gnss;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssClock;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssRequest;
import android.location.GnssSingleSatCorrection;
import android.location.IBatchedLocationCallback;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Message;
import android.os.RemoteException;

import com.android.server.LocalServices;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.GnssBatchingProvider;
import com.android.server.location.GnssCapabilitiesProvider;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssMeasurementCorrectionsProvider;
import com.android.server.location.GnssMeasurementsProvider;
import com.android.server.location.GnssMeasurementsProvider.GnssMeasurementProviderNative;
import com.android.server.location.GnssNavigationMessageProvider;
import com.android.server.location.GnssNavigationMessageProvider.GnssNavigationMessageProviderNative;
import com.android.server.location.GnssStatusListenerHelper;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.SettingsHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.location.gnss.GnssManagerService}.
 */
public class GnssManagerServiceTest {

    // Gnss Providers
    @Mock
    private GnssLocationProvider mMockGnssLocationProvider;
    @Mock
    private GnssBatchingProvider mMockGnssBatchingProvider;
    @Mock
    private GnssLocationProvider.GnssSystemInfoProvider mMockGnssSystemInfoProvider;
    @Mock
    private GnssCapabilitiesProvider mMockGnssCapabilitiesProvider;
    @Mock
    private GnssMeasurementCorrectionsProvider mMockGnssMeasurementCorrectionsProvider;
    @Mock
    private INetInitiatedListener mNetInitiatedListener;
    private GnssMeasurementsProvider mTestGnssMeasurementsProvider;
    private GnssStatusListenerHelper mTestGnssStatusProvider;
    private GnssNavigationMessageProvider mTestGnssNavigationMessageProvider;

    // Managers and services
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private SettingsHelper mSettingsHelper;
    @Mock
    private AppForegroundHelper mAppForegroundHelper;
    @Mock
    private LocationManagerInternal mLocationManagerInternal;

    // Context and handler
    @Mock
    private Handler mMockHandler;
    @Mock
    private Context mMockContext;

    // Class under test
    private GnssManagerService mGnssManagerService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        GnssLocationProvider.setIsSupportedForTest(true);

        when(mMockContext.getSystemServiceName(AppOpsManager.class)).thenReturn(
                Context.APP_OPS_SERVICE);
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(
                mAppOpsManager);
        enableLocationPermissions();

        when(mAppForegroundHelper.isAppForeground(anyInt())).thenReturn(true);

        LocalServices.addService(LocationManagerInternal.class, mLocationManagerInternal);

        // Mock Handler will execute posted runnables immediately
        when(mMockHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                (InvocationOnMock invocation) -> {
                    Message msg = (Message) (invocation.getArguments()[0]);
                    msg.getCallback().run();
                    return null;
                });

        // Setup providers
        mTestGnssMeasurementsProvider = createGnssMeasurementsProvider(
                mMockContext, mMockHandler);
        mTestGnssStatusProvider = createGnssStatusListenerHelper(
                mMockContext, mMockHandler);
        mTestGnssNavigationMessageProvider = createGnssNavigationMessageProvider(
                mMockContext, mMockHandler);

        // Setup GnssLocationProvider to return providers
        when(mMockGnssLocationProvider.getGnssStatusProvider()).thenReturn(
                mTestGnssStatusProvider);
        when(mMockGnssLocationProvider.getGnssBatchingProvider()).thenReturn(
                mMockGnssBatchingProvider);
        when(mMockGnssLocationProvider.getGnssCapabilitiesProvider()).thenReturn(
                mMockGnssCapabilitiesProvider);
        when(mMockGnssLocationProvider.getGnssSystemInfoProvider()).thenReturn(
                mMockGnssSystemInfoProvider);
        when(mMockGnssLocationProvider.getGnssMeasurementCorrectionsProvider()).thenReturn(
                mMockGnssMeasurementCorrectionsProvider);
        when(mMockGnssLocationProvider.getGnssMeasurementsProvider()).thenReturn(
                mTestGnssMeasurementsProvider);
        when(mMockGnssLocationProvider.getGnssNavigationMessageProvider()).thenReturn(
                mTestGnssNavigationMessageProvider);
        when(mMockGnssLocationProvider.getNetInitiatedListener()).thenReturn(
                mNetInitiatedListener);

        // Setup GnssBatching provider
        when(mMockGnssBatchingProvider.start(anyLong(), anyBoolean())).thenReturn(true);
        when(mMockGnssBatchingProvider.stop()).thenReturn(true);

        // Create GnssManagerService
        mGnssManagerService = new GnssManagerService(mMockContext, mSettingsHelper,
                mAppForegroundHelper, new LocationUsageLogger(),
                mMockGnssLocationProvider);
        mGnssManagerService.onSystemReady();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    private void overrideAsBinder(IInterface mockListener) {
        IBinder mockBinder = mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(mockBinder);
    }

    private IGnssStatusListener createMockGnssStatusListener() {
        IGnssStatusListener mockListener = mock(IGnssStatusListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private IGnssMeasurementsListener createMockGnssMeasurementsListener() {
        IGnssMeasurementsListener mockListener = mock(
                IGnssMeasurementsListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private IBatchedLocationCallback createMockBatchedLocationCallback() {
        IBatchedLocationCallback mockedCallback = mock(IBatchedLocationCallback.class);
        overrideAsBinder(mockedCallback);
        return mockedCallback;
    }

    private IGnssNavigationMessageListener createMockGnssNavigationMessageListener() {
        IGnssNavigationMessageListener mockListener = mock(IGnssNavigationMessageListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private GnssMeasurementCorrections createDummyGnssMeasurementCorrections() {
        GnssSingleSatCorrection gnssSingleSatCorrection =
                new GnssSingleSatCorrection.Builder().build();
        return
                new GnssMeasurementCorrections.Builder().setSingleSatelliteCorrectionList(
                        Arrays.asList(gnssSingleSatCorrection)).build();
    }

    private void enableLocationPermissions() {
        Mockito.doThrow(new SecurityException()).when(
                mMockContext).enforceCallingPermission(
                AdditionalMatchers.and(
                        AdditionalMatchers.not(eq(Manifest.permission.LOCATION_HARDWARE)),
                        AdditionalMatchers.not(eq(Manifest.permission.ACCESS_FINE_LOCATION))),
                anyString());
        when(mMockContext.checkPermission(
                eq(android.Manifest.permission.LOCATION_HARDWARE), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        // AppOpsManager will return true if OP_FINE_LOCATION is checked
        when(mAppOpsManager.checkOp(anyInt(), anyInt(), anyString())).thenAnswer(
                (InvocationOnMock invocation) -> {
                    int code = (int) (invocation.getArguments()[0]);
                    if (code == AppOpsManager.OP_FINE_LOCATION) {
                        return AppOpsManager.MODE_ALLOWED;
                    }
                    return AppOpsManager.MODE_ERRORED;
                });
    }

    private void disableLocationPermissions() {
        Mockito.doThrow(new SecurityException()).when(
                mMockContext).enforceCallingPermission(anyString(), nullable(String.class));
        Mockito.doThrow(new SecurityException()).when(
                mMockContext).checkPermission(anyString(), anyInt(), anyInt());

        when(mAppOpsManager.checkOp(anyInt(), anyInt(),
                anyString())).thenReturn(AppOpsManager.MODE_ERRORED);
    }

    private GnssStatusListenerHelper createGnssStatusListenerHelper(Context context,
            Handler handler) {
        return new GnssStatusListenerHelper(
                context, handler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return true;
            }

            @Override
            protected boolean isGpsEnabled() {
                return true;
            }
        };
    }

    private GnssMeasurementsProvider createGnssMeasurementsProvider(Context context,
            Handler handler) {
        GnssMeasurementProviderNative
                mockGnssMeasurementProviderNative = mock(GnssMeasurementProviderNative.class);
        return new GnssMeasurementsProvider(
                context, handler, mockGnssMeasurementProviderNative) {
            @Override
            protected boolean isGpsEnabled() {
                return true;
            }
        };
    }

    private GnssNavigationMessageProvider createGnssNavigationMessageProvider(Context context,
            Handler handler) {
        GnssNavigationMessageProviderNative mockGnssNavigationMessageProviderNative = mock(
                GnssNavigationMessageProviderNative.class);
        return new GnssNavigationMessageProvider(context, handler,
                mockGnssNavigationMessageProviderNative) {
            @Override
            protected boolean isGpsEnabled() {
                return true;
            }
        };
    }

    @Test
    public void getGnssYearOfHardwareTest() {
        final int gnssYearOfHardware = 2012;
        when(mMockGnssSystemInfoProvider.getGnssYearOfHardware()).thenReturn(gnssYearOfHardware);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssYearOfHardware()).isEqualTo(gnssYearOfHardware);
    }

    @Test
    public void getGnssHardwareModelNameTest() {
        final String gnssHardwareModelName = "hardwarename";
        when(mMockGnssSystemInfoProvider.getGnssHardwareModelName()).thenReturn(
                gnssHardwareModelName);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssHardwareModelName()).isEqualTo(
                gnssHardwareModelName);
    }

    @Test
    public void getGnssCapabilitiesWithoutPermissionsTest() {
        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.getGnssCapabilities("com.android.server"));
    }

    @Test
    public void getGnssCapabilitiesWithPermissionsTest() {
        final long mGnssCapabilities = 23132L;
        when(mMockGnssCapabilitiesProvider.getGnssCapabilities()).thenReturn(mGnssCapabilities);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssCapabilities("com.android.server")).isEqualTo(
                mGnssCapabilities);
    }

    @Test
    public void getGnssBatchSizeWithoutPermissionsTest() {
        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.getGnssBatchSize("com.android.server"));
    }

    @Test
    public void getGnssBatchSizeWithPermissionsTest() {
        final int gnssBatchSize = 10;
        when(mMockGnssBatchingProvider.getBatchSize()).thenReturn(gnssBatchSize);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssBatchSize("com.android.server")).isEqualTo(
                gnssBatchSize);
    }

    @Test
    public void startGnssBatchWithoutPermissionsTest() {
        final long periodNanos = 100L;
        final boolean wakeOnFifoFull = true;

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.startGnssBatch(periodNanos, wakeOnFifoFull,
                        "com.android.server"));
        verify(mMockGnssBatchingProvider, times(0)).start(periodNanos, wakeOnFifoFull);
    }

    @Test
    public void startGnssBatchWithPermissionsTest() {
        final long periodNanos = 100L;
        final boolean wakeOnFifoFull = true;

        enableLocationPermissions();

        assertThat(mGnssManagerService.startGnssBatch(periodNanos, wakeOnFifoFull,
                "com.android.server"))
                .isEqualTo(
                        true);
        verify(mMockGnssBatchingProvider, times(1)).start(100L, true);
    }

    @Test
    public void addGnssBatchCallbackWithoutPermissionsTest() throws RemoteException {
        IBatchedLocationCallback mockBatchedLocationCallback = createMockBatchedLocationCallback();
        List<Location> mockLocationList = new ArrayList<>();

        disableLocationPermissions();

        assertThrows(SecurityException.class, () -> mGnssManagerService.addGnssBatchingCallback(
                mockBatchedLocationCallback, "com.android.server", "abcd123",
                "TestBatchedLocationCallback"));

        mGnssManagerService.onReportLocation(mockLocationList);

        verify(mockBatchedLocationCallback, times(0)).onLocationBatch(mockLocationList);
    }

    @Test
    public void addGnssBatchCallbackWithPermissionsTest() throws RemoteException {
        IBatchedLocationCallback mockBatchedLocationCallback = createMockBatchedLocationCallback();
        List<Location> mockLocationList = new ArrayList<>();

        enableLocationPermissions();

        assertThat(mGnssManagerService.addGnssBatchingCallback(
                mockBatchedLocationCallback, "com.android.server",
                "abcd123", "TestBatchedLocationCallback")).isEqualTo(true);

        mGnssManagerService.onReportLocation(mockLocationList);

        verify(mockBatchedLocationCallback, times(1)).onLocationBatch(mockLocationList);
    }

    @Test
    public void replaceGnssBatchCallbackTest() throws RemoteException {
        IBatchedLocationCallback mockBatchedLocationCallback1 = createMockBatchedLocationCallback();
        IBatchedLocationCallback mockBatchedLocationCallback2 = createMockBatchedLocationCallback();
        List<Location> mockLocationList = new ArrayList<>();

        enableLocationPermissions();

        assertThat(mGnssManagerService.addGnssBatchingCallback(
                mockBatchedLocationCallback1, "com.android.server",
                "abcd123", "TestBatchedLocationCallback")).isEqualTo(true);
        assertThat(mGnssManagerService.addGnssBatchingCallback(
                mockBatchedLocationCallback2, "com.android.server",
                "abcd123", "TestBatchedLocationCallback")).isEqualTo(true);

        mGnssManagerService.onReportLocation(mockLocationList);

        verify(mockBatchedLocationCallback1, times(0)).onLocationBatch(mockLocationList);
        verify(mockBatchedLocationCallback2, times(1)).onLocationBatch(mockLocationList);
    }

    @Test
    public void flushGnssBatchWithoutPermissionsTest() {
        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.flushGnssBatch("com.android.server"));
        verify(mMockGnssBatchingProvider, times(0)).flush();
    }

    @Test
    public void flushGnssBatchWithPermissionsTest() {
        enableLocationPermissions();
        mGnssManagerService.flushGnssBatch("com.android.server");

        verify(mMockGnssBatchingProvider, times(1)).flush();
    }

    @Test
    public void removeGnssBatchingCallbackWithoutPermissionsTest() throws RemoteException {
        IBatchedLocationCallback mockBatchedLocationCallback = createMockBatchedLocationCallback();
        List<Location> mockLocationList = new ArrayList<>();

        enableLocationPermissions();

        mGnssManagerService.addGnssBatchingCallback(mockBatchedLocationCallback,
                "com.android.server", "abcd123", "TestBatchedLocationCallback");

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.removeGnssBatchingCallback());

        mGnssManagerService.onReportLocation(mockLocationList);

        verify(mockBatchedLocationCallback, times(1)).onLocationBatch(mockLocationList);
    }

    @Test
    public void removeGnssBatchingCallbackWithPermissionsTest() throws RemoteException {
        IBatchedLocationCallback mockBatchedLocationCallback = createMockBatchedLocationCallback();
        List<Location> mockLocationList = new ArrayList<>();

        enableLocationPermissions();

        mGnssManagerService.addGnssBatchingCallback(mockBatchedLocationCallback,
                "com.android.server", "abcd123", "TestBatchedLocationCallback");

        mGnssManagerService.removeGnssBatchingCallback();

        mGnssManagerService.onReportLocation(mockLocationList);

        verify(mockBatchedLocationCallback, times(0)).onLocationBatch(mockLocationList);
    }

    @Test
    public void stopGnssBatchWithoutPermissionsTest() {
        disableLocationPermissions();

        assertThrows(SecurityException.class, () -> mGnssManagerService.stopGnssBatch());
        verify(mMockGnssBatchingProvider, times(0)).stop();
    }

    @Test
    public void stopGnssBatchWithPermissionsTest() {
        enableLocationPermissions();

        assertThat(mGnssManagerService.stopGnssBatch()).isEqualTo(true);
        verify(mMockGnssBatchingProvider, times(1)).stop();
    }

    @Test
    public void registerGnssStatusCallbackWithoutPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        disableLocationPermissions();

        assertThrows(SecurityException.class, () -> mGnssManagerService
                .registerGnssStatusCallback(
                        mockGnssStatusListener, "com.android.server", "abcd123"));

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, times(0)).onFirstFix(timeToFirstFix);
    }

    @Test
    public void registerGnssStatusCallbackWithPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        enableLocationPermissions();

        assertThat(mGnssManagerService.registerGnssStatusCallback(
                mockGnssStatusListener, "com.android.server", "abcd123")).isEqualTo(true);

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, times(1)).onFirstFix(timeToFirstFix);
    }

    @Test
    public void unregisterGnssStatusCallbackWithPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        enableLocationPermissions();

        mGnssManagerService.registerGnssStatusCallback(
                mockGnssStatusListener, "com.android.server", "abcd123");

        mGnssManagerService.unregisterGnssStatusCallback(mockGnssStatusListener);

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, times(0)).onFirstFix(timeToFirstFix);
    }

    @Test
    public void addGnssMeasurementsListenerWithoutPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.addGnssMeasurementsListener(
                        new GnssRequest.Builder().build(), mockGnssMeasurementsListener,
                        "com.android.server", "abcd123", "TestGnssMeasurementsListener"));

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener, times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void addGnssMeasurementsListenerWithPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        assertThat(mGnssManagerService.addGnssMeasurementsListener(
                new GnssRequest.Builder().build(),
                mockGnssMeasurementsListener,
                "com.android.server", "abcd123",
                "TestGnssMeasurementsListener")).isEqualTo(true);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener, times(1)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void injectGnssMeasurementCorrectionsWithoutPermissionsTest() {
        GnssMeasurementCorrections gnssMeasurementCorrections =
                createDummyGnssMeasurementCorrections();

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections, "com.android.server"));
        verify(mMockGnssMeasurementCorrectionsProvider, times(0))
                .injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections);
    }

    @Test
    public void injectGnssMeasurementCorrectionsWithPermissionsTest() {
        GnssMeasurementCorrections gnssMeasurementCorrections =
                createDummyGnssMeasurementCorrections();

        enableLocationPermissions();

        mGnssManagerService.injectGnssMeasurementCorrections(
                gnssMeasurementCorrections, "com.android.server");
        verify(mMockGnssMeasurementCorrectionsProvider, times(1))
                .injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections);
    }

    @Test
    public void removeGnssMeasurementsListenerWithoutPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        mGnssManagerService.addGnssMeasurementsListener(new GnssRequest.Builder().build(),
                mockGnssMeasurementsListener,
                "com.android.server", "abcd123",
                "TestGnssMeasurementsListener");

        disableLocationPermissions();

        mGnssManagerService.removeGnssMeasurementsListener(
                mockGnssMeasurementsListener);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener, times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void removeGnssMeasurementsListenerWithPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        mGnssManagerService.addGnssMeasurementsListener(new GnssRequest.Builder().build(),
                mockGnssMeasurementsListener,
                "com.android.server", "abcd123",
                "TestGnssMeasurementsListener");

        disableLocationPermissions();

        mGnssManagerService.removeGnssMeasurementsListener(
                mockGnssMeasurementsListener);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener, times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void addGnssNavigationMessageListenerWithoutPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.addGnssNavigationMessageListener(
                        mockGnssNavigationMessageListener, "com.android.server",
                        "abcd123", "TestGnssNavigationMessageListener"));

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener, times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void addGnssNavigationMessageListenerWithPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        assertThat(mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, "com.android.server",
                "abcd123", "TestGnssNavigationMessageListener")).isEqualTo(true);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener, times(1)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void removeGnssNavigationMessageListenerWithoutPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, "com.android.server",
                "abcd123", "TestGnssNavigationMessageListener");

        disableLocationPermissions();

        mGnssManagerService.removeGnssNavigationMessageListener(
                mockGnssNavigationMessageListener);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener, times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void removeGnssNavigationMessageListenerWithPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, "com.android.server",
                "abcd123", "TestGnssNavigationMessageListener");

        mGnssManagerService.removeGnssNavigationMessageListener(
                mockGnssNavigationMessageListener);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener, times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void sendNiResponseWithPermissionsTest() throws RemoteException {
        int notifId = 0;
        int userResponse = 0;
        enableLocationPermissions();

        mGnssManagerService.sendNiResponse(notifId, userResponse);

        verify(mNetInitiatedListener, times(1)).sendNiResponse(notifId, userResponse);
    }
}
