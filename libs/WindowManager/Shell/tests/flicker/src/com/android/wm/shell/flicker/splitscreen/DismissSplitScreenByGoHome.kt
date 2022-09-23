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
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.appWindowBecomesInvisible
import com.android.wm.shell.flicker.layerBecomesInvisible
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesInvisible
import com.android.wm.shell.flicker.splitScreenDividerBecomesInvisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test dismiss split screen by go home.
 *
 * To run this test: `atest WMShellFlickerTests:DismissSplitScreenByGoHome`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class DismissSplitScreenByGoHome(
    testSpec: FlickerTestParameter
) : SplitScreenBase(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                SplitScreenUtils.enterSplit(wmHelper, tapl, primaryApp, secondaryApp)
            }
            transitions {
                tapl.goHome()
                wmHelper.StateSyncBuilder()
                    .withHomeActivityVisible()
                    .waitForAndVerify()
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun splitScreenDividerBecomesInvisible() = testSpec.splitScreenDividerBecomesInvisible()

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun primaryAppLayerBecomesInvisible() = testSpec.layerBecomesInvisible(primaryApp)

    // TODO(b/245472831): Move back to presubmit after shell transitions landing.
    @FlakyTest(bugId = 245472831)
    @Test
    fun secondaryAppLayerBecomesInvisible() = testSpec.layerBecomesInvisible(primaryApp)

    // TODO(b/245472831): Move back to presubmit after shell transitions landing.
    @FlakyTest(bugId = 245472831)
    @Test
    fun primaryAppBoundsBecomesInvisible() = testSpec.splitAppLayerBoundsBecomesInvisible(
        primaryApp, landscapePosLeft = tapl.isTablet, portraitPosTop = false)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun secondaryAppBoundsBecomesInvisible() = testSpec.splitAppLayerBoundsBecomesInvisible(
        secondaryApp, landscapePosLeft = !tapl.isTablet, portraitPosTop = true)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun primaryAppWindowBecomesInvisible() = testSpec.appWindowBecomesInvisible(primaryApp)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun secondaryAppWindowBecomesInvisible() = testSpec.appWindowBecomesInvisible(secondaryApp)

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() =
        super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() =
        super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() =
        super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() =
        super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() =
        super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes =
                    listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY))
        }
    }
}
