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
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipKeyboardTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
@FlakyTest(bugId = 218604389)
open class PipKeyboardTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val imeApp = ImeAppHelper(instrumentation)

    @Before
    open fun before() {
        assumeFalse(isShellTransitionsEnabled)
    }

    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition(eachRun = false) {
            setup {
                test {
                    imeApp.launchViaIntent(wmHelper)
                    setRotation(testSpec.startRotation)
                }
            }
            teardown {
                test {
                    imeApp.exit(wmHelper)
                    setRotation(Surface.ROTATION_0)
                }
            }
            transitions {
                // open the soft keyboard
                imeApp.openIME(wmHelper)
                createTag(TAG_IME_VISIBLE)

                // then close it again
                imeApp.closeIME(wmHelper)
            }
        }

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    /**
     * Ensure the pip window remains visible throughout any keyboard interactions
     */
    @Presubmit
    @Test
    open fun pipInVisibleBounds() {
        testSpec.assertWmVisibleRegion(pipApp.component) {
            val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)
            coversAtMost(displayBounds)
        }
    }

    /**
     * Ensure that the pip window does not obscure the keyboard
     */
    @Presubmit
    @Test
    open fun pipIsAboveAppWindow() {
        testSpec.assertWmTag(TAG_IME_VISIBLE) {
            isAboveWindow(FlickerComponentName.IME, pipApp.component)
        }
    }

    companion object {
        private const val TAG_IME_VISIBLE = "imeIsVisible"

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 3)
        }
    }
}
