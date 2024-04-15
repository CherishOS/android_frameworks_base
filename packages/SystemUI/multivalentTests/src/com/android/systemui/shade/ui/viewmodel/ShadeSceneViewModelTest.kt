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

package com.android.systemui.shade.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.qs.footerActionsController
import com.android.systemui.qs.footerActionsViewModelFactory
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewmodel.brightnessMirrorViewModel
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.domain.startable.shadeStartable
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.android.systemui.unfold.domain.interactor.unfoldTransitionInteractor
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ShadeSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    private val shadeRepository by lazy { kosmos.shadeRepository }

    private val qsSceneAdapter = FakeQSSceneAdapter({ mock() })

    private lateinit var underTest: ShadeSceneViewModel

    @Mock private lateinit var mediaDataManager: MediaDataManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            ShadeSceneViewModel(
                applicationScope = testScope.backgroundScope,
                deviceEntryInteractor = deviceEntryInteractor,
                shadeHeaderViewModel = kosmos.shadeHeaderViewModel,
                qsSceneAdapter = qsSceneAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
                brightnessMirrorViewModel = kosmos.brightnessMirrorViewModel,
                mediaDataManager = mediaDataManager,
                shadeInteractor = kosmos.shadeInteractor,
                footerActionsViewModelFactory = kosmos.footerActionsViewModelFactory,
                footerActionsController = kosmos.footerActionsController,
                sceneInteractor = kosmos.sceneInteractor,
                unfoldTransitionInteractor = kosmos.unfoldTransitionInteractor,
            )
    }

    @Test
    fun upTransitionSceneKey_deviceLocked_lockScreen() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Up))?.toScene)
                .isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Up))?.toScene)
                .isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Up))?.toScene)
                .isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Up))?.toScene)
                .isEqualTo(Scenes.Gone)
        }

    @Test
    fun isClickable_deviceUnlocked_false() =
        testScope.runTest {
            val isClickable by collectLastValue(underTest.isClickable)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(isClickable).isFalse()
        }

    @Test
    fun isClickable_deviceLockedSecurely_true() =
        testScope.runTest {
            val isClickable by collectLastValue(underTest.isClickable)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            assertThat(isClickable).isTrue()
        }

    @Test
    fun onContentClicked_deviceLockedSecurely_switchesToLockscreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun hasActiveMedia_mediaVisible() =
        testScope.runTest {
            whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true)

            assertThat(underTest.isMediaVisible()).isTrue()
        }

    @Test
    fun doesNotHaveActiveMedia_mediaNotVisible() =
        testScope.runTest {
            whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(false)

            assertThat(underTest.isMediaVisible()).isFalse()
        }

    @Test
    fun downTransitionSceneKey_inSplitShade_null() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            kosmos.shadeStartable.start()
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Down))?.toScene).isNull()
        }

    @Test
    fun downTransitionSceneKey_notSplitShade_quickSettings() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            kosmos.shadeStartable.start()
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Down))?.toScene)
                .isEqualTo(Scenes.QuickSettings)
        }

    @Test
    fun upTransitionSceneKey_customizing_noTransition() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)

            qsSceneAdapter.setCustomizing(true)
            assertThat(
                    destinationScenes!!.keys.filterIsInstance<Swipe>().filter {
                        it.direction == SwipeDirection.Up
                    }
                )
                .isEmpty()
        }

    @Test
    fun shadeMode() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)

            shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)

            shadeRepository.setShadeMode(ShadeMode.Single)
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)

            shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)
        }

    @Test
    fun unfoldTransitionProgress() =
        testScope.runTest {
            val unfoldProvider = kosmos.fakeUnfoldTransitionProgressProvider
            val progress by collectLastValue(underTest.unfoldTransitionProgress)

            unfoldProvider.onTransitionStarted()
            assertThat(progress).isEqualTo(1f)

            repeat(10) { repetition ->
                val transitionProgress = 0.1f * (repetition + 1)
                unfoldProvider.onTransitionProgress(transitionProgress)
                assertThat(progress).isEqualTo(transitionProgress)
            }

            unfoldProvider.onTransitionFinishing()
            assertThat(progress).isEqualTo(1f)

            unfoldProvider.onTransitionFinished()
            assertThat(progress).isEqualTo(1f)
        }
}
