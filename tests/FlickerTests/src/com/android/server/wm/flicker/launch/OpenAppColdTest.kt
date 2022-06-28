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

package com.android.server.wm.flicker.launch

import androidx.test.filters.FlakyTest
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from launcher
 *
 * To run this test: `atest FlickerTests:OpenAppColdTest`
 *
 * Actions:
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] and wait animation to complete
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
open class OpenAppColdTest(testSpec: FlickerTestParameter)
    : OpenAppFromLauncherTransition(testSpec) {
    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                eachRun {
                    removeAllTasksButHome()
                    this.setRotation(testSpec.startRotation)
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
        }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() {
        super.navBarLayerRotatesAndScales()
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerReplacesLauncher() = super.appLayerReplacesLauncher()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
        super.appWindowReplacesLauncherAsTopWindow()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsVisible() = super.navBarWindowIsVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests()
        }
    }
}
