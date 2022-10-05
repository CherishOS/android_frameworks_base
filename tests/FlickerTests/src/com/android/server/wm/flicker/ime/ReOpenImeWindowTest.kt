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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.FlakyTest
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions. To run this test: `atest FlickerTests:ReOpenImeWindowTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class ReOpenImeWindowTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
            this.setRotation(testSpec.startRotation)
            device.pressRecentApps()
            wmHelper.StateSyncBuilder().withRecentsActivityVisible().waitForAndVerify()
        }
        transitions {
            device.reopenAppFromOverview(wmHelper)
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).withImeShown().waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 251214932)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        // depends on how much of the animation transactions are sent to SF at once
        // sometimes this layer appears for 2-3 frames, sometimes for only 1
        val recentTaskComponent = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT,
                    recentTaskComponent
                )
            )
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 251214932)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        val component = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                ignoreWindows =
                    listOf(
                        ComponentNameMatcher.SPLASH_SCREEN,
                        ComponentNameMatcher.SNAPSHOT,
                        component
                    )
            )
        }
    }

    @FlakyTest(bugId = 251214932)
    @Test
    fun launcherWindowBecomesInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isAppWindowInvisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    @FlakyTest(bugId = 251214932)
    @Test
    fun imeWindowIsAlwaysVisible() = testSpec.imeWindowIsAlwaysVisible()

    @FlakyTest(bugId = 251214932)
    @Test
    fun imeAppWindowIsAlwaysVisible() {
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. Since we log 1x per frame, sometimes the activity visibility
        // and the app visibility are updated together, sometimes not, thus ignore activity
        // check at the start
        testSpec.assertWm { this.isAppWindowVisible(testApp) }
    }

    @FlakyTest(bugId = 251214932)
    @Test
    fun imeLayerBecomesVisible() {
        testSpec.assertLayers { this.isVisible(ComponentNameMatcher.IME) }
    }

    @FlakyTest(bugId = 251214932)
    @Test
    fun appLayerReplacesLauncher() {
        testSpec.assertLayers {
            this.isVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp)
        }
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() {
        super.navBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun navBarWindowIsAlwaysVisible() {
        super.navBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        super.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun entireScreenCovered() {
        super.entireScreenCovered()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() {
        super.navBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() {
        super.statusBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun statusBarWindowIsAlwaysVisible() {
        super.statusBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() {
        super.taskBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 251214932)
    @Test
    override fun taskBarWindowIsAlwaysVisible() {
        super.taskBarWindowIsAlwaysVisible()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0))
        }
    }
}
