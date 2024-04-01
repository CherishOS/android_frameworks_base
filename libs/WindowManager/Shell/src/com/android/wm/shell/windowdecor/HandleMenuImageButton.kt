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
package com.android.wm.shell.windowdecor

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageButton

/**
 * A custom [ImageButton] that intentionally does not handle hover events.
 * This is due to the hover events being handled by [DesktopModeWindowDecorViewModel]
 * in order to take the status bar layer into account. Handling it in both classes results in a
 * flicker when the hover moves from outside to inside status bar layer.
 */
class HandleMenuImageButton(context: Context?, attrs: AttributeSet?) :
    ImageButton(context, attrs) {
    override fun onHoverEvent(motionEvent: MotionEvent): Boolean {
        return false
    }
}
