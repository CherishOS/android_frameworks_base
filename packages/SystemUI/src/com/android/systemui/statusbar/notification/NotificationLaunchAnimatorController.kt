package com.android.systemui.statusbar.notification

import android.view.ViewGroup
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.NotificationShadeWindowViewController
import kotlin.math.ceil
import kotlin.math.max

/** A provider of [NotificationLaunchAnimatorController]. */
class NotificationLaunchAnimatorControllerProvider(
    private val notificationShadeWindowViewController: NotificationShadeWindowViewController,
    private val notificationListContainer: NotificationListContainer,
    private val headsUpManager: HeadsUpManagerPhone
) {
    fun getAnimatorController(
        notification: ExpandableNotificationRow
    ): NotificationLaunchAnimatorController {
        return NotificationLaunchAnimatorController(
            notificationShadeWindowViewController,
            notificationListContainer,
            notification,
            headsUpManager
        )
    }
}

/**
 * An [ActivityLaunchAnimator.Controller] that animates an [ExpandableNotificationRow]. An instance
 * of this class can be passed to [ActivityLaunchAnimator.startIntentWithAnimation] to animate a
 * notification expanding into an opening window.
 */
class NotificationLaunchAnimatorController(
    private val notificationShadeWindowViewController: NotificationShadeWindowViewController,
    private val notificationListContainer: NotificationListContainer,
    private val notification: ExpandableNotificationRow,
    private val headsUpManager: HeadsUpManagerPhone
) : ActivityLaunchAnimator.Controller {
    private val notificationKey = notification.entry.sbn.key

    override var launchContainer: ViewGroup
        get() = notification.rootView as ViewGroup
        set(ignored) {
            // Do nothing. Notifications are always animated inside their rootView.
        }

    override fun createAnimatorState(): ActivityLaunchAnimator.State {
        // If the notification panel is collapsed, the clip may be larger than the height.
        val height = max(0, notification.actualHeight - notification.clipBottomAmount)
        val location = notification.locationOnScreen

        val params = ExpandAnimationParameters(
                top = location[1],
                bottom = location[1] + height,
                left = location[0],
                right = location[0] + notification.width,
                topCornerRadius = notification.currentBackgroundRadiusTop,
                bottomCornerRadius = notification.currentBackgroundRadiusBottom
        )

        params.startTranslationZ = notification.translationZ
        params.startClipTopAmount = notification.clipTopAmount
        if (notification.isChildInGroup) {
            val parentClip = notification.notificationParent.clipTopAmount
            params.parentStartClipTopAmount = parentClip

            // We need to calculate how much the child is clipped by the parent because children
            // always have 0 clipTopAmount
            if (parentClip != 0) {
                val childClip = parentClip - notification.translationY
                if (childClip > 0) {
                    params.startClipTopAmount = ceil(childClip.toDouble()).toInt()
                }
            }
        }

        return params
    }

    override fun onIntentStarted(willAnimate: Boolean) {
        notificationShadeWindowViewController.setExpandAnimationRunning(willAnimate)

        if (!willAnimate) {
            removeHun(animate = true)
        }
    }

    private fun removeHun(animate: Boolean) {
        if (!headsUpManager.isAlerting(notificationKey)) {
            return
        }

        headsUpManager.removeNotification(notificationKey, true /* releaseImmediately */, animate)
    }

    override fun onLaunchAnimationCancelled() {
        // TODO(b/184121838): Should we call InteractionJankMonitor.cancel if the animation started
        // here?
        notificationShadeWindowViewController.setExpandAnimationRunning(false)
        removeHun(animate = true)
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        notification.isExpandAnimationRunning = true
        notificationListContainer.setExpandingNotification(notification)

        InteractionJankMonitor.getInstance().begin(notification,
            InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        InteractionJankMonitor.getInstance().end(InteractionJankMonitor.CUJ_NOTIFICATION_APP_START)

        notification.isExpandAnimationRunning = false
        notificationShadeWindowViewController.setExpandAnimationRunning(false)
        notificationListContainer.setExpandingNotification(null)
        applyParams(null)
        removeHun(animate = false)
    }

    private fun applyParams(params: ExpandAnimationParameters?) {
        notification.applyExpandAnimationParams(params)
        notificationListContainer.applyExpandAnimationParams(params)
    }

    override fun onLaunchAnimationProgress(
        state: ActivityLaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        val params = state as ExpandAnimationParameters
        params.progress = progress
        params.linearProgress = linearProgress

        applyParams(params)
    }
}
