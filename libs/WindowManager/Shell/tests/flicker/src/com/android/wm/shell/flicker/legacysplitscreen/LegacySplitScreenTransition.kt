/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.flicker.legacysplitscreen

import android.app.Instrumentation
import android.support.test.launcherhelper.LauncherStrategyFactory
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.isRotated
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.wm.shell.flicker.helpers.SplitScreenHelper

abstract class LegacySplitScreenTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val isRotated = testSpec.config.startRotation.isRotated()
    protected val splitScreenApp = SplitScreenHelper.getPrimary(instrumentation)
    protected val secondaryApp = SplitScreenHelper.getSecondary(instrumentation)
    protected val nonResizeableApp = SplitScreenHelper.getNonResizeable(instrumentation)
    protected val LAUNCHER_PACKAGE_NAME = LauncherStrategyFactory.getInstance(instrumentation)
        .launcherStrategy.supportedLauncherPackage

    protected open val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview(wmHelper)
                    secondaryApp.launchViaIntent(wmHelper)
                    splitScreenApp.launchViaIntent(wmHelper)
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    secondaryApp.exit(wmHelper)
                    splitScreenApp.exit(wmHelper)
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            transition(this, testSpec.config)
        }
    }

    internal open val cleanSetup: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview(wmHelper)
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    nonResizeableApp.exit(wmHelper)
                    splitScreenApp.exit(wmHelper)
                    device.pressHome()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    companion object {
        internal const val LIVE_WALLPAPER_PACKAGE_NAME =
            "com.breel.wallpapers18.soundviz.wallpaper.variations.SoundVizWallpaperV2"
        internal const val LETTERBOX_NAME = "Letterbox"
        internal const val TOAST_NAME = "Toast"
        internal const val SPLASH_SCREEN_NAME = "Splash Screen"
    }
}
