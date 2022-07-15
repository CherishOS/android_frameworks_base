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

package com.android.server.wm.flicker.quickswitch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.traces.common.ComponentMatcher
import com.android.server.wm.traces.common.Rect
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching to last opened app from launcher
 *
 * To run this test: `atest FlickerTests:QuickSwitchFromLauncherTest`
 *
 * Actions:
 *     Launch an app
 *     Navigate home to show launcher
 *     Swipe right from the bottom of the screen to quick switch back to the app
 *
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class QuickSwitchFromLauncherTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = SimpleAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                tapl.setExpectedRotation(testSpec.startRotation)
            }

            eachRun {
                testApp.launchViaIntent(wmHelper)
                device.pressHome()
                wmHelper.StateSyncBuilder()
                    .withHomeActivityVisible()
                    .withWindowSurfaceDisappeared(testApp)
                    .waitForAndVerify()

                startDisplayBounds = wmHelper.currentState.layerState
                    .physicalDisplayBounds ?: error("Display not found")
            }
        }
        transitions {
            tapl.workspace.quickSwitchToPreviousApp()
            wmHelper.StateSyncBuilder()
                .withFullScreenApp(testApp)
                .withNavOrTaskBarVisible()
                .withStatusBarVisible()
                .waitForAndVerify()
        }

        teardown {
            eachRun {
                testApp.exit(wmHelper)
            }
        }
    }

    /**
     * Checks that [testApp] windows fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppWindowsCoveringFullScreen() {
        testSpec.assertWmEnd {
            this.visibleRegion(testApp).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppLayersCoveringFullScreen() {
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp] is the top window at the end of the transition once we have fully quick
     * switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppBeingOnTop() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp)
        }
    }

    /**
     * Checks that the transition starts with the home activity being tagged as visible.
     */
    @Presubmit
    @Test
    fun startsWithHomeActivityFlaggedVisible() {
        testSpec.assertWmStart {
            this.isHomeActivityVisible()
        }
    }

    /**
     * Checks that the transition starts with the [ComponentMatcher.LAUNCHER] windows
     * filling/covering exactly display size
     */
    @Presubmit
    @Test
    fun startsWithLauncherWindowsCoverFullScreen() {
        testSpec.assertWmStart {
            this.visibleRegion(ComponentMatcher.LAUNCHER).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the [ComponentMatcher.LAUNCHER] layers
     * filling/covering exactly the display size.
     */
    @Presubmit
    @Test
    fun startsWithLauncherLayersCoverFullScreen() {
        testSpec.assertLayersStart {
            this.visibleRegion(ComponentMatcher.LAUNCHER).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the [ComponentMatcher.LAUNCHER] being the top window.
     */
    @Presubmit
    @Test
    fun startsWithLauncherBeingOnTop() {
        testSpec.assertWmStart {
            this.isAppWindowOnTop(ComponentMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the transition ends with the home activity being flagged as not visible. By this
     * point we should have quick switched away from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithHomeActivityFlaggedInvisible() {
        testSpec.assertWmEnd {
            this.isHomeActivityInvisible()
        }
    }

    /**
     * Checks that [testApp]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appWindowBecomesAndStaysVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp)
                    .then()
                    .isAppWindowVisible(testApp)
        }
    }

    /**
     * Checks that [testApp]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appLayerBecomesAndStaysVisible() {
        testSpec.assertLayers {
            this.isInvisible(testApp)
                    .then()
                    .isVisible(testApp)
        }
    }

    /**
     * Checks that the [ComponentMatcher.LAUNCHER] window starts off visible and becomes invisible
     * at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun launcherWindowBecomesAndStaysInvisible() {
        testSpec.assertWm {
            this.isAppWindowOnTop(ComponentMatcher.LAUNCHER)
                    .then()
                    .isAppWindowNotOnTop(ComponentMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the [ComponentMatcher.LAUNCHER] layer starts off visible and becomes invisible
     * at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun launcherLayerBecomesAndStaysInvisible() {
        testSpec.assertLayers {
            this.isVisible(ComponentMatcher.LAUNCHER)
                    .then()
                    .isInvisible(ComponentMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the [ComponentMatcher.LAUNCHER] window is visible at least until the app window
     * is visible. Ensures
     * that at any point, either the launcher or [testApp] windows are at least partially visible.
     */
    @Presubmit
    @Test
    fun appWindowIsVisibleOnceLauncherWindowIsInvisible() {
        testSpec.assertWm {
            this.isAppWindowOnTop(ComponentMatcher.LAUNCHER)
                    .then()
                    .isAppWindowVisible(ComponentMatcher.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp)
        }
    }

    /**
     * Checks that the [ComponentMatcher.LAUNCHER] layer is visible at least until the app layer
     * is visible. Ensures that at any point, either the launcher or [testApp] layers are at least
     * partially visible.
     */
    @Presubmit
    @Test
    fun appLayerIsVisibleOnceLauncherLayerIsInvisible() {
        testSpec.assertLayers {
            this.isVisible(ComponentMatcher.LAUNCHER)
                    .then()
                    .isVisible(ComponentMatcher.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(testApp)
        }
    }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 239148258)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 239148258)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        /** {@inheritDoc} */
        private var startDisplayBounds = Rect.EMPTY

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            ),
                            // TODO: Test with 90 rotation
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}
