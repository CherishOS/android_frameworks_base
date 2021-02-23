/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.rotation

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations.
 * To run this test: `atest FlickerTests:ChangeAppRotationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChangeAppRotationTest(
    testSpec: FlickerTestParameter
) : RotationTransition(testSpec) {
    override val testApp = SimpleAppHelper(instrumentation)
    override fun getAppLaunchParams(configuration: Bundle): Map<String, String> = emptyMap()

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation,
        testSpec.config.endRotation, allStates = false)

    @Presubmit
    @Test
    fun screenshotLayerBecomesInvisible() {
        testSpec.assertLayers {
            this.showsLayer(testApp.getPackage())
                .then()
                .showsLayer(SCREENSHOT_LAYER)
                .then()
                .showsLayer(testApp.getPackage())
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @FlakyTest(bugId = 140855415)
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    @FlakyTest(bugId = 140855415)
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales(
        testSpec.config.startRotation, testSpec.config.endRotation)

    @FlakyTest(bugId = 140855415)
    @Test
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales(
        testSpec.config.startRotation, testSpec.config.endRotation)

    @FlakyTest(bugId = 140855415)
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 140855415)
    @Test
    fun appLayerRotates_StartingPos() {
        testSpec.assertLayersStart {
            this.hasVisibleRegion(testApp.getPackage(), startingPos)
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    fun appLayerRotates_EndingPos() {
        testSpec.assertLayersEnd {
            this.hasVisibleRegion(testApp.getPackage(), endingPos)
        }
    }

    @FlakyTest(bugId = 151179149)
    @Test
    fun focusDoesNotChange() = testSpec.focusDoesNotChange()

    companion object {
        private const val SCREENSHOT_LAYER = "RotationLayer"

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigRotationTests(repetitions = 5)
        }
    }
}