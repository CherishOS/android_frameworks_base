/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.taptotransfer.receiver

import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@Ignore("b/216286227")
class MediaTttChipControllerReceiverTest : SysuiTestCase() {
    private lateinit var controllerReceiver: MediaTttChipControllerReceiver

    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var commandQueue: CommandQueue
    private lateinit var commandQueueCallback: CommandQueue.Callbacks

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controllerReceiver = MediaTttChipControllerReceiver(
                commandQueue, context, windowManager, Handler.getMain())

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallback = callbackCaptor.value!!
    }

    @Test
    fun commandQueueCallback_closeToSender_triggersChip() {
        val appName = "FakeAppName"
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            /* appIcon= */ null,
            appName
        )

        assertThat(getChipView().getAppIconView().contentDescription).isEqualTo(appName)
    }

    @Test
    fun commandQueueCallback_farFromSender_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
            routeInfo,
            null,
            null
        )

        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_closeThenFar_chipShownThenHidden() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
            routeInfo,
            null,
            null
        )

        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        verify(windowManager).removeView(viewCaptor.value)
    }

    @Test
    fun displayChip_nullAppIconDrawable_iconIsFromPackageName() {
        val state = ChipStateReceiver(PACKAGE_NAME, appIconDrawable = null, "appName")

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().drawable).isEqualTo(state.getAppIcon(context))

    }

    @Test
    fun displayChip_hasAppIconDrawable_iconIsDrawable() {
        val drawable = Icon.createWithResource(context, R.drawable.ic_cake).loadDrawable(context)
        val state = ChipStateReceiver(PACKAGE_NAME, drawable, "appName")

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().drawable).isEqualTo(drawable)
    }

    @Test
    fun displayChip_nullAppName_iconContentDescriptionIsFromPackageName() {
        val state = ChipStateReceiver(PACKAGE_NAME, appIconDrawable = null, appName = null)

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().contentDescription)
                .isEqualTo(state.getAppName(context))
    }

    @Test
    fun displayChip_hasAppName_iconContentDescriptionIsAppNameOverride() {
        val appName = "FakeAppName"
        val state = ChipStateReceiver(PACKAGE_NAME, appIconDrawable = null, appName)

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().contentDescription).isEqualTo(appName)
    }

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)
}

private const val PACKAGE_NAME = "com.android.systemui"

private val routeInfo = MediaRoute2Info.Builder("id", "Test route name")
    .addFeature("feature")
    .setPackageName(PACKAGE_NAME)
    .build()
