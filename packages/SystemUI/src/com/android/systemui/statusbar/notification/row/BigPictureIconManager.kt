/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.annotation.WorkerThread
import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Dumpable
import android.util.Log
import android.util.Size
import com.android.internal.R
import com.android.internal.widget.NotificationDrawableConsumer
import com.android.internal.widget.NotificationIconManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.Empty
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.FullImage
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.PlaceHolder
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BigPicImageLoader"
private const val DEBUG = false
private const val FREE_IMAGE_DELAY_MS = 3000L

/**
 * A helper class for [com.android.internal.widget.BigPictureNotificationImageView] to lazy-load
 * images from SysUI. It replaces the placeholder image with the fully loaded one, and vica versa.
 *
 * TODO(b/283082473) move the logs to a [com.android.systemui.log.LogBuffer]
 */
@SuppressWarnings("DumpableNotRegistered")
class BigPictureIconManager
@Inject
constructor(
    private val context: Context,
    private val imageLoader: ImageLoader,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val bgDispatcher: CoroutineDispatcher
) : NotificationIconManager, Dumpable {

    private var lastLoadingJob: Job? = null
    private var drawableConsumer: NotificationDrawableConsumer? = null
    private var displayedState: DrawableState = Empty(null)
    private var viewShown = false

    private var maxWidth = getMaxWidth()
    private var maxHeight = getMaxHeight()

    /**
     * Called when the displayed state changes of the view.
     *
     * @param shown true if the view is shown, and the image needs to be displayed.
     */
    fun onViewShown(shown: Boolean) {
        log("onViewShown:$shown")

        if (this.viewShown != shown) {
            this.viewShown = shown

            val state = displayedState

            this.lastLoadingJob?.cancel()
            this.lastLoadingJob =
                when {
                    state is Empty && shown -> state.icon?.let(::startLoadingJob)
                    state is PlaceHolder && shown -> startLoadingJob(state.icon)
                    state is FullImage && !shown ->
                        startFreeImageJob(state.icon, state.drawableSize)
                    else -> null
                }
        }
    }

    /**
     * Update the maximum width and height allowed for bitmaps, ex. after a configuration change.
     */
    fun updateMaxImageSizes() {
        log("updateMaxImageSizes")
        maxWidth = getMaxWidth()
        maxHeight = getMaxHeight()
    }

    /** Cancels all currently running jobs. */
    fun cancelJobs() {
        lastLoadingJob?.cancel()
    }

    @WorkerThread
    override fun updateIcon(drawableConsumer: NotificationDrawableConsumer, icon: Icon?): Runnable {
        if (this.drawableConsumer != null && this.drawableConsumer != drawableConsumer) {
            Log.wtf(TAG, "A consumer is already set for this iconManager.")
            return Runnable {}
        }

        if (displayedState.iconSameAs(icon)) {
            // We're already handling this icon, nothing to do here.
            log("skipping updateIcon for consumer:$drawableConsumer with icon:$icon")
            return Runnable {}
        }

        this.drawableConsumer = drawableConsumer
        this.displayedState = Empty(icon)
        this.lastLoadingJob?.cancel()

        val drawable = loadImageOrPlaceHolderSync(icon)

        log("icon updated")

        return Runnable { drawableConsumer.setImageDrawable(drawable) }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>?) {
        pw.println("BigPictureIconManager ${getDebugString()}")
    }

    @WorkerThread
    private fun loadImageOrPlaceHolderSync(icon: Icon?): Drawable? {
        icon ?: return null

        if (viewShown) {
            return loadImageSync(icon)
        }

        return loadPlaceHolderSync(icon) ?: loadImageSync(icon)
    }

    @WorkerThread
    private fun loadImageSync(icon: Icon): Drawable? {
        return imageLoader.loadDrawableSync(icon, context, maxWidth, maxHeight)?.also { drawable ->
            checkPlaceHolderSizeForDrawable(this.displayedState, drawable)
            this.displayedState = FullImage(icon, drawable.intrinsicSize)
        }
    }

    private fun checkPlaceHolderSizeForDrawable(
        displayedState: DrawableState,
        newDrawable: Drawable
    ) {
        if (displayedState is PlaceHolder) {
            val (oldWidth, oldHeight) = displayedState.drawableSize
            val (newWidth, newHeight) = newDrawable.intrinsicSize

            if (oldWidth != newWidth || oldHeight != newHeight) {
                Log.e(
                    TAG,
                    "Mismatch in dimensions, when replacing PlaceHolder " +
                        "$oldWidth X $oldHeight with Drawable $newWidth X $newHeight."
                )
            }
        }
    }

    @WorkerThread
    private fun loadPlaceHolderSync(icon: Icon): Drawable? {
        return imageLoader
            .loadSizeSync(icon, context)
            ?.resizeToMax(maxWidth, maxHeight) // match the dimensions of the fully loaded drawable
            ?.let { size -> createPlaceHolder(size) }
            ?.also { drawable -> this.displayedState = PlaceHolder(icon, drawable.intrinsicSize) }
    }

    private fun startLoadingJob(icon: Icon): Job =
        scope.launch {
            val drawable = withContext(bgDispatcher) { loadImageSync(icon) }
            withContext(mainDispatcher) { drawableConsumer?.setImageDrawable(drawable) }
            log("image loaded")
        }

    private fun startFreeImageJob(icon: Icon, drawableSize: Size): Job =
        scope.launch {
            delay(FREE_IMAGE_DELAY_MS)
            val drawable = createPlaceHolder(drawableSize)
            displayedState = PlaceHolder(icon, drawable.intrinsicSize)
            withContext(mainDispatcher) { drawableConsumer?.setImageDrawable(drawable) }
            log("placeholder loaded")
        }

    private fun createPlaceHolder(size: Size): Drawable {
        return PlaceHolderDrawable(width = size.width, height = size.height)
    }

    private fun isLowRam(): Boolean {
        return ActivityManager.isLowRamDeviceStatic()
    }

    private fun getMaxWidth() =
        context.resources.getDimensionPixelSize(
            if (isLowRam()) {
                R.dimen.notification_big_picture_max_width_low_ram
            } else {
                R.dimen.notification_big_picture_max_width
            }
        )

    private fun getMaxHeight() =
        context.resources.getDimensionPixelSize(
            if (isLowRam()) {
                R.dimen.notification_big_picture_max_height_low_ram
            } else {
                R.dimen.notification_big_picture_max_height
            }
        )

    private fun log(msg: String) {
        if (DEBUG) {
            Log.d(TAG, "$msg state=${getDebugString()}")
        }
    }

    private fun getDebugString() =
        "{ state:$displayedState, hasConsumer:${drawableConsumer != null}, viewShown:$viewShown}"

    private sealed class DrawableState(open val icon: Icon?) {
        data class Empty(override val icon: Icon?) : DrawableState(icon)
        data class PlaceHolder(override val icon: Icon, val drawableSize: Size) :
            DrawableState(icon)
        data class FullImage(override val icon: Icon, val drawableSize: Size) : DrawableState(icon)

        fun iconSameAs(other: Icon?): Boolean {
            val displayedIcon = icon
            return when {
                displayedIcon == null && other == null -> true
                displayedIcon != null && other != null -> displayedIcon.sameAs(other)
                else -> false
            }
        }
    }
}

/**
 * @return an image size that conforms to the maxWidth / maxHeight parameters. It can be the same
 *   instance, if the provided size was already small enough.
 */
private fun Size.resizeToMax(maxWidth: Int, maxHeight: Int): Size {
    if (width <= maxWidth && height <= maxHeight) {
        return this
    }

    // Calculate the scale factor for both dimensions
    val wScale =
        if (maxWidth <= 0) {
            1.0f
        } else {
            maxWidth.toFloat() / width.toFloat()
        }

    val hScale =
        if (maxHeight <= 0) {
            1.0f
        } else {
            maxHeight.toFloat() / height.toFloat()
        }

    // Scale down to the smaller scale factor
    val scale = min(wScale, hScale)
    if (scale < 1.0f) {
        val targetWidth = (width * scale).toInt()
        val targetHeight = (height * scale).toInt()

        return Size(targetWidth, targetHeight)
    }

    return this
}

private val Drawable.intrinsicSize
    get() = Size(/*width=*/ intrinsicWidth, /*height=*/ intrinsicHeight)

private operator fun Size.component1() = width

private operator fun Size.component2() = height
