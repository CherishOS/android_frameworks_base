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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip Stack in bounds after rotations.
 * To run this test: `atest WMShellFlickerTests:PipRotationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class PipRotationTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val fixedApp = FixedAppHelper(instrumentation)
    private val startingBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
    private val endingBounds = WindowUtils.getDisplayBounds(testSpec.config.endRotation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition(eachRun = false) { configuration ->
            setup {
                test {
                    fixedApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    setRotation(configuration.startRotation)
                }
            }
            transitions {
                setRotation(configuration.endRotation)
            }
            teardown {
                eachRun {
                    setRotation(Surface.ROTATION_0)
                }
            }
        }

    @FlakyTest(bugId = 185400889)
    @Test
    override fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation,
        testSpec.config.endRotation, allStates = false)

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation,
            testSpec.config.endRotation)

    @Presubmit
    @Test
    override fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation,
            testSpec.config.endRotation)

    @FlakyTest(bugId = 185400889)
    @Test
    fun appLayerRotates_StartingBounds() {
        testSpec.assertLayersStart {
            visibleRegion(fixedApp.defaultWindowName).coversExactly(startingBounds)
            visibleRegion(pipApp.defaultWindowName).coversAtMost(startingBounds)
        }
    }

    @FlakyTest(bugId = 185400889)
    @Test
    fun appLayerRotates_EndingBounds() {
        testSpec.assertLayersEnd {
            visibleRegion(fixedApp.defaultWindowName).coversExactly(endingBounds)
            visibleRegion(pipApp.defaultWindowName).coversAtMost(endingBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigRotationTests(
                supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                repetitions = 5)
        }
    }
}
