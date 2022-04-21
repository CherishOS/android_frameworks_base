/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import com.android.internal.R
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.monet.ColorScheme

/**
 * ColorTransition is responsible for managing the animation between two specific colors.
 * It uses a ValueAnimator to execute the animation and interpolate between the source color and
 * the target color.
 *
 * Selection of the target color from the scheme, and application of the interpolated color
 * are delegated to callbacks.
 */
open class ColorTransition(
    private val defaultColor: Int,
    private val extractColor: (ColorScheme) -> Int,
    private val applyColor: (Int) -> Unit
) : AnimatorUpdateListener {

    private val argbEvaluator = ArgbEvaluator()
    private val valueAnimator = buildAnimator()
    var sourceColor: Int = defaultColor
    var currentColor: Int = defaultColor
    var targetColor: Int = defaultColor

    override fun onAnimationUpdate(animation: ValueAnimator) {
        currentColor = argbEvaluator.evaluate(
            animation.animatedFraction, sourceColor, targetColor
        ) as Int
        applyColor(currentColor)
    }

    fun updateColorScheme(scheme: ColorScheme?) {
        val newTargetColor = if (scheme == null) defaultColor else extractColor(scheme)
        if (newTargetColor != targetColor) {
            sourceColor = currentColor
            targetColor = newTargetColor
            valueAnimator.cancel()
            valueAnimator.start()
        }
    }

    init {
        applyColor(defaultColor)
    }

    @VisibleForTesting
    open fun buildAnimator(): ValueAnimator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 333
        animator.addUpdateListener(this)
        return animator
    }
}

typealias ColorTransitionFactory = (Int, (ColorScheme) -> Int, (Int) -> Unit) -> ColorTransition

/**
 * ColorSchemeTransition constructs a ColorTransition for each color in the scheme
 * that needs to be transitioned when changed. It also sets up the assignment functions for sending
 * the sending the interpolated colors to the appropriate views.
 */
class ColorSchemeTransition internal constructor(
    private val context: Context,
    mediaViewHolder: MediaViewHolder,
    colorTransitionFactory: ColorTransitionFactory
) {
    constructor(context: Context, mediaViewHolder: MediaViewHolder) :
        this(context, mediaViewHolder, ::ColorTransition)

    val bgColor = context.getColor(com.android.systemui.R.color.material_dynamic_secondary95)

    val surfaceColor = colorTransitionFactory(
        bgColor,
        ::surfaceFromScheme
    ) { surfaceColor ->
        val colorList = ColorStateList.valueOf(surfaceColor)
        mediaViewHolder.player.backgroundTintList = colorList
        mediaViewHolder.albumView.foregroundTintList = colorList
        mediaViewHolder.albumView.backgroundTintList = colorList
        mediaViewHolder.seamlessIcon.imageTintList = colorList
        mediaViewHolder.seamlessText.setTextColor(surfaceColor)
        mediaViewHolder.gutsViewHolder.setSurfaceColor(surfaceColor)
    }

    val accentPrimary = colorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::accentPrimaryFromScheme
    ) { accentPrimary ->
        val accentColorList = ColorStateList.valueOf(accentPrimary)
        mediaViewHolder.actionPlayPause.backgroundTintList = accentColorList
        mediaViewHolder.seamlessButton.backgroundTintList = accentColorList
        mediaViewHolder.gutsViewHolder.setAccentPrimaryColor(accentPrimary)
    }

    val textPrimary = colorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::textPrimaryFromScheme
    ) { textPrimary ->
        mediaViewHolder.titleText.setTextColor(textPrimary)
        val textColorList = ColorStateList.valueOf(textPrimary)
        mediaViewHolder.seekBar.thumb.setTintList(textColorList)
        mediaViewHolder.seekBar.progressTintList = textColorList
        mediaViewHolder.scrubbingElapsedTimeView.setTextColor(textColorList)
        mediaViewHolder.scrubbingTotalTimeView.setTextColor(textColorList)
        for (button in mediaViewHolder.getTransparentActionButtons()) {
            button.imageTintList = textColorList
        }
        mediaViewHolder.gutsViewHolder.setTextPrimaryColor(textPrimary)
    }

    val textPrimaryInverse = colorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimaryInverse),
        ::textPrimaryInverseFromScheme
    ) { textPrimaryInverse ->
        mediaViewHolder.actionPlayPause.imageTintList = ColorStateList.valueOf(textPrimaryInverse)
    }

    val textSecondary = colorTransitionFactory(
        loadDefaultColor(R.attr.textColorSecondary),
        ::textSecondaryFromScheme
    ) { textSecondary -> mediaViewHolder.artistText.setTextColor(textSecondary) }

    val textTertiary = colorTransitionFactory(
        loadDefaultColor(R.attr.textColorTertiary),
        ::textTertiaryFromScheme
    ) { textTertiary ->
        mediaViewHolder.seekBar.progressBackgroundTintList = ColorStateList.valueOf(textTertiary)
    }

    val colorTransitions = arrayOf(
        surfaceColor, accentPrimary, textPrimary,
        textPrimaryInverse, textSecondary, textTertiary)

    private fun loadDefaultColor(id: Int): Int {
        return Utils.getColorAttr(context, id).defaultColor
    }

    fun updateColorScheme(colorScheme: ColorScheme?) {
        colorTransitions.forEach { it.updateColorScheme(colorScheme) }
    }
}
