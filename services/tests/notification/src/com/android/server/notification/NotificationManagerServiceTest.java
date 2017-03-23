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

package com.android.server.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.annotation.UiThreadTest;
import android.support.test.InstrumentationRegistry;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

public class NotificationManagerServiceTest {
    private static final long WAIT_FOR_IDLE_TIMEOUT = 2;
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";
    private final int uid = Binder.getCallingUid();
    private NotificationManagerService mNotificationManagerService;
    private INotificationManager mBinderService;
    private IPackageManager mPackageManager = mock(IPackageManager.class);
    private final PackageManager mPackageManagerClient = mock(PackageManager.class);
    private Context mContext = InstrumentationRegistry.getTargetContext();
    private final String PKG = mContext.getPackageName();
    private HandlerThread mThread;
    private final RankingHelper mRankingHelper = mock(RankingHelper.class);
    private NotificationChannel mTestNotificationChannel = new NotificationChannel(
            TEST_CHANNEL_ID, TEST_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);

    @Before
    @Test
    @UiThreadTest
    public void setUp() throws Exception {
        mNotificationManagerService = new NotificationManagerService(mContext);

        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;
        when(mPackageManager.getApplicationInfo(any(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManagerClient.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        final LightsManager mockLightsManager = mock(LightsManager.class);
        when(mockLightsManager.getLight(anyInt())).thenReturn(mock(Light.class));
        // Use a separate thread for service looper.
        mThread = new HandlerThread("TestThread");
        mThread.start();
        // Mock NotificationListeners to bypass security checks.
        final NotificationManagerService.NotificationListeners mockNotificationListeners =
                mock(NotificationManagerService.NotificationListeners.class);
        when(mockNotificationListeners.checkServiceTokenLocked(any())).thenReturn(
                mockNotificationListeners.new ManagedServiceInfo(null,
                        new ComponentName(PKG, "test_class"), uid, true, null, 0));

        mNotificationManagerService.init(mThread.getLooper(), mPackageManager,
                mPackageManagerClient, mockLightsManager, mockNotificationListeners);

        // Tests call directly into the Binder.
        mBinderService = mNotificationManagerService.getBinderService();

        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(mTestNotificationChannel)));
    }

    public void waitForIdle() throws Exception {
        MessageQueue queue = mThread.getLooper().getQueue();
        if (queue.isIdle()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        queue.addIdleHandler(new MessageQueue.IdleHandler() {
                @Override public boolean queueIdle() {
                    latch.countDown();
                    return false;
                }
        });
        // Timeout is valid in the cases where the queue goes idle before the IdleHandler
        // is added.
        latch.await(WAIT_FOR_IDLE_TIMEOUT, TimeUnit.SECONDS);
        waitForIdle();
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel) {
        return generateNotificationRecord(channel, null);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel,
            Notification.TvExtender extender) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        if (extender != null) {
            nb.extend(extender);
        }
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", uid, 0,
                nb.build(), new UserHandle(uid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_SingleChannel() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertTrue(createdChannel != null);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_NullChannelThrowsException() throws Exception {
        try {
            mBinderService.createNotificationChannels("test_pkg",
                    new ParceledListSlice(Arrays.asList(null)));
            fail("Exception should be thrown immediately.");
        } catch (NullPointerException e) {
            // pass
        }
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_TwoChannels() throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id1", "name", NotificationManager.IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        assertTrue(mBinderService.getNotificationChannel("test_pkg", "id1") != null);
        assertTrue(mBinderService.getNotificationChannel("test_pkg", "id2") != null);
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_SecondCreateDoesNotChangeImportance()
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel)));

        // Recreating the channel doesn't throw, but ignores importance.
        final NotificationChannel dupeChannel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(dupeChannel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    @UiThreadTest
    public void testCreateNotificationChannels_IdenticalChannelsInListIgnoresSecond()
            throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels("test_pkg",
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel("test_pkg", "id");
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_suspended() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerSuspendedByAdmin(eq(r));
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_blockedChannel() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    @UiThreadTest
    public void testBlockedNotifications_blockedApp() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        r.setUserImportance(NotificationManager.IMPORTANCE_NONE);
        assertTrue(mNotificationManagerService.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    @UiThreadTest
    @Ignore("Flaky")
    public void testEnqueueNotificationWithTag_PopulatesGetActiveNotifications() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), new int[1], 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationImmediatelyAfterEnqueue() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), new int[1], 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationWhilePostedAndEnqueued() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), new int[1], 0);
        waitForIdle();
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null).getNotification(), new int[1], 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelNotificationsFromListenerImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], sbn.getUserId());
        mBinderService.cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotificationsImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_IgnoreForegroundService() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_IgnoreOtherPackages() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], sbn.getUserId());
        mBinderService.cancelAllNotifications("other_pkg_name", sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_NullPkgRemovesAll() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], sbn.getUserId());
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testCancelAllNotifications_NullPkgIgnoresUserAllNotifications() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag",
                sbn.getId(), sbn.getNotification(), new int[1], UserHandle.USER_ALL);
        // Null pkg is how we signal a user switch.
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    @UiThreadTest
    public void testTvExtenderChannelOverride_onTv() throws Exception {
        mNotificationManagerService.setIsTelevision(true);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean())).thenReturn(
                        new NotificationChannel("foo", "foo", NotificationManager.IMPORTANCE_HIGH));

        Notification.TvExtender tv = new Notification.TvExtender().setChannel("foo");
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), new int[1], 0);
        verify(mRankingHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean());
    }

    @Test
    @UiThreadTest
    public void testTvExtenderChannelOverride_notOnTv() throws Exception {
        mNotificationManagerService.setIsTelevision(false);
        mNotificationManagerService.setRankingHelper(mRankingHelper);
        when(mRankingHelper.getNotificationChannel(
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(
                mTestNotificationChannel);

        Notification.TvExtender tv = new Notification.TvExtender().setChannel("foo");
        mBinderService.enqueueNotificationWithTag(PKG, "opPkg", "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), new int[1], 0);
        verify(mRankingHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq(mTestNotificationChannel.getId()), anyBoolean());
    }
}
