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

package com.android.systemui.authentication.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AuthenticationInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val utils = SceneTestUtils(this, testScope)
    private val repository: AuthenticationRepository = utils.authenticationRepository()
    private val underTest =
        utils.authenticationInteractor(
            repository = repository,
        )

    @Test
    fun authMethod() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.PIN(1234))

            underTest.setAuthenticationMethod(AuthenticationMethodModel.Password("password"))
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password("password"))
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()

            underTest.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun unlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()

            underTest.unlockDevice()
            runCurrent()

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun biometricUnlock() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()

            underTest.biometricUnlock()
            runCurrent()

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun toggleBypassEnabled() =
        testScope.runTest {
            val isBypassEnabled by collectLastValue(underTest.isBypassEnabled)
            assertThat(isBypassEnabled).isFalse()

            underTest.toggleBypassEnabled()
            assertThat(isBypassEnabled).isTrue()

            underTest.toggleBypassEnabled()
            assertThat(isBypassEnabled).isFalse()
        }

    @Test
    fun isAuthenticationRequired_lockedAndSecured_true() =
        testScope.runTest {
            underTest.lockDevice()
            runCurrent()
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Password("password"))

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            underTest.lockDevice()
            runCurrent()
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            underTest.unlockDevice()
            runCurrent()
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Password("password"))

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            underTest.unlockDevice()
            runCurrent()
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun authenticate_withCorrectPin_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(isUnlocked).isFalse()

            assertThat(underTest.authenticate(listOf(1, 2, 3, 4))).isTrue()
            assertThat(isUnlocked).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPin_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            assertThat(isUnlocked).isFalse()

            assertThat(underTest.authenticate(listOf(9, 8, 7))).isFalse()
            assertThat(isUnlocked).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withCorrectPassword_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Password("password"))
            assertThat(isUnlocked).isFalse()

            assertThat(underTest.authenticate("password".toList())).isTrue()
            assertThat(isUnlocked).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPassword_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(AuthenticationMethodModel.Password("password"))
            assertThat(isUnlocked).isFalse()

            assertThat(underTest.authenticate("alohomora".toList())).isFalse()
            assertThat(isUnlocked).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withCorrectPattern_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(
                    listOf(
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 0,
                        ),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 1,
                        ),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 2,
                        ),
                    )
                )
            )
            assertThat(isUnlocked).isFalse()

            assertThat(
                    underTest.authenticate(
                        listOf(
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 0,
                                y = 0,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 0,
                                y = 1,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 0,
                                y = 2,
                            ),
                        )
                    )
                )
                .isTrue()
            assertThat(isUnlocked).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPattern_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            underTest.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(
                    listOf(
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 0,
                        ),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 1,
                        ),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(
                            x = 0,
                            y = 2,
                        ),
                    )
                )
            )
            assertThat(isUnlocked).isFalse()

            assertThat(
                    underTest.authenticate(
                        listOf(
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 0,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 1,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 2,
                            ),
                        )
                    )
                )
                .isFalse()
            assertThat(isUnlocked).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun unlocksDevice_whenAuthMethodBecomesNone() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()

            repository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(isUnlocked).isTrue()
        }
}
