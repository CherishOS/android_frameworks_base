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

package com.android.server.wm.flicker.rotation

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import org.junit.Test

abstract class RotationTransition(protected val testSpec: FlickerTestParameter) {
    protected abstract val testApp: StandardAppHelper

    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val startingPos get() = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
    protected val endingPos get() = WindowUtils.getDisplayBounds(testSpec.config.endRotation)

    protected open val transition: FlickerBuilder.(Map<String, Any?>) -> Unit = {
        withTestName { testSpec.name }
        repeat { testSpec.config.repetitions }
        setup {
            test {
                device.wakeUpAndGoToHomeScreen()
            }
            eachRun {
                this.setRotation(testSpec.config.startRotation)
            }
        }
        teardown {
            test {
                testApp.exit()
            }
        }
        transitions {
            this.setRotation(testSpec.config.endRotation)
        }
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(testSpec.config)
        }
    }

    @Presubmit
    @Test
    open fun navBarWindowIsAlwaysVisible() {
        testSpec.navBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun navBarLayerIsAlwaysVisible() {
        testSpec.navBarLayerIsAlwaysVisible()
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun navBarLayerRotatesAndScales() {
        testSpec.navBarLayerRotatesAndScales(
            testSpec.config.startRotation, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    open fun statusBarWindowIsAlwaysVisible() {
        testSpec.statusBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun statusBarLayerIsAlwaysVisible() {
        testSpec.statusBarLayerIsAlwaysVisible()
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun statusBarLayerRotatesScales() {
        testSpec.statusBarLayerRotatesScales(
            testSpec.config.startRotation, testSpec.config.endRotation)
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    open fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    @Presubmit
    @Test
    open fun noUncoveredRegions() {
        testSpec.noUncoveredRegions(testSpec.config.startRotation,
            testSpec.config.endRotation, allStates = false)
    }

    @FlakyTest(bugId = 151179149)
    @Test
    open fun focusDoesNotChange() {
        testSpec.focusDoesNotChange()
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun appLayerRotates_StartingPos() {
        testSpec.assertLayersStart {
            this.coversExactly(startingPos, testApp.getPackage())
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    open fun appLayerRotates_EndingPos() {
        testSpec.assertLayersEnd {
            this.coversExactly(endingPos, testApp.getPackage())
        }
    }
}