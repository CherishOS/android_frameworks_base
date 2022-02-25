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

package com.android.systemui.media.taptotransfer.common

import android.annotation.LayoutRes
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.android.internal.widget.CachingIconView
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.DelayableExecutor

/**
 * A superclass controller that provides common functionality for showing chips on the sender device
 * and the receiver device.
 *
 * Subclasses need to override and implement [updateChipView], which is where they can control what
 * gets displayed to the user.
 */
abstract class MediaTttChipControllerCommon<T : MediaTttChipState>(
    internal val context: Context,
    private val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    private val tapGestureDetector: TapGestureDetector,
    @LayoutRes private val chipLayoutRes: Int
) {
    /** The window layout parameters we'll use when attaching the view to a window. */
    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = WINDOW_TITLE
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /** The chip view currently being displayed. Null if the chip is not being displayed. */
    var chipView: ViewGroup? = null

    /** A [Runnable] that, when run, will cancel the pending timeout of the chip. */
    var cancelChipViewTimeout: Runnable? = null

    /**
     * Displays the chip with the current state.
     *
     * This method handles inflating and attaching the view, then delegates to [updateChipView] to
     * display the correct information in the chip.
     */
    fun displayChip(chipState: T) {
        val oldChipView = chipView
        if (chipView == null) {
            chipView = LayoutInflater
                .from(context)
                .inflate(chipLayoutRes, null) as ViewGroup
        }
        val currentChipView = chipView!!

        updateChipView(chipState, currentChipView)

        // Add view if necessary
        if (oldChipView == null) {
            tapGestureDetector.addOnGestureDetectedCallback(TAG, this::removeChip)
            windowManager.addView(chipView, windowLayoutParams)
        }

        // Cancel and re-set the chip timeout each time we get a new state.
        cancelChipViewTimeout?.run()
        cancelChipViewTimeout = mainExecutor.executeDelayed(this::removeChip, TIMEOUT_MILLIS)
    }

    /** Hides the chip. */
    fun removeChip() {
        // TODO(b/203800347): We may not want to hide the chip if we're currently in a
        //  TransferTriggered state: Once the user has initiated the transfer, they should be able
        //  to move away from the receiver device but still see the status of the transfer.
        if (chipView == null) { return }
        tapGestureDetector.removeOnGestureDetectedCallback(TAG)
        windowManager.removeView(chipView)
        chipView = null
    }

    /**
     * A method implemented by subclasses to update [currentChipView] based on [chipState].
     */
    abstract fun updateChipView(chipState: T, currentChipView: ViewGroup)

    /**
     * An internal method to set the icon on the view.
     *
     * This is in the common superclass since both the sender and the receiver show an icon.
     */
    internal fun setIcon(chipState: T, currentChipView: ViewGroup) {
        val appIconView = currentChipView.requireViewById<CachingIconView>(R.id.app_icon)
        appIconView.contentDescription = chipState.getAppName(context)

        val appIcon = chipState.getAppIcon(context)
        val visibility = if (appIcon != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        appIconView.setImageDrawable(appIcon)
        appIconView.visibility = visibility
    }
}

// Used in CTS tests UpdateMediaTapToTransferSenderDisplayTest and
// UpdateMediaTapToTransferReceiverDisplayTest
private const val WINDOW_TITLE = "Media Transfer Chip View"
private val TAG = MediaTttChipControllerCommon::class.simpleName!!
@VisibleForTesting
const val TIMEOUT_MILLIS = 3000L
