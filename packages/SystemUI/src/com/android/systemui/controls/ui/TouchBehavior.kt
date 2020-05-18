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

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.service.controls.Control
import android.service.controls.templates.ControlTemplate

import com.android.systemui.R
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MAX_LEVEL
import com.android.systemui.controls.ui.ControlViewHolder.Companion.MIN_LEVEL

/**
 * Supports touch events, but has no notion of state as the {@link ToggleBehavior} does. Must be
 * used with {@link StatelessTemplate}.
 */
class TouchBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var template: ControlTemplate
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh

        cvh.layout.setOnClickListener(View.OnClickListener() {
            cvh.controlActionCoordinator.touch(cvh, template.getTemplateId(), control)
        })
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        this.control = cws.control!!
        cvh.setStatusText(control.getStatusText())
        template = control.getControlTemplate()

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        val enabled = if (colorOffset > 0) true else false
        clipLayer.setLevel(if (enabled) MAX_LEVEL else MIN_LEVEL)
        cvh.applyRenderInfo(enabled, colorOffset)
    }
}
