package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.statusbar.NotificationShadeWindowController

/**
 * A [ActivityLaunchAnimator.Controller] that takes care of collapsing the status bar at the right
 * time.
 */
class StatusBarLaunchAnimatorController(
    private val delegate: ActivityLaunchAnimator.Controller,
    private val shadeViewController: ShadeViewController,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val shadeController: ShadeController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val isLaunchForActivity: Boolean = true
) : ActivityLaunchAnimator.Controller by delegate {
    // Always sync the opening window with the shade, given that we draw a hole punch in the shade
    // of the same size and position as the opening app to make it visible.
    override val openingWindowSyncView: View?
        get() = notificationShadeWindowController.windowRootView

    override fun onIntentStarted(willAnimate: Boolean) {
        delegate.onIntentStarted(willAnimate)
        if (willAnimate) {
            shadeAnimationInteractor.setIsLaunchingActivity(true)
        } else {
            shadeController.collapseOnMainThread()
        }
    }

    override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
        delegate.onTransitionAnimationStart(isExpandingFullyAbove)
        shadeAnimationInteractor.setIsLaunchingActivity(true)
        if (!isExpandingFullyAbove) {
            shadeViewController.collapseWithDuration(
                ActivityLaunchAnimator.TIMINGS.totalDuration.toInt())
        }
    }

    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
        shadeAnimationInteractor.setIsLaunchingActivity(false)
        shadeController.onLaunchAnimationEnd(isExpandingFullyAbove)
    }

    override fun onTransitionAnimationProgress(
        state: TransitionAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        delegate.onTransitionAnimationProgress(state, progress, linearProgress)
        shadeViewController.applyLaunchAnimationProgress(linearProgress)
    }

    override fun onLaunchAnimationCancelled(newKeyguardOccludedState: Boolean?) {
        delegate.onLaunchAnimationCancelled()
        shadeAnimationInteractor.setIsLaunchingActivity(false)
        shadeController.onLaunchAnimationCancelled(isLaunchForActivity)
    }
}