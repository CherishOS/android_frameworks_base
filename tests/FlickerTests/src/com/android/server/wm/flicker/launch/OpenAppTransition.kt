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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.replacesLayer
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Test

/**
 * Base class for app launch tests
 */
abstract class OpenAppTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected open val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    /**
     * Defines the transition used to run the test
     */
    protected open val transition: FlickerBuilder.(Map<String, Any?>) -> Unit = {
        withTestName { testSpec.name }
        repeat { testSpec.config.repetitions }
        setup {
            test {
                device.wakeUpAndGoToHomeScreen()
                this.setRotation(testSpec.config.startRotation)
            }
        }
        teardown {
            test {
                testApp.exit()
            }
        }
    }

    /**
     * Entry point for the test runner. It will use this method to initialize and cache
     * flicker executions
     */
    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(testSpec.config)
        }
    }

    /**
     * Checks that the navigation bar window is visible during the whole transition
     */
    open fun navBarWindowIsVisible() {
        testSpec.navBarWindowIsVisible()
    }

    /**
     * Checks that the navigation bar layer is visible at the start and end of the trace
     */
    open fun navBarLayerIsVisible() {
        testSpec.navBarLayerIsVisible()
    }

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     */
    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the status bar window is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    /**
     * Checks that the status bar layer is visible at the start and end of the trace
     */
    @Presubmit
    @Test
    open fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

    /**
     * Checks the position of the status bar at the start and end of the transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    /**
     * Checks that all windows that are visible on the trace, are visible for at least 2
     * consecutive entries.
     */
    @Presubmit
    @Test
    open fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry()
        }
    }

    /**
     * Checks that all layers that are visible on the trace, are visible for at least 2
     * consecutive entries.
     */
    @Presubmit
    @Test
    open fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry()
        }
    }

    /**
     * Checks that all parts of the screen are covered during the transition
     */
    @Presubmit
    @Test
    open fun entireScreenCovered() = testSpec.entireScreenCovered()

    /**
     * Checks that the focus changes from the launcher to [testApp]
     */
    @Presubmit
    @Test
    open fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges("NexusLauncherActivity", testApp.`package`)
        }
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] layer is visible at the start of the transition, and
     * is replaced by [testApp], which remains visible until the end
     */
    open fun appLayerReplacesLauncher() {
        testSpec.replacesLayer(LAUNCHER_COMPONENT, testApp.component)
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] window is visible at the start of the transition, and
     * is replaced by a snapshot or splash screen (optional), and finally, is replaced by
     * [testApp], which remains visible until the end
     */
    @Presubmit
    @Test
    open fun appWindowReplacesLauncherAsTopWindow() {
        testSpec.assertWm {
            this.isAppWindowOnTop(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(testApp.component)
        }
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] window is visible at the start, and
     * becomes invisible during the transition
     */
    open fun launcherWindowBecomesInvisible() {
        testSpec.assertWm {
            this.isAppWindowOnTop(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowNotOnTop(LAUNCHER_COMPONENT)
        }
    }
}