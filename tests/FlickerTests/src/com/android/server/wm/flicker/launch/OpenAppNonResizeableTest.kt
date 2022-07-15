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

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.statusBarLayerPositionAtEnd
import com.android.server.wm.traces.common.ComponentMatcher
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app while the device is locked
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppNonResizeableTest`
 *
 * Actions:
 *     Lock the device.
 *     Launch an app on top of the lock screen [testApp] and wait animation to complete
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
open class OpenAppNonResizeableTest(testSpec: FlickerTestParameter) :
    OpenAppFromLockTransition(testSpec) {
    override val testApp = NonResizeableAppHelper(instrumentation)

    /**
     * Checks that the [ComponentMatcher.NAV_BAR] layer starts invisible, becomes visible during
     * unlocking animation and remains visible at the end
     */
    @FlakyTest(bugId = 227083463)
    @Test
    fun navBarLayerVisibilityChanges() {
        testSpec.assertLayers {
            this.isInvisible(ComponentMatcher.NAV_BAR)
                .then()
                .isVisible(ComponentMatcher.NAV_BAR)
        }
    }

    /**
     * Checks if [testApp] is visible at the end of the transition
     */
    @Presubmit
    @Test
    fun appWindowBecomesVisibleAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowVisible(testApp)
        }
    }

    /**
     * Checks that the [ComponentMatcher.NAV_BAR] starts the transition invisible, then becomes
     * visible during the unlocking animation and remains visible at the end of the transition
     */
    @Presubmit
    @Test
    fun navBarWindowsVisibilityChanges() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.assertWm {
            this.isNonAppWindowInvisible(ComponentMatcher.NAV_BAR)
                .then()
                .isAboveAppWindowVisible(ComponentMatcher.NAV_BAR)
        }
    }

    /**
     * Checks that the [ComponentMatcher.TASK_BAR] starts the transition invisible, then becomes
     * visible during the unlocking animation and remains visible at the end of the transition
     */
    @Presubmit
    @Test
    fun taskBarLayerIsVisibleAtEnd() {
        Assume.assumeTrue(testSpec.isTablet)
        testSpec.assertLayersEnd {
            this.isVisible(ComponentMatcher.TASK_BAR)
        }
    }

    /**
     * Checks that the [ComponentMatcher.STATUS_BAR] layer is visible at the end of the trace
     *
     * It is not possible to check at the start because the screen is off
     */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(ComponentMatcher.STATUS_BAR)
        }
    }

    /**
     * Checks the position of the [ComponentMatcher.NAV_BAR] at the end of the transition
     */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtEnd() = super.navBarLayerPositionAtEnd()

    /** {@inheritDoc} */
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarLayerIsVisibleAtStartAndEnd() { }

    /** {@inheritDoc} */
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarLayerIsVisibleAtStartAndEnd() { }

    /** {@inheritDoc} */
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarWindowIsAlwaysVisible() { }

    /** {@inheritDoc} */
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarWindowIsAlwaysVisible() { }

    /** {@inheritDoc} */
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarWindowIsAlwaysVisible() { }

    /**
     * Checks the position of the [ComponentMatcher.STATUS_BAR] at the end of the
     * transition
     */
    @Postsubmit
    @Test
    fun statusBarLayerPositionEnd() = testSpec.statusBarLayerPositionAtEnd()

    /**
     * Checks the [ComponentMatcher.NAV_BAR] is visible at the end of the transition
     */
    @Postsubmit
    @Test
    fun navBarLayerIsVisibleAtEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(ComponentMatcher.NAV_BAR)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerBecomesVisible() {
        Assume.assumeFalse(testSpec.isTablet)
        super.appLayerBecomesVisible()
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 227143265)
    @Test
    fun appLayerBecomesVisibleTablet() {
        Assume.assumeTrue(testSpec.isTablet)
        super.appLayerBecomesVisible()
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest(bugId = 218470989)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 227143265)
    @Test
    override fun appWindowBecomesTopWindow() =
        super.appWindowBecomesTopWindow()

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
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedNavigationModes =
                            listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY),
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}
