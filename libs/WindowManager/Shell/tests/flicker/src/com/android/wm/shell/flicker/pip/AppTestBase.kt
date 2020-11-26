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

package com.android.wm.shell.flicker.pip

import android.app.ActivityTaskManager
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.os.SystemClock
import com.android.wm.shell.flicker.NonRotationTestBase

abstract class AppTestBase(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    companion object {
        fun removeAllTasksButHome() {
            val ALL_ACTIVITY_TYPE_BUT_HOME = intArrayOf(
                    ACTIVITY_TYPE_STANDARD, ACTIVITY_TYPE_ASSISTANT, ACTIVITY_TYPE_RECENTS,
                    ACTIVITY_TYPE_UNDEFINED)
            val atm = ActivityTaskManager.getService()
            atm.removeRootTasksWithActivityTypes(ALL_ACTIVITY_TYPE_BUT_HOME)
        }

        fun waitForAnimationComplete() {
            // TODO: UiDevice doesn't have reliable way to wait for the completion of animation.
            // Consider to introduce WindowManagerStateHelper to access Activity state.
            SystemClock.sleep(1000)
        }
    }
}
