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

package com.android.systemui.controls.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import android.util.MathUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.graphics.ColorUtils
import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.concurrency.DelayableExecutor
import kotlin.reflect.KClass

/**
 * Wraps the widgets that make up the UI representation of a {@link Control}. Updates to the view
 * are signaled via calls to {@link #bindData}. Similar to the ViewHolder concept used in
 * RecyclerViews.
 */
class ControlViewHolder(
    val layout: ViewGroup,
    val controlsController: ControlsController,
    val uiExecutor: DelayableExecutor,
    val bgExecutor: DelayableExecutor,
    val usePanels: Boolean
) {

    companion object {
        const val STATE_ANIMATION_DURATION = 700L
        private const val UPDATE_DELAY_IN_MILLIS = 3000L
        private const val ALPHA_ENABLED = (255.0 * 0.2).toInt()
        private const val ALPHA_DISABLED = 0
        private val FORCE_PANEL_DEVICES = setOf(
            DeviceTypes.TYPE_THERMOSTAT,
            DeviceTypes.TYPE_CAMERA
        )
    }

    private val toggleBackgroundIntensity: Float = layout.context.resources
            .getFraction(R.fraction.controls_toggle_bg_intensity, 1, 1)
    private val dimmedAlpha: Float = layout.context.resources
            .getFraction(R.fraction.controls_dimmed_alpha, 1, 1)
    private var stateAnimator: ValueAnimator? = null
    private val baseLayer: GradientDrawable
    val icon: ImageView = layout.requireViewById(R.id.icon)
    val status: TextView = layout.requireViewById(R.id.status)
    val title: TextView = layout.requireViewById(R.id.title)
    val subtitle: TextView = layout.requireViewById(R.id.subtitle)
    val context: Context = layout.getContext()
    val clipLayer: ClipDrawable
    lateinit var cws: ControlWithState
    var cancelUpdate: Runnable? = null
    var behavior: Behavior? = null
    var lastAction: ControlAction? = null
    val deviceType: Int
        get() = cws.control?.let { it.getDeviceType() } ?: cws.ci.deviceType
    var dimmed: Boolean = false
        set(value) {
            field = value
            bindData(cws)
        }

    init {
        val ld = layout.getBackground() as LayerDrawable
        ld.mutate()
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable
        clipLayer.alpha = ALPHA_DISABLED
        baseLayer = ld.findDrawableByLayerId(R.id.background) as GradientDrawable
        // needed for marquee to start
        status.setSelected(true)
    }

    fun bindData(cws: ControlWithState) {
        this.cws = cws

        cancelUpdate?.run()

        val (controlStatus, template) = cws.control?.let {
            title.setText(it.getTitle())
            subtitle.setText(it.getSubtitle())
            Pair(it.status, it.controlTemplate)
        } ?: run {
            title.setText(cws.ci.controlTitle)
            subtitle.setText(cws.ci.controlSubtitle)
            Pair(Control.STATUS_UNKNOWN, ControlTemplate.NO_TEMPLATE)
        }

        cws.control?.let {
            layout.setClickable(true)
            layout.setOnLongClickListener(View.OnLongClickListener() {
                ControlActionCoordinator.longPress(this@ControlViewHolder)
                true
            })
        }

        val clazz = findBehavior(controlStatus, template, deviceType)
        if (behavior == null || behavior!!::class != clazz) {
            // Behavior changes can signal a change in template from the app or
            // first time setup
            behavior = clazz.java.newInstance()
            behavior?.initialize(this)

            // let behaviors define their own, if necessary, and clear any existing ones
            layout.setAccessibilityDelegate(null)
        }

        behavior?.bind(cws)

        layout.setContentDescription("${title.text} ${subtitle.text} ${status.text}")
    }

    fun actionResponse(@ControlAction.ResponseResult response: Int) {
        // TODO: b/150931809 - handle response codes
    }

    fun setTransientStatus(tempStatus: String) {
        val previousText = status.getText()

        cancelUpdate = uiExecutor.executeDelayed({
                status.setText(previousText)
            }, UPDATE_DELAY_IN_MILLIS)

        status.setText(tempStatus)
    }

    fun action(action: ControlAction) {
        lastAction = action
        controlsController.action(cws.componentName, cws.ci, action)
    }

    fun usePanel(): Boolean =
        usePanels && deviceType in ControlViewHolder.FORCE_PANEL_DEVICES

    private fun findBehavior(
        status: Int,
        template: ControlTemplate,
        deviceType: Int
    ): KClass<out Behavior> {
        return when {
            status == Control.STATUS_UNKNOWN -> UnknownBehavior::class
            deviceType == DeviceTypes.TYPE_CAMERA -> TouchBehavior::class
            template is ToggleTemplate -> ToggleBehavior::class
            template is StatelessTemplate -> TouchBehavior::class
            template is ToggleRangeTemplate -> ToggleRangeBehavior::class
            template is TemperatureControlTemplate -> TemperatureControlBehavior::class
            else -> DefaultBehavior::class
        }
    }

    internal fun applyRenderInfo(enabled: Boolean, offset: Int = 0, animated: Boolean = true) {
        setEnabled(enabled)

        val ri = RenderInfo.lookup(context, cws.componentName, deviceType, enabled, offset)

        val fg = context.resources.getColorStateList(ri.foreground, context.theme)
        val bg = context.resources.getColor(R.color.control_default_background, context.theme)
        val dimAlpha = if (dimmed) dimmedAlpha else 1f
        var (clip, newAlpha) = if (enabled) {
            listOf(ri.enabledBackground, ALPHA_ENABLED)
        } else {
            listOf(R.color.control_default_background, ALPHA_DISABLED)
        }

        status.setTextColor(fg)
        icon.setImageDrawable(ri.icon)

        // do not color app icons
        if (deviceType != DeviceTypes.TYPE_ROUTINE) {
            icon.imageTintList = fg
        }

        (clipLayer.getDrawable() as GradientDrawable).apply {
            val newClipColor = context.resources.getColor(clip, context.theme)
            val newBaseColor = if (behavior is ToggleRangeBehavior) {
                ColorUtils.blendARGB(bg, newClipColor, toggleBackgroundIntensity)
            } else {
                bg
            }
            stateAnimator?.cancel()
            if (animated) {
                val oldColor = color?.defaultColor ?: newClipColor
                val oldBaseColor = baseLayer.color?.defaultColor ?: newBaseColor
                val oldAlpha = layout.alpha
                stateAnimator = ValueAnimator.ofInt(clipLayer.alpha, newAlpha).apply {
                    addUpdateListener {
                        alpha = it.animatedValue as Int
                        setColor(ColorUtils.blendARGB(oldColor, newClipColor, it.animatedFraction))
                        baseLayer.setColor(ColorUtils.blendARGB(oldBaseColor,
                                newBaseColor, it.animatedFraction))
                        layout.alpha = MathUtils.lerp(oldAlpha, dimAlpha, it.animatedFraction)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            stateAnimator = null
                        }
                    })
                    duration = STATE_ANIMATION_DURATION
                    interpolator = Interpolators.CONTROL_STATE
                    start()
                }
            } else {
                alpha = newAlpha
                setColor(newClipColor)
                baseLayer.setColor(newBaseColor)
                layout.alpha = dimAlpha
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        status.setEnabled(enabled)
        icon.setEnabled(enabled)
    }
}
