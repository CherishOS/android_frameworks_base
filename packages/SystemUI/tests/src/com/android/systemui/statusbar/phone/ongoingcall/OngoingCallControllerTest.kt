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

package com.android.systemui.statusbar.phone.ongoingcall

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IUidObserver
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.service.notification.NotificationListenerService.REASON_USER_STOPPED
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val CALL_UID = 900

// A process state that represents the process being visible to the user.
private const val PROC_STATE_VISIBLE = ActivityManager.PROCESS_STATE_TOP

// A process state that represents the process being invisible to the user.
private const val PROC_STATE_INVISIBLE = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class OngoingCallControllerTest : SysuiTestCase() {

    private val clock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(clock)

    private lateinit var controller: OngoingCallController
    private lateinit var notifCollectionListener: NotifCollectionListener

    @Mock private lateinit var mockOngoingCallListener: OngoingCallListener
    @Mock private lateinit var mockActivityStarter: ActivityStarter
    @Mock private lateinit var mockIActivityManager: IActivityManager

    private lateinit var chipView: LinearLayout

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            chipView = LayoutInflater.from(mContext)
                    .inflate(R.layout.ongoing_call_chip, null) as LinearLayout
        }

        MockitoAnnotations.initMocks(this)
        val featureFlags = mock(FeatureFlags::class.java)
        `when`(featureFlags.isOngoingCallStatusBarChipEnabled).thenReturn(true)
        val notificationCollection = mock(CommonNotifCollection::class.java)

        controller = OngoingCallController(
                notificationCollection,
                featureFlags,
                clock,
                mockActivityStarter,
                mainExecutor,
                mockIActivityManager)
        controller.init()
        controller.addCallback(mockOngoingCallListener)
        controller.setChipView(chipView)

        val collectionListenerCaptor = ArgumentCaptor.forClass(NotifCollectionListener::class.java)
        verify(notificationCollection).addCollectionListener(collectionListenerCaptor.capture())
        notifCollectionListener = collectionListenerCaptor.value!!

        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)
    }

    @Test
    fun onEntryUpdated_isOngoingCallNotif_listenerNotifiedWithRightCallTime() {
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryUpdated_notOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryUpdated(createNotCallNotifEntry())

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryRemoved_ongoingCallNotif_listenerNotified() {
        notifCollectionListener.onEntryRemoved(createOngoingCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun onEntryRemoved_notOngoingCallNotif_listenerNotNotified() {
        notifCollectionListener.onEntryRemoved(createNotCallNotifEntry(), REASON_USER_STOPPED)

        verify(mockOngoingCallListener, never()).onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun hasOngoingCall_noOngoingCallNotifSent_returnsFalse() {
        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentAndCallAppNotVisible_returnsTrue() {
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isTrue()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentButCallAppVisible_returnsFalse() {
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_VISIBLE)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentButInvalidChipView_returnsFalse() {
        val invalidChipView = LinearLayout(context)
        controller.setChipView(invalidChipView)

        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    @Test
    fun hasOngoingCall_ongoingCallNotifSentThenRemoved_returnsFalse() {
        val ongoingCallNotifEntry = createOngoingCallNotifEntry()

        notifCollectionListener.onEntryUpdated(ongoingCallNotifEntry)
        notifCollectionListener.onEntryRemoved(ongoingCallNotifEntry, REASON_USER_STOPPED)

        assertThat(controller.hasOngoingCall()).isFalse()
    }

    /**
     * This test fakes a theme change during an ongoing call.
     *
     * When a theme change happens, [CollapsedStatusBarFragment] and its views get re-created, so
     * [OngoingCallController.setChipView] gets called with a new view. If there's an active ongoing
     * call when the theme changes, the new view needs to be updated with the call information.
     */
    @Test
    fun setChipView_whenHasOngoingCallIsTrue_listenerNotifiedWithNewView() {
        // Start an ongoing call.
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        lateinit var newChipView: LinearLayout
        TestableLooper.get(this).runWithLooper {
            newChipView = LayoutInflater.from(mContext)
                    .inflate(R.layout.ongoing_call_chip, null) as LinearLayout
        }

        // Change the chip view associated with the controller.
        controller.setChipView(newChipView)

        // Verify the listener was notified once for the initial call and again when the new view
        // was set.
        verify(mockOngoingCallListener, times(2))
                .onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun callProcessChangesToVisible_listenerNotified() {
        // Start the call while the process is invisible.
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_INVISIBLE)
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        val captor = ArgumentCaptor.forClass(IUidObserver.Stub::class.java)
        verify(mockIActivityManager).registerUidObserver(
                captor.capture(), any(), any(), nullable(String::class.java))
        val uidObserver = captor.value

        // Update the process to visible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_VISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady();

        // Once for when the call was started, and another time when the process visibility changes.
        verify(mockOngoingCallListener, times(2))
                .onOngoingCallStateChanged(anyBoolean())
    }

    @Test
    fun callProcessChangesToInvisible_listenerNotified() {
        // Start the call while the process is visible.
        `when`(mockIActivityManager.getUidProcessState(eq(CALL_UID), nullable(String::class.java)))
                .thenReturn(PROC_STATE_VISIBLE)
        notifCollectionListener.onEntryUpdated(createOngoingCallNotifEntry())

        val captor = ArgumentCaptor.forClass(IUidObserver.Stub::class.java)
        verify(mockIActivityManager).registerUidObserver(
                captor.capture(), any(), any(), nullable(String::class.java))
        val uidObserver = captor.value

        // Update the process to invisible.
        uidObserver.onUidStateChanged(CALL_UID, PROC_STATE_INVISIBLE, 0, 0)
        mainExecutor.advanceClockToLast()
        mainExecutor.runAllReady();

        // Once for when the call was started, and another time when the process visibility changes.
        verify(mockOngoingCallListener, times(2))
                .onOngoingCallStateChanged(anyBoolean())
    }

    private fun createOngoingCallNotifEntry(): NotificationEntry {
        val notificationEntryBuilder = NotificationEntryBuilder()
        notificationEntryBuilder.modifyNotification(context).style = ongoingCallStyle

        val contentIntent = mock(PendingIntent::class.java)
        `when`(contentIntent.intent).thenReturn(mock(Intent::class.java))
        notificationEntryBuilder.modifyNotification(context).setContentIntent(contentIntent)
        notificationEntryBuilder.setUid(CALL_UID)
        return notificationEntryBuilder.build()
    }

    private fun createNotCallNotifEntry() = NotificationEntryBuilder().build()
}

private val ongoingCallStyle = Notification.CallStyle.forOngoingCall(
        Person.Builder().setName("name").build(),
        /* hangUpIntent= */ mock(PendingIntent::class.java))
