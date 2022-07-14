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

package com.android.wm.shell.flicker.bubble

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test launching a new activity from bubble.
 *
 * To run this test: `atest WMShellFlickerTests:LaunchBubbleFromLockScreen`
 *
 * Actions:
 *     Launch an bubble from notification on lock screen
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@Group4
class LaunchBubbleFromLockScreen(testSpec: FlickerTestParameter) : BaseBubbleScreen(testSpec) {

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition {
            setup {
                eachRun {
                    val addBubbleBtn = waitAndGetAddBubbleBtn()
                    addBubbleBtn?.click() ?: error("Bubble widget not found")
                    device.sleep()
                    wmHelper.StateSyncBuilder()
                        .withoutTopVisibleAppWindows()
                        .waitForAndVerify()
                    device.wakeUp()
                }
            }
            transitions {
                // Swipe & wait for the notification shade to expand so all can be seen
                val wm = context.getSystemService(WindowManager::class.java)
                    ?: error("Unable to obtain WM service")
                val metricInsets = wm.currentWindowMetrics.windowInsets
                val insets = metricInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()
                        or WindowInsets.Type.displayCutout()
                )
                device.swipe(100, insets.top + 100, 100, device.displayHeight / 2, 4)
                device.waitForIdle(2000)
                instrumentation.uiAutomation.syncInputTransactions()

                val notification = device.wait(
                    Until.findObject(
                        By.text("BubbleChat")
                    ), FIND_OBJECT_TIMEOUT
                )
                notification?.click() ?: error("Notification not found")
                instrumentation.uiAutomation.syncInputTransactions()
                val showBubble = device.wait(
                    Until.findObject(
                        By.res("com.android.systemui", "bubble_view")
                    ), FIND_OBJECT_TIMEOUT
                )
                showBubble?.click() ?: error("Bubble notify not found")
                instrumentation.uiAutomation.syncInputTransactions()
                val cancelAllBtn = waitAndGetCancelAllBtn()
                cancelAllBtn?.click() ?: error("Cancel widget not found")
            }
        }

    @Presubmit
    @Test
    fun testAppIsVisibleAtEnd() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayersEnd {
            this.isVisible(testApp)
        }
    }

    @FlakyTest
    @Test
    fun testAppIsVisibleAtEnd_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayersEnd {
            this.isVisible(testApp)
        }
    }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() =
        super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() =
        super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() =
        super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() =
        super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() =
        super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()
}
