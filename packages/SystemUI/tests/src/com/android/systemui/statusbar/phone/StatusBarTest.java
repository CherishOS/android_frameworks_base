/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.StatusBarManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.ViewGroup.LayoutParams;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.NotificationEntryManager;
import com.android.systemui.statusbar.NotificationGutsManager;
import com.android.systemui.statusbar.NotificationListContainer;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLogger;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class StatusBarTest extends SysuiTestCase {

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private UnlockMethodCache mUnlockMethodCache;
    private KeyguardIndicationController mKeyguardIndicationController;
    private NotificationStackScrollLayout mStackScroller;
    private TestableStatusBar mStatusBar;
    private FakeMetricsLogger mMetricsLogger;
    private HeadsUpManager mHeadsUpManager;
    private NotificationData mNotificationData;
    private PowerManager mPowerManager;
    private SystemServicesProxy mSystemServicesProxy;
    private NotificationPanelView mNotificationPanelView;
    private ScrimController mScrimController;
    private IStatusBarService mBarService;
    private NotificationListener mNotificationListener;
    private NotificationLogger mNotificationLogger;
    private ArrayList<Entry> mNotificationList;
    private FingerprintUnlockController mFingerprintUnlockController;
    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private TestableNotificationEntryManager mEntryManager;
    private NotificationViewHierarchyManager mViewHierarchyManager;
    private VisualStabilityManager mVisualStabilityManager;

    @Before
    public void setup() throws Exception {
        mContext.setTheme(R.style.Theme_SystemUI_Light);
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(DeviceProvisionedController.class);
        mDependency.injectMockDependency(NotificationGroupManager.class);
        mDependency.injectMockDependency(NotificationGutsManager.class);
        mDependency.injectMockDependency(NotificationRemoteInputManager.class);
        mDependency.injectMockDependency(NotificationMediaManager.class);
        mDependency.injectMockDependency(ForegroundServiceController.class);
        mNotificationListener = mDependency.injectMockDependency(NotificationListener.class);
        mViewHierarchyManager = mDependency.injectMockDependency(
                NotificationViewHierarchyManager.class);
        mVisualStabilityManager = mDependency.injectMockDependency(VisualStabilityManager.class);
        mDependency.injectTestDependency(KeyguardMonitor.class, mock(KeyguardMonitorImpl.class));
        CommandQueue commandQueue = mock(CommandQueue.class);
        when(commandQueue.asBinder()).thenReturn(new Binder());
        mContext.putComponent(CommandQueue.class, commandQueue);
        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));
        mStatusBarKeyguardViewManager = mock(StatusBarKeyguardViewManager.class);
        mUnlockMethodCache = mock(UnlockMethodCache.class);
        mKeyguardIndicationController = mock(KeyguardIndicationController.class);
        mStackScroller = mock(NotificationStackScrollLayout.class);
        when(mStackScroller.generateLayoutParams(any())).thenReturn(new LayoutParams(0, 0));
        mMetricsLogger = new FakeMetricsLogger();
        mHeadsUpManager = mock(HeadsUpManager.class);
        mNotificationData = mock(NotificationData.class);
        mSystemServicesProxy = mock(SystemServicesProxy.class);
        mNotificationPanelView = mock(NotificationPanelView.class);
        when(mNotificationPanelView.getLayoutParams()).thenReturn(new LayoutParams(0, 0));
        mNotificationList = mock(ArrayList.class);
        mScrimController = mock(ScrimController.class);
        mFingerprintUnlockController = mock(FingerprintUnlockController.class);
        IPowerManager powerManagerService = mock(IPowerManager.class);
        HandlerThread handlerThread = new HandlerThread("TestThread");
        handlerThread.start();
        mPowerManager = new PowerManager(mContext, powerManagerService,
                new Handler(handlerThread.getLooper()));
        when(powerManagerService.isInteractive()).thenReturn(true);
        mBarService = mock(IStatusBarService.class);
        mNotificationLogger = new NotificationLogger(mNotificationListener, mDependency.get(
                UiOffloadThread.class));

        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);

        mNotificationListener = mDependency.get(NotificationListener.class);
        mNotificationLogger =  mDependency.get(NotificationLogger.class);
        mEntryManager = new TestableNotificationEntryManager(mMetricsLogger,
                mSystemServicesProxy, mPowerManager, mContext);

        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager, mUnlockMethodCache,
                mKeyguardIndicationController, mStackScroller, mHeadsUpManager,
                mPowerManager, mNotificationPanelView, mBarService, mNotificationListener,
                mNotificationLogger, mVisualStabilityManager, mViewHierarchyManager,
                mEntryManager, mScrimController, mFingerprintUnlockController);
        mStatusBar.mContext = mContext;
        mStatusBar.mComponents = mContext.getComponents();
        doAnswer(invocation -> {
            OnDismissAction onDismissAction = (OnDismissAction) invocation.getArguments()[0];
            onDismissAction.onDismiss();
            return null;
        }).when(mStatusBarKeyguardViewManager).dismissWithAction(any(), any(), anyBoolean());

        doAnswer(invocation -> {
            Runnable runnable = (Runnable) invocation.getArguments()[0];
            runnable.run();
            return null;
        }).when(mStatusBarKeyguardViewManager).addAfterKeyguardGoneRunnable(any());

        mEntryManager.setUpForTest(mStatusBar, mStackScroller, mStatusBar, mHeadsUpManager,
                mNotificationData);
        mNotificationLogger.setUpWithPresenter(mStatusBar, mStackScroller);

        when(mStackScroller.getActivatedChild()).thenReturn(null);
        TestableLooper.get(this).setMessageHandler(m -> {
            if (m.getCallback() == mStatusBar.mNotificationLogger.getVisibilityReporter()) {
                return false;
            }
            return true;
        });
    }

    @Test
    public void testSetBouncerShowing_noCrash() {
        mStatusBar.mCommandQueue = mock(CommandQueue.class);
        mStatusBar.setBouncerShowing(true);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showingAndOccluded() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(true);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_notShowing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void lockscreenStateMetrics_notShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(false);
        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden insecure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_notShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden secure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(false);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing insecure lockscreen showing",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_isShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing secure lockscreen showing log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowingBouncer() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mUnlockMethodCache.canSkipBouncer()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mUnlockMethodCache.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing bouncer log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.BOUNCER)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void onActivatedMetrics() {
        ActivatableNotificationView view =  mock(ActivatableNotificationView.class);
        mStatusBar.onActivated(view);

        MetricsAsserts.assertHasLog("missing lockscreen note tap log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.ACTION_LS_NOTE)
                        .setType(MetricsEvent.TYPE_ACTION));
    }

    @Test
    public void testShouldPeek_nonSuppressedGroupSummary() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertTrue(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedGroupSummary() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertFalse(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedScreenOn_dozing() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        mStatusBar.mDozing = true;
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(true);
        when(mNotificationData.shouldSuppressScreenOff(any())).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);

        assertTrue(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedScreenOn_noDoze() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        mStatusBar.mDozing = false;
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(true);
        when(mNotificationData.shouldSuppressScreenOff(any())).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        assertFalse(mEntryManager.shouldPeek(entry, sbn));
    }
    @Test
    public void testShouldPeek_suppressedScreenOff_dozing() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        mStatusBar.mDozing = true;
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOff(any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        assertFalse(mEntryManager.shouldPeek(entry, sbn));
    }

    @Test
    public void testShouldPeek_suppressedScreenOff_noDoze() {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationData.shouldFilterOut(any())).thenReturn(false);
        when(mSystemServicesProxy.isDreaming()).thenReturn(false);
        when(mNotificationData.getImportance(any())).thenReturn(IMPORTANCE_HIGH);

        mStatusBar.mDozing = false;
        when(mNotificationData.shouldSuppressScreenOn(any())).thenReturn(false);
        when(mNotificationData.shouldSuppressScreenOff(any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        assertTrue(mEntryManager.shouldPeek(entry, sbn));
    }


    @Test
    public void testLogHidden() {
        try {
            mStatusBar.handleVisibleToUserChanged(false);
            waitForUiOffloadThread();
            verify(mBarService, times(1)).onPanelHidden();
            verify(mBarService, never()).onPanelRevealed(anyBoolean(), anyInt());
        } catch (RemoteException e) {
            fail();
        }
    }

    @Test
    public void testPanelOpenForPeek() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(true);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            waitForUiOffloadThread();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 1);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            waitForUiOffloadThread();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(true, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndNoClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationData.getActiveNotifications()).thenReturn(mNotificationList);
        when(mNotificationList.size()).thenReturn(5);
        when(mNotificationPanelView.isFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            waitForUiOffloadThread();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testDisableExpandStatusBar() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        mStatusBar.setUserSetupForTest(true);
        when(mStatusBar.isDeviceProvisioned()).thenReturn(true);

        mStatusBar.disable(StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);
        verify(mNotificationPanelView).setQsExpansionEnabled(false);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelView, never()).expand(anyBoolean());
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelView, never()).expand(anyBoolean());

        mStatusBar.disable(StatusBarManager.DISABLE_NONE, StatusBarManager.DISABLE2_NONE, false);
        verify(mNotificationPanelView).setQsExpansionEnabled(true);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelView).expand(anyBoolean());
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelView).expand(anyBoolean());
    }

    @Test
    public void testDump_DoesNotCrash() {
        mStatusBar.dump(null, new PrintWriter(new ByteArrayOutputStream()), null);
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testUpdateKeyguardState_DoesNotCrash() {
        mStatusBar.mStatusBarWindow = mock(StatusBarWindowView.class);
        mStatusBar.mState = StatusBarState.KEYGUARD;
        mStatusBar.mDozeScrimController = mock(DozeScrimController.class);
        mStatusBar.mNotificationIconAreaController = mock(NotificationIconAreaController.class);
        mStatusBar.mLockscreenUserManager = mock(NotificationLockscreenUserManager.class);
        when(mStatusBar.mLockscreenUserManager.getCurrentProfiles()).thenReturn(
                new SparseArray<>());
        mStatusBar.updateKeyguardState(false, false);
    }

    @Test
    public void testFingerprintNotification_UpdatesScrims() {
        mStatusBar.mStatusBarWindowManager = mock(StatusBarWindowManager.class);
        mStatusBar.mDozeScrimController = mock(DozeScrimController.class);
        mStatusBar.notifyFpAuthModeChanged();
        verify(mScrimController).transitionTo(any(), any());
    }

    @Test
    public void testFingerprintUnlock_UpdatesScrims() {
        // Simulate unlocking from AoD with fingerprint.
        when(mFingerprintUnlockController.getMode())
                .thenReturn(FingerprintUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBar.updateScrimController();
        verify(mScrimController).transitionTo(eq(ScrimState.UNLOCKED), any());
    }

    static class TestableStatusBar extends StatusBar {
        public TestableStatusBar(StatusBarKeyguardViewManager man,
                UnlockMethodCache unlock, KeyguardIndicationController key,
                NotificationStackScrollLayout stack, HeadsUpManager hum,
                PowerManager pm, NotificationPanelView panelView,
                IStatusBarService barService, NotificationListener notificationListener,
                NotificationLogger notificationLogger,
                VisualStabilityManager visualStabilityManager,
                NotificationViewHierarchyManager viewHierarchyManager,
                TestableNotificationEntryManager entryManager, ScrimController scrimController,
                FingerprintUnlockController fingerprintUnlockController) {
            mStatusBarKeyguardViewManager = man;
            mUnlockMethodCache = unlock;
            mKeyguardIndicationController = key;
            mStackScroller = stack;
            mHeadsUpManager = hum;
            mPowerManager = pm;
            mNotificationPanel = panelView;
            mBarService = barService;
            mNotificationListener = notificationListener;
            mNotificationLogger = notificationLogger;
            mWakefulnessLifecycle = createAwakeWakefulnessLifecycle();
            mVisualStabilityManager = visualStabilityManager;
            mViewHierarchyManager = viewHierarchyManager;
            mEntryManager = entryManager;
            mScrimController = scrimController;
            mFingerprintUnlockController = fingerprintUnlockController;
        }

        private WakefulnessLifecycle createAwakeWakefulnessLifecycle() {
            WakefulnessLifecycle wakefulnessLifecycle = new WakefulnessLifecycle();
            wakefulnessLifecycle.dispatchStartedWakingUp();
            wakefulnessLifecycle.dispatchFinishedWakingUp();
            return wakefulnessLifecycle;
        }

        @Override
        protected void updateTheme() {
            // Do nothing for now, until we have more mocking and StatusBar is smaller.
        }

        public void setBarStateForTest(int state) {
            mState = state;
        }

        public void setUserSetupForTest(boolean userSetup) {
            mUserSetup = userSetup;
        }

    }

    private class TestableNotificationEntryManager extends NotificationEntryManager {

        public TestableNotificationEntryManager(MetricsLogger metricsLogger,
                SystemServicesProxy systemServicesProxy, PowerManager powerManager,
                Context context) {
            super(mDependency.get(NotificationLockscreenUserManager.class),
                    mDependency.get(NotificationGroupManager.class),
                    mDependency.get(NotificationGutsManager.class),
                    mDependency.get(NotificationRemoteInputManager.class),
                    mDependency.get(NotificationMediaManager.class),
                    mDependency.get(ForegroundServiceController.class),
                    mDependency.get(NotificationListener.class),
                    metricsLogger,
                    mDependency.get(DeviceProvisionedController.class),
                    mDependency.get(VisualStabilityManager.class),
                    mDependency.get(UiOffloadThread.class),
                    context);
            mSystemServicesProxy = systemServicesProxy;
            mPowerManager = powerManager;
        }

        public void setUpForTest(NotificationPresenter presenter,
                NotificationListContainer listContainer,
                Callback callback,
                HeadsUpManager headsUpManager,
                NotificationData notificationData) {
            super.setUpWithPresenter(presenter, listContainer, callback, headsUpManager);
            mNotificationData = notificationData;
            mUseHeadsUp = true;
        }
    }
}
