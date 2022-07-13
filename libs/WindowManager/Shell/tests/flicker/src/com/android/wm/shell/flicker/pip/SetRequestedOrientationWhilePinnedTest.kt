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
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.testapp.Components
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test exiting Pip with orientation changes.
 * To run this test: `atest WMShellFlickerTests:SetRequestedOrientationWhilePinnedTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
open class SetRequestedOrientationWhilePinnedTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
    private val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                }
                eachRun {
                    // Launch the PiP activity fixed as landscape.
                    pipApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString()))
                    // Enter PiP.
                    broadcastActionTrigger.doAction(Components.PipActivity.ACTION_ENTER_PIP)
                    // System bar may fade out during fixed rotation.
                    wmHelper.StateSyncBuilder()
                        .withPipShown()
                        .withRotation(Surface.ROTATION_0)
                        .withNavBarStatusBarVisible()
                        .waitForAndVerify()
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                    setRotation(Surface.ROTATION_0)
                }
                test {
                    removeAllTasksButHome()
                }
            }
            transitions {
                // Launch the activity back into fullscreen and ensure that it is now in landscape
                pipApp.launchViaIntent(wmHelper)
                // System bar may fade out during fixed rotation.
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(pipApp)
                    .withRotation(Surface.ROTATION_90)
                    .withAppTransitionIdle()
                    .withNavBarStatusBarVisible()
                    .waitForAndVerify()
            }
        }

    @Presubmit
    @Test
    fun displayEndsAt90Degrees() {
        testSpec.assertWmEnd {
            hasRotation(Surface.ROTATION_90)
        }
    }

    @Presubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Presubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @Presubmit
    @Test
    fun pipWindowInsideDisplay() {
        testSpec.assertWmStart {
            visibleRegion(pipApp).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAppShowsOnTop() {
        testSpec.assertWmEnd {
            isAppWindowOnTop(pipApp)
        }
    }

    @Presubmit
    @Test
    fun pipLayerInsideDisplay() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAlwaysVisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(pipApp)
        }
    }

    @Presubmit
    @Test
    fun pipAppLayerCoversFullScreen() {
        testSpec.assertLayersEnd {
            visibleRegion(pipApp).coversExactly(endingBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 1)
        }
    }
}
