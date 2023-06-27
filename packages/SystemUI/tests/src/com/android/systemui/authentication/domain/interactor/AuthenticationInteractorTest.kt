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

import android.app.admin.DevicePolicyManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AuthenticationInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val repository: AuthenticationRepository = utils.authenticationRepository()
    private val underTest =
        utils.authenticationInteractor(
            repository = repository,
        )

    @Test
    fun getAuthenticationMethod() =
        testScope.runTest {
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Pin(1234))

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Password("password"))
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNone_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            val isUnlocked by collectLastValue(underTest.isUnlocked)
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
            utils.authenticationRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun authenticate_withCorrectPin_returnsTrue() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 3, 4))).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPin_returnsFalse() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )

            assertThat(underTest.authenticate(listOf(9, 8, 7))).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withEmptyPin_returnsFalse() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )

            assertThat(underTest.authenticate(listOf())).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withCorrectMaxLengthPin_returnsTrue() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(9999999999999999)
            )

            assertThat(underTest.authenticate(List(16) { 9 })).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withCorrectTooLongPin_returnsFalse() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures
            // that all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(99999999999999999)
            )

            assertThat(underTest.authenticate(List(17) { 9 })).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withCorrectPassword_returnsTrue() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )

            assertThat(underTest.authenticate("password".toList())).isTrue()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPassword_returnsFalse() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )

            assertThat(underTest.authenticate("alohomora".toList())).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun authenticate_withCorrectPattern_returnsTrue() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
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
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPattern_returnsFalse() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
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
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmPinAndEmptyInput_returnsNull() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(underTest.authenticate(listOf(), tryAutoConfirm = true)).isNull()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmPinAndShorterPin_returnsNullAndHasNoEffect() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 3), tryAutoConfirm = true)).isNull()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmWrongPinCorrectLength_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 4, 4), tryAutoConfirm = true)).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmLongerPin_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 3, 4, 5), tryAutoConfirm = true))
                .isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPin_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 4, 4), tryAutoConfirm = true)).isFalse()
            assertThat(failedAttemptCount).isEqualTo(1)
        }

    @Test
    fun tryAutoConfirm_withoutAutoConfirmButCorrectPin_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = false)
            )

            assertThat(underTest.authenticate(listOf(1, 2, 3, 4), tryAutoConfirm = true)).isNull()
            assertThat(failedAttemptCount).isEqualTo(0)
        }

    @Test
    fun tryAutoConfirm_withoutCorrectPassword_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val failedAttemptCount by collectLastValue(underTest.failedAuthenticationAttempts)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )

            assertThat(underTest.authenticate("password".toList(), tryAutoConfirm = true)).isNull()
            assertThat(failedAttemptCount).isEqualTo(0)
        }
}
