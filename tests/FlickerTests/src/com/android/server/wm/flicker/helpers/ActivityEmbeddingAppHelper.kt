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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.tools.common.PlatformConsts
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.window.extensions.WindowExtensions
import androidx.window.extensions.WindowExtensionsProvider
import androidx.window.extensions.embedding.ActivityEmbeddingComponent
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.Assume.assumeNotNull

class ActivityEmbeddingAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.ActivityEmbedding.MainActivity.LABEL,
    component: ComponentNameMatcher = MAIN_ACTIVITY_COMPONENT
) : StandardAppHelper(instr, launcherName, component) {

    /**
     * Clicks the button to launch the secondary activity, which should split with the main activity
     * based on the split pair rule.
     */
    fun launchSecondaryActivity(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(getPackage(), "launch_secondary_activity_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch secondary activity button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(SECONDARY_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(MAIN_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    /**
     * Clicks the button to launch the placeholder primary activity, which should launch the
     * placeholder secondary activity based on the placeholder rule.
     */
    fun launchPlaceholderSplit(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(getPackage(), "launch_placeholder_split_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch placeholder split button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(PLACEHOLDER_PRIMARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(PLACEHOLDER_SECONDARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    companion object {
        private const val TAG = "ActivityEmbeddingAppHelper"

        val MAIN_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.MainActivity.COMPONENT.toFlickerComponent()

        val SECONDARY_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.SecondaryActivity.COMPONENT.toFlickerComponent()

        val PLACEHOLDER_PRIMARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT
                .toFlickerComponent()

        val PLACEHOLDER_SECONDARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderSecondaryActivity.COMPONENT
                .toFlickerComponent()

        @JvmStatic
        fun getWindowExtensions(): WindowExtensions? {
            try {
                return WindowExtensionsProvider.getWindowExtensions()
            } catch (e: NoClassDefFoundError) {
                Log.d(TAG, "Extension implementation not found")
            } catch (e: UnsupportedOperationException) {
                Log.d(TAG, "Stub Extension")
            }
            return null
        }

        @JvmStatic
        fun getActivityEmbeddingComponent(): ActivityEmbeddingComponent? {
            return getWindowExtensions()?.activityEmbeddingComponent
        }

        @JvmStatic
        fun assumeActivityEmbeddingSupportedDevice() {
            assumeNotNull(getActivityEmbeddingComponent())
        }
    }
}
