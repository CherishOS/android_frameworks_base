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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupManagerTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private NotificationGroupManager mGroupManager;
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);

    @Mock HeadsUpManager mHeadsUpManager;
    @Mock AmbientPulseManager mAmbientPulseManager;

    @Before
    public void setup() {
        mDependency.injectTestDependency(AmbientPulseManager.class, mAmbientPulseManager);

        initializeGroupManager();
    }

    private void initializeGroupManager() {
        mGroupManager = new NotificationGroupManager();
        mGroupManager.setHeadsUpManager(mHeadsUpManager);
    }

    @Test
    public void testIsOnlyChildInGroup() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        assertTrue(mGroupManager.isOnlyChildInGroup(childEntry.notification));
    }

    @Test
    public void testIsChildInGroupWithSummary() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testIsSummaryOfGroupWithChildren() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        assertTrue(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
        assertEquals(summaryEntry, mGroupManager.getGroupSummary(childEntry.notification));
    }

    @Test
    public void testRemoveChildFromGroupWithSummary() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(childEntry);

        assertFalse(mGroupManager.isChildInGroupWithSummary(childEntry.notification));
    }

    @Test
    public void testRemoveSummaryFromGroupWithSummary() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());

        mGroupManager.onEntryRemoved(summaryEntry);

        assertNull(mGroupManager.getGroupSummary(childEntry.notification));
        assertFalse(mGroupManager.isSummaryOfGroup(summaryEntry.notification));
    }

    @Test
    public void testHeadsUpEntryIsIsolated() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());
        when(mHeadsUpManager.isAlerting(childEntry.key)).thenReturn(true);

        mGroupManager.onHeadsUpStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry, mGroupManager.getGroupSummary(childEntry.notification));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(childEntry.notification));
    }

    @Test
    public void testAmbientPulseEntryIsIsolated() {
        NotificationData.Entry childEntry = mGroupTestHelper.createChildNotification();
        NotificationData.Entry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);
        mGroupManager.onEntryAdded(mGroupTestHelper.createChildNotification());
        when(mAmbientPulseManager.isAlerting(childEntry.key)).thenReturn(true);

        mGroupManager.onAmbientStateChanged(childEntry, true);

        // Child entries that are heads upped should be considered separate groups visually even if
        // they are the same group logically
        assertEquals(childEntry, mGroupManager.getGroupSummary(childEntry.notification));
        assertEquals(summaryEntry, mGroupManager.getLogicalGroupSummary(childEntry.notification));
    }
}
