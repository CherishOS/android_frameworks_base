/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager.OnGroupExpansionChangeListener
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
class GroupExpansionManagerTest : SysuiTestCase() {
    private lateinit var gem: GroupExpansionManagerImpl

    private val dumpManager: DumpManager = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()
    private val featureFlags = FakeFeatureFlags()

    private val pipeline: NotifPipeline = mock()
    private lateinit var beforeRenderListListener: OnBeforeRenderListListener

    private val summary1 = notificationEntry("foo", 1)
    private val summary2 = notificationEntry("bar", 1)
    private val entries =
        listOf<ListEntry>(
            GroupEntryBuilder()
                .setSummary(summary1)
                .setChildren(
                    listOf(
                        notificationEntry("foo", 2),
                        notificationEntry("foo", 3),
                        notificationEntry("foo", 4)
                    )
                )
                .build(),
            GroupEntryBuilder()
                .setSummary(summary2)
                .setChildren(
                    listOf(
                        notificationEntry("bar", 2),
                        notificationEntry("bar", 3),
                        notificationEntry("bar", 4)
                    )
                )
                .build(),
            notificationEntry("baz", 1)
        )

    private fun notificationEntry(pkg: String, id: Int) =
        NotificationEntryBuilder().setPkg(pkg).setId(id).build().apply { row = mock() }

    @Before
    fun setUp() {
        whenever(groupMembershipManager.getGroupSummary(summary1)).thenReturn(summary1)
        whenever(groupMembershipManager.getGroupSummary(summary2)).thenReturn(summary2)

        gem = GroupExpansionManagerImpl(dumpManager, groupMembershipManager, featureFlags)
    }

    @Test
    fun testNotifyOnlyOnChange_enabled() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        var listenerCalledCount = 0
        gem.registerGroupExpansionChangeListener { _, _ -> listenerCalledCount++ }

        gem.setGroupExpanded(summary1, false)
        Assert.assertEquals(0, listenerCalledCount)
        gem.setGroupExpanded(summary1, true)
        Assert.assertEquals(1, listenerCalledCount)
        gem.setGroupExpanded(summary2, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(summary1, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(summary2, false)
        Assert.assertEquals(3, listenerCalledCount)
    }

    @Test
    fun testNotifyOnlyOnChange_disabled() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)

        var listenerCalledCount = 0
        gem.registerGroupExpansionChangeListener { _, _ -> listenerCalledCount++ }

        gem.setGroupExpanded(summary1, false)
        Assert.assertEquals(1, listenerCalledCount)
        gem.setGroupExpanded(summary1, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(summary2, true)
        Assert.assertEquals(3, listenerCalledCount)
        gem.setGroupExpanded(summary1, true)
        Assert.assertEquals(4, listenerCalledCount)
        gem.setGroupExpanded(summary2, false)
        Assert.assertEquals(5, listenerCalledCount)
    }

    @Test
    fun testSyncWithPipeline() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)
        gem.attach(pipeline)
        beforeRenderListListener = withArgCaptor {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        val listener: OnGroupExpansionChangeListener = mock()
        gem.registerGroupExpansionChangeListener(listener)

        beforeRenderListListener.onBeforeRenderList(entries)
        verify(listener, never()).onGroupExpansionChange(any(), any())

        // Expand one of the groups.
        gem.setGroupExpanded(summary1, true)
        verify(listener).onGroupExpansionChange(summary1.row, true)

        // Empty the pipeline list and verify that the group is no longer expanded.
        beforeRenderListListener.onBeforeRenderList(emptyList())
        verify(listener).onGroupExpansionChange(summary1.row, false)
        verifyNoMoreInteractions(listener)
    }
}
