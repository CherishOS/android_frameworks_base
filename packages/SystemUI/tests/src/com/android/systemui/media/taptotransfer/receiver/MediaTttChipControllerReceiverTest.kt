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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaRoute2Info
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaTttChipControllerReceiverTest : SysuiTestCase() {
    private lateinit var controllerReceiver: MediaTttChipControllerReceiver

    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var applicationInfo: ApplicationInfo
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var commandQueue: CommandQueue
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var fakeAppIconDrawable: Drawable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        fakeAppIconDrawable = context.getDrawable(R.drawable.ic_cake)!!
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(fakeAppIconDrawable)
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(packageManager.getApplicationInfo(
            eq(PACKAGE_NAME), any<PackageManager.ApplicationInfoFlags>()
        )).thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)

        controllerReceiver = MediaTttChipControllerReceiver(
            commandQueue,
            context,
            windowManager,
            FakeExecutor(FakeSystemClock()),
            TapGestureDetector(context),
            Handler.getMain(),
        )

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

        assertThat(getChipView().getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
    }

    @Test
    fun displayChip_hasAppIconDrawable_iconIsDrawable() {
        val drawable = context.getDrawable(R.drawable.ic_alarm)!!
        val state = ChipStateReceiver(PACKAGE_NAME, drawable, "appName")

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().drawable).isEqualTo(drawable)
    }

    @Test
    fun displayChip_nullAppName_iconContentDescriptionIsFromPackageName() {
        val state = ChipStateReceiver(PACKAGE_NAME, appIconDrawable = null, appName = null)

        controllerReceiver.displayChip(state)

        assertThat(getChipView().getAppIconView().contentDescription).isEqualTo(APP_NAME)
    }

    @Test
    fun displayChip_hasAppName_iconContentDescriptionIsAppNameOverride() {
        val appName = "Override App Name"
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

private const val APP_NAME = "Fake app name"
private const val PACKAGE_NAME = "com.android.systemui"

private val routeInfo = MediaRoute2Info.Builder("id", "Test route name")
    .addFeature("feature")
    .setPackageName(PACKAGE_NAME)
    .build()
