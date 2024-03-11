/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.animation.Animator
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.android.internal.logging.UiEventLogger
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.res.R
import com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER

/**
 * Legacy implementation of screenshot view methods. Just proxies the calls down into the original
 * ScreenshotView.
 */
class LegacyScreenshotViewProxy(context: Context, private val logger: UiEventLogger) :
    ScreenshotViewProxy {
    override val view: ScreenshotView =
        LayoutInflater.from(context).inflate(R.layout.screenshot, null) as ScreenshotView
    override val screenshotPreview: View

    override var defaultDisplay: Int = Display.DEFAULT_DISPLAY
        set(value) {
            view.setDefaultDisplay(value)
        }
    override var defaultTimeoutMillis: Long = 6000
        set(value) {
            view.setDefaultTimeoutMillis(value)
        }
    override var flags: FeatureFlags? = null
        set(value) {
            view.setFlags(value)
        }
    override var packageName: String = ""
        set(value) {
            view.setPackageName(value)
        }
    override var callbacks: ScreenshotView.ScreenshotViewCallback? = null
        set(value) {
            view.setCallbacks(value)
        }
    override var screenshot: ScreenshotData? = null
        set(value) {
            view.setScreenshot(value)
        }

    override val isAttachedToWindow
        get() = view.isAttachedToWindow
    override val isDismissing
        get() = view.isDismissing
    override val isPendingSharedTransition
        get() = view.isPendingSharedTransition

    init {
        view.setUiEventLogger(logger)
        addPredictiveBackListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        setOnKeyListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        if (LogConfig.DEBUG_WINDOW) {
            Log.d(TAG, "adding OnComputeInternalInsetsListener")
        }
        view.viewTreeObserver.addOnComputeInternalInsetsListener(view)
        screenshotPreview = view.screenshotPreview
    }

    override fun reset() = view.reset()
    override fun updateInsets(insets: WindowInsets) = view.updateInsets(insets)
    override fun updateOrientation(insets: WindowInsets) = view.updateOrientation(insets)

    override fun badgeScreenshot(userBadgedIcon: Drawable) = view.badgeScreenshot(userBadgedIcon)

    override fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator =
        view.createScreenshotDropInAnimation(screenRect, showFlash)

    override fun addQuickShareChip(quickShareAction: Notification.Action) =
        view.addQuickShareChip(quickShareAction)

    override fun setChipIntents(imageData: ScreenshotController.SavedImageData) =
        view.setChipIntents(imageData)

    override fun requestDismissal(event: ScreenshotEvent) {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "screenshot dismissal requested")
        }
        // If we're already animating out, don't restart the animation
        if (view.isDismissing) {
            if (DEBUG_DISMISS) {
                Log.v(TAG, "Already dismissing, ignoring duplicate command $event")
            }
            return
        }
        logger.log(event, 0, packageName)
        view.animateDismissal()
    }

    override fun showScrollChip(packageName: String, onClick: Runnable) =
        view.showScrollChip(packageName, onClick)

    override fun hideScrollChip() = view.hideScrollChip()

    override fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean
    ) =
        view.prepareScrollingTransition(
            response,
            screenBitmap,
            newScreenshot,
            screenshotTakenInPortrait
        )

    override fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    ) = view.startLongScreenshotTransition(transitionDestination, onTransitionEnd, longScreenshot)

    override fun restoreNonScrollingUi() = view.restoreNonScrollingUi()

    override fun stopInputListening() = view.stopInputListening()

    override fun requestFocus() {
        view.requestFocus()
    }

    override fun announceForAccessibility(string: String) = view.announceForAccessibility(string)

    override fun getViewTreeObserver(): ViewTreeObserver = view.viewTreeObserver

    override fun post(runnable: Runnable) {
        view.post(runnable)
    }

    private fun addPredictiveBackListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        val onBackInvokedCallback = OnBackInvokedCallback {
            if (LogConfig.DEBUG_INPUT) {
                Log.d(TAG, "Predictive Back callback dispatched")
            }
            onDismissRequested.invoke(ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER)
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (LogConfig.DEBUG_INPUT) {
                        Log.d(TAG, "Registering Predictive Back callback")
                    }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            onBackInvokedCallback
                        )
                }

                override fun onViewDetachedFromWindow(view: View) {
                    if (LogConfig.DEBUG_INPUT) {
                        Log.d(TAG, "Unregistering Predictive Back callback")
                    }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.unregisterOnBackInvokedCallback(onBackInvokedCallback)
                }
            }
        )
    }
    private fun setOnKeyListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        view.setOnKeyListener(
            object : View.OnKeyListener {
                override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (LogConfig.DEBUG_INPUT) {
                            Log.d(TAG, "onKeyEvent: $keyCode")
                        }
                        onDismissRequested.invoke(ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER)
                        return true
                    }
                    return false
                }
            }
        )
    }

    class Factory : ScreenshotViewProxy.Factory {
        override fun getProxy(context: Context, logger: UiEventLogger): ScreenshotViewProxy {
            return LegacyScreenshotViewProxy(context, logger)
        }
    }

    companion object {
        private const val TAG = "LegacyScreenshotViewProxy"
    }
}
