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

package com.android.wm.shell.onehanded;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.IOverlayManager;
import android.os.Handler;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.util.ArrayMap;
import android.view.Display;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerImpl;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class OneHandedControllerTest extends OneHandedTestCase {
    Display mDisplay;
    OneHandedController mOneHandedController;
    OneHandedTimeoutHandler mTimeoutHandler;

    @Mock
    DisplayController mMockDisplayController;
    @Mock
    OneHandedBackgroundPanelOrganizer mMockBackgroundOrganizer;
    @Mock
    OneHandedDisplayAreaOrganizer mMockDisplayAreaOrganizer;
    @Mock
    OneHandedTouchHandler mMockTouchHandler;
    @Mock
    OneHandedTutorialHandler mMockTutorialHandler;
    @Mock
    OneHandedGestureHandler mMockGestureHandler;
    @Mock
    OneHandedTimeoutHandler mMockTimeoutHandler;
    @Mock
    OneHandedUiEventLogger mMockUiEventLogger;
    @Mock
    IOverlayManager mMockOverlayManager;
    @Mock
    TaskStackListenerImpl mMockTaskStackListener;
    @Mock
    ShellExecutor mMockShellMainExecutor;
    @Mock
    SurfaceControl mMockLeash;
    @Mock
    Handler mMockShellMainHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDisplay = mContext.getDisplay();
        mTimeoutHandler = Mockito.spy(new OneHandedTimeoutHandler(mMockShellMainExecutor));

        when(mMockDisplayController.getDisplay(anyInt())).thenReturn(mDisplay);
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        when(mMockDisplayAreaOrganizer.getDisplayAreaTokenMap()).thenReturn(new ArrayMap<>());
        when(mMockBackgroundOrganizer.getBackgroundSurface()).thenReturn(mMockLeash);

        OneHandedController oneHandedController = new OneHandedController(
                mContext,
                mMockDisplayController,
                mMockBackgroundOrganizer,
                mMockDisplayAreaOrganizer,
                mMockTouchHandler,
                mMockTutorialHandler,
                mMockGestureHandler,
                mTimeoutHandler,
                mMockUiEventLogger,
                mMockOverlayManager,
                mMockTaskStackListener,
                mMockShellMainExecutor,
                mMockShellMainHandler);
        mOneHandedController = Mockito.spy(oneHandedController);
    }

    @Test
    public void testDefaultShouldNotInOneHanded() {
        final OneHandedAnimationController animationController = new OneHandedAnimationController(
                mContext);
        OneHandedDisplayAreaOrganizer displayAreaOrganizer = new OneHandedDisplayAreaOrganizer(
                mContext, mMockDisplayController, animationController, mMockTutorialHandler,
                mMockBackgroundOrganizer, mMockShellMainExecutor);

        assertThat(displayAreaOrganizer.isInOneHanded()).isFalse();
    }

    @Test
    public void testEnabledNoRegisterAndUnregisterInSameCall() {
        mOneHandedController.setOneHandedEnabled(true);

        verify(mMockDisplayAreaOrganizer).registerOrganizer(anyInt());
    }

    @Test
    public void testDisabledNoRegisterAndUnregisterInSameCall() {
        mOneHandedController.setOneHandedEnabled(false);

        verify(mMockDisplayAreaOrganizer, never()).registerOrganizer(anyInt());
    }

    @Test
    public void testStartOneHanded() {
        mOneHandedController.setOneHandedEnabled(true);
        mOneHandedController.startOneHanded();

        verify(mMockDisplayAreaOrganizer).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testStopOneHanded() {
        when(mMockDisplayAreaOrganizer.isInOneHanded()).thenReturn(false);
        mOneHandedController.setOneHandedEnabled(true);
        mOneHandedController.stopOneHanded();

        verify(mMockDisplayAreaOrganizer, never()).scheduleOffset(anyInt(), anyInt());
    }

    @Test
    public void testRegisterTransitionCallbackAfterInit() {
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTouchHandler);
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockGestureHandler);
        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(mMockTutorialHandler);
    }

    @Test
    public void testRegisterTransitionCallback() {
        OneHandedTransitionCallback callback = new OneHandedTransitionCallback() {};
        mOneHandedController.registerTransitionCallback(callback);

        verify(mMockDisplayAreaOrganizer).registerTransitionCallback(callback);
    }


    @Test
    public void testStopOneHanded_shouldRemoveTimer() {
        mOneHandedController.stopOneHanded();

        verify(mTimeoutHandler).removeTimer();
    }

    @Test
    public void testUpdateIsEnabled() {
        final boolean enabled = true;
        mOneHandedController.setOneHandedEnabled(enabled);

        verify(mMockTouchHandler, atLeastOnce()).onOneHandedEnabled(enabled);
    }

    @Test
    public void testUpdateSwipeToNotificationIsEnabled() {
        final boolean enabled = true;
        mOneHandedController.setSwipeToNotificationEnabled(enabled);

        verify(mMockGestureHandler, atLeastOnce()).onOneHandedEnabled(enabled);
    }

    @Ignore("b/167943723, refactor it and fix it")
    @Test
    public void tesSettingsObserver_updateTapAppToExit() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, 1);

        verify(mOneHandedController).setTaskChangeToExit(true);
    }

    @Ignore("b/167943723, refactor it and fix it")
    @Test
    public void tesSettingsObserver_updateEnabled() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);

        verify(mOneHandedController).setOneHandedEnabled(true);
    }

    @Ignore("b/167943723, refactor it and fix it")
    @Test
    public void tesSettingsObserver_updateTimeout() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);

        verify(mMockTimeoutHandler).setTimeout(
                OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    @Ignore("b/167943723, refactor it and fix it")
    @Test
    public void tesSettingsObserver_updateSwipeToNotification() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED, 1);

        verify(mOneHandedController).setSwipeToNotificationEnabled(true);
    }
}
