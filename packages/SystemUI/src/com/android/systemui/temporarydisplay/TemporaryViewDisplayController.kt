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

package com.android.systemui.temporarydisplay

import android.annotation.LayoutRes
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor

/**
 * A generic controller that can temporarily display a new view in a new window.
 *
 * Subclasses need to override and implement [updateView], which is where they can control what
 * gets displayed to the user.
 *
 * The generic type T is expected to contain all the information necessary for the subclasses to
 * display the view in a certain state, since they receive <T> in [updateView].
 */
abstract class TemporaryViewDisplayController<T : TemporaryViewInfo, U : TemporaryViewLogger>(
    internal val context: Context,
    internal val logger: U,
    internal val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    private val accessibilityManager: AccessibilityManager,
    private val configurationController: ConfigurationController,
    private val powerManager: PowerManager,
    @LayoutRes private val viewLayoutRes: Int,
) : CoreStartable {
    /**
     * Window layout params that will be used as a starting point for the [windowLayoutParams] of
     * all subclasses.
     */
    internal val commonWindowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /**
     * The window layout parameters we'll use when attaching the view to a window.
     *
     * Subclasses must override this to provide their specific layout params, and they should use
     * [commonWindowLayoutParams] as part of their layout params.
     */
    internal abstract val windowLayoutParams: WindowManager.LayoutParams

    /** A container for all the display-related objects. Null if the view is not being displayed. */
    private var displayInfo: DisplayInfo? = null

    /** A [Runnable] that, when run, will cancel the pending timeout of the view. */
    private var cancelViewTimeout: Runnable? = null

    /**
     * Displays the view with the provided [newInfo].
     *
     * This method handles inflating and attaching the view, then delegates to [updateView] to
     * display the correct information in the view.
     */
    fun displayView(newInfo: T) {
        val currentDisplayInfo = displayInfo

        if (currentDisplayInfo != null &&
            currentDisplayInfo.info.windowTitle == newInfo.windowTitle) {
            // We're already displaying information in the correctly-titled window, so we just need
            // to update the view.
            currentDisplayInfo.info = newInfo
            updateView(currentDisplayInfo.info, currentDisplayInfo.view)
        } else {
            if (currentDisplayInfo != null) {
                // We're already displaying information but that information is under a different
                // window title. So, we need to remove the old window with the old title and add a
                // new window with the new title.
                removeView(removalReason = "New info has new window title: ${newInfo.windowTitle}")
            }

            // At this point, we're guaranteed to no longer be displaying a view.
            // So, set up all our callbacks and inflate the view.
            configurationController.addCallback(displayScaleListener)
            // Wake the screen if necessary so the user will see the view. (Per b/239426653, we want
            // the view to show over the dream state, so we should only wake up if the screen is
            // completely off.)
            if (!powerManager.isScreenOn) {
                powerManager.wakeUp(
                    SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION,
                    "com.android.systemui:${newInfo.wakeReason}",
                )
            }
            logger.logViewAddition(newInfo.windowTitle)
            inflateAndUpdateView(newInfo)
        }

        // Cancel and re-set the view timeout each time we get a new state.
        val timeout = accessibilityManager.getRecommendedTimeoutMillis(
            newInfo.timeoutMs,
            // Not all views have controls so FLAG_CONTENT_CONTROLS might be superfluous, but
            // include it just to be safe.
            FLAG_CONTENT_ICONS or FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS
       )
        cancelViewTimeout?.run()
        cancelViewTimeout = mainExecutor.executeDelayed(
            { removeView(REMOVAL_REASON_TIMEOUT) },
            timeout.toLong()
        )
    }

    /** Inflates a new view, updates it with [newInfo], and adds the view to the window. */
    private fun inflateAndUpdateView(newInfo: T) {
        val newView = LayoutInflater
                .from(context)
                .inflate(viewLayoutRes, null) as ViewGroup
        val newViewController = TouchableRegionViewController(newView, this::getTouchableRegion)
        newViewController.init()

        // We don't need to hold on to the view controller since we never set anything additional
        // on it -- it will be automatically cleaned up when the view is detached.
        val newDisplayInfo = DisplayInfo(newView, newInfo)
        displayInfo = newDisplayInfo
        updateView(newDisplayInfo.info, newDisplayInfo.view)

        val paramsWithTitle = WindowManager.LayoutParams().also {
            it.copyFrom(windowLayoutParams)
            it.title = newInfo.windowTitle
        }
        windowManager.addView(newView, paramsWithTitle)
        animateViewIn(newView)
    }

    /** Removes then re-inflates the view. */
    private fun reinflateView() {
        val currentViewInfo = displayInfo ?: return

        windowManager.removeView(currentViewInfo.view)
        inflateAndUpdateView(currentViewInfo.info)
    }

    private val displayScaleListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            reinflateView()
        }
    }

    /**
     * Hides the view.
     *
     * @param removalReason a short string describing why the view was removed (timeout, state
     *     change, etc.)
     */
    fun removeView(removalReason: String) {
        val currentDisplayInfo = displayInfo ?: return

        val currentView = currentDisplayInfo.view
        animateViewOut(currentView) { windowManager.removeView(currentView) }

        logger.logViewRemoval(removalReason)
        configurationController.removeCallback(displayScaleListener)
        // Re-set to null immediately (instead as part of the animation end runnable) so
        // that if a new view event comes in while this view is animating out, we still display the
        // new view appropriately.
        displayInfo = null
        // No need to time the view out since it's already gone
        cancelViewTimeout?.run()
    }

    /**
     * A method implemented by subclasses to update [currentView] based on [newInfo].
     */
    abstract fun updateView(newInfo: T, currentView: ViewGroup)

    /**
     * Fills [outRect] with the touchable region of this view. This will be used by WindowManager
     * to decide which touch events go to the view.
     */
    abstract fun getTouchableRegion(view: View, outRect: Rect)

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * appears.
     */
    internal open fun animateViewIn(view: ViewGroup) {}

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * disappears.
     *
     * @param onAnimationEnd an action that *must* be run once the animation finishes successfully.
     */
    internal open fun animateViewOut(view: ViewGroup, onAnimationEnd: Runnable) {
        onAnimationEnd.run()
    }

    /** A container for all the display-related state objects. */
    private inner class DisplayInfo(
        /** The view currently being displayed. */
        val view: ViewGroup,

        /** The info currently being displayed. */
        var info: T,
    )
}

private const val REMOVAL_REASON_TIMEOUT = "TIMEOUT"

private data class IconInfo(
    val iconName: String,
    val icon: Drawable,
    /** True if [icon] is the app's icon, and false if [icon] is some generic default icon. */
    val isAppIcon: Boolean
)
