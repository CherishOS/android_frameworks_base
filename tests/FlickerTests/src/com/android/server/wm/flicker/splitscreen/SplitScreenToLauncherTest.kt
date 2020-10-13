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

package com.android.server.wm.flicker.splitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.DOCKED_STACK_DIVIDER
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest FlickerTests:SplitScreenToLauncherTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SplitScreenToLauncherTest(
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")

            // b/161435597 causes the test not to work on 90 degrees
            return FlickerTestRunnerFactory(instrumentation, listOf(Surface.ROTATION_0))
                .buildTest { configuration ->
                    withTestName {
                        buildTestTag("splitScreenToLauncher", testApp, configuration)
                    }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                        }
                        eachRun {
                            testApp.open()
                            this.setRotation(configuration.endRotation)
                            device.launchSplitScreen()
                            device.waitForIdle()
                        }
                    }
                    teardown {
                        eachRun {
                            testApp.exit()
                        }
                        test {
                            if (device.isInSplitScreen()) {
                                device.exitSplitScreen()
                            }
                        }
                    }
                    transitions {
                        device.exitSplitScreen()
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                        }

                        layersTrace {
                            navBarLayerIsAlwaysVisible()
                            statusBarLayerIsAlwaysVisible()
                            noUncoveredRegions(configuration.endRotation)
                            navBarLayerRotatesAndScales(configuration.endRotation)
                            statusBarLayerRotatesScales(configuration.endRotation)

                            // b/161435597 causes the test not to work on 90 degrees
                            all("dividerLayerBecomesInvisible") {
                                this.showsLayer(DOCKED_STACK_DIVIDER)
                                    .then()
                                    .hidesLayer(DOCKED_STACK_DIVIDER)
                            }

                            all("appLayerBecomesInvisible") {
                                this.showsLayer(testApp.getPackage())
                                    .then()
                                    .hidesLayer(testApp.getPackage())
                            }
                        }

                        eventLog {
                            focusDoesNotChange(bugId = 151179149)
                        }
                    }
                }
        }
    }
}
