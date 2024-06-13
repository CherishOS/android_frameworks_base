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

package com.android.systemui.communal.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
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
class CommunalTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val communalSceneRepository = kosmos.fakeCommunalSceneRepository

    private val underTest: CommunalTransitionViewModel by lazy {
        kosmos.communalTransitionViewModel
    }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenLockscreenAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenDreamingAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.DREAMING)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.DREAMING)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenOccludedAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.OCCLUDED)
            assertThat(isUmoOnCommunal).isTrue()

            exitCommunal(to = KeyguardState.OCCLUDED)
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun isUmoOnCommunal_noLongerVisible_returnsFalse() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            runCurrent()

            enterCommunal(from = KeyguardState.LOCKSCREEN)
            assertThat(isUmoOnCommunal).isTrue()

            // Communal is no longer visible.
            communalSceneRepository.changeScene(CommunalScenes.Blank)

            // isUmoOnCommunal returns false, even without any keyguard transition.
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun isUmoOnCommunal_idleOnCommunal_returnsTrue() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isFalse()

            // Communal is fully visible.
            communalSceneRepository.changeScene(CommunalScenes.Communal)

            // isUmoOnCommunal returns true, even without any keyguard transition.
            assertThat(isUmoOnCommunal).isTrue()
        }

    private suspend fun TestScope.enterCommunal(from: KeyguardState) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = from,
            to = KeyguardState.GLANCEABLE_HUB,
            testScope
        )
        communalSceneRepository.changeScene(CommunalScenes.Communal)
        runCurrent()
    }

    private suspend fun TestScope.exitCommunal(to: KeyguardState) {
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.GLANCEABLE_HUB,
            to = to,
            testScope
        )
        communalSceneRepository.changeScene(CommunalScenes.Blank)
        runCurrent()
    }
}
