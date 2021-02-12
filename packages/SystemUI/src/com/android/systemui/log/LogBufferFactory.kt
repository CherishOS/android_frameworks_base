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

package com.android.systemui.log

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import javax.inject.Inject

@SysUISingleton
class LogBufferFactory @Inject constructor(
    private val dumpManager: DumpManager,
    private val logcatEchoTracker: LogcatEchoTracker
) {
    @JvmOverloads
    fun create(name: String, maxPoolSize: Int, flexSize: Int = 10): LogBuffer {
        val buffer = LogBuffer(name, maxPoolSize, flexSize, logcatEchoTracker)
        dumpManager.registerBuffer(name, buffer)
        return buffer
    }
}
