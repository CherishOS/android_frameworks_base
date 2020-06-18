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

package com.android.systemui.globalactions;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.service.dreams.IDreamManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.FeatureFlagUtils;
import android.view.IWindowManager;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.controls.controller.ControlsController;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.settings.CurrentUserContextTracker;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class GlobalActionsDialogTest extends SysuiTestCase {
    private GlobalActionsDialog mGlobalActionsDialog;

    @Mock private GlobalActions.GlobalActionsManager mWindowManagerFuncs;
    @Mock private AudioManager mAudioManager;
    @Mock private IDreamManager mDreamManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private ContentResolver mContentResolver;
    @Mock private Resources mResources;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private UserManager mUserManager;
    @Mock private TrustManager mTrustManager;
    @Mock private IActivityManager mActivityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private NotificationShadeDepthController mDepthController;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private ControlsUiController mControlsUiController;
    @Mock private IWindowManager mWindowManager;
    @Mock private Executor mBackgroundExecutor;
    @Mock private ControlsListingController mControlsListingController;
    @Mock private ControlsController mControlsController;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private RingerModeTracker mRingerModeTracker;
    @Mock private RingerModeLiveData mRingerModeLiveData;
    @Mock private SysUiState mSysUiState;
    @Mock GlobalActionsPanelPlugin mWalletPlugin;
    @Mock GlobalActionsPanelPlugin.PanelViewController mWalletController;
    @Mock private Handler mHandler;
    @Mock private CurrentUserContextTracker mCurrentUserContextTracker;
    private ControlsComponent mControlsComponent;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mCurrentUserContextTracker.getCurrentUserContext()).thenReturn(mContext);
        mControlsComponent = new ControlsComponent(
                true,
                () -> mControlsController,
                () -> mControlsUiController,
                () -> mControlsListingController
        );

        mGlobalActionsDialog = new GlobalActionsDialog(mContext,
                mWindowManagerFuncs,
                mAudioManager,
                mDreamManager,
                mDevicePolicyManager,
                mLockPatternUtils,
                mBroadcastDispatcher,
                mConnectivityManager,
                mTelephonyManager,
                mContentResolver,
                null,
                mResources,
                mConfigurationController,
                mActivityStarter,
                mKeyguardStateController,
                mUserManager,
                mTrustManager,
                mActivityManager,
                null,
                mMetricsLogger,
                mDepthController,
                mColorExtractor,
                mStatusBarService,
                mNotificationShadeWindowController,
                mWindowManager,
                mBackgroundExecutor,
                mUiEventLogger,
                mRingerModeTracker,
                mSysUiState,
                mHandler,
                mControlsComponent,
                mCurrentUserContextTracker
        );
        mGlobalActionsDialog.setZeroDialogPressDelayForTesting();

        ColorExtractor.GradientColors backdropColors = new ColorExtractor.GradientColors();
        backdropColors.setMainColor(Color.BLACK);
        when(mColorExtractor.getNeutralColors()).thenReturn(backdropColors);
        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);
    }

    @Test
    public void testShouldLogShow() {
        mGlobalActionsDialog.onShow(null);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    @Test
    public void testShouldLogDismiss() {
        mGlobalActionsDialog.onDismiss(mGlobalActionsDialog.mDialog);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_POWER_MENU_CLOSE);
    }

    @Test
    public void testShouldLogBugreportPress() throws InterruptedException {
        GlobalActionsDialog.BugReportAction bugReportAction =
                mGlobalActionsDialog.makeBugReportActionForTesting();
        bugReportAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_BUGREPORT_PRESS);
    }

    @Test
    public void testShouldLogBugreportLongPress() {
        GlobalActionsDialog.BugReportAction bugReportAction =
                mGlobalActionsDialog.makeBugReportActionForTesting();
        bugReportAction.onLongPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
    }

    @Test
    public void testShouldLogEmergencyDialerPress() {
        GlobalActionsDialog.EmergencyDialerAction emergencyDialerAction =
                mGlobalActionsDialog.makeEmergencyDialerActionForTesting();
        emergencyDialerAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
    }

    @Test
    public void testShouldLogScreenshotPress() {
        GlobalActionsDialog.ScreenshotAction screenshotAction =
                mGlobalActionsDialog.makeScreenshotActionForTesting();
        screenshotAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_SCREENSHOT_PRESS);
    }

    @Test
    public void testShouldLogScreenshotLongPress() {
        FeatureFlagUtils.setEnabled(mContext, FeatureFlagUtils.SCREENRECORD_LONG_PRESS, true);
        GlobalActionsDialog.ScreenshotAction screenshotAction =
                mGlobalActionsDialog.makeScreenshotActionForTesting();
        screenshotAction.onLongPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_SCREENSHOT_LONG_PRESS);
    }

    private void verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent event) {
        mTestableLooper.processAllMessages();
        verify(mUiEventLogger, times(1))
                .log(event);
    }

    @Test
    public void testCreateActionItems_maxThree_noOverflow() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow 3 items to be shown
        doReturn(3).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(3, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
        assertEquals(0, mGlobalActionsDialog.mPowerItems.size());
    }

    @Test
    public void testCreateActionItems_maxThree_condensePower() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow 3 items to be shown
        doReturn(3).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        // make sure lockdown action will be shown
        doReturn(true).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(3, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
        assertEquals(2, mGlobalActionsDialog.mPowerItems.size());

        // PowerOptionsAction should appear immediately after the Emergency action

        GlobalActionsDialog.Action firstItem = mGlobalActionsDialog.mItems.get(0);
        GlobalActionsDialog.Action secondItem = mGlobalActionsDialog.mItems.get(1);

        assertTrue(firstItem instanceof GlobalActionsDialog.EmergencyAction);
        assertTrue(secondItem instanceof GlobalActionsDialog.PowerOptionsAction);
    }

    @Test
    public void testCreateActionItems_maxThree_condensePower_noEmergency() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow 3 items to be shown
        doReturn(3).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // make sure lockdown action will be shown
        doReturn(true).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_SCREENSHOT,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(3, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
        assertEquals(2, mGlobalActionsDialog.mPowerItems.size());

        // When Emergency isn't used, PowerOptionsAction should be first

        GlobalActionsDialog.Action firstItem = mGlobalActionsDialog.mItems.get(0);
        GlobalActionsDialog.Action secondItem = mGlobalActionsDialog.mItems.get(1);

        assertTrue(firstItem instanceof GlobalActionsDialog.PowerOptionsAction);
        assertTrue(secondItem instanceof GlobalActionsDialog.ScreenshotAction);
    }

    @Test
    public void testCreateActionItems_maxFour_condensePower() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow 3 items to be shown
        doReturn(4).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // make sure lockdown action will be shown
        doReturn(true).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_SCREENSHOT
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(4, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
        assertEquals(2, mGlobalActionsDialog.mPowerItems.size());

        // with four items, make sure power still shows up immediately after Emergency
        GlobalActionsDialog.Action firstItem = mGlobalActionsDialog.mItems.get(0);
        GlobalActionsDialog.Action secondItem = mGlobalActionsDialog.mItems.get(1);

        assertTrue(firstItem instanceof GlobalActionsDialog.EmergencyAction);
        assertTrue(secondItem instanceof GlobalActionsDialog.PowerOptionsAction);
    }

    @Test
    public void testCreateActionItems_maxThree_doNotCondensePower() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow 3 items to be shown
        doReturn(3).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // make sure lockdown action will be shown
        doReturn(true).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_SCREENSHOT,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(3, mGlobalActionsDialog.mItems.size());
        assertEquals(1, mGlobalActionsDialog.mOverflowItems.size());
        assertEquals(0, mGlobalActionsDialog.mPowerItems.size());
    }

    @Test
    public void testCreateActionItems_maxAny() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow any number of power menu items to be shown
        doReturn(Integer.MAX_VALUE).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // ensure items are not blocked by keyguard or device provisioning
        doReturn(true).when(mGlobalActionsDialog).shouldShowAction(any());
        // make sure lockdown action will be shown
        doReturn(true).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(4, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
    }

    @Test
    public void testCreateActionItems_maxThree_itemNotShown() {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        // allow only 3 items to be shown
        doReturn(3).when(mGlobalActionsDialog).getMaxShownPowerItems();
        // make sure lockdown action will NOT be shown
        doReturn(false).when(mGlobalActionsDialog).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                // lockdown action not allowed
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
        mGlobalActionsDialog.createActionItems();

        assertEquals(3, mGlobalActionsDialog.mItems.size());
        assertEquals(0, mGlobalActionsDialog.mOverflowItems.size());
    }

    @Test
    public void testShouldShowLockScreenMessage() throws RemoteException {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        mGlobalActionsDialog.mDialog = null;
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        when(mActivityManager.getCurrentUser()).thenReturn(newUserInfo());
        when(mLockPatternUtils.getStrongAuthForUser(anyInt())).thenReturn(STRONG_AUTH_NOT_REQUIRED);
        mGlobalActionsDialog.mShowLockScreenCardsAndControls = false;
        setupDefaultActions();
        when(mWalletPlugin.onPanelShown(any(), anyBoolean())).thenReturn(mWalletController);
        when(mWalletController.getPanelContent()).thenReturn(new FrameLayout(mContext));

        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);

        GlobalActionsDialog.ActionsDialog dialog = mGlobalActionsDialog.mDialog;
        assertThat(dialog).isNotNull();
        assertThat(dialog.mLockMessageContainer.getVisibility()).isEqualTo(View.VISIBLE);

        // Dismiss the dialog so that it does not pollute other tests
        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);
    }

    @Test
    public void testShouldNotShowLockScreenMessage_whenWalletOrControlsShownOnLockScreen()
            throws RemoteException {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        mGlobalActionsDialog.mDialog = null;
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        when(mActivityManager.getCurrentUser()).thenReturn(newUserInfo());
        when(mLockPatternUtils.getStrongAuthForUser(anyInt())).thenReturn(STRONG_AUTH_NOT_REQUIRED);
        mGlobalActionsDialog.mShowLockScreenCardsAndControls = true;
        setupDefaultActions();
        when(mWalletPlugin.onPanelShown(any(), anyBoolean())).thenReturn(mWalletController);
        when(mWalletController.getPanelContent()).thenReturn(new FrameLayout(mContext));

        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);

        GlobalActionsDialog.ActionsDialog dialog = mGlobalActionsDialog.mDialog;
        assertThat(dialog).isNotNull();
        assertThat(dialog.mLockMessageContainer.getVisibility()).isEqualTo(View.GONE);

        // Dismiss the dialog so that it does not pollute other tests
        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);
    }

    @Test
    public void testShouldNotShowLockScreenMessage_whenControlsAndWalletBothDisabled()
            throws RemoteException {
        mGlobalActionsDialog = spy(mGlobalActionsDialog);
        mGlobalActionsDialog.mDialog = null;
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        when(mActivityManager.getCurrentUser()).thenReturn(newUserInfo());
        when(mLockPatternUtils.getStrongAuthForUser(anyInt())).thenReturn(STRONG_AUTH_NOT_REQUIRED);
        mGlobalActionsDialog.mShowLockScreenCardsAndControls = true;
        setupDefaultActions();
        when(mWalletPlugin.onPanelShown(any(), anyBoolean())).thenReturn(mWalletController);
        when(mWalletController.getPanelContent()).thenReturn(null);
        when(mControlsUiController.getAvailable()).thenReturn(false);

        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);

        GlobalActionsDialog.ActionsDialog dialog = mGlobalActionsDialog.mDialog;
        assertThat(dialog).isNotNull();
        assertThat(dialog.mLockMessageContainer.getVisibility()).isEqualTo(View.GONE);

        // Dismiss the dialog so that it does not pollute other tests
        mGlobalActionsDialog.showOrHideDialog(false, true, mWalletPlugin);
    }

    private UserInfo newUserInfo() {
        return new UserInfo(0, null, null, UserInfo.FLAG_PRIMARY, null);
    }

    private void setupDefaultActions() {
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_SCREENSHOT,
        };
        doReturn(actions).when(mGlobalActionsDialog).getDefaultActions();
    }
}
