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

package com.android.systemui.statusbar.notification.interruption;

import static android.app.Notification.VISIBILITY_PUBLIC;
import static android.app.Notification.VISIBILITY_SECRET;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static com.android.systemui.statusbar.notification.collection.EntryUtilKt.modifyEntry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.SectionHeaderVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardNotificationVisibilityProviderTest  extends SysuiTestCase {
    private static final int NOTIF_USER_ID = 0;
    private static final int CURR_USER_ID = 1;

    @Mock
    private Handler mMainHandler;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private SectionHeaderVisibilityProvider mSectionHeaderVisibilityProvider;
    @Mock private KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;

    private NotificationEntry mEntry;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        // TODO refactor the test of KeyguardNotificationVisibilityProvider out
        mKeyguardNotificationVisibilityProvider = spy(new KeyguardNotificationVisibilityProvider(
                mContext,
                mMainHandler,
                mKeyguardStateController,
                mLockscreenUserManager,
                mKeyguardUpdateMonitor,
                mHighPriorityProvider,
                mStatusBarStateController,
                mBroadcastDispatcher
        ));

        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .build();
    }

    @Test
    public void unfilteredState() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // THEN don't filter out the entry
        assertFalse(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void keyguardNotShowing() {
        // GIVEN the lockscreen isn't showing
        setupUnfilteredState(mEntry);
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        // THEN don't filter out the entry
        assertFalse(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void doNotShowLockscreenNotifications() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN we shouldn't show any lockscreen notifications
        when(mLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(false);

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void lockdown() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in lockdown:
        when(mKeyguardUpdateMonitor.isUserInLockdown(NOTIF_USER_ID)).thenReturn(true);

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void publicMode_settingsDisallow() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in public mode and settings are configured to disallow
        // notifications in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(NOTIF_USER_ID)).thenReturn(true);
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(NOTIF_USER_ID))
                .thenReturn(false);

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void publicMode_notifDisallowed() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in public mode and settings are configured to disallow
        // notifications in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(CURR_USER_ID)).thenReturn(true);
        mEntry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setVisibilityOverride(VISIBILITY_SECRET).build());

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void doesNotExceedThresholdToShow() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification doesn't exceed the threshold to show on the lockscreen
        mEntry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setImportance(IMPORTANCE_MIN)
                .build());
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(mEntry));
    }

    @Test
    public void summaryExceedsThresholdToShow() {
        // GIVEN the notification doesn't exceed the threshold to show on the lockscreen
        // but it's part of a group (has a parent)
        final NotificationEntry entryWithParent = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .build();

        final GroupEntry parent = new GroupEntryBuilder()
                .setKey("test_group_key")
                .setSummary(new NotificationEntryBuilder()
                        .setImportance(IMPORTANCE_HIGH)
                        .build())
                .addChild(entryWithParent)
                .build();

        setupUnfilteredState(entryWithParent);
        entryWithParent.setRanking(new RankingBuilder()
                .setKey(entryWithParent.getKey())
                .setImportance(IMPORTANCE_MIN)
                .build());

        // WHEN its parent does exceed threshold tot show on the lockscreen
        when(mHighPriorityProvider.isHighPriority(parent)).thenReturn(true);

        // THEN don't filter out the entry
        assertFalse(mKeyguardNotificationVisibilityProvider.hideNotification(entryWithParent));

        // WHEN its parent doesn't exceed threshold to show on lockscreen
        when(mHighPriorityProvider.isHighPriority(parent)).thenReturn(false);
        modifyEntry(parent.getSummary(), builder -> builder
                .setImportance(IMPORTANCE_MIN)
                .done());

        // THEN filter out the entry
        assertTrue(mKeyguardNotificationVisibilityProvider.hideNotification(entryWithParent));
    }

    /**
     * setup a state where the notification will not be filtered by the
     * KeyguardNotificationCoordinator when the keyguard is showing.
     */
    private void setupUnfilteredState(NotificationEntry entry) {
        // keyguard is showing
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // show notifications on the lockscreen
        when(mLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(true);

        // neither the current user nor the notification's user is in lockdown
        when(mLockscreenUserManager.getCurrentUserId()).thenReturn(CURR_USER_ID);
        when(mKeyguardUpdateMonitor.isUserInLockdown(NOTIF_USER_ID)).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUserInLockdown(CURR_USER_ID)).thenReturn(false);

        // not in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(CURR_USER_ID)).thenReturn(false);
        when(mLockscreenUserManager.isLockscreenPublicMode(NOTIF_USER_ID)).thenReturn(false);

        // entry's ranking - should show on all lockscreens
        // + priority of the notification exceeds the threshold to be shown on the lockscreen
        entry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setVisibilityOverride(VISIBILITY_PUBLIC)
                .setImportance(IMPORTANCE_HIGH)
                .build());

        // settings allows notifications in public mode
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(CURR_USER_ID)).thenReturn(true);
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(NOTIF_USER_ID))
                .thenReturn(true);

        // notification doesn't have a summary

        // notification is high priority, so it shouldn't be filtered
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(true);
    }
}
