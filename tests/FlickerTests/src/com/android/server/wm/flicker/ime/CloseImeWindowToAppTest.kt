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

package com.android.server.wm.flicker.ime

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 * To run this test: `atest FlickerTests:CloseImeWindowToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseImeWindowToAppTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    testApp.launchViaIntent()
                    this.setRotation(testSpec.config.startRotation)
                }
                eachRun {
                    testApp.openIME(device, wmHelper)
                }
            }
            teardown {
                test {
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            transitions {
                testApp.closeIME(device, wmHelper)
            }
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry(listOf(IME_WINDOW_TITLE))

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() = testSpec.imeAppWindowIsAlwaysVisible(testApp)

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation)

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation)

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation)

    @Presubmit
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    fun imeLayerBecomesInvisible() = testSpec.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppLayerIsAlwaysVisible() = testSpec.imeAppLayerIsAlwaysVisible(testApp)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(repetitions = 5)
        }
    }
}