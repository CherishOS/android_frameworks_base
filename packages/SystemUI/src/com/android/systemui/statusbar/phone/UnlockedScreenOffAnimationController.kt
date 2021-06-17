package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.view.View
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.statusbar.notification.AnimatableProperty
import com.android.systemui.statusbar.notification.PropertyAnimator
import com.android.systemui.statusbar.notification.stack.AnimationProperties
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * When to show the keyguard (AOD) view. This should be once the light reveal scrim is barely
 * visible, because the transition to KEYGUARD causes brief jank.
 */
private const val ANIMATE_IN_KEYGUARD_DELAY = 600L

/**
 * Duration for the light reveal portion of the animation.
 */
private const val LIGHT_REVEAL_ANIMATION_DURATION = 750L

/**
 * Controller for the unlocked screen off animation, which runs when the device is going to sleep
 * and we're unlocked.
 *
 * This animation uses a [LightRevealScrim] that lives in the status bar to hide the screen contents
 * and then animates in the AOD UI.
 */
@SysUISingleton
class UnlockedScreenOffAnimationController @Inject constructor(
    private val context: Context,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val statusBarStateControllerImpl: StatusBarStateControllerImpl,
    private val keyguardViewMediatorLazy: dagger.Lazy<KeyguardViewMediator>,
    private val keyguardStateController: KeyguardStateController
) : WakefulnessLifecycle.Observer {
    private val handler = Handler()

    private lateinit var statusBar: StatusBar
    private lateinit var lightRevealScrim: LightRevealScrim

    private var lightRevealAnimationPlaying = false
    private var aodUiAnimationPlaying = false

    private val lightRevealAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = LIGHT_REVEAL_ANIMATION_DURATION
        interpolator = Interpolators.FAST_OUT_SLOW_IN_REVERSE
        addUpdateListener { lightRevealScrim.revealAmount = it.animatedValue as Float }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator?) {
                lightRevealScrim.revealAmount = 1f
                lightRevealAnimationPlaying = false
            }

            override fun onAnimationEnd(animation: Animator?) {
                lightRevealAnimationPlaying = false
            }
        })
    }

    fun initialize(
        statusBar: StatusBar,
        lightRevealScrim: LightRevealScrim
    ) {
        this.lightRevealScrim = lightRevealScrim
        this.statusBar = statusBar

        wakefulnessLifecycle.addObserver(this)
    }

    /**
     * Animates in the provided keyguard view, ending in the same position that it will be in on
     * AOD.
     */
    fun animateInKeyguard(keyguardView: View, after: Runnable) {
        keyguardView.alpha = 0f
        keyguardView.visibility = View.VISIBLE

        val currentY = keyguardView.y

        // Move the keyguard up by 10% so we can animate it back down.
        keyguardView.y = currentY - keyguardView.height * 0.1f

        val duration = StackStateAnimator.ANIMATION_DURATION_WAKEUP

        // We animate the Y properly separately using the PropertyAnimator, as the panel
        // view also needs to update the end position.
        PropertyAnimator.cancelAnimation(keyguardView, AnimatableProperty.Y)
        PropertyAnimator.setProperty<View>(keyguardView, AnimatableProperty.Y, currentY,
                AnimationProperties().setDuration(duration.toLong()),
                true /* animate */)

        keyguardView.animate()
                .setDuration(duration.toLong())
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
                .withEndAction {
                    aodUiAnimationPlaying = false

                    // Lock the keyguard if it was waiting for the screen off animation to end.
                    keyguardViewMediatorLazy.get().maybeHandlePendingLock()

                    // Tell the StatusBar to become keyguard for real - we waited on that since it
                    // is slow and would have caused the animation to jank.
                    statusBar.updateIsKeyguard()

                    // Run the callback given to us by the KeyguardVisibilityHelper.
                    after.run()
                }
                .start()
    }

    override fun onStartedWakingUp() {
        lightRevealAnimator.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onFinishedWakingUp() {
        // Set this to false in onFinishedWakingUp rather than onStartedWakingUp so that other
        // observers (such as StatusBar) can ask us whether we were playing the screen off animation
        // and reset accordingly.
        lightRevealAnimationPlaying = false
        aodUiAnimationPlaying = false

        // Make sure the status bar is in the correct keyguard state, forcing it if necessary. This
        // is required if the screen off animation is cancelled, since it might be incorrectly left
        // in the KEYGUARD or SHADE states depending on when it was cancelled and whether 'lock
        // instantly' is enabled. We need to force it so that the state is set even if we're going
        // from SHADE to SHADE or KEYGUARD to KEYGUARD, since we might have changed parts of the UI
        // (such as showing AOD in the shade) without actually changing the StatusBarState. This
        // ensures that the UI definitely reflects the desired state.
        statusBar.updateIsKeyguard(true /* force */)
    }

    override fun onStartedGoingToSleep() {
        if (shouldPlayUnlockedScreenOffAnimation()) {
            lightRevealAnimationPlaying = true
            lightRevealAnimator.start()

            handler.postDelayed({
                aodUiAnimationPlaying = true

                // Show AOD. That'll cause the KeyguardVisibilityHelper to call #animateInKeyguard.
                statusBar.notificationPanelViewController.showAodUi()
            }, ANIMATE_IN_KEYGUARD_DELAY)
        }
    }

    /**
     * Whether we want to play the screen off animation when the phone starts going to sleep, based
     * on the current state of the device.
     */
    fun shouldPlayUnlockedScreenOffAnimation(): Boolean {
        // We only play the unlocked screen off animation if we are... unlocked.
        if (statusBarStateControllerImpl.state != StatusBarState.SHADE) {
            return false
        }

        // We currently draw both the light reveal scrim, and the AOD UI, in the shade. If it's
        // already expanded and showing notifications/QS, the animation looks really messy. For now,
        // disable it if the notification panel is expanded.
        if (statusBar.notificationPanelViewController.isFullyExpanded) {
            return false
        }

        // If we're not allowed to rotate the keyguard, then only do the screen off animation if
        // we're in portrait. Otherwise, AOD will animate in sideways, which looks weird.
        if (!keyguardStateController.isKeyguardScreenRotationAllowed &&
                context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            return false
        }

        // Otherwise, good to go.
        return true
    }

    /**
     * Whether we're doing the light reveal animation or we're done with that and animating in the
     * AOD UI.
     */
    fun isScreenOffAnimationPlaying(): Boolean {
        return lightRevealAnimationPlaying || aodUiAnimationPlaying
    }

    /**
     * Whether the light reveal animation is playing. The second part of the screen off animation,
     * where AOD animates in, might still be playing if this returns false.
     */
    fun isScreenOffLightRevealAnimationPlaying(): Boolean {
        return lightRevealAnimationPlaying
    }
}