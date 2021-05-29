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
package com.android.wm.shell.bubbles

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.util.ContrastColorUtil
import com.android.wm.shell.R
import com.android.wm.shell.animation.Interpolators

/**
 * User education view to highlight the collapsed stack of bubbles.
 * Shown only the first time a user taps the stack.
 */
class StackEducationView constructor(context: Context) : LinearLayout(context) {

    private val TAG = if (BubbleDebugConfig.TAG_WITH_CLASS_NAME) "BubbleStackEducationView"
        else BubbleDebugConfig.TAG_BUBBLES

    private val ANIMATE_DURATION: Long = 200
    private val ANIMATE_DURATION_SHORT: Long = 40

    private val view by lazy { findViewById<View>(R.id.stack_education_layout) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.stack_education_title) }
    private val descTextView by lazy { findViewById<TextView>(R.id.stack_education_description) }

    private var isHiding = false

    init {
        LayoutInflater.from(context).inflate(R.layout.bubble_stack_user_education, this)

        visibility = View.GONE
        elevation = resources.getDimensionPixelSize(R.dimen.bubble_elevation).toFloat()

        // BubbleStackView forces LTR by default
        // since most of Bubble UI direction depends on positioning by the user.
        // This view actually lays out differently in RTL, so we set layout LOCALE here.
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    override fun setLayoutDirection(layoutDirection: Int) {
        super.setLayoutDirection(layoutDirection)
        setDrawableDirection()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        layoutDirection = resources.configuration.layoutDirection
        setTextColor()
    }

    private fun setTextColor() {
        val ta = mContext.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent,
            android.R.attr.textColorPrimaryInverse))
        val bgColor = ta.getColor(0 /* index */, Color.BLACK)
        var textColor = ta.getColor(1 /* index */, Color.WHITE)
        ta.recycle()
        textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, true)
        titleTextView.setTextColor(textColor)
        descTextView.setTextColor(textColor)
    }

    private fun setDrawableDirection() {
        view.setBackgroundResource(
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR)
                R.drawable.bubble_stack_user_education_bg
            else R.drawable.bubble_stack_user_education_bg_rtl)
    }

    /**
     * If necessary, shows the user education view for the bubble stack. This appears the first
     * time a user taps on a bubble.
     *
     * @return true if user education was shown, false otherwise.
     */
    fun show(stackPosition: PointF): Boolean {
        if (visibility == VISIBLE) return false

        setAlpha(0f)
        setVisibility(View.VISIBLE)
        post {
            with(view) {
                val bubbleSize = context.resources.getDimensionPixelSize(
                    R.dimen.bubble_size)
                translationY = stackPosition.y + bubbleSize / 2 - getHeight() / 2
            }
            animate()
                .setDuration(ANIMATE_DURATION)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
        }
        setShouldShow(false)
        return true
    }

    /**
     * If necessary, hides the stack education view.
     *
     * @param fromExpansion if true this indicates the hide is happening due to the bubble being
     *                      expanded, false if due to a touch outside of the bubble stack.
     */
    fun hide(fromExpansion: Boolean) {
        if (visibility != VISIBLE || isHiding) return

        animate()
            .alpha(0f)
            .setDuration(if (fromExpansion) ANIMATE_DURATION_SHORT else ANIMATE_DURATION)
            .withEndAction { visibility = GONE }
    }

    private fun setShouldShow(shouldShow: Boolean) {
        context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_STACK_EDUCATION, !shouldShow).apply()
    }
}

const val PREF_STACK_EDUCATION: String = "HasSeenBubblesOnboarding"