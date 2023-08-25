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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PinBouncerViewModelTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val utils = SceneTestUtils(this, testScope)
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val bouncerViewModel =
        BouncerViewModel(
            applicationContext = context,
            applicationScope = testScope.backgroundScope,
            interactorFactory =
                object : BouncerInteractor.Factory {
                    override fun create(containerName: String): BouncerInteractor {
                        return bouncerInteractor
                    }
                },
            containerName = CONTAINER_NAME,
        )
    private val underTest =
        PinBouncerViewModel(
            applicationScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pin, ENTER_YOUR_PIN)
        overrideResource(R.string.kg_wrong_pin, WRONG_PIN)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message?.text).isEqualTo(ENTER_YOUR_PIN)
            assertThat(pinLengths).isEqualTo(0 to 0)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onPinButtonClicked() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()

            underTest.onPinButtonClicked(1)

            assertThat(message?.text).isEmpty()
            assertThat(pinLengths).isEqualTo(0 to 1)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onBackspaceButtonClicked() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            assertThat(pinLengths).isEqualTo(0 to 1)

            underTest.onBackspaceButtonClicked()

            assertThat(message?.text).isEmpty()
            assertThat(pinLengths).isEqualTo(1 to 0)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onBackspaceButtonLongPressed() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)

            underTest.onBackspaceButtonLongPressed()
            repeat(4) { index ->
                assertThat(pinLengths).isEqualTo(4 - index to 3 - index)
                advanceTimeBy(PinBouncerViewModel.BACKSPACE_LONG_PRESS_DELAY_MS)
            }

            assertThat(message?.text).isEmpty()
            assertThat(pinLengths).isEqualTo(1 to 0)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_whenCorrect() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)

            underTest.onAuthenticateButtonClicked()

            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onAuthenticateButtonClicked_whenWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!

            underTest.onAuthenticateButtonClicked()

            assertThat(pinLengths).isEqualTo(0 to 0)
            assertThat(message?.text).isEqualTo(WRONG_PIN)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_correctAfterWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val pinLengths by collectLastValue(underTest.pinLengths)
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!
            underTest.onAuthenticateButtonClicked()
            assertThat(message?.text).isEqualTo(WRONG_PIN)
            assertThat(pinLengths).isEqualTo(0 to 0)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Enter the correct PIN:
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            assertThat(message?.text).isEmpty()

            underTest.onAuthenticateButtonClicked()

            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
        private const val ENTER_YOUR_PIN = "Enter your pin"
        private const val WRONG_PIN = "Wrong pin"
    }
}
