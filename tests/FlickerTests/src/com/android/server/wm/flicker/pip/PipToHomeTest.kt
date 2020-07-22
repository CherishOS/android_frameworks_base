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

package com.android.server.wm.flicker.pip

import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.CommonTransitions
import com.android.server.wm.flicker.TransitionRunner
import com.android.server.wm.flicker.WmTraceSubject
import com.android.server.wm.flicker.helpers.PipAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest FlickerTests:PipToHomeTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 152738416)
class PipToHomeTest(
    beginRotationName: String,
    beginRotation: Int
) : PipTestBase(beginRotationName, beginRotation) {
    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.exitPipModeToHome(testApp as PipAppHelper, instrumentation,
                uiDevice, beginRotation)
                .includeJankyRuns().build()

    @Test
    fun checkVisibility_backgroundWindowVisibleBehindPipLayer() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAppWindowOnTop(sPipWindowTitle)
                    .then()
                    .showsBelowAppWindow("Wallpaper")
                    .then()
                    .showsAppWindowOnTop("Wallpaper")
                    .forAllEntries()
        }
    }
}
