/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class OverlayShadeViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor = kosmos.sceneInteractor
    private val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }

    private val underTest = kosmos.overlayShadeViewModel

    @Test
    fun backgroundScene_deviceLocked_lockscreen() =
        testScope.runTest {
            val backgroundScene by collectLastValue(underTest.backgroundScene)

            lockDevice()

            assertThat(backgroundScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun backgroundScene_deviceUnlocked_gone() =
        testScope.runTest {
            val backgroundScene by collectLastValue(underTest.backgroundScene)

            lockDevice()
            unlockDevice()

            assertThat(backgroundScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun backgroundScene_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val backgroundScene by collectLastValue(underTest.backgroundScene)
            val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            runCurrent()

            assertThat(backgroundScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun backgroundScene_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val backgroundScene by collectLastValue(underTest.backgroundScene)
            val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            runCurrent()

            assertThat(backgroundScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun onScrimClicked_onLockscreen_goesToLockscreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            lockDevice()
            sceneInteractor.changeScene(Scenes.Bouncer, "reason")
            runCurrent()
            assertThat(currentScene).isNotEqualTo(Scenes.Lockscreen)

            underTest.onScrimClicked()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun onScrimClicked_deviceWasEntered_goesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backgroundScene by collectLastValue(underTest.backgroundScene)

            lockDevice()
            unlockDevice()
            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            runCurrent()
            assertThat(backgroundScene).isEqualTo(Scenes.Gone)
            assertThat(currentScene).isNotEqualTo(Scenes.Gone)

            underTest.onScrimClicked()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    private fun TestScope.lockDevice() {
        val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
        assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        runCurrent()
    }

    private fun TestScope.unlockDevice() {
        val deviceUnlockStatus by collectLastValue(deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        sceneInteractor.changeScene(Scenes.Gone, "reason")
        runCurrent()
    }
}
