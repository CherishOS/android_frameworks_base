/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.PollingCheck;
import android.testing.UiThreadTest;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class NotificationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";

    private NotificationInfo mNotificationInfo;
    private final INotificationManager mMockINotificationManager = mock(INotificationManager.class);
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mDefaultNotificationChannel;
    private StatusBarNotification mSbn;

    @Before
    public void setUp() throws Exception {
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationInfo) layoutInflater.inflate(R.layout.notification_info,
                null);

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
                applicationInfo);
        final PackageInfo systemPackageInfo = new PackageInfo();
        systemPackageInfo.packageName = TEST_SYSTEM_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_SYSTEM_PACKAGE_NAME), anyInt()))
                .thenReturn(systemPackageInfo);
        when(mMockPackageManager.getPackageInfo(eq("android"), anyInt()))
                .thenReturn(packageInfo);

        // Package has one channel by default.
        when(mMockINotificationManager.getNumNotificationChannelsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean())).thenReturn(1);

        // Some test channels.
        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        mDefaultNotificationChannel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME,
                IMPORTANCE_LOW);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
    }

    // TODO: if tests are taking too long replace this with something that makes the animation
    // finish instantly.
    private void waitForUndoButton() {
        PollingCheck.waitFor(1000,
                () -> VISIBLE == mNotificationInfo.findViewById(R.id.confirmation).getVisibility());
    }
    private void waitForStopButton() {
        PollingCheck.waitFor(1000,
                () -> VISIBLE == mNotificationInfo.findViewById(R.id.prompt).getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkgname);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    public void testBindNotification_SetsPackageIcon() throws Exception {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final ImageView iconView = mNotificationInfo.findViewById(R.id.pkgicon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(GONE, groupNameView.getVisibility());
        final TextView groupDividerView = mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(GONE, groupDividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsGroupNameIfNonNull() throws Exception {
        mNotificationChannel.setGroup("test_group_id");
        final NotificationChannelGroup notificationChannelGroup =
                new NotificationChannelGroup("test_group_id", "Test Group Name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
                eq("test_group_id"), eq(TEST_PACKAGE_NAME), eq(TEST_UID)))
                .thenReturn(notificationChannelGroup);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final TextView groupNameView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(View.VISIBLE, groupNameView.getVisibility());
        assertEquals("Test Group Name", groupNameView.getText());
        final TextView groupDividerView = mNotificationInfo.findViewById(R.id.pkg_group_divider);
        assertEquals(View.VISIBLE, groupDividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(TEST_CHANNEL_NAME, textView.getText());
    }

    @Test
    public void testBindNotification_DefaultChannelDoesNotUseChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mDefaultNotificationChannel, 1, mSbn, null, null, null, null);
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_UnblockablePackageUsesChannelName() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));
        final TextView textView = mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                }, null, null);

        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonReappearsAfterSecondBind() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                }, null, null);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertEquals(View.VISIBLE, settingsButton.getVisibility());
    }

    @Test
    public void testOnClickListenerPassesNullChannelForBundle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 2, mSbn, null,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(null, c);
                    latch.countDown();
                }, null, null);

        mNotificationInfo.findViewById(R.id.info).performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    @UiThreadTest
    public void testBindNotification_ChannelNameInvisibleWhenBundleFromDifferentChannels()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 2, mSbn, null, null, null, null);
        final TextView channelNameView =
                mNotificationInfo.findViewById(R.id.channel_name);
        assertEquals(GONE, channelNameView.getVisibility());
    }

    @Test
    @UiThreadTest
    public void testStopInvisibleIfBundleFromDifferentChannels() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 2, mSbn, null, null, null, null);
        final TextView blockView = mNotificationInfo.findViewById(R.id.block);
        assertEquals(GONE, blockView.getVisibility());
    }

    @Test
    public void testbindNotification_BlockingHelper() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null,
                null, null, true);
        final TextView view = mNotificationInfo.findViewById(R.id.block_prompt);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.inline_blocking_helper), view.getText());
    }

    @Test
    public void testbindNotification_UnblockableTextVisibleWhenAppUnblockable() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));
        final TextView view = mNotificationInfo.findViewById(R.id.block_prompt);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(mContext.getString(R.string.notification_unblockable_desc),
                view.getText());
    }

    @Test
    public void testBindNotification_DoesNotUpdateNotificationChannel() throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testDoesNotUpdateNotificationChannelAfterImportanceChanged() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);

        mNotificationInfo.findViewById(R.id.block).performClick();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnchanged()
            throws Exception {
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);

        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testHandleCloseControls_DoesNotUpdateNotificationChannelIfUnspecified()
            throws Exception {
        mNotificationChannel.setImportance(NotificationManager.IMPORTANCE_UNSPECIFIED);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);

        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testNonBlockableAppDoesNotBecomeBlocked() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null,
                null, Collections.singleton(TEST_PACKAGE_NAME));
        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), any());
    }

    @Test
    public void testBlockChangedCallsUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null, null);

        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);

        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertTrue((updated.getValue().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_IMPORTANCE) != 0);
    }

    @Test
    public void testBlockUndoDoesNotCallUpdateNotificationChannel() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));


        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.findViewById(R.id.undo).performClick();
        waitForStopButton();
        mNotificationInfo.handleCloseControls(true, false);

        ArgumentCaptor<NotificationChannel> updated =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), eq(TEST_UID), updated.capture());
        assertEquals(IMPORTANCE_LOW, mNotificationChannel.getImportance());
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfSaveIsFalse() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn, null, null, null,
                Collections.singleton(TEST_PACKAGE_NAME));

        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(false, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsDoesNotUpdateIfCheckSaveListenerIsNoOp() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn,
                (Runnable saveImportance, StatusBarNotification sbn) -> {
                }, null, null, Collections.singleton(TEST_PACKAGE_NAME));

        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testCloseControlsUpdatesWhenCheckSaveListenerUsesCallback() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, mSbn,
                (Runnable saveImportance, StatusBarNotification sbn) -> {
                    saveImportance.run();
                }, null, null, null);

        mNotificationInfo.findViewById(R.id.block).performClick();
        waitForUndoButton();
        mNotificationInfo.handleCloseControls(true, false);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(mNotificationChannel));
    }

    @Test
    public void testDisplaySettingsLink() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String settingsText = "work chats";
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, sbn, null, null,
                (View v, Intent intent) -> {
                    latch.countDown();
                }, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());
        settingsLink.performClick();
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testDisplaySettingsLink_multipleChannels() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String settingsText = "work chats";
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 2, sbn, null, null,
                (View v, Intent intent) -> {
                    latch.countDown();
                }, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(View.VISIBLE, settingsLink.getVisibility());
        settingsLink.performClick();
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testNoSettingsLink_noHandlingActivity() throws Exception {
        final String settingsText = "work chats";
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(null);
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setSettingsText(settingsText).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 2, sbn, null, null, null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(GONE, settingsLink.getVisibility());
    }

    @Test
    public void testNoSettingsLink_noLinkText() throws Exception {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = TEST_PACKAGE_NAME;
        ri.activityInfo.name = "something";
        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(ris);
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        Notification n = new Notification.Builder(mContext, mNotificationChannel.getId()).build();
        StatusBarNotification sbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME,
                0, null, 0, 0, n, UserHandle.CURRENT, null, 0);

        mNotificationInfo.bindNotification(mMockPackageManager, mMockINotificationManager,
                TEST_PACKAGE_NAME, mNotificationChannel, 1, sbn, null, null, null, null);
        final TextView settingsLink = mNotificationInfo.findViewById(R.id.app_settings);
        assertEquals(GONE, settingsLink.getVisibility());
    }


    @Test
    public void testWillBeRemovedReturnsFalseBeforeBind() throws Exception {
        assertFalse(mNotificationInfo.willBeRemoved());
    }
}
