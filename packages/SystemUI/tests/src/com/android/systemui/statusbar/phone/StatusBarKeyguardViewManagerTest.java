/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    @Mock
    private ViewMediatorCallback mViewMediatorCallback;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private ViewGroup mContainer;
    @Mock
    private NotificationPanelViewController mNotificationPanelView;
    @Mock
    private BiometricUnlockController mBiometrucUnlockController;
    @Mock
    private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private View mNotificationContainer;
    @Mock
    private KeyguardBypassController mBypassController;
    @Mock
    private KeyguardBouncer.Factory mKeyguardBouncerFactory;
    @Mock
    private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock
    private KeyguardBouncer mBouncer;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    private StatusBarKeyguardViewManager.AlternateAuthInterceptor mAlternateAuthInterceptor;
    @Mock
    private KeyguardMessageArea mKeyguardMessageArea;
    @Mock
    private ShadeController mShadeController;

    private WakefulnessLifecycle mWakefulnessLifecycle;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mKeyguardBouncerFactory.create(
                any(ViewGroup.class),
                any(KeyguardBouncer.BouncerExpansionCallback.class)))
                .thenReturn(mBouncer);
        when(mStatusBar.getBouncerContainer()).thenReturn(mContainer);
        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        mWakefulnessLifecycle = new WakefulnessLifecycle(
                getContext(),
                null,
                mock(DumpManager.class));
        mStatusBarKeyguardViewManager = new StatusBarKeyguardViewManager(
                getContext(),
                mViewMediatorCallback,
                mLockPatternUtils,
                mStatusBarStateController,
                mock(ConfigurationController.class),
                mKeyguardUpdateMonitor,
                mock(NavigationModeController.class),
                mock(DockManager.class),
                mock(NotificationShadeWindowController.class),
                mKeyguardStateController,
                mock(NotificationMediaManager.class),
                mKeyguardBouncerFactory,
                mWakefulnessLifecycle,
                mUnlockedScreenOffAnimationController,
                mKeyguardMessageAreaFactory,
                () -> mShadeController);
        mStatusBarKeyguardViewManager.registerStatusBar(
                mStatusBar,
                mNotificationPanelView,
                new PanelExpansionStateManager(),
                mBiometrucUnlockController,
                mNotificationContainer,
                mBypassController);
        mStatusBarKeyguardViewManager.show(null);
    }

    @Test
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {};
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                false /* afterKeyguardGone */);
        verify(mBouncer).showWithDismissAction(eq(action), eq(cancelAction));
    }

    @Test
    public void showBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mBouncer.isSecure()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
        verify(mBouncer, never()).show(anyBoolean());
    }

    @Test
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
        verify(mBouncer).show(anyBoolean(), eq(true));
    }

    @Test
    public void onPanelExpansionChanged_neverHidesFullscreenBouncer() {
        // TODO: StatusBar should not be here, mBouncer.isFullscreenBouncer() should do the same.
        when(mStatusBar.isFullScreenUserSwitcherState()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_VISIBLE));
    }

    @Test
    public void onPanelExpansionChanged_neverHidesScrimmedBouncer() {
        when(mBouncer.isShowing()).thenReturn(true);
        when(mBouncer.isScrimmed()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_VISIBLE));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        when(mNotificationPanelView.isUnlockHintRunning()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer).setExpansion(eq(KeyguardBouncer.EXPANSION_HIDDEN));
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncer() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer).show(eq(false), eq(false));

        // But not when it's already visible
        reset(mBouncer);
        when(mBouncer.isShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer, never()).show(eq(false), eq(false));

        // Or animating away
        reset(mBouncer);
        when(mBouncer.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer, never()).show(eq(false), eq(false));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animate */);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ 0.5f,
                /* expanded= */ false,
                /* tracking= */ true);
        verify(mBouncer, never()).setExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenWakeAndUnlock() {
        when(mBiometrucUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                /* expanded= */ true,
                /* tracking= */ false);
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenLaunchingApp() {
        when(mStatusBar.isInLaunchTransition()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                /* fraction= */ KeyguardBouncer.EXPANSION_VISIBLE,
                /* expanded= */ true,
                /* tracking= */ false);
        verify(mBouncer, never()).setExpansion(anyFloat());
    }

    @Test
    public void setOccluded_animatesPanelExpansion_onlyIfBouncerHidden() {
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mStatusBar).animateKeyguardUnoccluding();

        when(mBouncer.isShowing()).thenReturn(true);
        clearInvocations(mStatusBar);
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, true /* animated */);
        verify(mStatusBar, never()).animateKeyguardUnoccluding();
    }

    @Test
    public void setOccluded_onKeyguardOccludedChangedCalledCorrectly() {
        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor).onKeyguardOccludedChanged(false);

        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor, never()).onKeyguardOccludedChanged(anyBoolean());

        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor).onKeyguardOccludedChanged(true);

        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor, never()).onKeyguardOccludedChanged(anyBoolean());
    }

    @Test
    public void setOccluded_isInLaunchTransition_onKeyguardOccludedChangedCalled() {
        when(mStatusBar.isInLaunchTransition()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor).onKeyguardOccludedChanged(true);
    }

    @Test
    public void setOccluded_isLaunchingActivityOverLockscreen_onKeyguardOccludedChangedCalled() {
        when(mStatusBar.isLaunchingActivityOverLockscreen()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardUpdateMonitor).onKeyguardOccludedChanged(true);
    }

    @Test
    public void testHiding_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hideBouncer(true);
        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    public void testHiding_doesntCancelWhenShowing() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(action, cancelAction,
                true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action).onDismiss();
        verify(cancelAction, never()).run();
    }

    @Test
    public void testShowing_whenAlternateAuthShowing() {
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        assertTrue("Is showing not accurate when alternative auth showing",
                mStatusBarKeyguardViewManager.isShowing());
    }

    @Test
    public void testWillBeShowing_whenAlternateAuthShowing() {
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        assertTrue("Is or will be showing not accurate when alternative auth showing",
                mStatusBarKeyguardViewManager.bouncerIsOrWillBeShowing());
    }

    @Test
    public void testHideAltAuth_onShowBouncer() {
        // GIVEN alt auth is showing
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mAlternateAuthInterceptor.isShowingAlternateAuthBouncer()).thenReturn(true);
        reset(mAlternateAuthInterceptor);

        // WHEN showBouncer is called
        mStatusBarKeyguardViewManager.showBouncer(true);

        // THEN alt bouncer should be hidden
        verify(mAlternateAuthInterceptor).hideAlternateAuthBouncer();
    }

    @Test
    public void testShowAltAuth_unlockingWithBiometricNotAllowed() {
        // GIVEN alt auth exists, unlocking with biometric isn't allowed
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(false);

        // WHEN showGenericBouncer is called
        final boolean scrimmed = true;
        mStatusBarKeyguardViewManager.showGenericBouncer(scrimmed);

        // THEN regular bouncer is shown
        verify(mBouncer).show(anyBoolean(), eq(scrimmed));
        verify(mAlternateAuthInterceptor, never()).showAlternateAuthBouncer();
    }

    @Test
    public void testShowAltAuth_unlockingWithBiometricAllowed() {
        // GIVEN alt auth exists, unlocking with biometric is allowed
        mStatusBarKeyguardViewManager.setAlternateAuthInterceptor(mAlternateAuthInterceptor);
        when(mBouncer.isShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true);

        // WHEN showGenericBouncer is called
        mStatusBarKeyguardViewManager.showGenericBouncer(true);

        // THEN alt auth bouncer is shown
        verify(mAlternateAuthInterceptor).showAlternateAuthBouncer();
        verify(mBouncer, never()).show(anyBoolean(), anyBoolean());
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mBouncer).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mBouncer).updateKeyguardPosition(1.0f);
    }
}
