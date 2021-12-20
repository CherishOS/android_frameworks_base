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

package com.android.systemui.biometrics

import android.annotation.SuppressLint
import android.annotation.UiThread
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.biometrics.BiometricOverlayConstants
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricOverlayConstants.ShowReason
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener
import androidx.annotation.LayoutRes
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.SystemClock

private const val TAG = "UdfpsControllerOverlay"

/**
 * Keeps track of the overlay state and UI resources associated with a single FingerprintService
 * request. This state can persist across configuration changes via the [show] and [hide]
 * methods.
 */
@UiThread
class UdfpsControllerOverlay(
    private val context: Context,
    fingerprintManager: FingerprintManager,
    private val inflater: LayoutInflater,
    private val windowManager: WindowManager,
    private val accessibilityManager: AccessibilityManager,
    private val statusBarStateController: StatusBarStateController,
    private val panelExpansionStateManager: PanelExpansionStateManager,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val dialogManager: SystemUIDialogManager,
    private val dumpManager: DumpManager,
    private val transitionController: LockscreenShadeTransitionController,
    private val configurationController: ConfigurationController,
    private val systemClock: SystemClock,
    private val keyguardStateController: KeyguardStateController,
    private val unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController,
    private val sensorProps: FingerprintSensorPropertiesInternal,
    private var hbmProvider: UdfpsHbmProvider,
    @ShowReason val requestReason: Int,
    private val controllerCallback: IUdfpsOverlayControllerCallback,
    private val onTouch: (View, MotionEvent, Boolean) -> Boolean
) {
    /** The view, when [isShowing], or null. */
    var overlayView: UdfpsView? = null
        private set

    private var overlayTouchListener: TouchExplorationStateChangeListener? = null

    private val coreLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
        0 /* flags set in computeLayoutParams() */,
        PixelFormat.TRANSLUCENT
    ).apply {
        title = TAG
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
    }

    /** A helper if the [requestReason] was due to enrollment. */
    val enrollHelper: UdfpsEnrollHelper? = if (requestReason.isEnrollmentReason()) {
        UdfpsEnrollHelper(context, fingerprintManager, requestReason)
    } else {
        null
    }

    /** If the overlay is currently showing. */
    val isShowing: Boolean
        get() = overlayView != null

    /** Opposite of [isShowing]. */
    val isHiding: Boolean
        get() = overlayView == null

    /** The animation controller if the overlay [isShowing]. */
    val animationViewController: UdfpsAnimationViewController<*>?
        get() = overlayView?.animationViewController

    /** Show the overlay or return false and do nothing if it is already showing. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(controller: UdfpsController): Boolean {
        if (overlayView == null) {
            try {
                overlayView = (inflater.inflate(
                    R.layout.udfps_view, null, false
                ) as UdfpsView).apply {
                    sensorProperties = sensorProps
                    setHbmProvider(hbmProvider)
                    val animation = inflateUdfpsAnimation(this, controller)
                    if (animation != null) {
                        animation.init()
                        animationViewController = animation
                    }
                    // This view overlaps the sensor area
                    // prevent it from being selectable during a11y
                    if (requestReason.isImportantForAccessibility()) {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }

                    windowManager.addView(this,
                        coreLayoutParams.updateForLocation(sensorProps.location, animation))

                    overlayTouchListener = TouchExplorationStateChangeListener {
                        if (accessibilityManager.isTouchExplorationEnabled) {
                            setOnHoverListener { v, event -> onTouch(v, event, true) }
                            setOnTouchListener(null)
                        } else {
                            setOnHoverListener(null)
                            setOnTouchListener { v, event -> onTouch(v, event, true) }
                        }
                    }
                    accessibilityManager.addTouchExplorationStateChangeListener(
                        overlayTouchListener!!
                    )
                    overlayTouchListener?.onTouchExplorationStateChanged(true)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "showUdfpsOverlay | failed to add window", e)
            }
            return true
        }

        Log.v(TAG, "showUdfpsOverlay | the overlay is already showing")
        return false
    }

    private fun inflateUdfpsAnimation(
        view: UdfpsView,
        controller: UdfpsController
    ): UdfpsAnimationViewController<*>? {
        return when (requestReason) {
            REASON_ENROLL_FIND_SENSOR,
            REASON_ENROLL_ENROLLING -> {
                UdfpsEnrollViewController(
                    view.addUdfpsView(R.layout.udfps_enroll_view) {
                        updateSensorLocation(sensorProps)
                    },
                    enrollHelper ?: throw IllegalStateException("no enrollment helper"),
                    statusBarStateController,
                    panelExpansionStateManager,
                    dialogManager,
                    dumpManager
                )
            }
            BiometricOverlayConstants.REASON_AUTH_KEYGUARD -> {
                UdfpsKeyguardViewController(
                    view.addUdfpsView(R.layout.udfps_keyguard_view),
                    statusBarStateController,
                    panelExpansionStateManager,
                    statusBarKeyguardViewManager,
                    keyguardUpdateMonitor,
                    dumpManager,
                    transitionController,
                    configurationController,
                    systemClock,
                    keyguardStateController,
                    unlockedScreenOffAnimationController,
                    dialogManager,
                    controller
                )
            }
            BiometricOverlayConstants.REASON_AUTH_BP -> {
                // note: empty controller, currently shows no visual affordance
                UdfpsBpViewController(
                    view.addUdfpsView(R.layout.udfps_bp_view),
                    statusBarStateController,
                    panelExpansionStateManager,
                    dialogManager,
                    dumpManager
                )
            }
            BiometricOverlayConstants.REASON_AUTH_OTHER,
            BiometricOverlayConstants.REASON_AUTH_SETTINGS -> {
                UdfpsFpmOtherViewController(
                    view.addUdfpsView(R.layout.udfps_fpm_other_view),
                    statusBarStateController,
                    panelExpansionStateManager,
                    dialogManager,
                    dumpManager
                )
            }
            else -> {
                Log.e(TAG, "Animation for reason $requestReason not supported yet")
                null
            }
        }
    }

    /** Hide the overlay or return false and do nothing if it is already hidden. */
    fun hide(): Boolean {
        val wasShowing = isShowing

        overlayView?.apply {
            if (isIlluminationRequested) {
                stopIllumination()
            }
            windowManager.removeView(this)
            setOnTouchListener(null)
            setOnHoverListener(null)
            animationViewController = null
            overlayTouchListener?.let {
                accessibilityManager.removeTouchExplorationStateChangeListener(it)
            }
        }
        overlayView = null
        overlayTouchListener = null

        return wasShowing
    }

    fun onEnrollmentProgress(remaining: Int) {
        enrollHelper?.onEnrollmentProgress(remaining)
    }

    fun onAcquiredGood() {
        enrollHelper?.animateIfLastStep()
    }

    fun onEnrollmentHelp() {
        enrollHelper?.onEnrollmentHelp()
    }

    /** Cancel this request. */
    fun cancel() {
        try {
            controllerCallback.onUserCanceled()
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception", e)
        }
    }

    private fun WindowManager.LayoutParams.updateForLocation(
        location: SensorLocationInternal,
        animation: UdfpsAnimationViewController<*>?
    ): WindowManager.LayoutParams {
        val paddingX = animation?.paddingX ?: 0
        val paddingY = animation?.paddingY ?: 0
        flags = (Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS
            or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)
        if (animation != null && animation.listenForTouchesOutsideView()) {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        // Default dimensions assume portrait mode.
        x = location.sensorLocationX - location.sensorRadius - paddingX
        y = location.sensorLocationY - location.sensorRadius - paddingY
        height = 2 * location.sensorRadius + 2 * paddingX
        width = 2 * location.sensorRadius + 2 * paddingY

        // Gets the size based on the current rotation of the display.
        val p = Point()
        context.display!!.getRealSize(p)
        when (context.display!!.rotation) {
            Surface.ROTATION_90 -> {
                if (!shouldRotate(animation)) {
                    Log.v(TAG, "skip rotating udfps location ROTATION_90")
                } else {
                    Log.v(TAG, "rotate udfps location ROTATION_90")
                    x = (location.sensorLocationY - location.sensorRadius - paddingX)
                    y = (p.y - location.sensorLocationX - location.sensorRadius - paddingY)
                }
            }
            Surface.ROTATION_270 -> {
                if (!shouldRotate(animation)) {
                    Log.v(TAG, "skip rotating udfps location ROTATION_270")
                } else {
                    Log.v(TAG, "rotate udfps location ROTATION_270")
                    x = (p.x - location.sensorLocationY - location.sensorRadius - paddingX)
                    y = (location.sensorLocationX - location.sensorRadius - paddingY)
                }
            }
            else -> {}
        }

        // avoid announcing window title
        accessibilityTitle = " "

        return this
    }

    private fun shouldRotate(animation: UdfpsAnimationViewController<*>?): Boolean {
        if (animation !is UdfpsKeyguardViewController) {
            // always rotate view if we're not on the keyguard
            return true
        }

        // on the keyguard, make sure we don't rotate if we're going to sleep or not occluded
        return !(keyguardUpdateMonitor.isGoingToSleep || !keyguardStateController.isOccluded)
    }

    private inline fun <reified T : View> UdfpsView.addUdfpsView(
        @LayoutRes id: Int,
        init: T.() -> Unit = {}
    ): T {
        val subView = inflater.inflate(id, null) as T
        addView(subView)
        subView.init()
        return subView
    }
}

@ShowReason
private fun Int.isEnrollmentReason() =
    this == REASON_ENROLL_FIND_SENSOR || this == REASON_ENROLL_ENROLLING

@ShowReason
private fun Int.isImportantForAccessibility() =
    this == REASON_ENROLL_FIND_SENSOR ||
        this == REASON_ENROLL_ENROLLING ||
        this == BiometricOverlayConstants.REASON_AUTH_BP
