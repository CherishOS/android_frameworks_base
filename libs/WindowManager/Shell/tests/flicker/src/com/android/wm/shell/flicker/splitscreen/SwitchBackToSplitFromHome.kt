/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.flicker.splitscreen

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Presubmit
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerBecomesVisible
import com.android.wm.shell.flicker.splitScreenEntered
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switch to split pair from home.
 *
 * To run this test: `atest WMShellFlickerTests:SwitchBackToSplitFromHome`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SwitchBackToSplitFromHome(testSpec: FlickerTestParameter) : SplitScreenBase(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                SplitScreenUtils.enterSplit(wmHelper, tapl, primaryApp, secondaryApp)

                tapl.goHome()
                wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
            }
            transitions {
                tapl.workspace.quickSwitchToPreviousApp()
                SplitScreenUtils.waitForSplitComplete(wmHelper, primaryApp, secondaryApp)
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() = testSpec.splitScreenEntered(primaryApp, secondaryApp, fromOtherApp = true)

    @Presubmit
    @Test
    fun splitScreenDividerBecomesVisible() = testSpec.splitScreenDividerBecomesVisible()

    @Presubmit @Test fun primaryAppLayerBecomesVisible() = testSpec.layerBecomesVisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible() = testSpec.layerBecomesVisible(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
        testSpec.splitAppLayerBoundsIsVisibleAtEnd(
            primaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsIsVisibleAtEnd() =
        testSpec.splitAppLayerBoundsIsVisibleAtEnd(
            secondaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )

    @Presubmit
    @Test
    fun primaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(secondaryApp)

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() = super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                    supportedNavigationModes =
                        listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY)
                )
        }
    }
}
