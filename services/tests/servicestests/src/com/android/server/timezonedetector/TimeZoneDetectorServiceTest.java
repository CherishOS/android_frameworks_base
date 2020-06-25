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

package com.android.server.timezonedetector;

import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_POSSESSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.timezonedetector.ITimeZoneConfigurationListener;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimeZoneDetectorServiceTest {

    private static final int ARBITRARY_USER_ID = 9999;

    private Context mMockContext;
    private StubbedTimeZoneDetectorStrategy mStubbedTimeZoneDetectorStrategy;

    private TimeZoneDetectorService mTimeZoneDetectorService;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mStubbedTimeZoneDetectorStrategy = new StubbedTimeZoneDetectorStrategy();

        mTimeZoneDetectorService = new TimeZoneDetectorService(
                mMockContext, mTestHandler, mStubbedTimeZoneDetectorStrategy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test(expected = SecurityException.class)
    public void testGetCapabilities_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        try {
            mTimeZoneDetectorService.getCapabilities();
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                    anyString());
        }
    }

    @Test
    public void testGetCapabilities() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeZoneCapabilities capabilities = createTimeZoneCapabilities();
        mStubbedTimeZoneDetectorStrategy.initializeCapabilities(capabilities);

        assertEquals(capabilities, mTimeZoneDetectorService.getCapabilities());

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testGetConfiguration_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        try {
            mTimeZoneDetectorService.getConfiguration();
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                    anyString());
        }
    }

    @Test
    public void testGetConfiguration() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeZoneConfiguration configuration =
                createTimeZoneConfiguration(false /* autoDetectionEnabled */);
        mStubbedTimeZoneDetectorStrategy.initializeConfiguration(configuration);

        assertEquals(configuration, mTimeZoneDetectorService.getConfiguration());

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testAddConfigurationListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeZoneConfigurationListener mockListener = mock(ITimeZoneConfigurationListener.class);
        try {
            mTimeZoneDetectorService.addConfigurationListener(mockListener);
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                    anyString());
        }
    }

    @Test
    public void testConfigurationChangeListenerRegistrationAndCallbacks() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeZoneConfiguration autoDetectDisabledConfiguration =
                createTimeZoneConfiguration(false /* autoDetectionEnabled */);
        mStubbedTimeZoneDetectorStrategy.initializeConfiguration(autoDetectDisabledConfiguration);

        IBinder mockListenerBinder = mock(IBinder.class);
        ITimeZoneConfigurationListener mockListener = mock(ITimeZoneConfigurationListener.class);
        when(mockListener.asBinder()).thenReturn(mockListenerBinder);

        mTimeZoneDetectorService.addConfigurationListener(mockListener);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.WRITE_SECURE_SETTINGS),
                anyString());
        verify(mockListenerBinder).linkToDeath(any(), eq(0));

        // Simulate the configuration being changed and verify the mockListener was notified.
        TimeZoneConfiguration autoDetectEnabledConfiguration =
                createTimeZoneConfiguration(true /* autoDetectionEnabled */);
        mStubbedTimeZoneDetectorStrategy.updateConfiguration(
                ARBITRARY_USER_ID, autoDetectEnabledConfiguration);

        verify(mockListener).onChange(autoDetectEnabledConfiguration);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestManualTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestManualTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        boolean expectedResult = true; // The test strategy always returns true.
        assertEquals(expectedResult,
                mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion));

        mStubbedTimeZoneDetectorStrategy.verifySuggestManualTimeZoneCalled(timeZoneSuggestion);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testSuggestTelephonyTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test(expected = SecurityException.class)
    public void testSuggestTelephonyTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestTelephonyTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();
        mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeZoneDetectorStrategy.verifySuggestTelephonyTimeZoneCalled(timeZoneSuggestion);
    }

    @Test
    public void testDump() {
        when(mMockContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mTimeZoneDetectorService.dump(null, null, null);

        verify(mMockContext).checkCallingOrSelfPermission(eq(android.Manifest.permission.DUMP));
        mStubbedTimeZoneDetectorStrategy.verifyDumpCalled();
    }

    @Test
    public void testAutoTimeZoneDetectionChanged() throws Exception {
        mTimeZoneDetectorService.handleAutoTimeZoneDetectionChanged();
        mTestHandler.assertTotalMessagesEnqueued(1);
        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeZoneDetectorStrategy.verifyHandleAutoTimeZoneConfigChangedCalled();

        mStubbedTimeZoneDetectorStrategy.resetCallTracking();

        mTimeZoneDetectorService.handleAutoTimeZoneDetectionChanged();
        mTestHandler.assertTotalMessagesEnqueued(2);
        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeZoneDetectorStrategy.verifyHandleAutoTimeZoneConfigChangedCalled();
    }

    private static TimeZoneConfiguration createTimeZoneConfiguration(
            boolean autoDetectionEnabled) {
        return new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(autoDetectionEnabled)
                .build();
    }

    private static TimeZoneCapabilities createTimeZoneCapabilities() {
        return new TimeZoneCapabilities.Builder(ARBITRARY_USER_ID)
                .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZone(CAPABILITY_POSSESSED)
                .build();
    }

    private static ManualTimeZoneSuggestion createManualTimeZoneSuggestion() {
        return new ManualTimeZoneSuggestion("TestZoneId");
    }

    private static TelephonyTimeZoneSuggestion createTelephonyTimeZoneSuggestion() {
        int slotIndex = 1234;
        return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                .setZoneId("TestZoneId")
                .setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET)
                .setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE)
                .build();
    }
}
