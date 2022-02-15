/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackEvent;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest WMShellUnitTests:BackAnimationControllerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BackAnimationControllerTest {

    private final ShellExecutor mShellExecutor = new TestShellExecutor();

    @Mock
    private Context mContext;

    @Mock
    private SurfaceControl.Transaction mTransaction;

    @Mock
    private IActivityTaskManager mActivityTaskManager;

    @Mock
    private IOnBackInvokedCallback mIOnBackInvokedCallback;

    private BackAnimationController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mController = new BackAnimationController(
                mShellExecutor, mTransaction, mActivityTaskManager, mContext);
    }

    private void createNavigationInfo(RemoteAnimationTarget topAnimationTarget,
            SurfaceControl screenshotSurface,
            HardwareBuffer hardwareBuffer,
            int backType) {
        BackNavigationInfo navigationInfo = new BackNavigationInfo(
                backType,
                topAnimationTarget,
                screenshotSurface,
                hardwareBuffer,
                new WindowConfiguration(),
                new RemoteCallback((bundle) -> {}),
                null);
        try {
            doReturn(navigationInfo).when(mActivityTaskManager).startBackNavigation();
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    RemoteAnimationTarget createAnimationTarget() {
        SurfaceControl topWindowLeash = new SurfaceControl();
        return new RemoteAnimationTarget(-1, RemoteAnimationTarget.MODE_CLOSING, topWindowLeash,
                false, new Rect(), new Rect(), -1,
                new Point(0, 0), new Rect(), new Rect(), new WindowConfiguration(),
                true, null, null, null, false, -1);
    }

    @Test
    public void crossActivity_screenshotAttachedAndVisible() {
        SurfaceControl screenshotSurface = new SurfaceControl();
        HardwareBuffer hardwareBuffer = mock(HardwareBuffer.class);
        createNavigationInfo(createAnimationTarget(), screenshotSurface, hardwareBuffer,
                BackNavigationInfo.TYPE_CROSS_ACTIVITY);
        mController.onMotionEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0),
                BackEvent.EDGE_LEFT);
        verify(mTransaction).setBuffer(screenshotSurface, hardwareBuffer);
        verify(mTransaction).setVisibility(screenshotSurface, true);
        verify(mTransaction).apply();
    }

    @Test
    public void crossActivity_surfaceMovesWithGesture() {
        SurfaceControl screenshotSurface = new SurfaceControl();
        HardwareBuffer hardwareBuffer = mock(HardwareBuffer.class);
        RemoteAnimationTarget animationTarget = createAnimationTarget();
        createNavigationInfo(animationTarget, screenshotSurface, hardwareBuffer,
                BackNavigationInfo.TYPE_CROSS_ACTIVITY);
        mController.onMotionEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0),
                BackEvent.EDGE_LEFT);
        mController.onMotionEvent(
                MotionEvent.obtain(10, 0, MotionEvent.ACTION_MOVE, 100, 100, 0),
                BackEvent.EDGE_LEFT);
        verify(mTransaction).setPosition(animationTarget.leash, 100, 100);
        verify(mTransaction, atLeastOnce()).apply();
    }

    @Test
    public void backToHome_dispatchesEvents() throws RemoteException {
        mController.setBackToLauncherCallback(mIOnBackInvokedCallback);
        RemoteAnimationTarget animationTarget = createAnimationTarget();
        createNavigationInfo(animationTarget, null, null,
                BackNavigationInfo.TYPE_RETURN_TO_HOME);

        // Check that back start is dispatched.
        mController.onMotionEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0),
                BackEvent.EDGE_LEFT);
        verify(mIOnBackInvokedCallback).onBackStarted();

        // Check that back progress is dispatched.
        mController.onMotionEvent(
                MotionEvent.obtain(10, 0, MotionEvent.ACTION_MOVE, 100, 100, 0),
                BackEvent.EDGE_LEFT);
        ArgumentCaptor<BackEvent> backEventCaptor = ArgumentCaptor.forClass(BackEvent.class);
        verify(mIOnBackInvokedCallback).onBackProgressed(backEventCaptor.capture());
        assertEquals(animationTarget, backEventCaptor.getValue().getDepartingAnimationTarget());

        // Check that back invocation is dispatched.
        mController.setTriggerBack(true);   // Fake trigger back
        mController.onMotionEvent(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0),
                BackEvent.EDGE_LEFT);
        verify(mIOnBackInvokedCallback).onBackInvoked();
    }
}
