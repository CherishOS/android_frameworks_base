package com.android.systemui.plugins.animation

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.IRemoteAnimationFinishedCallback
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import junit.framework.AssertionFailedError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import kotlin.concurrent.thread

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ActivityLaunchAnimatorTest : SysuiTestCase() {
    private val activityLaunchAnimator = ActivityLaunchAnimator()
    private val rootView = View(mContext)
    @Spy private val controller = TestLaunchAnimatorController(rootView)
    @Mock lateinit var iCallback: IRemoteAnimationFinishedCallback

    @get:Rule val rule = MockitoJUnit.rule()

    private fun startIntentWithAnimation(
        controller: ActivityLaunchAnimator.Controller? = this.controller,
        intentStarter: (RemoteAnimationAdapter?) -> Int
    ) {
        // We start in a new thread so that we can ensure that the callbacks are called in the main
        // thread.
        thread {
            activityLaunchAnimator.startIntentWithAnimation(controller, intentStarter)
        }.join()
    }

    @Test
    fun animationAdapterIsNullIfControllerIsNull() {
        var startedIntent = false
        var animationAdapter: RemoteAnimationAdapter? = null

        startIntentWithAnimation(controller = null) { adapter ->
            startedIntent = true
            animationAdapter = adapter

            ActivityManager.START_SUCCESS
        }

        assertTrue(startedIntent)
        assertNull(animationAdapter)
    }

    @Test
    fun animatesIfActivityOpens() {
        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        var animationAdapter: RemoteAnimationAdapter? = null
        startIntentWithAnimation { adapter ->
            animationAdapter = adapter
            ActivityManager.START_SUCCESS
        }

        assertNotNull(animationAdapter)
        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        assertTrue(willAnimateCaptor.value)
    }

    @Test
    fun doesNotAnimateIfActivityIsAlreadyOpen() {
        val willAnimateCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        startIntentWithAnimation { ActivityManager.START_DELIVERED_TO_TOP }

        waitForIdleSync()
        verify(controller).onIntentStarted(willAnimateCaptor.capture())
        assertFalse(willAnimateCaptor.value)
    }

    @Test
    fun doesNotStartIfAnimationIsCancelled() {
        val runner = ActivityLaunchAnimator.Runner(controller)
        runner.onAnimationCancelled()
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onLaunchAnimationCancelled()
        verify(controller, never()).onLaunchAnimationStart(anyBoolean())
    }

    @Test
    fun abortsIfNoOpeningWindowIsFound() {
        val runner = ActivityLaunchAnimator.Runner(controller)
        runner.onAnimationStart(0, emptyArray(), emptyArray(), emptyArray(), iCallback)

        waitForIdleSync()
        verify(controller).onLaunchAnimationAborted()
        verify(controller, never()).onLaunchAnimationStart(anyBoolean())
    }

    @Test
    fun startsAnimationIfWindowIsOpening() {
        val runner = ActivityLaunchAnimator.Runner(controller)
        runner.onAnimationStart(0, arrayOf(fakeWindow()), emptyArray(), emptyArray(), iCallback)
        waitForIdleSync()
        verify(controller).onLaunchAnimationStart(anyBoolean())
    }

    private fun fakeWindow() = RemoteAnimationTarget(
            0, RemoteAnimationTarget.MODE_OPENING, SurfaceControl(), false, Rect(), Rect(), 0,
            Point(), Rect(), Rect(), WindowConfiguration(), false, SurfaceControl(), Rect(),
            ActivityManager.RunningTaskInfo()
    )
}

/**
 * A simple implementation of [ActivityLaunchAnimator.Controller] which throws if it is called
 * outside of the main thread.
 */
private class TestLaunchAnimatorController(
    private val rootView: View
) : ActivityLaunchAnimator.Controller {
    override fun getRootView(): View = rootView

    override fun createAnimatorState() = ActivityLaunchAnimator.State(
            top = 100,
            bottom = 200,
            left = 300,
            right = 400,
            topCornerRadius = 10f,
            bottomCornerRadius = 20f
    )

    private fun assertOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw AssertionFailedError("Called outside of main thread.")
        }
    }

    override fun onIntentStarted(willAnimate: Boolean) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationProgress(
        state: ActivityLaunchAnimator.State,
        progress: Float,
        linearProgress: Float
    ) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
        assertOnMainThread()
    }

    override fun onLaunchAnimationCancelled() {
        assertOnMainThread()
    }

    override fun onLaunchAnimationTimedOut() {
        assertOnMainThread()
    }

    override fun onLaunchAnimationAborted() {
        assertOnMainThread()
    }
}
