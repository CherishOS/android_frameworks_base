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
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.platform.app.InstrumentationRegistry
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
import com.android.server.wm.flicker.helpers.isRotated
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
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
 *     Swipe left from the bottom of the screen to quick switch forward to the second app [testApp2]
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class QuickSwitchBetweenTwoAppsForwardTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private val testApp1 = SimpleAppHelper(instrumentation)
    private val testApp2 = NonResizeableAppHelper(instrumentation)

    private val startDisplayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            setup {
                eachRun {
                    testApp1.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp1.component)

                    testApp2.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp2.component)

                    // Swipe right from bottom to quick switch back
                    // NOTE: We don't perform an edge-to-edge swipe but instead only swipe in the middle
                    // as to not accidentally trigger a swipe back or forward action which would result
                    // in the same behavior but not testing quick swap.
                    device.swipe(
                            startDisplayBounds.bounds.right / 3,
                            startDisplayBounds.bounds.bottom,
                            2 * startDisplayBounds.bounds.right / 3,
                            startDisplayBounds.bounds.bottom,
                            if (testSpec.config.startRotation.isRotated()) 75 else 30
                    )

                    wmHelper.waitForFullScreenApp(testApp1.component)
                    wmHelper.waitForAppTransitionIdle()
                }
            }
            transitions {
                // Swipe left from bottom to quick switch forward
                // NOTE: We don't perform an edge-to-edge swipe but instead only swipe in the middle
                // as to not accidentally trigger a swipe back or forward action which would result
                // in the same behavior but not testing quick swap.
                device.swipe(
                        2 * startDisplayBounds.bounds.right / 3,
                        startDisplayBounds.bounds.bottom,
                        startDisplayBounds.bounds.right / 3,
                        startDisplayBounds.bounds.bottom,
                        if (testSpec.config.startRotation.isRotated()) 75 else 30
                )

                wmHelper.waitForFullScreenApp(testApp2.component)
                wmHelper.waitForAppTransitionIdle()
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
     * Checks that the transition starts with [testApp1]'s windows filling/covering exactly the
     * entirety of the display.
     */
    @Postsubmit
    @Test
    fun startsWithApp1WindowsCoverFullScreen() {
        testSpec.assertWmStart {
            this.frameRegion(testApp1.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp1]'s layers filling/covering exactly the
     * entirety of the display.
     */
    @Postsubmit
    @Test
    fun startsWithApp1LayersCoverFullScreen() {
        testSpec.assertLayersStart {
            this.visibleRegion(testApp1.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp1] being the top window.
     */
    @Postsubmit
    @Test
    fun startsWithApp1WindowBeingOnTop() {
        testSpec.assertWmStart {
            this.isAppWindowOnTop(testApp1.component)
        }
    }

    /**
     * Checks that [testApp2] windows fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from [testApp1] back to the [testApp2].
     */
    @Postsubmit
    @Test
    fun endsWithApp2WindowsCoveringFullScreen() {
        testSpec.assertWmEnd {
            this.frameRegion(testApp2.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp2] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from [testApp1] back to the [testApp2].
     */
    @Postsubmit
    @Test
    fun endsWithApp2LayersCoveringFullScreen() {
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp2.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp2] is the top window at the end of the transition once we have fully quick
     * switched from [testApp1] back to the [testApp2].
     */
    @Postsubmit
    @Test
    fun endsWithApp2BeingOnTop() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp2.component)
        }
    }

    /**
     * Checks that [testApp2]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Postsubmit
    @Test
    fun app2WindowBecomesAndStaysVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp2.component)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp2.component)
        }
    }

    /**
     * Checks that [testApp2]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Postsubmit
    @Test
    fun app2LayerBecomesAndStaysVisible() {
        testSpec.assertLayers {
            this.isInvisible(testApp2.component)
                    .then()
                    .isVisible(testApp2.component)
        }
    }

    /**
     * Checks that [testApp1]'s window starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Postsubmit
    @Test
    fun app1WindowBecomesAndStaysInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp1.component)
                    .then()
                    .isAppWindowInvisible(testApp1.component)
        }
    }

    /**
     * Checks that [testApp1]'s layer starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Postsubmit
    @Test
    fun app1LayerBecomesAndStaysInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp1.component)
                    .then()
                    .isInvisible(testApp1.component)
        }
    }

    /**
     * Checks that [testApp1]'s window is visible at least until [testApp2]'s window is visible.
     * Ensures that at any point, either [testApp2] or [testApp1]'s windows are at least partially
     * visible.
     */
    @Postsubmit
    @Test
    fun app2WindowIsVisibleOnceApp1WindowIsInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp1.component)
                    .then()
                    .isAppWindowVisible(LAUNCHER_COMPONENT, isOptional = true)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp2.component)
        }
    }

    /**
     * Checks that [testApp1]'s layer is visible at least until [testApp2]'s window is visible.
     * Ensures that at any point, either [testApp2] or [testApp1]'s windows are at least partially
     * visible.
     */
    @Postsubmit
    @Test
    fun app2LayerIsVisibleOnceApp1LayerIsInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp1.component)
                    .then()
                    .isVisible(LAUNCHER_COMPONENT, isOptional = true)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(testApp2.component)
        }
    }

    /**
     * Checks that the navbar window is visible throughout the entire transition.
     */
    @Postsubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsVisible()

    /**
     * Checks that the navbar layer is visible throughout the entire transition.
     */
    @Postsubmit
    @Test
    fun navBarLayerAlwaysIsVisible() = testSpec.navBarLayerIsVisible()

    /**
     * Checks that the navbar is always in the right position and covers the expected region.
     *
     * NOTE: This doesn't check that the navbar is visible or not.
     */
    @Postsubmit
    @Test
    fun navbarIsAlwaysInRightPosition() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the status bar window is visible throughout the entire transition.
     */
    @Postsubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsVisible()

    /**
     * Checks that the status bar layer is visible throughout the entire transition.
     */
    @Postsubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsVisible()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 5,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            ),
                            supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90)
                    )
        }
    }
}