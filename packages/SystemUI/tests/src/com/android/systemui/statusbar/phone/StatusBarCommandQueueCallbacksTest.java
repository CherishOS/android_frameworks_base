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

package com.android.systemui.statusbar.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.os.PowerManager;
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class StatusBarCommandQueueCallbacksTest extends SysuiTestCase {
    @Mock private StatusBar mStatusBar;
    @Mock private ShadeController mShadeController;
    @Mock private CommandQueue mCommandQueue;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private LegacySplitScreen mLegacySplitScreen;
    @Mock private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final MetricsLogger mMetricsLogger = new FakeMetricsLogger();
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private AssistManager mAssistManager;
    @Mock private DozeServiceHost mDozeServiceHost;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private PowerManager mPowerManager;
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private Vibrator mVibrator;
    @Mock private LightBarController mLightBarController;

    StatusBarCommandQueueCallbacks mSbcqCallbacks;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mSbcqCallbacks = new StatusBarCommandQueueCallbacks(
                mStatusBar,
                mContext,
                mContext.getResources(),
                mShadeController,
                mCommandQueue,
                mNotificationPanelViewController,
                Optional.of(mLegacySplitScreen),
                mRemoteInputQuickSettingsDisabler,
                mMetricsLogger,
                mKeyguardUpdateMonitor,
                mKeyguardStateController,
                mHeadsUpManager,
                mWakefulnessLifecycle,
                mDeviceProvisionedController,
                mStatusBarKeyguardViewManager,
                mAssistManager,
                mDozeServiceHost,
                mStatusBarStateController,
                mNotificationShadeWindowView,
                mNotificationStackScrollLayoutController,
                new StatusBarHideIconsForBouncerManager(
                        mCommandQueue, new FakeExecutor(new FakeSystemClock()), new DumpManager()),
                mPowerManager,
                mVibratorHelper,
                Optional.of(mVibrator),
                mLightBarController,
                new DisableFlagsLogger(),
                DEFAULT_DISPLAY);

        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        when(mRemoteInputQuickSettingsDisabler.adjustDisableFlags(anyInt()))
                .thenAnswer((Answer<Integer>) invocation -> invocation.getArgument(0));
    }

    @Test
    public void testDisableNotificationShade() {
        when(mStatusBar.getDisabled1()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mStatusBar.getDisabled2()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mCommandQueue.panelsEnabled()).thenReturn(false);
        mSbcqCallbacks.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);

        verify(mStatusBar).updateQsExpansionEnabled();
        verify(mShadeController).animateCollapsePanels();

        // Trying to open it does nothing.
        mSbcqCallbacks.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController, never()).expandWithoutQs();
        mSbcqCallbacks.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());
    }

    @Test
    public void testEnableNotificationShade() {
        when(mStatusBar.getDisabled1()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mStatusBar.getDisabled2()).thenReturn(StatusBarManager.DISABLE2_NOTIFICATION_SHADE);
        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mSbcqCallbacks.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NONE, false);
        verify(mStatusBar).updateQsExpansionEnabled();
        verify(mShadeController, never()).animateCollapsePanels();

        // Can now be opened.
        mSbcqCallbacks.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController).expandWithoutQs();
        mSbcqCallbacks.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController).expandWithQs();
    }

    @Test
    public void testSuppressAmbientDisplay_suppress() {
        mSbcqCallbacks.suppressAmbientDisplay(true);
        verify(mDozeServiceHost).setDozeSuppressed(true);
    }

    @Test
    public void testSuppressAmbientDisplay_unsuppress() {
        mSbcqCallbacks.suppressAmbientDisplay(false);
        verify(mDozeServiceHost).setDozeSuppressed(false);
    }


}
