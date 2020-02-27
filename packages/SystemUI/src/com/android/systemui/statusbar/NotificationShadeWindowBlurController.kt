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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.View
import com.android.internal.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.Interpolators
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.PanelExpansionListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Controller responsible for statusbar window blur.
 */
@Singleton
class NotificationShadeWindowBlurController @Inject constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val blurUtils: BlurUtils,
    private val biometricUnlockController: BiometricUnlockController,
    private val keyguardStateController: KeyguardStateController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val choreographer: Choreographer,
    dumpManager: DumpManager
) : PanelExpansionListener, Dumpable {
    companion object {
        private const val WAKE_UP_ANIMATION_ENABLED = true
        private const val SHADE_BLUR_ENABLED = true
    }

    lateinit var root: View
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    private var shadeExpansion = 1.0f
    private var shadeBlurRadius = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }
    private var wakeAndUnlockBlurRadius = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }
    private var incomingNotificationBlurRadius = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /**
     * Callback that updates the window blur value and is called only once per frame.
     */
    private val updateBlurCallback = Choreographer.FrameCallback {
        updateScheduled = false

        var notificationBlur = 0
        if (statusBarStateController.state == StatusBarState.KEYGUARD) {
            notificationBlur = (incomingNotificationBlurRadius * shadeExpansion).toInt()
        }

        val blur = max(max(shadeBlurRadius, wakeAndUnlockBlurRadius), notificationBlur)
        blurUtils.applyBlur(root.viewRootImpl, blur)
    }

    /**
     * Animate blurs when unlocking.
     */
    private val keyguardStateCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardFadingAwayChanged() {
            if (!keyguardStateController.isKeyguardFadingAway ||
                    biometricUnlockController.mode != MODE_WAKE_AND_UNLOCK) {
                return
            }

            keyguardAnimator?.cancel()
            keyguardAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = keyguardStateController.keyguardFadingAwayDuration
                startDelay = keyguardStateController.keyguardFadingAwayDelay
                interpolator = Interpolators.DECELERATE_QUINT
                addUpdateListener { animation: ValueAnimator ->
                    wakeAndUnlockBlurRadius =
                            blurUtils.radiusForRatio(animation.animatedValue as Float)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        keyguardAnimator = null
                        scheduleUpdate()
                    }
                })
                start()
            }
        }

        override fun onKeyguardShowingChanged() {
            if (keyguardStateController.isShowing) {
                keyguardAnimator?.cancel()
                notificationAnimator?.cancel()
            }
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        if (WAKE_UP_ANIMATION_ENABLED) {
            keyguardStateController.addCallback(keyguardStateCallback)
        }
    }

    /**
     * Update blurs when pulling down the shade
     */
    override fun onPanelExpansionChanged(expansion: Float, tracking: Boolean) {
        if (!SHADE_BLUR_ENABLED) {
            return
        }

        var newBlur = 0
        if (statusBarStateController.state == StatusBarState.SHADE) {
            newBlur = blurUtils.radiusForRatio(expansion)
        }

        if (shadeBlurRadius == newBlur) {
            return
        }
        shadeBlurRadius = newBlur
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        choreographer.postFrameCallback(updateBlurCallback)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").use {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeBlurRadius: $shadeBlurRadius")
            it.println("wakeAndUnlockBlur: $wakeAndUnlockBlurRadius")
        }
    }
}