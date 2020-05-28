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

package com.android.systemui.bubbles.storage

import android.content.pm.LauncherApps
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BubbleVolatileRepositoryTest : SysuiTestCase() {

    private val user0 = UserHandle.of(0)
    private val user10 = UserHandle.of(10)

    private val bubble1 = BubbleEntity(0, PKG_MESSENGER, "shortcut-1", "k1")
    private val bubble2 = BubbleEntity(10, PKG_CHAT, "alice and bob", "k2")
    private val bubble3 = BubbleEntity(0, PKG_MESSENGER, "shortcut-2", "k3")

    private val bubbles = listOf(bubble1, bubble2, bubble3)

    private lateinit var repository: BubbleVolatileRepository
    private lateinit var launcherApps: LauncherApps

    @Before
    fun setup() {
        launcherApps = mock(LauncherApps::class.java)
        repository = BubbleVolatileRepository(launcherApps)
    }

    @Test
    fun testAddBubbles() {
        repository.addBubbles(bubbles)
        assertEquals(bubbles, repository.bubbles)
        verify(launcherApps).cacheShortcuts(eq(PKG_MESSENGER),
                eq(listOf("shortcut-1", "shortcut-2")), eq(user0),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))
        verify(launcherApps).cacheShortcuts(eq(PKG_CHAT),
                eq(listOf("alice and bob")), eq(user10),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))

        repository.addBubbles(listOf(bubble1))
        assertEquals(listOf(bubble2, bubble3, bubble1), repository.bubbles)
        verifyNoMoreInteractions(launcherApps)
    }

    @Test
    fun testRemoveBubbles() {
        repository.addBubbles(bubbles)
        assertEquals(bubbles, repository.bubbles)

        repository.removeBubbles(listOf(bubble3))
        assertEquals(listOf(bubble1, bubble2), repository.bubbles)
        verify(launcherApps).uncacheShortcuts(eq(PKG_MESSENGER),
                eq(listOf("shortcut-2")), eq(user0),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))
    }

    @Test
    fun testAddAndRemoveBubblesWhenExceedingCapacity() {
        repository.capacity = 2
        // push bubbles beyond capacity
        repository.addBubbles(bubbles)
        // verify it is trim down to capacity
        assertEquals(listOf(bubble2, bubble3), repository.bubbles)
        verify(launcherApps).cacheShortcuts(eq(PKG_MESSENGER),
                eq(listOf("shortcut-2")), eq(user0),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))
        verify(launcherApps).cacheShortcuts(eq(PKG_CHAT),
                eq(listOf("alice and bob")), eq(user10),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))

        repository.addBubbles(listOf(bubble1))
        // verify the oldest bubble is popped
        assertEquals(listOf(bubble3, bubble1), repository.bubbles)
        verify(launcherApps).uncacheShortcuts(eq(PKG_CHAT),
                eq(listOf("alice and bob")), eq(user10),
                eq(LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS))
    }
}

private const val PKG_MESSENGER = "com.example.messenger"
private const val PKG_CHAT = "com.example.chat"
