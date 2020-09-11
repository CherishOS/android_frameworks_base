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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class VisualStabilityCoordinatorTest extends SysuiTestCase {

    private VisualStabilityCoordinator mCoordinator;

    // captured listeners and pluggables:
    private NotifCollectionListener mCollectionListener;

    @Mock private NotifPipeline mNotifPipeline;
    @Mock private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private Pluggable.PluggableListener<NotifStabilityManager> mInvalidateListener;
    @Mock private HeadsUpManager mHeadsUpManager;

    @Captor private ArgumentCaptor<WakefulnessLifecycle.Observer> mWakefulnessObserverCaptor;
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mSBStateListenerCaptor;
    @Captor private ArgumentCaptor<NotifStabilityManager> mNotifStabilityManagerCaptor;
    @Captor private ArgumentCaptor<NotifCollectionListener> mNotifCollectionListenerCaptor;

    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);

    private WakefulnessLifecycle.Observer mWakefulnessObserver;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private NotifStabilityManager mNotifStabilityManager;
    private NotificationEntry mEntry;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCoordinator = new VisualStabilityCoordinator(
                mHeadsUpManager,
                mWakefulnessLifecycle,
                mStatusBarStateController,
                mFakeExecutor);

        mCoordinator.attach(mNotifPipeline);

        // capture arguments:
        verify(mWakefulnessLifecycle).addObserver(mWakefulnessObserverCaptor.capture());
        mWakefulnessObserver = mWakefulnessObserverCaptor.getValue();

        verify(mStatusBarStateController).addCallback(mSBStateListenerCaptor.capture());
        mStatusBarStateListener = mSBStateListenerCaptor.getValue();

        verify(mNotifPipeline).setVisualStabilityManager(mNotifStabilityManagerCaptor.capture());
        mNotifStabilityManager = mNotifStabilityManagerCaptor.getValue();
        mNotifStabilityManager.setInvalidationListener(mInvalidateListener);

        mEntry = new NotificationEntryBuilder()
                .setPkg("testPkg1")
                .build();

        when(mHeadsUpManager.isAlerting(mEntry.getKey())).thenReturn(false);
    }

    @Test
    public void testScreenOff_groupAndSectionChangesAllowed() {
        // GIVEN screen is off, panel isn't expanded and device isn't pulsing
        setScreenOn(false);
        setPanelExpanded(false);
        setPulsing(false);

        // THEN group changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are allowed
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPanelNotExpanded_groupAndSectionChangesAllowed() {
        // GIVEN screen is on but the panel isn't expanded and device isn't pulsing
        setScreenOn(true);
        setPanelExpanded(false);
        setPulsing(false);

        // THEN group changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are allowed
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPanelExpanded_groupAndSectionChangesNotAllowed() {
        // GIVEN the panel true expanded and device isn't pulsing
        setScreenOn(true);
        setPanelExpanded(true);
        setPulsing(false);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPulsing_screenOff_groupAndSectionChangesNotAllowed() {
        // GIVEN the device is pulsing and screen is off
        setScreenOn(false);
        setPulsing(true);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testPulsing_panelNotExpanded_groupAndSectionChangesNotAllowed() {
        // GIVEN the device is pulsing and screen is off with the panel not expanded
        setScreenOn(false);
        setPanelExpanded(false);
        setPulsing(true);

        // THEN group changes are NOT allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are NOT allowed
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testOverrideReorderingSuppression_onlySectionChangesAllowed() {
        // GIVEN section changes typically wouldn't be allowed because the panel is expanded and
        // we're not pulsing
        setScreenOn(true);
        setPanelExpanded(true);
        setPulsing(true);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN group changes aren't allowed
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // THEN section changes are allowed for this notification but not other notifications
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(
                new NotificationEntryBuilder()
                        .setPkg("testPkg2")
                        .build()));
    }

    @Test
    public void testTemporarilyAllowSectionChanges_callsInvalidate() {
        // GIVEN section changes typically wouldn't be allowed because the panel is expanded
        setScreenOn(true);
        setPanelExpanded(true);
        setPulsing(false);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.uptimeMillis());

        // THEN the notification list is invalidated
        verifyInvalidateCalled(true);
    }

    @Test
    public void testTemporarilyAllowSectionChanges_noInvalidationCalled() {
        // GIVEN section changes typically WOULD be allowed
        setScreenOn(false);
        setPanelExpanded(false);
        setPulsing(false);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN invalidate is not called because this entry was never suppressed from reordering
        verifyInvalidateCalled(false);
    }

    @Test
    public void testTemporarilyAllowSectionChangesTimeout() {
        // GIVEN section changes typically WOULD be allowed
        setScreenOn(false);
        setPanelExpanded(false);
        setPulsing(false);
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());

        // THEN invalidate is not called because this entry was never suppressed from reordering;
        // THEN section changes are allowed for this notification
        verifyInvalidateCalled(false);
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN we're pulsing (now disallowing reordering)
        setPulsing(true);

        // THEN we're still allowed to reorder this section because it's still in the list of
        // notifications to allow section changes
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // WHEN the timeout for the temporarily allow section reordering runnable is finsihed
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runNextReady();

        // THEN section changes aren't allowed anymore
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));
    }

    @Test
    public void testTemporarilyAllowSectionChanges_isPulsingChangeBeforeTimeout() {
        // GIVEN section changes typically wouldn't be allowed because the device is pulsing
        setScreenOn(false);
        setPanelExpanded(false);
        setPulsing(true);

        // WHEN we temporarily allow section changes for this notification entry
        mCoordinator.temporarilyAllowSectionChanges(mEntry, mFakeSystemClock.currentTimeMillis());
        verifyInvalidateCalled(true); // can now reorder, so invalidates

        // WHEN reordering is now allowed because device isn't pulsing anymore
        setPulsing(false);

        // THEN invalidate isn't called since reordering was already allowed
        verifyInvalidateCalled(false);
    }

    @Test
    public void testNeverSuppressedChanges_noInvalidationCalled() {
        // GIVEN no notifications are currently being suppressed from grouping nor being sorted

        // WHEN device isn't pulsing anymore
        setPulsing(false);

        // WHEN screen isn't on
        setScreenOn(false);

        // WHEN panel isn't expanded
        setPanelExpanded(false);

        // THEN we never see any calls to invalidate since there weren't any notifications that
        // were being suppressed from grouping or section changes
        verifyInvalidateCalled(false);
    }

    @Test
    public void testNotSuppressingGroupChangesAnymore_invalidationCalled() {
        // GIVEN visual stability is being maintained b/c panel is expanded
        setPulsing(false);
        setScreenOn(true);
        setPanelExpanded(true);

        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));

        // WHEN the panel isn't expanded anymore
        setPanelExpanded(false);

        //  invalidate is called because we were previously suppressing a group change
        verifyInvalidateCalled(true);
    }

    @Test
    public void testHeadsUp_allowedToChangeGroupAndSection() {
        // GIVEN group + section changes disallowed
        setScreenOn(true);
        setPanelExpanded(true);
        setPulsing(true);
        assertFalse(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertFalse(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

        // GIVEN mEntry is a HUN
        when(mHeadsUpManager.isAlerting(mEntry.getKey())).thenReturn(true);

        // THEN group + section changes are allowed
        assertTrue(mNotifStabilityManager.isGroupChangeAllowed(mEntry));
        assertTrue(mNotifStabilityManager.isSectionChangeAllowed(mEntry));

    }

    private void setPulsing(boolean pulsing) {
        mStatusBarStateListener.onPulsingChanged(pulsing);
    }

    private void setScreenOn(boolean screenOn) {
        if (screenOn) {
            mWakefulnessObserver.onStartedWakingUp();
        } else {
            mWakefulnessObserver.onFinishedGoingToSleep();
        }
    }

    private void setPanelExpanded(boolean expanded) {
        mStatusBarStateListener.onExpandedChanged(expanded);
    }

    private void verifyInvalidateCalled(boolean invalidateCalled) {
        if (invalidateCalled) {
            verify(mInvalidateListener).onPluggableInvalidated(mNotifStabilityManager);
        } else {
            verify(mInvalidateListener, never()).onPluggableInvalidated(mNotifStabilityManager);
        }

        reset(mInvalidateListener);
    }
}
