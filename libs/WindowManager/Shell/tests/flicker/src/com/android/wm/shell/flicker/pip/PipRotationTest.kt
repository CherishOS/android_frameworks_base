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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.FlakyTest
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip Stack in bounds after rotations.
 *
 * To run this test: `atest WMShellFlickerTests:PipRotationTest`
 *
 * Actions:
 *     Launch a [pipApp] in pip mode
 *     Launch another app [fixedApp] (appears below pip)
 *     Rotate the screen from [testSpec.startRotation] to [testSpec.endRotation]
 *     (usually, 0->90 and 90->0)
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited from [PipTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
open class PipRotationTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val fixedApp = FixedAppHelper(instrumentation)
    private val screenBoundsStart = WindowUtils.getDisplayBounds(testSpec.startRotation)
    private val screenBoundsEnd = WindowUtils.getDisplayBounds(testSpec.endRotation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition(eachRun = false) {
            setup {
                test {
                    fixedApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    setRotation(testSpec.startRotation)
                }
            }
            transitions {
                setRotation(testSpec.endRotation)
            }
        }

    /**
     * Checks that all parts of the screen are covered at the start and end of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    override fun entireScreenCovered() = testSpec.entireScreenCovered()

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /**
     * Checks that [fixedApp] layer is within [screenBoundsStart] at the start of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun fixedAppLayer_StartingBounds() {
        testSpec.assertLayersStart {
            visibleRegion(fixedApp).coversAtMost(screenBoundsStart)
        }
    }

    /**
     * Checks that [fixedApp] layer is within [screenBoundsEnd] at the end of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun fixedAppLayer_EndingBounds() {
        testSpec.assertLayersEnd {
            visibleRegion(fixedApp).coversAtMost(screenBoundsEnd)
        }
    }

    /**
     * Checks that [fixedApp] plus [pipApp] layers are within [screenBoundsEnd] at the start
     * of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun appLayers_StartingBounds() {
        testSpec.assertLayersStart {
            visibleRegion(fixedApp.or(pipApp)).coversExactly(screenBoundsStart)
        }
    }

    /**
     * Checks that [fixedApp] plus [pipApp] layers are within [screenBoundsEnd] at the end
     * of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun appLayers_EndingBounds() {
        testSpec.assertLayersEnd {
            visibleRegion(fixedApp.or(pipApp)).coversExactly(screenBoundsEnd)
        }
    }

    /**
     * Checks that [pipApp] layer is within [screenBoundsStart] at the start of the transition
     */
    private fun pipLayerRotates_StartingBounds_internal() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp).coversAtMost(screenBoundsStart)
        }
    }

    /**
     * Checks that [pipApp] layer is within [screenBoundsStart] at the start of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun pipLayerRotates_StartingBounds() {
        pipLayerRotates_StartingBounds_internal()
    }

    /**
     * Checks that [pipApp] layer is within [screenBoundsEnd] at the end of the transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun pipLayerRotates_EndingBounds() {
        testSpec.assertLayersEnd {
            visibleRegion(pipApp).coversAtMost(screenBoundsEnd)
        }
    }

    /**
     * Ensure that the [pipApp] window does not obscure the [fixedApp] at the start of the
     * transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun pipIsAboveFixedAppWindow_Start() {
        testSpec.assertWmStart {
            isAboveWindow(pipApp, fixedApp)
        }
    }

    /**
     * Ensure that the [pipApp] window does not obscure the [fixedApp] at the end of the
     * transition
     */
    @FlakyTest(bugId = 240499181)
    @Test
    fun pipIsAboveFixedAppWindow_End() {
        testSpec.assertWmEnd {
            isAboveWindow(pipApp, fixedApp)
        }
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() {
        super.navBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun navBarWindowIsAlwaysVisible() {
        super.navBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        super.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() {
        super.statusBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun statusBarWindowIsAlwaysVisible() {
        super.statusBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() {
        super.taskBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun taskBarWindowIsAlwaysVisible() {
        super.taskBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest(bugId = 240499181)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigRotationTests(
                supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                repetitions = 3
            )
        }
    }
}
