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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.appPairsDividerBecomesVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:OpenAppToLegacySplitScreen`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class OpenAppToLegacySplitScreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            super.transition(this, configuration)
            transitions {
                device.launchSplitScreen(wmHelper)
                wmHelper.waitForAppTransitionIdle()
            }
        }

    override val ignoredWindows: List<FlickerComponentName>
        get() = listOf(LAUNCHER_COMPONENT, splitScreenApp.component,
            FlickerComponentName.SPLASH_SCREEN,
            FlickerComponentName.SNAPSHOT)

    @FlakyTest
    @Test
    fun appWindowBecomesVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(splitScreenApp.component)
                    .then()
                    .isAppWindowVisible(splitScreenApp.component)
        }
    }

    @Presubmit
    @Test
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    @Presubmit
    @Test
    fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    @Presubmit
    @Test
    fun appPairsDividerBecomesVisible() = testSpec.appPairsDividerBecomesVisible()

    @FlakyTest
    @Test
    fun layerBecomesVisible() {
        testSpec.assertLayers {
            this.isInvisible(splitScreenApp.component)
                    .then()
                    .isVisible(splitScreenApp.component)
        }
    }

    @Presubmit
    @Test
    fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges(splitScreenApp.`package`,
                    "recents_animation_input_consumer", "NexusLauncherActivity")
        }
    }

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0) // bugId = 179116910
            )
        }
    }
}
