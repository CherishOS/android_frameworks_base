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

package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.AuthenticationStateListener
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricSourceType
import android.hardware.biometrics.events.AuthenticationAcquiredInfo
import android.hardware.biometrics.events.AuthenticationStartedInfo
import android.hardware.biometrics.events.AuthenticationStoppedInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.AuthenticationReason.SettingsOperations
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.shared.Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class BiometricStatusRepositoryTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var biometricManager: BiometricManager

    private lateinit var underTest: BiometricStatusRepository

    private val testScope = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() {
        mSetFlagsRule.enableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        underTest = BiometricStatusRepositoryImpl(testScope.backgroundScope, biometricManager)
    }

    @Test
    fun updatesFingerprintAuthenticationReason_whenBiometricPromptAuthenticationStarted() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(BiometricSourceType.FINGERPRINT, REASON_AUTH_BP)
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(AuthenticationReason.BiometricPromptAuthentication)
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenDeviceEntryAuthenticationStarted() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_AUTH_KEYGUARD
                    )
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(AuthenticationReason.DeviceEntryAuthentication)
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenOtherAuthenticationStarted() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_AUTH_OTHER
                    )
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(AuthenticationReason.OtherAuthentication)
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenSettingsAuthenticationStarted() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_AUTH_SETTINGS
                    )
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(AuthenticationReason.SettingsAuthentication(SettingsOperations.OTHER))
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenEnrollmentAuthenticationStarted() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_ENROLL_FIND_SENSOR
                    )
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(
                    AuthenticationReason.SettingsAuthentication(
                        SettingsOperations.ENROLL_FIND_SENSOR
                    )
                )

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_ENROLL_ENROLLING
                    )
                    .build()
            )
            assertThat(fingerprintAuthenticationReason)
                .isEqualTo(
                    AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_ENROLLING)
                )
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenAuthenticationStopped() =
        testScope.runTest {
            val fingerprintAuthenticationReason by
                collectLastValue(underTest.fingerprintAuthenticationReason)
            runCurrent()

            val listener = biometricManager.captureListener()

            listener.onAuthenticationStarted(
                AuthenticationStartedInfo.Builder(BiometricSourceType.FINGERPRINT, REASON_AUTH_BP)
                    .build()
            )
            listener.onAuthenticationStopped(
                AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT, REASON_AUTH_BP)
                    .build()
            )
            assertThat(fingerprintAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)
        }

    @Test
    fun updatesFingerprintAcquiredStatusWhenBiometricPromptAuthenticationAcquired() =
        testScope.runTest {
            val fingerprintAcquiredStatus by collectLastValue(underTest.fingerprintAcquiredStatus)
            runCurrent()

            val listener = biometricManager.captureListener()
            listener.onAuthenticationAcquired(
                AuthenticationAcquiredInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_AUTH_BP,
                        BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                    )
                    .build()
            )

            assertThat(fingerprintAcquiredStatus)
                .isEqualTo(
                    AcquiredFingerprintAuthenticationStatus(
                        AuthenticationReason.BiometricPromptAuthentication,
                        BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                    )
                )
        }

    @Test
    fun updatesFingerprintAcquiredStatusWhenDeviceEntryAuthenticationAcquired() =
        testScope.runTest {
            val fingerprintAcquiredStatus by collectLastValue(underTest.fingerprintAcquiredStatus)
            runCurrent()

            val listener = biometricManager.captureListener()
            listener.onAuthenticationAcquired(
                AuthenticationAcquiredInfo.Builder(
                        BiometricSourceType.FINGERPRINT,
                        REASON_AUTH_KEYGUARD,
                        BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                    )
                    .build()
            )

            assertThat(fingerprintAcquiredStatus)
                .isEqualTo(
                    AcquiredFingerprintAuthenticationStatus(
                        AuthenticationReason.DeviceEntryAuthentication,
                        BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                    )
                )
        }
}

private fun BiometricManager.captureListener() =
    withArgCaptor<AuthenticationStateListener> {
        verify(this@captureListener).registerAuthenticationStateListener(capture())
    }
