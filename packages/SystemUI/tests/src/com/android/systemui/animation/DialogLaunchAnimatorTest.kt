package com.android.systemui.animation

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.dreams.IDreamManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.policy.DecorView
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DialogLaunchAnimatorTest : SysuiTestCase() {
    private val launchAnimator = LaunchAnimator(TEST_TIMINGS, TEST_INTERPOLATORS)
    private lateinit var dialogLaunchAnimator: DialogLaunchAnimator
    private val attachedViews = mutableSetOf<View>()

    @Mock lateinit var dreamManager: IDreamManager
    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        dialogLaunchAnimator = DialogLaunchAnimator(
            dreamManager, launchAnimator, isForTesting = true)
    }

    @After
    fun tearDown() {
        runOnMainThreadAndWaitForIdleSync {
            attachedViews.forEach {
                ViewUtils.detachView(it)
            }
        }
    }

    @Test
    fun testShowDialogFromView() {
        // Show the dialog. showFromView() must be called on the main thread with a dialog created
        // on the main thread too.
        val dialog = createAndShowDialog()

        assertTrue(dialog.isShowing)

        // The dialog is now fullscreen.
        val window = dialog.window
        val decorView = window.decorView as DecorView
        assertEquals(MATCH_PARENT, window.attributes.width)
        assertEquals(MATCH_PARENT, window.attributes.height)
        assertEquals(MATCH_PARENT, decorView.layoutParams.width)
        assertEquals(MATCH_PARENT, decorView.layoutParams.height)

        // The single DecorView child is a transparent fullscreen view that will dismiss the dialog
        // when clicked.
        assertEquals(1, decorView.childCount)
        val transparentBackground = decorView.getChildAt(0) as ViewGroup
        assertEquals(MATCH_PARENT, transparentBackground.layoutParams.width)
        assertEquals(MATCH_PARENT, transparentBackground.layoutParams.height)

        // The single transparent background child is a fake window with the same size and
        // background as the dialog initially had.
        assertEquals(1, transparentBackground.childCount)
        val dialogContentWithBackground = transparentBackground.getChildAt(0) as ViewGroup
        assertEquals(TestDialog.DIALOG_WIDTH, dialogContentWithBackground.layoutParams.width)
        assertEquals(TestDialog.DIALOG_HEIGHT, dialogContentWithBackground.layoutParams.height)
        assertEquals(dialog.windowBackground, dialogContentWithBackground.background)

        // The dialog content is inside this fake window view.
        assertNotNull(
            dialogContentWithBackground.findViewByPredicate { it === dialog.contentView })

        // Clicking the transparent background should dismiss the dialog.
        runOnMainThreadAndWaitForIdleSync {
            // TODO(b/204561691): Remove this call to disableAllCurrentDialogsExitAnimations() and
            // make sure that the test still pass on git_master/cf_x86_64_phone-userdebug in
            // Forrest.
            dialogLaunchAnimator.disableAllCurrentDialogsExitAnimations()

            transparentBackground.performClick()
        }
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testStackedDialogsDismissesAll() {
        val firstDialog = createAndShowDialog()
        val secondDialog = createDialogAndShowFromDialog(firstDialog)

        assertTrue(firstDialog.isShowing)
        assertTrue(secondDialog.isShowing)
        runOnMainThreadAndWaitForIdleSync {
            dialogLaunchAnimator.disableAllCurrentDialogsExitAnimations()
            dialogLaunchAnimator.dismissStack(secondDialog)
        }

        assertFalse(firstDialog.isShowing)
        assertFalse(secondDialog.isShowing)
    }

    private fun createAndShowDialog(): TestDialog {
        return runOnMainThreadAndWaitForIdleSync {
            val touchSurfaceRoot = LinearLayout(context)
            val touchSurface = View(context)
            touchSurfaceRoot.addView(touchSurface)

            // We need to attach the root to the window manager otherwise the exit animation will
            // be skipped
            ViewUtils.attachView(touchSurfaceRoot)
            attachedViews.add(touchSurfaceRoot)

            val dialog = TestDialog(context)
            dialogLaunchAnimator.showFromView(dialog, touchSurface)
            dialog
        }
    }

    private fun createDialogAndShowFromDialog(animateFrom: Dialog): TestDialog {
        return runOnMainThreadAndWaitForIdleSync {
            val dialog = TestDialog(context)
            dialogLaunchAnimator.showFromDialog(dialog, animateFrom)
            dialog
        }
    }

    private fun <T : Any> runOnMainThreadAndWaitForIdleSync(f: () -> T): T {
        lateinit var result: T
        context.mainExecutor.execute {
            result = f()
        }
        waitForIdleSync()
        return result
    }

    private class TestDialog(context: Context) : Dialog(context) {
        companion object {
            const val DIALOG_WIDTH = 100
            const val DIALOG_HEIGHT = 200
        }

        val contentView = View(context)
        val windowBackground = ColorDrawable(Color.RED)

        init {
            // We need to set the window type for dialogs shown by SysUI, otherwise WM will throw.
            window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(contentView)

            window.setLayout(DIALOG_WIDTH, DIALOG_HEIGHT)
            window.setBackgroundDrawable(windowBackground)
        }
    }
}