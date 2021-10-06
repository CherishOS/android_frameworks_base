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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_UNSET;
import static android.view.WindowManager.TRANSIT_OPEN;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link AppTransition}.
 *
 * Build/Install/Run:
 *  atest WmTests:AppTransitionTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppTransitionTests extends WindowTestsBase {
    private DisplayContent mDc;

    @Before
    public void setUp() throws Exception {
        doNothing().when(mWm.mRoot).performSurfacePlacement();
        mDc = mWm.getDefaultDisplayContentLocked();
    }

    @Test
    public void testKeyguardOverride() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null /* wallpaperTarget */, null /* oldWallpaper */,
                        false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testKeyguardKeep() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null /* wallpaperTarget */, null /* oldWallpaper */,
                        false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testCrashing() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CLOSE, TRANSIT_FLAG_APP_CRASHED);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null /* wallpaperTarget */, null /* oldWallpaper */,
                        false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testKeepKeyguard_withCrashing() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.prepareAppTransition(TRANSIT_CLOSE, TRANSIT_FLAG_APP_CRASHED);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null /* wallpaperTarget */, null /* oldWallpaper */,
                        false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testSkipTransitionAnimation() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CLOSE);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_UNSET,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null /* wallpaperTarget */, null /* oldWallpaper */,
                        true /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testAppTransitionStateForMultiDisplay() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        // Create 2 app window tokens to represent 2 activity window.
        final ActivityRecord activity1 = createActivityRecord(dc1);
        final ActivityRecord activity2 = createActivityRecord(dc2);

        activity1.allDrawn = true;
        activity1.startingDisplayed = true;
        activity1.startingMoved = true;

        // Simulate activity resume / finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected for each display.
        dc1.prepareAppTransition(TRANSIT_OPEN);
        dc2.prepareAppTransition(TRANSIT_CLOSE);
        // One activity window is visible for resuming & the other activity window is invisible
        // for finishing in different display.
        activity1.setVisibility(true, false);
        activity2.setVisibility(false, false);

        // Make sure each display is in animating stage.
        assertTrue(dc1.mOpeningApps.size() > 0);
        assertTrue(dc2.mClosingApps.size() > 0);
        assertTrue(dc1.isAppTransitioning());
        assertTrue(dc2.isAppTransitioning());
    }

    @Test
    public void testCleanAppTransitionWhenRootTaskReparent() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        final Task rootTask1 = createTask(dc1);
        final Task task1 = createTaskInRootTask(rootTask1, 0 /* userId */);
        final ActivityRecord activity1 = createNonAttachedActivityRecord(dc1);
        task1.addChild(activity1, 0);

        // Simulate same app is during opening / closing transition set stage.
        dc1.mClosingApps.add(activity1);
        assertTrue(dc1.mClosingApps.size() > 0);

        dc1.prepareAppTransition(TRANSIT_OPEN);
        assertTrue(dc1.mAppTransition.containsTransitRequest(TRANSIT_OPEN));
        assertTrue(dc1.mAppTransition.isTransitionSet());

        dc1.mOpeningApps.add(activity1);
        assertTrue(dc1.mOpeningApps.size() > 0);

        // Move root task to another display.
        rootTask1.reparent(dc2.getDefaultTaskDisplayArea(), true);

        // Verify if token are cleared from both pending transition list in former display.
        assertFalse(dc1.mOpeningApps.contains(activity1));
        assertFalse(dc1.mOpeningApps.contains(activity1));
    }

    @Test
    public void testLoadAnimationSafely() {
        DisplayContent dc = createNewDisplay(Display.STATE_ON);
        assertNull(dc.mAppTransition.loadAnimationSafely(
                getInstrumentation().getTargetContext(), -1));
    }

    @Test
    public void testCancelRemoteAnimationWhenFreeze() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        doReturn(false).when(dc).onDescendantOrientationChanged(any());
        final WindowState exitingAppWindow = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                dc, "exiting app");
        final ActivityRecord exitingActivity= exitingAppWindow.mActivityRecord;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        // Set a remote animator.
        final TestRemoteAnimationRunner runner = new TestRemoteAnimationRunner();
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                runner, 100, 50, true /* changeNeedsSnapshot */);
        // RemoteAnimationController will tracking RemoteAnimationAdapter's caller with calling pid.
        adapter.setCallingPidUid(123, 456);

        // Simulate activity finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected.
        dc.prepareAppTransition(TRANSIT_CLOSE);
        assertTrue(dc.mAppTransition.containsTransitRequest(TRANSIT_CLOSE));
        dc.mAppTransition.overridePendingAppTransitionRemote(adapter);
        exitingActivity.setVisibility(false, false);
        assertTrue(dc.mClosingApps.size() > 0);

        // Make sure window is in animating stage before freeze, and cancel after freeze.
        assertTrue(dc.isAppTransitioning());
        assertFalse(runner.mCancelled);
        dc.mAppTransition.freeze();
        assertFalse(dc.isAppTransitioning());
        assertTrue(runner.mCancelled);
    }

    @Test
    public void testGetAnimationStyleResId() {
        // Verify getAnimationStyleResId will return as LayoutParams.windowAnimations when without
        // specifying window type.
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.windowAnimations = 0x12345678;
        assertEquals(attrs.windowAnimations, mDc.mAppTransition.getAnimationStyleResId(attrs));

        // Verify getAnimationStyleResId will return system resource Id when the window type is
        // starting window.
        attrs.type = TYPE_APPLICATION_STARTING;
        assertEquals(mDc.mAppTransition.getDefaultWindowAnimationStyleResId(),
                mDc.mAppTransition.getAnimationStyleResId(attrs));
    }

    private class TestRemoteAnimationRunner implements IRemoteAnimationRunner {
        boolean mCancelled = false;
        @Override
        public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
        }

        @Override
        public void onAnimationCancelled() {
            mCancelled = true;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }
}
