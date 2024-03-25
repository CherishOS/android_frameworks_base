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
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.View.OnKeyListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import com.android.internal.logging.UiEventLogger
import com.android.systemui.flags.FeatureFlags

/** Abstraction of the surface between ScreenshotController and ScreenshotView */
interface ScreenshotViewProxy {
    val view: ViewGroup
    val screenshotPreview: View

    var defaultDisplay: Int
    var defaultTimeoutMillis: Long
    var onBackInvokedCallback: OnBackInvokedCallback
    var onKeyListener: OnKeyListener?
    var flags: FeatureFlags?
    var packageName: String
    var logger: UiEventLogger?
    var callbacks: ScreenshotView.ScreenshotViewCallback?
    var screenshot: ScreenshotData?

    val isAttachedToWindow: Boolean
    val isDismissing: Boolean
    val isPendingSharedTransition: Boolean

    fun reset()
    fun updateInsets(insets: WindowInsets)
    fun updateOrientation(insets: WindowInsets)
    fun badgeScreenshot(userBadgedIcon: Drawable)
    fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator
    fun addQuickShareChip(quickShareAction: Notification.Action)
    fun setChipIntents(imageData: ScreenshotController.SavedImageData)
    fun animateDismissal()

    fun showScrollChip(packageName: String, onClick: Runnable)
    fun hideScrollChip()
    fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean
    )
    fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    )
    fun restoreNonScrollingUi()

    fun stopInputListening()
    fun requestFocus()
    fun announceForAccessibility(string: String)
    fun getViewTreeObserver(): ViewTreeObserver
    fun post(runnable: Runnable)

    interface Factory {
        fun getProxy(context: Context): ScreenshotViewProxy
    }
}
