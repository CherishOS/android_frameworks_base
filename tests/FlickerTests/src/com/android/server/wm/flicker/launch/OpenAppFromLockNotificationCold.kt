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

package com.android.server.wm.flicker.launch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.navBarLayerPositionAtEnd
import com.android.server.wm.flicker.statusBarLayerPositionAtEnd
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification from the lock screen.
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppFromLockNotificationCold`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Postsubmit
open class OpenAppFromLockNotificationCold(testSpec: FlickerTestParameter) :
    OpenAppFromNotificationCold(testSpec) {

    override val openingNotificationsFromLockScreen = true

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            // Needs to run at start of transition,
            // so before the transition defined in super.transition
            transitions {
                device.wakeUp()
            }

            super.transition(this)

            // Needs to run at the end of the setup, so after the setup defined in super.transition
            setup {
                device.sleep()
                wmHelper.StateSyncBuilder()
                    .withoutTopVisibleAppWindows()
                    .waitForAndVerify()
            }
        }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 229735718)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 203538234)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 203538234)
    @Test
    override fun appWindowBecomesTopWindow() = super.appWindowBecomesTopWindow()

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun navBarLayerPositionAtStartAndEnd() { }

    /**
     * Checks the position of the [ComponentMatcher.NAV_BAR] at the end of the transition
     */
    @Postsubmit
    @Test
    fun navBarLayerPositionAtEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.navBarLayerPositionAtEnd()
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun statusBarLayerPositionAtStartAndEnd() { }

    /**
     * Checks the position of the [ComponentMatcher.STATUS_BAR] at the start and end of the
     * transition
     */
    @Postsubmit
    @Test
    fun statusBarLayerPositionEnd() = testSpec.statusBarLayerPositionAtEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appLayerBecomesVisible() = super.appLayerBecomesVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowIsTopWindowAtEnd() =
        super.appWindowIsTopWindowAtEnd()

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
