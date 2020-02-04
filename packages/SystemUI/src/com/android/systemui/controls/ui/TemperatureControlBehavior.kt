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

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.service.controls.Control
import android.service.controls.templates.TemperatureControlTemplate
import android.widget.TextView

import com.android.systemui.R

class TemperatureControlBehavior : Behavior {
    lateinit var clipLayer: Drawable
    lateinit var control: Control
    lateinit var cvh: ControlViewHolder
    lateinit var template: TemperatureControlTemplate
    lateinit var status: TextView
    lateinit var context: Context

    override fun apply(cvh: ControlViewHolder, cws: ControlWithState) {
        this.control = cws.control!!
        this.cvh = cvh
        status = cvh.status

        status.setText(control.getStatusText())

        val ld = cvh.layout.getBackground() as LayerDrawable
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer)

        template = control.getControlTemplate() as TemperatureControlTemplate

        val activeMode = template.getCurrentActiveMode()
        val enabled = activeMode != 0 && activeMode != TemperatureControlTemplate.MODE_OFF
        val deviceType = control.getDeviceType()

        clipLayer.setLevel(if (enabled) MAX_LEVEL else MIN_LEVEL)
        cvh.setEnabled(enabled)
        cvh.applyRenderInfo(RenderInfo.lookup(deviceType, activeMode, enabled))
    }
}
