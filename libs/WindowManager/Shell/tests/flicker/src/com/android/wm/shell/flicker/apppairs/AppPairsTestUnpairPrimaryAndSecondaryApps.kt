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

package com.android.wm.shell.flicker.apppairs

import android.os.SystemClock
import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.traces.layers.getVisibleBounds
import com.android.wm.shell.flicker.APP_PAIR_SPLIT_DIVIDER
import com.android.wm.shell.flicker.appPairsDividerIsInvisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launch app from launcher.
 * To run this test: `atest WMShellFlickerTests:AppPairsTestUnpairPrimaryAndSecondaryApps`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class AppPairsTestUnpairPrimaryAndSecondaryApps(
    testSpec: FlickerTestParameter
) : AppPairsTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            setup {
                executeShellCommand(
                    composePairsCommand(primaryTaskId, secondaryTaskId, pair = true))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
            transitions {
                // TODO pair apps through normal UX flow
                executeShellCommand(
                    composePairsCommand(primaryTaskId, secondaryTaskId, pair = false))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
        }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @FlakyTest
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    fun appPairsDividerIsInvisible() = testSpec.appPairsDividerIsInvisible()

    @Presubmit
    @Test
    fun bothAppWindowsInvisible() {
        testSpec.assertWmEnd {
            isInvisible(primaryApp.defaultWindowName)
            isInvisible(secondaryApp.defaultWindowName)
        }
    }

    @FlakyTest
    @Test
    fun appsStartingBounds() {
        testSpec.assertLayersStart {
            val dividerRegion = entry.getVisibleBounds(APP_PAIR_SPLIT_DIVIDER)
            visibleRegion(primaryApp.defaultWindowName)
                .coversExactly(appPairsHelper.getPrimaryBounds(dividerRegion))
            visibleRegion(secondaryApp.defaultWindowName)
                .coversExactly(appPairsHelper.getSecondaryBounds(dividerRegion))
        }
    }

    @FlakyTest
    @Test
    fun appsEndingBounds() {
        testSpec.assertLayersEnd {
            notContains(primaryApp.defaultWindowName)
            notContains(secondaryApp.defaultWindowName)
        }
    }

    @FlakyTest
    @Test
    override fun navBarLayerIsAlwaysVisible() {
        super.navBarLayerIsAlwaysVisible()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = AppPairsHelper.TEST_REPETITIONS)
        }
    }
}
