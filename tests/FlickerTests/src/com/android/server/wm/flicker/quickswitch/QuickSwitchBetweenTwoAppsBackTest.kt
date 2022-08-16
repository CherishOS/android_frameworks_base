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

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching back to previous app from last opened app
 *
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsBackTest`
 *
 * Actions:
 *     Launch an app [testApp1]
 *     Launch another app [testApp2]
 *     Swipe right from the bottom of the screen to quick switch back to the first app [testApp1]
 *
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
open class QuickSwitchBetweenTwoAppsBackTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val taplInstrumentation = LauncherInstrumentation()

    private val testApp1 = SimpleAppHelper(instrumentation)
    private val testApp2 = NonResizeableAppHelper(instrumentation)

    private val startDisplayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                test {
                    taplInstrumentation.setExpectedRotation(testSpec.startRotation)
                }

                eachRun {
                    testApp1.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp1.component)

                    testApp2.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp2.component)
                }
            }
            transitions {
                taplInstrumentation.launchedAppState.quickSwitchToPreviousApp()
                wmHelper.waitForFullScreenApp(testApp1.component)
                wmHelper.waitForAppTransitionIdle()
                wmHelper.waitForNavBarStatusBarVisible()
            }

            teardown {
                test {
                    testApp1.exit()
                    testApp2.exit()
                }
            }
        }
    }

    /**
     * Checks that the transition starts with [testApp2]'s windows filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    fun startsWithApp2WindowsCoverFullScreen() {
        testSpec.assertWmStart {
            this.frameRegion(testApp2.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp2]'s layers filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    fun startsWithApp2LayersCoverFullScreen() {
        testSpec.assertLayersStart {
            this.visibleRegion(testApp2.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp2] being the top window.
     */
    @Presubmit
    @Test
    fun startsWithApp2WindowBeingOnTop() {
        testSpec.assertWmStart {
            this.isAppWindowOnTop(testApp2.component)
        }
    }

    /**
     * Checks that [testApp1] windows fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    fun endsWithApp1WindowsCoveringFullScreen() {
        testSpec.assertWmEnd {
            this.frameRegion(testApp1.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp1] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    fun endsWithApp1LayersCoveringFullScreen() {
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp1.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp1] is the top window at the end of the transition once we have fully quick
     * switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    fun endsWithApp1BeingOnTop() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp1.component)
        }
    }

    /**
     * Checks that [testApp1]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun app1WindowBecomesAndStaysVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp1.component)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp1.component)
        }
    }

    /**
     * Checks that [testApp1]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun app1LayerBecomesAndStaysVisible() {
        testSpec.assertLayers {
            this.isInvisible(testApp1.component)
                    .then()
                    .isVisible(testApp1.component)
        }
    }

    /**
     * Checks that [testApp2]'s window starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun app2WindowBecomesAndStaysInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp2.component)
                    .then()
                    .isAppWindowInvisible(testApp2.component)
        }
    }

    /**
     * Checks that [testApp2]'s layer starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun app2LayerBecomesAndStaysInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp2.component)
                    .then()
                    .isInvisible(testApp2.component)
        }
    }

    /**
     * Checks that [testApp2]'s window is visible at least until [testApp1]'s window is visible.
     * Ensures that at any point, either [testApp1] or [testApp2]'s windows are at least partially
     * visible.
     */
    @Presubmit
    @Test
    fun app1WindowIsVisibleOnceApp2WindowIsInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp2.component)
                    .then()
                    // TODO: Do we actually want to test this? Seems too implementation specific...
                    .isAppWindowVisible(LAUNCHER_COMPONENT, isOptional = true)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp1.component)
        }
    }

    /**
     * Checks that [testApp2]'s layer is visible at least until [testApp1]'s window is visible.
     * Ensures that at any point, either [testApp1] or [testApp2]'s windows are at least partially
     * visible.
     */
    @Presubmit
    @Test
    fun app1LayerIsVisibleOnceApp2LayerIsInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp2.component)
                    .then()
                    .isVisible(LAUNCHER_COMPONENT, isOptional = true)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(testApp1.component)
        }
    }

    /**
     * Checks that the navbar window is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsVisible()

    /**
     * Checks that the navbar layer is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun navBarLayerAlwaysIsVisible() = testSpec.navBarLayerIsVisible()

    /**
     * Checks that the navbar is always in the right position and covers the expected region.
     *
     * NOTE: This doesn't check that the navbar is visible or not.
     */
    @Presubmit
    @Test
    fun navbarIsAlwaysInRightPosition() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the status bar window is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsVisible()

    /**
     * Checks that the status bar layer is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsVisible()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            ),
                            supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90)
                    )
        }
    }
}
