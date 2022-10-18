/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.flicker.splitscreen

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowBecomesInvisible
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerBecomesInvisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesInvisible
import com.android.wm.shell.flicker.splitScreenDismissed
import com.android.wm.shell.flicker.splitScreenDividerBecomesInvisible
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test dismiss split screen by dragging the divider bar.
 *
 * To run this test: `atest WMShellFlickerTests:DismissSplitScreenByDivider`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DismissSplitScreenByDivider (testSpec: FlickerTestParameter) : SplitScreenBase(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                SplitScreenUtils.enterSplit(wmHelper, tapl, primaryApp, secondaryApp)
            }
            transitions {
                if (tapl.isTablet) {
                    SplitScreenUtils.dragDividerToDismissSplit(device, wmHelper,
                        dragToRight = false, dragToBottom = true)
                } else {
                    SplitScreenUtils.dragDividerToDismissSplit(device, wmHelper,
                        dragToRight = true, dragToBottom = true)
                }
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(secondaryApp)
                    .waitForAndVerify()
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() = testSpec.splitScreenDismissed(primaryApp, secondaryApp, toHome = false)

    @Presubmit
    @Test
    fun splitScreenDividerBecomesInvisible() = testSpec.splitScreenDividerBecomesInvisible()

    @Presubmit
    @Test
    fun primaryAppLayerBecomesInvisible() = testSpec.layerBecomesInvisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerIsVisibleAtEnd() = testSpec.layerIsVisibleAtEnd(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsBecomesInvisible() = testSpec.splitAppLayerBoundsBecomesInvisible(
        primaryApp, landscapePosLeft = tapl.isTablet, portraitPosTop = false)

    private fun secondaryAppBoundsIsFullscreenAtEnd_internal() {
        testSpec.assertLayers {
            this.isVisible(secondaryApp)
                .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isInvisible(secondaryApp)
                .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isVisible(secondaryApp, isOptional = true)
                .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT, isOptional = true)
                .then()
                .contains(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .invoke("secondaryAppBoundsIsFullscreenAtEnd") {
                    val displayBounds = WindowUtils.getDisplayBounds(testSpec.endRotation)
                    it.visibleRegion(secondaryApp).coversExactly(displayBounds)
                }
        }
    }

    @Presubmit
    @Test
    fun secondaryAppBoundsIsFullscreenAtEnd() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        secondaryAppBoundsIsFullscreenAtEnd_internal()
    }

    @FlakyTest(bugId = 250528485)
    @Test
    fun secondaryAppBoundsIsFullscreenAtEnd_shellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        secondaryAppBoundsIsFullscreenAtEnd_internal()
    }

    @Presubmit
    @Test
    fun primaryAppWindowBecomesInvisible() = testSpec.appWindowBecomesInvisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowIsVisibleAtEnd() = testSpec.appWindowIsVisibleAtEnd(secondaryApp)

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
    @FlakyTest(bugId = 206753786)
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

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests()
        }
    }
}
