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

package com.android.systemui.statusbar

import android.app.ActivityManager
import android.content.res.Resources
import android.os.SystemProperties
import android.util.MathUtils
import android.view.SurfaceControl
import android.view.ViewRootImpl
import androidx.annotation.VisibleForTesting
import com.android.internal.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BlurUtils @Inject constructor(
    @Main private val resources: Resources,
    dumpManager: DumpManager
) : Dumpable {
    private val minBlurRadius = resources.getDimensionPixelSize(R.dimen.min_window_blur_radius)
    private val maxBlurRadius = resources.getDimensionPixelSize(R.dimen.max_window_blur_radius)
    private val blurSysProp = SystemProperties
            .getBoolean("ro.surface_flinger.supports_background_blur", false)

    init {
        dumpManager.registerDumpable(javaClass.name, this)
    }

    /**
     * Translates a ratio from 0 to 1 to a blur radius in pixels.
     */
    fun radiusForRatio(ratio: Float): Int {
        if (ratio == 0f) {
            return 0
        }
        return MathUtils.lerp(minBlurRadius.toFloat(), maxBlurRadius.toFloat(), ratio).toInt()
    }

    /**
     * Applies background blurs to a {@link ViewRootImpl}.
     *
     * @param viewRootImpl The window root.
     * @param radius blur radius in pixels.
     */
    fun applyBlur(viewRootImpl: ViewRootImpl?, radius: Int) {
        if (viewRootImpl == null || !viewRootImpl.surfaceControl.isValid ||
                !supportsBlursOnWindows()) {
            return
        }
        createTransaction().use {
            it.setBackgroundBlurRadius(viewRootImpl.surfaceControl, radius)
            it.apply()
        }
    }

    @VisibleForTesting
    open fun createTransaction(): SurfaceControl.Transaction {
        return SurfaceControl.Transaction()
    }

    /**
     * If this device can render blurs.
     *
     * @see android.view.SurfaceControl.Transaction#setBackgroundBlurRadius(SurfaceControl, int)
     * @return {@code true} when supported.
     */
    open fun supportsBlursOnWindows(): Boolean {
        return blurSysProp && ActivityManager.isHighEndGfx()
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("BlurUtils:")
            it.increaseIndent()
            it.println("minBlurRadius: $minBlurRadius")
            it.println("maxBlurRadius: $maxBlurRadius")
            it.println("blurSysProp: $blurSysProp")
            it.println("supportsBlursOnWindows: ${supportsBlursOnWindows()}")
        }
    }
}