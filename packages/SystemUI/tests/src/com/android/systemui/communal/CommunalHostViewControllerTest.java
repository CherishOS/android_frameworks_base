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

package com.android.systemui.communal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalHostViewControllerTest extends SysuiTestCase {
    @Mock
    private CommunalStateController mCommunalStateController;

    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    @Mock
    private KeyguardStateController mKeyguardStateController;

    @Mock
    private StatusBarStateController mStatusBarStateController;

    @Mock
    private CommunalHostView mCommunalView;

    @Mock
    private DozeParameters mDozeParameters;

    @Mock
    private ScreenOffAnimationController mScreenOffAnimationController;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private CommunalHostViewController mController;

    @Mock
    private CommunalSource mCommunalSource;

    @Mock
    private View mChildView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mCommunalView.isAttachedToWindow()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);

        mController = new CommunalHostViewController(mFakeExecutor, mCommunalStateController,
                mKeyguardUpdateMonitor, mKeyguardStateController, mDozeParameters,
                mScreenOffAnimationController, mStatusBarStateController, mCommunalView);
        mController.init();
        mFakeExecutor.runAllReady();

        Mockito.clearInvocations(mCommunalView);
    }

    @Test
    public void testShow() {
        ArgumentCaptor<KeyguardStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);

        // Capture callback value for later use.
        verify(mKeyguardStateController).addCallback(callbackCapture.capture());

        // Verify the communal view is shown when the controller is initialized with keyguard
        // showing (see setup).
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.VISIBLE);

        // Trigger keyguard off to ensure visibility of communal view is changed accordingly.
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        callbackCapture.getValue().onKeyguardShowingChanged();
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.INVISIBLE);
    }

    @Test
    public void testHideOnOcclude() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> callbackCapture =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);

        // Capture callback value for later use.
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCapture.capture());

        // Establish a visible communal view.
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.VISIBLE);
        Mockito.clearInvocations(mCommunalView);

        // Occlude.
        Mockito.clearInvocations(mCommunalView);
        callbackCapture.getValue().onKeyguardOccludedChanged(true);
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.INVISIBLE);

        // Unocclude.
        Mockito.clearInvocations(mCommunalView);
        callbackCapture.getValue().onKeyguardOccludedChanged(false);
        mFakeExecutor.runAllReady();
        verify(mCommunalView).setVisibility(View.VISIBLE);
    }

    @Test
    public void testReportOcclusion() {
        // Ensure CommunalHostViewController reports view occluded when either the QS or Shade is
        // expanded.
        clearInvocations(mCommunalStateController);
        mController.updateShadeExpansion(0);
        verify(mCommunalStateController).setCommunalViewOccluded(false);
        clearInvocations(mCommunalStateController);
        mController.updateQsExpansion(.5f);
        verify(mCommunalStateController).setCommunalViewOccluded(true);
        clearInvocations(mCommunalStateController);
        mController.updateShadeExpansion(.7f);
        verify(mCommunalStateController).setCommunalViewOccluded(true);
        clearInvocations(mCommunalStateController);
        mController.updateShadeExpansion(0);
        verify(mCommunalStateController).setCommunalViewOccluded(true);
        clearInvocations(mCommunalStateController);
        mController.updateQsExpansion(0f);
        verify(mCommunalStateController).setCommunalViewOccluded(false);
        clearInvocations(mCommunalStateController);
    }

    @Test
    public void testCommunalStateControllerHideNotified() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> callbackCapture =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);

        // Capture callback value for later use.
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCapture.capture());

        // Establish a visible communal view.
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();

        // Occlude
        clearInvocations(mCommunalStateController);
        callbackCapture.getValue().onKeyguardOccludedChanged(true);
        mFakeExecutor.runAllReady();

        // Verify state controller is notified communal view is hidden.
        verify(mCommunalStateController).setCommunalViewShowing(false);
    }

    @Test
    public void testAlphaPropagation() {
        final float alpha = 0.8f;

        // Ensure alpha setting is propagated to children.
        when(mCommunalView.getChildCount()).thenReturn(1);
        when(mCommunalView.getChildAt(0)).thenReturn(mChildView);
        mController.setAlpha(alpha);
        verify(mChildView).setAlpha(alpha);
        verify(mCommunalView).setAlpha(alpha);
    }

    @Test
    public void testMultipleShowRequestSuppression() {
        // Ensure first request invokes source.
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        verify(mCommunalSource).requestCommunalView(any());
        clearInvocations(mCommunalSource);

        // Ensure subsequent identical request is suppressed
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        verify(mCommunalSource, never()).requestCommunalView(any());
    }

    @Test
    public void testNoShowInvocationOnBouncer() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> callbackCapture =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);

        // Capture callback value for later use.
        verify(mKeyguardUpdateMonitor).registerCallback(callbackCapture.capture());

        // Set source so it will be cleared if in invalid state.
        mController.show(new WeakReference<>(mCommunalSource));
        mFakeExecutor.runAllReady();
        clearInvocations(mCommunalStateController, mCommunalView);

        // Change bouncer to showing.
        callbackCapture.getValue().onKeyguardBouncerChanged(true);
        mFakeExecutor.runAllReady();

        // Verify that there were no requests to remove all child views or set the communal
        // state to not showing.
        verify(mCommunalStateController, never()).setCommunalViewShowing(eq(false));
        verify(mCommunalView, never()).removeAllViews();
    }
}
