/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_UNSET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link AppWindowToken} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AppWindowTokenTests
 */
@FlakyTest(bugId = 68267650)
@SmallTest
@Presubmit
public class AppWindowTokenTests extends WindowTestsBase {

    TaskStack mStack;
    Task mTask;
    WindowTestUtils.TestAppWindowToken mToken;

    @Before
    public void setUp() throws Exception {
        mStack = createTaskStackOnDisplay(mDisplayContent);
        mTask = createTaskInStack(mStack, 0 /* userId */);
        mToken = WindowTestUtils.createTestAppWindowToken(mDisplayContent);

        mTask.addChild(mToken, 0);
    }

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        assertEquals(0, mToken.getWindowsCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, mToken, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, mToken,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, mToken, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, mToken, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, mToken.getWindowsCount());
        assertTrue(mToken.hasWindow(win1));
        assertTrue(mToken.hasWindow(startingWin));
        assertTrue(mToken.hasWindow(baseWin));
        assertTrue(mToken.hasWindow(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, mToken.getLastChild());

        // The base application window should be below all other windows.
        assertEquals(baseWin, mToken.getFirstChild());
        mToken.removeImmediately();
    }

    @Test
    @Presubmit
    public void testFindMainWindow() {
        assertNull(mToken.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mToken, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, mToken, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, mToken, "window12");
        assertEquals(window1, mToken.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, mToken.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, mToken, "window2");
        assertEquals(window2, mToken.findMainWindow());
        mToken.removeImmediately();
    }

    @Test
    @Presubmit
    public void testGetTopFullscreenWindow() {
        assertNull(mToken.getTopFullscreenWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mToken, "window1");
        final WindowState window11 = createWindow(null, TYPE_APPLICATION, mToken, "window11");
        final WindowState window12 = createWindow(null, TYPE_APPLICATION, mToken, "window12");
        assertEquals(window12, mToken.getTopFullscreenWindow());
        window12.mAttrs.width = 500;
        assertEquals(window11, mToken.getTopFullscreenWindow());
        window11.mAttrs.width = 500;
        assertEquals(window1, mToken.getTopFullscreenWindow());
        mToken.removeImmediately();
    }

    @Test
    public void testLandscapeSeascapeRotationByApp() {
        // Some plumbing to get the service ready for rotation updates.
        mWm.mDisplayReady = true;
        mWm.mDisplayEnabled = true;

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

        // Set initial orientation and update.
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mWm.updateOrientationFromAppTokens(mDisplayContent.getOverrideConfiguration(), null,
                mDisplayContent.getDisplayId());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.resizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        mToken.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        mWm.updateOrientationFromAppTokens(mDisplayContent.getOverrideConfiguration(), null,
                mDisplayContent.getDisplayId());
        mWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, mDisplayContent.getLastOrientation());
        assertTrue(appWindow.resizeReported);
        appWindow.removeImmediately();
    }

    @Test
    public void testLandscapeSeascapeRotationByPolicy() {
        // Some plumbing to get the service ready for rotation updates.
        mWm.mDisplayReady = true;
        mWm.mDisplayEnabled = true;

        final DisplayRotation spiedRotation = spy(mDisplayContent.getDisplayRotation());
        mDisplayContent.setDisplayRotation(spiedRotation);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

        // Set initial orientation and update.
        performRotation(spiedRotation, Surface.ROTATION_90);
        appWindow.resizeReported = false;

        // Update the rotation to perform 180 degree rotation and check that resize was reported.
        performRotation(spiedRotation, Surface.ROTATION_270);
        assertTrue(appWindow.resizeReported);

        appWindow.removeImmediately();
    }

    private void performRotation(DisplayRotation spiedRotation, int rotationToReport) {
        doReturn(rotationToReport).when(spiedRotation).rotationForOrientation(anyInt(), anyInt());
        int oldRotation = mDisplayContent.getRotation();
        mWm.updateRotation(false, false);
        // Must manually apply here since ATM doesn't know about the display during this test
        // (meaning it can't perform the normal sendNewConfiguration flow).
        mDisplayContent.applyRotationLocked(oldRotation, mDisplayContent.getRotation());
        // Prevent the next rotation from being deferred by animation.
        mWm.mAnimator.setScreenRotationAnimationLocked(mDisplayContent.getDisplayId(), null);
        mWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
    }

    @Test
    @Presubmit
    public void testGetOrientation() {
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mToken.setFillsParent(false);
        // Can specify orientation if app doesn't fill parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mToken.getOrientation());

        mToken.setFillsParent(true);
        mToken.setHidden(true);
        mToken.sendingToBottom = true;
        // Can not specify orientation if app isn't visible even though it fills parent.
        assertEquals(SCREEN_ORIENTATION_UNSET, mToken.getOrientation());
        // Can specify orientation if the current orientation candidate is orientation behind.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mToken.getOrientation(SCREEN_ORIENTATION_BEHIND));
    }

    @Test
    @Presubmit
    public void testKeyguardFlagsDuringRelaunch() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.flags |= FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD;
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);

        // Add window with show when locked flag
        mToken.addWindow(appWindow);
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Start relaunching
        mToken.startRelaunching();
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Remove window and make sure that we still report back flag
        mToken.removeChild(appWindow);
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Finish relaunching and ensure flag is now not reported
        mToken.finishRelaunching();
        assertFalse(mToken.containsShowWhenLockedWindow() || mToken.containsDismissKeyguardWindow());
    }

    @Test
    @FlakyTest(detail = "Promote once confirmed non-flaky")
    public void testStuckExitingWindow() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAnimatingExit = true;
        closingWindow.mRemoveOnExit = true;
        closingWindow.mAppToken.setVisibility(null, false /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);

        // We pretended that we were running an exit animation, but that should have been cleared up
        // by changing visibility of AppWindowToken
        closingWindow.removeIfPossible();
        assertTrue(closingWindow.mRemoved);
    }

    @Test
    public void testTransitionAnimationPositionAndBounds() {
        final Rect stackBounds = new Rect(
                0/* left */, 0 /* top */, 1000 /* right */, 1000 /* bottom */);
        final Rect taskBounds = new Rect(
                100/* left */, 200 /* top */, 600 /* right */, 600 /* bottom */);
        mStack.setBounds(stackBounds);
        mTask.setBounds(taskBounds);

        mTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTransitionAnimationPositionAndBounds(taskBounds.left, taskBounds.top, stackBounds);

        mTask.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        assertTransitionAnimationPositionAndBounds(stackBounds.left, stackBounds.top, stackBounds);
    }

    private void assertTransitionAnimationPositionAndBounds(int expectedX, int expectedY,
            Rect expectedBounds) {
        final Point outPosition = new Point();
        final Rect outBounds = new Rect();
        mToken.getAnimationBounds(outPosition, outBounds);
        assertEquals(expectedX, outPosition.x);
        assertEquals(expectedY, outPosition.y);
        assertEquals(expectedBounds, outBounds);
    }
}
