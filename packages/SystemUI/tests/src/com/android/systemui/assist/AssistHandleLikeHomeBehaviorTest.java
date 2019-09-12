/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.shared.system.QuickStepContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AssistHandleLikeHomeBehaviorTest extends SysuiTestCase {

    private AssistHandleLikeHomeBehavior mAssistHandleLikeHomeBehavior;

    @Mock private WakefulnessLifecycle mMockWakefulnessLifecycle;
    @Mock private SysUiState mMockSysUiState;
    @Mock private AssistHandleCallbacks mMockAssistHandleCallbacks;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mAssistHandleLikeHomeBehavior = new AssistHandleLikeHomeBehavior(
                () -> mMockWakefulnessLifecycle, () -> mMockSysUiState);
    }

    @Test
    public void onModeActivated_beginsObserving() {
        // Arrange

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockWakefulnessLifecycle).getWakefulness();
        verify(mMockWakefulnessLifecycle).addObserver(any(WakefulnessLifecycle.Observer.class));
        verify(mMockSysUiState).addCallback(any(SysUiState.SysUiStateCallback.class));
        verifyNoMoreInteractions(mMockWakefulnessLifecycle, mMockSysUiState);
    }

    @Test
    public void onModeActivated_showsHandlesWhenAwake() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeActivated_hidesHandlesWhenNotAwake() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP);

        // Act
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(mMockAssistHandleCallbacks);
    }

    @Test
    public void onModeDeactivated_stopsObserving() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        ArgumentCaptor<SysUiState.SysUiStateCallback> sysUiStateCallback =
                ArgumentCaptor.forClass(SysUiState.SysUiStateCallback.class);
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        verify(mMockSysUiState).addCallback(sysUiStateCallback.capture());
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onModeDeactivated();

        // Assert
        verify(mMockWakefulnessLifecycle).removeObserver(eq(observer.getValue()));
        verify(mMockSysUiState).removeCallback(eq(sysUiStateCallback.getValue()));
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistantGesturePerformed_doesNothing() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onAssistantGesturePerformed();

        // Assert
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onAssistHandlesRequested_doesNothing() {
        // Arrange
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        mAssistHandleLikeHomeBehavior.onAssistHandlesRequested();

        // Assert
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onWake_handlesShow() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onStartedWakingUp();

        // Assert
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onFinishedWakingUp();

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onSleep_handlesHide() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockWakefulnessLifecycle).addObserver(observer.capture());
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onStartedGoingToSleep();

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        observer.getValue().onFinishedGoingToSleep();

        // Assert
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onHomeHandleHide_handlesHide() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<SysUiState.SysUiStateCallback> sysUiStateCallback =
                ArgumentCaptor.forClass(SysUiState.SysUiStateCallback.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockSysUiState).addCallback(sysUiStateCallback.capture());
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);

        // Assert
        verify(mMockAssistHandleCallbacks).hide();
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }

    @Test
    public void onHomeHandleUnhide_handlesShow() {
        // Arrange
        when(mMockWakefulnessLifecycle.getWakefulness())
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        ArgumentCaptor<SysUiState.SysUiStateCallback> sysUiStateCallback =
                ArgumentCaptor.forClass(SysUiState.SysUiStateCallback.class);
        mAssistHandleLikeHomeBehavior.onModeActivated(mContext, mMockAssistHandleCallbacks);
        verify(mMockSysUiState).addCallback(sysUiStateCallback.capture());
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);
        reset(mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);

        // Act
        sysUiStateCallback.getValue().onSystemUiStateChanged(
                ~QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN);

        // Assert
        verify(mMockAssistHandleCallbacks).showAndStay();
        verifyNoMoreInteractions(
                mMockWakefulnessLifecycle, mMockSysUiState, mMockAssistHandleCallbacks);
    }
}
