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
package com.android.systemui.unfold.updates

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import androidx.core.util.Consumer
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdatesListener
import com.android.systemui.unfold.updates.hinge.FULLY_OPEN_DEGREES
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import java.util.concurrent.Executor

class DeviceFoldStateProvider(
    context: Context,
    private val hingeAngleProvider: HingeAngleProvider,
    private val screenStatusProvider: ScreenStatusProvider,
    private val deviceStateManager: DeviceStateManager,
    private val mainExecutor: Executor
) : FoldStateProvider {

    private val outputListeners: MutableList<FoldUpdatesListener> = mutableListOf()

    @FoldUpdate
    private var lastFoldUpdate: Int? = null

    private val hingeAngleListener = HingeAngleListener()
    private val screenListener = ScreenStatusListener()
    private val foldStateListener = FoldStateListener(context)

    private var isFolded = false
    private var isUnfoldHandled = true

    override fun start() {
        deviceStateManager.registerCallback(
            mainExecutor,
            foldStateListener
        )
        screenStatusProvider.addCallback(screenListener)
        hingeAngleProvider.addCallback(hingeAngleListener)
    }

    override fun stop() {
        screenStatusProvider.removeCallback(screenListener)
        deviceStateManager.unregisterCallback(foldStateListener)
        hingeAngleProvider.removeCallback(hingeAngleListener)
        hingeAngleProvider.stop()
    }

    override fun addCallback(listener: FoldUpdatesListener) {
        outputListeners.add(listener)
    }

    override fun removeCallback(listener: FoldUpdatesListener) {
        outputListeners.remove(listener)
    }

    override val isFullyOpened: Boolean
        get() = !isFolded && lastFoldUpdate == FOLD_UPDATE_FINISH_FULL_OPEN

    private fun onHingeAngle(angle: Float) {
        when (lastFoldUpdate) {
            FOLD_UPDATE_FINISH_FULL_OPEN -> {
                if (FULLY_OPEN_DEGREES - angle > START_CLOSING_THRESHOLD_DEGREES) {
                    lastFoldUpdate = FOLD_UPDATE_START_CLOSING
                    outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_START_CLOSING) }
                }
            }
            FOLD_UPDATE_START_OPENING -> {
                if (FULLY_OPEN_DEGREES - angle < FULLY_OPEN_THRESHOLD_DEGREES) {
                    lastFoldUpdate = FOLD_UPDATE_FINISH_FULL_OPEN
                    outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) }
                }
            }
            FOLD_UPDATE_START_CLOSING -> {
                if (FULLY_OPEN_DEGREES - angle < START_CLOSING_THRESHOLD_DEGREES) {
                    lastFoldUpdate = FOLD_UPDATE_FINISH_FULL_OPEN
                    outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) }
                }
            }
        }

        outputListeners.forEach { it.onHingeAngleUpdate(angle) }
    }

    private inner class FoldStateListener(context: Context) :
        DeviceStateManager.FoldStateListener(context, { folded: Boolean ->
            isFolded = folded

            if (folded) {
                lastFoldUpdate = FOLD_UPDATE_FINISH_CLOSED
                outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_FINISH_CLOSED) }
                hingeAngleProvider.stop()
                isUnfoldHandled = false
            } else {
                lastFoldUpdate = FOLD_UPDATE_START_OPENING
                outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_START_OPENING) }
                hingeAngleProvider.start()
            }
        })

    private inner class ScreenStatusListener :
        ScreenStatusProvider.ScreenListener {

        override fun onScreenTurnedOn() {
            // Trigger this event only if we are unfolded and this is the first screen
            // turned on event since unfold started. This prevents running the animation when
            // turning on the internal display using the power button.
            // Initially isUnfoldHandled is true so it will be reset to false *only* when we
            // receive 'folded' event. If SystemUI started when device is already folded it will
            // still receive 'folded' event on startup.
            if (!isFolded && !isUnfoldHandled) {
                outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) }
                isUnfoldHandled = true
            }
        }
    }

    private inner class HingeAngleListener : Consumer<Float> {

        override fun accept(angle: Float) {
            onHingeAngle(angle)
        }
    }
}

private const val START_CLOSING_THRESHOLD_DEGREES = 95f
private const val FULLY_OPEN_THRESHOLD_DEGREES = 15f