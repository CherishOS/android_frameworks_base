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
 *
 */

package com.android.systemui.communal.domain.interactor

import android.app.smartspace.SmartspaceTarget
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalInteractorTest : SysuiTestCase() {
    private lateinit var testScope: TestScope

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var editWidgetsActivityStarter: EditWidgetsActivityStarter

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope(StandardTestDispatcher())

        val withDeps = CommunalInteractorFactory.create(testScope)

        tutorialRepository = withDeps.tutorialRepository
        communalRepository = withDeps.communalRepository
        mediaRepository = withDeps.mediaRepository
        widgetRepository = withDeps.widgetRepository
        smartspaceRepository = withDeps.smartspaceRepository
        keyguardRepository = withDeps.keyguardRepository
        editWidgetsActivityStarter = withDeps.editWidgetsActivityStarter

        underTest = withDeps.communalInteractor
    }

    @Test
    fun communalEnabled() =
        testScope.runTest {
            communalRepository.setIsCommunalEnabled(true)
            assertThat(underTest.isCommunalEnabled).isTrue()
        }

    @Test
    fun communalDisabled() =
        testScope.runTest {
            communalRepository.setIsCommunalEnabled(false)
            assertThat(underTest.isCommunalEnabled).isFalse()
        }

    @Test
    fun widget_tutorialCompletedAndWidgetsAvailable_showWidgetContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets are available.
            val widgets =
                listOf(
                    CommunalWidgetContentModel(
                        appWidgetId = 0,
                        priority = 30,
                        providerInfo = mock(),
                    ),
                    CommunalWidgetContentModel(
                        appWidgetId = 1,
                        priority = 20,
                        providerInfo = mock(),
                    ),
                    CommunalWidgetContentModel(
                        appWidgetId = 2,
                        priority = 10,
                        providerInfo = mock(),
                    ),
                )
            widgetRepository.setCommunalWidgets(widgets)

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent!!).isNotEmpty()
            widgetContent!!.forEachIndexed { index, model ->
                assertThat(model.appWidgetId).isEqualTo(widgets[index].appWidgetId)
            }
        }

    @Test
    fun smartspace_onlyShowTimersWithRemoteViews() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Not a timer
            val target1 = mock(SmartspaceTarget::class.java)
            whenever(target1.smartspaceTargetId).thenReturn("target1")
            whenever(target1.featureType).thenReturn(SmartspaceTarget.FEATURE_WEATHER)
            whenever(target1.remoteViews).thenReturn(mock(RemoteViews::class.java))
            whenever(target1.creationTimeMillis).thenReturn(0L)

            // Does not have RemoteViews
            val target2 = mock(SmartspaceTarget::class.java)
            whenever(target2.smartspaceTargetId).thenReturn("target2")
            whenever(target2.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target2.remoteViews).thenReturn(null)
            whenever(target2.creationTimeMillis).thenReturn(0L)

            // Timer and has RemoteViews
            val target3 = mock(SmartspaceTarget::class.java)
            whenever(target3.smartspaceTargetId).thenReturn("target3")
            whenever(target3.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target3.remoteViews).thenReturn(mock(RemoteViews::class.java))
            whenever(target3.creationTimeMillis).thenReturn(0L)

            val targets = listOf(target1, target2, target3)
            smartspaceRepository.setCommunalSmartspaceTargets(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent)
            assertThat(smartspaceContent?.size).isEqualTo(1)
            assertThat(smartspaceContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("target3"))
        }

    @Test
    fun smartspaceDynamicSizing_oneCard_fullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 1,
            expectedSizes =
                listOf(
                    CommunalContentSize.FULL,
                )
        )

    @Test
    fun smartspace_dynamicSizing_twoCards_halfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 2,
            expectedSizes =
                listOf(
                    CommunalContentSize.HALF,
                    CommunalContentSize.HALF,
                )
        )

    @Test
    fun smartspace_dynamicSizing_threeCards_thirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 3,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
        )

    @Test
    fun smartspace_dynamicSizing_fourCards_oneFullAndThreeThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 4,
            expectedSizes =
                listOf(
                    CommunalContentSize.FULL,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
        )

    @Test
    fun smartspace_dynamicSizing_fiveCards_twoHalfAndThreeThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 5,
            expectedSizes =
                listOf(
                    CommunalContentSize.HALF,
                    CommunalContentSize.HALF,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
        )

    @Test
    fun smartspace_dynamicSizing_sixCards_allThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 6,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
        )

    private fun testSmartspaceDynamicSizing(
        totalTargets: Int,
        expectedSizes: List<CommunalContentSize>,
    ) =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val targets = mutableListOf<SmartspaceTarget>()
            for (index in 0 until totalTargets) {
                targets.add(smartspaceTimer(index.toString()))
            }

            smartspaceRepository.setCommunalSmartspaceTargets(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent)
            assertThat(smartspaceContent?.size).isEqualTo(totalTargets)
            for (index in 0 until totalTargets) {
                assertThat(smartspaceContent?.get(index)?.size).isEqualTo(expectedSizes[index])
            }
        }

    @Test
    fun umo_mediaPlaying_showsUmo() =
        testScope.runTest {
            // Tutorial completed.
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            mediaRepository.mediaActive()

            val umoContent by collectLastValue(underTest.ongoingContent)

            assertThat(umoContent?.size).isEqualTo(1)
            assertThat(umoContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(umoContent?.get(0)?.key).isEqualTo(CommunalContentModel.KEY.umo())
        }

    @Test
    fun ongoing_shouldOrderAndSizeByTimestamp() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Timer1 started
            val timer1 = smartspaceTimer("timer1", timestamp = 1L)
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1))

            // Umo started
            mediaRepository.mediaActive(timestamp = 2L)

            // Timer2 started
            val timer2 = smartspaceTimer("timer2", timestamp = 3L)
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1, timer2))

            // Timer3 started
            val timer3 = smartspaceTimer("timer3", timestamp = 4L)
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1, timer2, timer3))

            val ongoingContent by collectLastValue(underTest.ongoingContent)
            assertThat(ongoingContent?.size).isEqualTo(4)
            assertThat(ongoingContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer3"))
            assertThat(ongoingContent?.get(0)?.size).isEqualTo(CommunalContentSize.FULL)
            assertThat(ongoingContent?.get(1)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer2"))
            assertThat(ongoingContent?.get(1)?.size).isEqualTo(CommunalContentSize.THIRD)
            assertThat(ongoingContent?.get(2)?.key).isEqualTo(CommunalContentModel.KEY.umo())
            assertThat(ongoingContent?.get(2)?.size).isEqualTo(CommunalContentSize.THIRD)
            assertThat(ongoingContent?.get(3)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer1"))
            assertThat(ongoingContent?.get(3)?.size).isEqualTo(CommunalContentSize.THIRD)
        }

    @Test
    fun cta_visibilityTrue_shows() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            communalRepository.setCtaTileInViewModeVisibility(true)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent?.size).isEqualTo(1)
            assertThat(ctaTileContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
            assertThat(ctaTileContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.CTA_TILE_IN_VIEW_MODE_KEY)
        }

    @Test
    fun ctaTile_visibilityFalse_doesNotShow() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            communalRepository.setCtaTileInViewModeVisibility(false)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent).isEmpty()
        }

    @Test
    fun listensToSceneChange() =
        testScope.runTest {
            var desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(CommunalSceneKey.Blank)

            val targetScene = CommunalSceneKey.Communal
            communalRepository.setDesiredScene(targetScene)
            desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        testScope.runTest {
            val targetScene = CommunalSceneKey.Communal

            underTest.onSceneChanged(targetScene)

            val desiredScene = collectLastValue(communalRepository.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun transitionProgress_onTargetScene_fullProgress() =
        testScope.runTest {
            val targetScene = CommunalSceneKey.Blank
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(targetScene)
                )
            underTest.setTransitionState(transitionState)

            // We're on the target scene.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
        }

    @Test
    fun transitionProgress_notOnTargetScene_noProgress() =
        testScope.runTest {
            val targetScene = CommunalSceneKey.Blank
            val currentScene = CommunalSceneKey.Communal
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Transition progress is still idle, but we're not on the target scene.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))
        }

    @Test
    fun transitionProgress_transitioningToTrackedScene() =
        testScope.runTest {
            val currentScene = CommunalSceneKey.Communal
            val targetScene = CommunalSceneKey.Blank
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            var transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Progress starts at 0.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableCommunalTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Transition(.4f))

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Transition(1f))

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableCommunalTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
        }

    @Test
    fun transitionProgress_transitioningAwayFromTrackedScene() =
        testScope.runTest {
            val currentScene = CommunalSceneKey.Blank
            val targetScene = CommunalSceneKey.Communal
            val transitionProgressFlow = underTest.transitionProgressToScene(currentScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            var transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(
                    ObservableCommunalTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Progress starts at 0.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableCommunalTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f

            // This is a transition we don't care about the progress of.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.OtherTransition)

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.OtherTransition)

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableCommunalTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
        }

    @Test
    fun isCommunalShowing() =
        testScope.runTest {
            var isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(false)

            underTest.onSceneChanged(CommunalSceneKey.Communal)

            isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(true)
        }

    @Test
    fun testShowWidgetEditorStartsActivity() =
        testScope.runTest {
            underTest.showWidgetEditor()
            verify(editWidgetsActivityStarter).startActivity()
        }

    private fun smartspaceTimer(id: String, timestamp: Long = 0L): SmartspaceTarget {
        val timer = mock(SmartspaceTarget::class.java)
        whenever(timer.smartspaceTargetId).thenReturn(id)
        whenever(timer.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
        whenever(timer.remoteViews).thenReturn(mock(RemoteViews::class.java))
        whenever(timer.creationTimeMillis).thenReturn(timestamp)
        return timer
    }
}
