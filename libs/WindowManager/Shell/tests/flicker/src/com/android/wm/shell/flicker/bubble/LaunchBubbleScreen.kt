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

package com.android.wm.shell.flicker.bubble

import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test creating a bubble notification
 *
 * To run this test: `atest WMShellFlickerTests:LaunchBubbleScreen`
 *
 * Actions:
 *     Launch an app and enable app's bubble notification
 *     Send a bubble notification
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@Group4
open class LaunchBubbleScreen(testSpec: FlickerTestParameter) : BaseBubbleScreen(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition {
            transitions {
                val addBubbleBtn = waitAndGetAddBubbleBtn()
                addBubbleBtn?.click() ?: error("Bubble widget not found")
            }
        }

    @Presubmit
    @Test
    open fun testAppIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(testApp.component)
        }
    }
}
