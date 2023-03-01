/*
 * Copyright (c) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.android.systemui.statusbar.notification

import com.android.systemui.log.dagger.NotificationLockscreenLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

class NotificationWakeUpCoordinatorLogger
@Inject
constructor(@NotificationLockscreenLog private val buffer: LogBuffer) {
    private var lastSetDozeAmountLogWasFractional = false
    private var lastSetDozeAmountLogState = -1
    private var lastSetDozeAmountLogSource = "undefined"
    private var lastOnDozeAmountChangedLogWasFractional = false

    fun logSetDozeAmount(
        linear: Float,
        eased: Float,
        source: String,
        state: Int,
        changed: Boolean,
    ) {
        // Avoid logging on every frame of the animation if important values are not changing
        val isFractional = linear != 1f && linear != 0f
        if (
            lastSetDozeAmountLogWasFractional &&
                isFractional &&
                lastSetDozeAmountLogState == state &&
                lastSetDozeAmountLogSource == source
        ) {
            return
        }
        lastSetDozeAmountLogWasFractional = isFractional
        lastSetDozeAmountLogState = state
        lastSetDozeAmountLogSource = source

        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = linear.toDouble()
                str2 = eased.toString()
                str3 = source
                int1 = state
                bool1 = changed
            },
            {
                "setDozeAmount(linear=$double1, eased=$str2, source=$str3)" +
                    " state=${StatusBarState.toString(int1)} changed=$bool1"
            }
        )
    }

    fun logMaybeClearDozeAmountOverrideHidingNotifs(
        willRemove: Boolean,
        onKeyguard: Boolean,
        dozing: Boolean,
        bypass: Boolean,
        animating: Boolean,
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 =
                    "willRemove=$willRemove onKeyguard=$onKeyguard dozing=$dozing" +
                        " bypass=$bypass animating=$animating"
            },
            { "maybeClearDozeAmountOverrideHidingNotifs() $str1" }
        )
    }

    fun logOnDozeAmountChanged(linear: Float, eased: Float) {
        // Avoid logging on every frame of the animation when values are fractional
        val isFractional = linear != 1f && linear != 0f
        if (lastOnDozeAmountChangedLogWasFractional && isFractional) return
        lastOnDozeAmountChangedLogWasFractional = isFractional
        buffer.log(
            TAG,
            DEBUG,
            {
                double1 = linear.toDouble()
                str2 = eased.toString()
            },
            { "onDozeAmountChanged(linear=$double1, eased=$str2)" }
        )
    }

    fun logOnStateChanged(newState: Int, storedState: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                int1 = newState
                int2 = storedState
            },
            {
                "onStateChanged(newState=${StatusBarState.toString(int1)})" +
                    " stored=${StatusBarState.toString(int2)}"
            }
        )
    }
}

private const val TAG = "NotificationWakeUpCoordinator"
