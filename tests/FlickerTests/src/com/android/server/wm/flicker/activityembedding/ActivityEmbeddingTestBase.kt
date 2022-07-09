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

package com.android.server.wm.flicker.activityembedding

import android.app.Instrumentation
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.Before

abstract class ActivityEmbeddingTestBase {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val testApp = ActivityEmbeddingAppHelper(instrumentation)

    @Before
    fun assumeActivityEmbeddingSupported() {
        // The test should only be run on devices that support ActivityEmbedding.
        ActivityEmbeddingAppHelper.assumeActivityEmbeddingSupportedDevice()
    }
}
