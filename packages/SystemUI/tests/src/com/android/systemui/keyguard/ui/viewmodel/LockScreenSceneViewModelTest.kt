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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepositoryImpl
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repo.BouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.domain.interactor.LockScreenSceneInteractor
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
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
class LockScreenSceneViewModelTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val sceneInteractor =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    private val mAuthenticationInteractor =
        AuthenticationInteractor(
            applicationScope = testScope.backgroundScope,
            repository = AuthenticationRepositoryImpl(),
        )

    private val underTest =
        LockScreenSceneViewModel(
            applicationScope = testScope.backgroundScope,
            interactorFactory =
                object : LockScreenSceneInteractor.Factory {
                    override fun create(containerName: String): LockScreenSceneInteractor {
                        return LockScreenSceneInteractor(
                            applicationScope = testScope.backgroundScope,
                            authenticationInteractor = mAuthenticationInteractor,
                            bouncerInteractorFactory =
                                object : BouncerInteractor.Factory {
                                    override fun create(containerName: String): BouncerInteractor {
                                        return BouncerInteractor(
                                            applicationScope = testScope.backgroundScope,
                                            applicationContext = context,
                                            repository = BouncerRepository(),
                                            authenticationInteractor = mAuthenticationInteractor,
                                            sceneInteractor = sceneInteractor,
                                            containerName = containerName,
                                        )
                                    }
                                },
                            sceneInteractor = sceneInteractor,
                            containerName = CONTAINER_NAME,
                        )
                    }
                },
            containerName = CONTAINER_NAME
        )

    @Test
    fun lockButtonIcon_whenLocked() =
        testScope.runTest {
            val lockButtonIcon by collectLastValue(underTest.lockButtonIcon)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            mAuthenticationInteractor.lockDevice()

            assertThat((lockButtonIcon as? Icon.Resource)?.res)
                .isEqualTo(R.drawable.ic_device_lock_on)
        }

    @Test
    fun lockButtonIcon_whenUnlocked() =
        testScope.runTest {
            val lockButtonIcon by collectLastValue(underTest.lockButtonIcon)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            mAuthenticationInteractor.unlockDevice()

            assertThat((lockButtonIcon as? Icon.Resource)?.res)
                .isEqualTo(R.drawable.ic_device_lock_off)
        }

    @Test
    fun upTransitionSceneKey_swipeToUnlockedEnabled_gone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            mAuthenticationInteractor.lockDevice()

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun upTransitionSceneKey_swipeToUnlockedNotEnabled_bouncer() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            mAuthenticationInteractor.lockDevice()

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Bouncer)
        }

    @Test
    fun onLockButtonClicked_deviceLockedSecurely_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            mAuthenticationInteractor.lockDevice()
            runCurrent()

            underTest.onLockButtonClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onContentClicked_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            mAuthenticationInteractor.unlockDevice()
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onContentClicked_deviceLockedSecurely_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            mAuthenticationInteractor.lockDevice()
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onLockButtonClicked_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            mAuthenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            mAuthenticationInteractor.unlockDevice()
            runCurrent()

            underTest.onLockButtonClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
    }
}
