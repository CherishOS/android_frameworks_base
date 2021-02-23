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

package com.android.wm.shell.flicker.legacysplitscreen

import android.os.Bundle
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.WALLPAPER_TITLE
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.canSplitScreen
import com.android.server.wm.flicker.helpers.openQuickstep
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.Assert
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open non-resizable activity will auto exit split screen mode
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenNonResizableNotDock`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FlakyTest(bugId = 173875043)
class EnterSplitScreenNonResizableNotDock(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            super.transition(this, configuration)
            teardown {
                eachRun {
                    nonResizeableApp.exit(wmHelper)
                }
            }
            transitions {
                nonResizeableApp.launchViaIntent(wmHelper)
                device.openQuickstep(wmHelper)
                if (device.canSplitScreen(wmHelper)) {
                    Assert.fail("Non-resizeable app should not enter split screen")
                }
            }
        }

    @Test
    fun dockedStackDividerIsInvisible() = testSpec.dockedStackDividerIsInvisible()

    @FlakyTest(bugId = 178447631)
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry(
            listOf(LAUNCHER_PACKAGE_NAME,
                SPLASH_SCREEN_NAME,
                nonResizeableApp.defaultWindowName,
                splitScreenApp.defaultWindowName)
        )

    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry(
            listOf(WALLPAPER_TITLE,
                LAUNCHER_PACKAGE_NAME,
                SPLASH_SCREEN_NAME,
                nonResizeableApp.defaultWindowName,
                splitScreenApp.defaultWindowName)
        )

    @Test
    fun appWindowIsVisible() {
        testSpec.assertWmEnd {
            isInvisible(nonResizeableApp.defaultWindowName)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0)) // b/178685668
        }
    }
}