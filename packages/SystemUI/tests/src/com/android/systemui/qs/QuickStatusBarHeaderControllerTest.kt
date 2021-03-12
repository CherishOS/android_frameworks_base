/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyDialogController
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.utils.leaks.FakeZenModeController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QuickStatusBarHeaderControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var view: QuickStatusBarHeader
    @Mock
    private lateinit var zenModeController: FakeZenModeController
    @Mock
    private lateinit var nextAlarmController: NextAlarmController
    @Mock
    private lateinit var privacyItemController: PrivacyItemController
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var ringerModeTracker: RingerModeTracker
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var qsTileHost: QSTileHost
    @Mock
    private lateinit var statusBarIconController: StatusBarIconController
    @Mock
    private lateinit var commandQueue: CommandQueue
    @Mock
    private lateinit var demoModeController: DemoModeController
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var quickQSPanelController: QuickQSPanelController
    @Mock(answer = Answers.RETURNS_SELF)
    private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock
    private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock
    private lateinit var privacyLogger: PrivacyLogger
    @Mock
    private lateinit var colorExtractor: SysuiColorExtractor
    @Mock
    private lateinit var iconContainer: StatusIconContainer
    @Mock
    private lateinit var qsCarrierGroup: QSCarrierGroup
    @Mock
    private lateinit var privacyChip: OngoingPrivacyChip
    @Mock
    private lateinit var privacyDialogController: PrivacyDialogController
    @Mock
    private lateinit var clock: Clock
    @Mock
    private lateinit var mockView: View
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var context: Context

    private lateinit var controller: QuickStatusBarHeaderController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        stubViews()
        `when`(iconContainer.context).thenReturn(context)
        `when`(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)
        `when`(view.resources).thenReturn(mContext.resources)
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.context).thenReturn(context)

        controller = QuickStatusBarHeaderController(
                view,
                zenModeController,
                nextAlarmController,
                privacyItemController,
                ringerModeTracker,
                activityStarter,
                uiEventLogger,
                qsTileHost,
                statusBarIconController,
                commandQueue,
                demoModeController,
                userTracker,
                quickQSPanelController,
                qsCarrierGroupControllerBuilder,
                privacyLogger,
                colorExtractor,
                privacyDialogController
        )
    }

    @After
    fun tearDown() {
        controller.onViewDetached()
    }

    @Test
    fun testIgnoredSlotsOnAttached_noIndicators() {
        setPrivacyController(micCamera = false, location = false)

        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(iconContainer).setIgnoredSlots(capture(captor))

        assertThat(captor.value).isEmpty()
    }

    @Test
    fun testIgnoredSlotsOnAttached_onlyMicCamera() {
        setPrivacyController(micCamera = true, location = false)

        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(iconContainer).setIgnoredSlots(capture(captor))

        val cameraString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_camera)
        val micString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_microphone)

        assertThat(captor.value).containsExactly(cameraString, micString)
    }

    @Test
    fun testIgnoredSlotsOnAttached_onlyLocation() {
        setPrivacyController(micCamera = false, location = true)

        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(iconContainer).setIgnoredSlots(capture(captor))

        val locationString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_location)

        assertThat(captor.value).containsExactly(locationString)
    }

    @Test
    fun testIgnoredSlotsOnAttached_locationMicCamera() {
        setPrivacyController(micCamera = true, location = true)

        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(iconContainer).setIgnoredSlots(capture(captor))

        val cameraString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_camera)
        val micString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_microphone)
        val locationString = mContext.resources.getString(
                com.android.internal.R.string.status_bar_location)

        assertThat(captor.value).containsExactly(cameraString, micString, locationString)
    }

    @Test
    fun testPrivacyChipClicked() {
        controller.init()

        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))

        captor.value.onClick(privacyChip)

        verify(privacyDialogController).showDialog(any(Context::class.java))
    }

    private fun stubViews() {
        `when`(view.findViewById<View>(anyInt())).thenReturn(mockView)
        `when`(view.findViewById<QSCarrierGroup>(R.id.carrier_group)).thenReturn(qsCarrierGroup)
        `when`(view.findViewById<StatusIconContainer>(R.id.statusIcons)).thenReturn(iconContainer)
        `when`(view.findViewById<OngoingPrivacyChip>(R.id.privacy_chip)).thenReturn(privacyChip)
        `when`(view.findViewById<Clock>(R.id.clock)).thenReturn(clock)
    }

    private fun setPrivacyController(micCamera: Boolean, location: Boolean) {
        `when`(privacyItemController.micCameraAvailable).thenReturn(micCamera)
        `when`(privacyItemController.locationAvailable).thenReturn(location)
    }
}