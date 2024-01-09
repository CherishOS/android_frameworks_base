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

package com.android.systemui.communal.view.viewmodel

import android.app.smartspace.SmartspaceTarget
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractorFactory
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel.Companion.POPUP_AUTO_HIDE_TIMEOUT_MS
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalViewModelTest : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var powerManager: PowerManager

    private lateinit var testScope: TestScope

    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository

    private lateinit var underTest: CommunalViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()

        val withDeps = CommunalInteractorFactory.create()
        keyguardRepository = withDeps.keyguardRepository
        communalRepository = withDeps.communalRepository
        tutorialRepository = withDeps.tutorialRepository
        widgetRepository = withDeps.widgetRepository
        smartspaceRepository = withDeps.smartspaceRepository
        mediaRepository = withDeps.mediaRepository

        underTest =
            CommunalViewModel(
                testScope,
                withDeps.communalInteractor,
                WidgetInteractionHandler(mock()),
                withDeps.tutorialInteractor,
                Provider { shadeViewController },
                powerManager,
                mediaHost,
            )
    }

    @Test
    fun tutorial_tutorialNotCompletedAndKeyguardVisible_showTutorialContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial not started.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(
                Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
            )

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent!!).isNotEmpty()
            communalContent!!.forEach { model ->
                assertThat(model is CommunalContentModel.Tutorial).isTrue()
            }
        }

    @Test
    fun ordering_smartspaceBeforeUmoBeforeWidgetsBeforeCtaTile() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
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
                )
            widgetRepository.setCommunalWidgets(widgets)

            // Smartspace available.
            val target = Mockito.mock(SmartspaceTarget::class.java)
            whenever(target.smartspaceTargetId).thenReturn("target")
            whenever(target.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target.remoteViews).thenReturn(Mockito.mock(RemoteViews::class.java))
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(target))

            // Media playing.
            mediaRepository.mediaActive()

            // CTA Tile not dismissed.
            communalRepository.setCtaTileInViewModeVisibility(true)

            val communalContent by collectLastValue(underTest.communalContent)

            // Order is smart space, then UMO, widget content and cta tile.
            assertThat(communalContent?.size).isEqualTo(5)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(communalContent?.get(1)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(3))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(4))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun dismissCta_hidesCtaTileAndShowsPopup_thenHidesPopupAfterTimeout() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            communalRepository.setCtaTileInViewModeVisibility(true)

            val communalContent by collectLastValue(underTest.communalContent)
            val isPopupOnDismissCtaShowing by collectLastValue(underTest.isPopupOnDismissCtaShowing)

            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)

            underTest.onDismissCtaTile()

            // hide CTA tile and show the popup
            assertThat(communalContent).isEmpty()
            assertThat(isPopupOnDismissCtaShowing).isEqualTo(true)

            // hide popup after time elapsed
            advanceTimeBy(POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(isPopupOnDismissCtaShowing).isEqualTo(false)
        }

    @Test
    fun popup_onDismiss_hidesImmediately() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            communalRepository.setCtaTileInViewModeVisibility(true)

            val isPopupOnDismissCtaShowing by collectLastValue(underTest.isPopupOnDismissCtaShowing)

            underTest.onDismissCtaTile()
            assertThat(isPopupOnDismissCtaShowing).isEqualTo(true)

            // dismiss the popup directly
            underTest.onHidePopupAfterDismissCta()
            assertThat(isPopupOnDismissCtaShowing).isEqualTo(false)
        }
}
