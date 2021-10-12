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

package com.android.systemui.statusbar.phone.ongoingcall

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.MotionEvent.*
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.phone.StatusBarWindowController
import javax.inject.Inject

/**
 * A class to detect when a user swipes away the status bar. To be notified when the swipe away
 * gesture is detected, add a callback via [addOnGestureDetectedCallback].
 */
@SysUISingleton
open class SwipeStatusBarAwayGestureHandler @Inject constructor(
    context: Context,
    private val statusBarWindowController: StatusBarWindowController,
) {

    /**
     * Active callbacks, each associated with a tag. Gestures will only be monitored if
     * [callbacks.size] > 0.
     */
    private val callbacks: MutableMap<String, () -> Unit> = mutableMapOf()

    private var startY: Float = 0f
    private var startTime: Long = 0L
    private var monitoringCurrentTouch: Boolean = false

    private var inputMonitor: InputMonitorCompat? = null
    private var inputReceiver: InputChannelCompat.InputEventReceiver? = null

    // TODO(b/195839150): Update this threshold when the config changes?
    private var swipeDistanceThreshold: Int = context.resources.getDimensionPixelSize(
        com.android.internal.R.dimen.system_gestures_start_threshold
    )

    /** Adds a callback that will be triggered when the swipe away gesture is detected. */
    fun addOnGestureDetectedCallback(tag: String, callback: () -> Unit) {
        val callbacksWasEmpty = callbacks.isEmpty()
        callbacks[tag] = callback
        if (callbacksWasEmpty) {
            startGestureListening()
        }
    }

    /** Removes the callback. */
    fun removeOnGestureDetectedCallback(tag: String) {
        callbacks.remove(tag)
        if (callbacks.isEmpty()) {
             stopGestureListening()
        }
    }

    private fun onInputEvent(ev: InputEvent) {
        if (ev !is MotionEvent) {
            return
        }

        when (ev.actionMasked) {
            ACTION_DOWN -> {
                if (
                    // Gesture starts just below the status bar
                    // TODO(b/195839150): Is [statusBarHeight] the correct dimension to use for
                    //   determining which down touches are valid?
                    ev.y >= statusBarWindowController.statusBarHeight
                    && ev.y <= 3 * statusBarWindowController.statusBarHeight
                ) {
                    Log.d(TAG, "Beginning gesture detection, y=${ev.y}")
                    startY = ev.y
                    startTime = ev.eventTime
                    monitoringCurrentTouch = true
                } else {
                    monitoringCurrentTouch = false
                }
            }
            ACTION_MOVE -> {
                if (!monitoringCurrentTouch) {
                    return
                }
                if (
                    // Gesture is up
                    ev.y < startY
                    // Gesture went far enough
                    && (startY - ev.y) >= swipeDistanceThreshold
                    // Gesture completed quickly enough
                    && (ev.eventTime - startTime) < SWIPE_TIMEOUT_MS
                ) {
                    Log.i(TAG, "Gesture detected; notifying callbacks")
                    callbacks.values.forEach { it.invoke() }
                    monitoringCurrentTouch = false
                }
            }
            ACTION_CANCEL, ACTION_UP -> {
                monitoringCurrentTouch = false
            }
        }
    }

    /** Start listening for the swipe gesture. */
    private fun startGestureListening() {
        stopGestureListening()

        if (DEBUG) { Log.d(TAG, "Input listening started") }
        inputMonitor = InputMonitorCompat(TAG, Display.DEFAULT_DISPLAY).also {
            inputReceiver = it.getInputReceiver(
                Looper.getMainLooper(),
                Choreographer.getInstance(),
                this::onInputEvent
            )
        }
    }

    /** Stop listening for the swipe gesture. */
    private fun stopGestureListening() {
        inputMonitor?.let {
            if (DEBUG) { Log.d(TAG, "Input listening stopped") }
            inputMonitor = null
            it.dispose()
        }
        inputReceiver?.let {
            inputReceiver = null
            it.dispose()
        }
    }
}

private const val SWIPE_TIMEOUT_MS: Long = 500
private val TAG = SwipeStatusBarAwayGestureHandler::class.simpleName
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
