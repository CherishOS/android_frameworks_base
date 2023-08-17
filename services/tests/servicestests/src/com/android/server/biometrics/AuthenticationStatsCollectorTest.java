/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptySet;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.biometrics.sensors.BiometricNotification;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

@Presubmit
@SmallTest
public class AuthenticationStatsCollectorTest {

    private AuthenticationStatsCollector mAuthenticationStatsCollector;
    private static final float FRR_THRESHOLD = 0.2f;
    private static final int USER_ID_1 = 1;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private BiometricNotification mBiometricNotification;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getFraction(eq(R.fraction.config_biometricNotificationFrrThreshold),
                anyInt(), anyInt())).thenReturn(FRR_THRESHOLD);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        when(mContext.getSystemServiceName(FingerprintManager.class))
                .thenReturn(Context.FINGERPRINT_SERVICE);
        when(mContext.getSystemService(Context.FINGERPRINT_SERVICE))
                .thenReturn(mFingerprintManager);
        when(mContext.getSystemServiceName(FaceManager.class)).thenReturn(Context.FACE_SERVICE);
        when(mContext.getSystemService(Context.FACE_SERVICE)).thenReturn(mFaceManager);

        when(mContext.getSharedPreferences(any(File.class), anyInt()))
                .thenReturn(mSharedPreferences);
        when(mSharedPreferences.getStringSet(anyString(), anySet())).thenReturn(emptySet());

        mAuthenticationStatsCollector = new AuthenticationStatsCollector(mContext,
                0 /* modality */, mBiometricNotification);
    }


    @Test
    public void authenticate_authenticationSucceeded_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertThat(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1)).isNull();

        mAuthenticationStatsCollector.authenticate(USER_ID_1, true /* authenticated */);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getUserId()).isEqualTo(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getEnrollmentNotifications()).isEqualTo(0);
    }

    @Test
    public void authenticate_authenticationFailed_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertThat(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1)).isNull();

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);

        assertThat(authenticationStats.getUserId()).isEqualTo(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getEnrollmentNotifications()).isEqualTo(0);
    }

    @Test
    public void authenticate_frrNotExceeded_notificationNotExceeded_shouldNotSendNotification() {

        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        40 /* rejectedAttempts */, 0 /* enrollmentNotifications */,
                        0 /* modality */));

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that no notification should be sent.
        verify(mBiometricNotification, never()).sendFaceEnrollNotification(any());
        verify(mBiometricNotification, never()).sendFpEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }

    @Test
    public void authenticate_frrExceeded_notificationExceeded_shouldNotSendNotification() {

        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        400 /* rejectedAttempts */, 2 /* enrollmentNotifications */,
                        0 /* modality */));

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that no notification should be sent.
        verify(mBiometricNotification, never()).sendFaceEnrollNotification(any());
        verify(mBiometricNotification, never()).sendFpEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }

    @Test
    public void authenticate_frrExceeded_bothBiometricsEnrolled_shouldNotSendNotification() {

        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        400 /* rejectedAttempts */, 0 /* enrollmentNotifications */,
                        0 /* modality */));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that no notification should be sent.
        verify(mBiometricNotification, never()).sendFaceEnrollNotification(any());
        verify(mBiometricNotification, never()).sendFpEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }

    @Test
    public void authenticate_frrExceeded_singleModality_shouldNotSendNotification() {

        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        400 /* rejectedAttempts */, 0 /* enrollmentNotifications */,
                        0 /* modality */));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(false);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that no notification should be sent.
        verify(mBiometricNotification, never()).sendFaceEnrollNotification(any());
        verify(mBiometricNotification, never()).sendFpEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }

    @Test
    public void authenticate_frrExceeded_faceEnrolled_shouldSendFpNotification() {
        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        400 /* rejectedAttempts */, 0 /* enrollmentNotifications */,
                        0 /* modality */));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(false);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that fingerprint enrollment notification should be sent.
        verify(mBiometricNotification, times(1))
                .sendFpEnrollNotification(mContext);
        verify(mBiometricNotification, never()).sendFaceEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }

    @Test
    public void authenticate_frrExceeded_fpEnrolled_shouldSendFaceNotification() {
        mAuthenticationStatsCollector.setAuthenticationStatsForUser(USER_ID_1,
                new AuthenticationStats(USER_ID_1, 500 /* totalAttempts */,
                        400 /* rejectedAttempts */, 0 /* enrollmentNotifications */,
                        0 /* modality */));

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(false);

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated */);

        // Assert that fingerprint enrollment notification should be sent.
        verify(mBiometricNotification, times(1))
                .sendFaceEnrollNotification(mContext);
        verify(mBiometricNotification, never()).sendFpEnrollNotification(any());
        // Assert that data has been reset.
        AuthenticationStats authenticationStats = mAuthenticationStatsCollector
                .getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getFrr()).isWithin(0f).of(-1.0f);
    }
}
