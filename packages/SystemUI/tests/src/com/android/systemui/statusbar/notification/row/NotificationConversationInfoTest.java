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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.bubbles.BubblesTestActivity;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationConversationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";
    private static final String CONVERSATION_ID = "convo";

    private TestableLooper mTestableLooper;
    private NotificationConversationInfo mNotificationInfo;
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mConversationChannel;
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;
    private StatusBarNotification mBubbleSbn;
    private NotificationEntry mBubbleEntry;
    @Mock
    private ShortcutInfo mShortcutInfo;
    private Drawable mImage;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private INotificationManager mMockINotificationManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private VisualStabilityManager mVisualStabilityManager;
    @Mock
    private BubbleController mBubbleController;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private ShortcutManager mShortcutManager;
    @Mock
    private NotificationGuts mNotificationGuts;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(BubbleController.class, mBubbleController);
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationConversationInfo) layoutInflater.inflate(
                R.layout.notification_conversation_info,
                null);
        mNotificationInfo.mShowHomeScreen = true;
        mNotificationInfo.setGutsParent(mNotificationGuts);
        doAnswer((Answer<Object>) invocation -> {
            mNotificationInfo.handleCloseControls(true, false);
            return null;
        }).when(mNotificationGuts).closeControls(anyInt(), anyInt(), eq(true), eq(false));
        // Our view is never attached to a window so the View#post methods in NotificationInfo never
        // get called. Setting this will skip the post and do the action immediately.
        mNotificationInfo.mSkipPost = true;

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq(TEST_PACKAGE_NAME), anyInt())).thenReturn(
                applicationInfo);
        final PackageInfo systemPackageInfo = new PackageInfo();
        systemPackageInfo.packageName = TEST_SYSTEM_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_SYSTEM_PACKAGE_NAME), anyInt()))
                .thenReturn(systemPackageInfo);
        when(mMockPackageManager.getPackageInfo(eq("android"), anyInt()))
                .thenReturn(packageInfo);

        when(mShortcutInfo.getShortLabel()).thenReturn("Convo name");
        List<ShortcutInfo> shortcuts = Arrays.asList(mShortcutInfo);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        mImage = mContext.getDrawable(R.drawable.ic_star);
        when(mLauncherApps.getShortcutBadgedIconDrawable(eq(mShortcutInfo),
                anyInt())).thenReturn(mImage);

        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);

        Notification notification = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setShortcutId(CONVERSATION_ID)
                .setStyle(new Notification.MessagingStyle(new Person.Builder().setName("m").build())
                        .addMessage(new Notification.MessagingStyle.Message(
                                "hello!", 1000, new Person.Builder().setName("other").build())))
                .build();
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                notification, UserHandle.CURRENT, null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).build();

        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(mContext, BubblesTestActivity.class), 0);
        mBubbleSbn = new SbnBuilder(mSbn).setBubbleMetadata(
                new Notification.BubbleMetadata.Builder()
                        .createIntentBubble(bubbleIntent,
                                Icon.createWithResource(mContext, R.drawable.android)).build())
                .build();
        mBubbleEntry = new NotificationEntryBuilder().setSbn(mBubbleSbn).build();

        mConversationChannel = new NotificationChannel(
                TEST_CHANNEL + " : " + CONVERSATION_ID, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        mConversationChannel.setConversationId(TEST_CHANNEL, CONVERSATION_ID);
        when(mMockINotificationManager.getConversationNotificationChannel(anyString(), anyInt(),
                anyString(), eq(TEST_CHANNEL), eq(false), eq(CONVERSATION_ID)))
                .thenReturn(mConversationChannel);
    }

    @Test
    public void testBindNotification_SetsTextShortcutName() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.name);
        assertEquals(mShortcutInfo.getShortLabel(), textView.getText().toString());
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testBindNotification_SetsShortcutIcon() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final ImageView view = mNotificationInfo.findViewById(R.id.conversation_icon);
        assertEquals(mImage, view.getDrawable());
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextChannelName() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.parent_channel_name);
        assertTrue(textView.getText().toString().contains(mNotificationChannel.getName()));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }

    @Test
    public void testBindNotification_SetsTextGroupName() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
               anyString(), anyString(), anyInt())).thenReturn(group);
        mNotificationChannel.setGroup(group.getId());
        mConversationChannel.setGroup(group.getId());

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertTrue(textView.getText().toString().contains(group.getName()));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(VISIBLE, textView.getVisibility());
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.group_divider).getVisibility());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(GONE, textView.getVisibility());
        assertEquals(GONE, mNotificationInfo.findViewById(R.id.group_divider).getVisibility());
    }

    @Test
    public void testBindNotification_noDelegate() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(GONE, nameView.getVisibility());
        final TextView dividerView = mNotificationInfo.findViewById(R.id.pkg_divider);
        assertEquals(GONE, dividerView.getVisibility());
    }

    @Test
    public void testBindNotification_delegate() throws Exception {
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, "other", 0, null, TEST_UID, 0,
                mSbn.getNotification(), UserHandle.CURRENT, null, 0);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = 7;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq("other"), anyInt())).thenReturn(
                applicationInfo);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("Other");

        NotificationEntry entry = new NotificationEntryBuilder().setSbn(mSbn).build();
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                entry,
                null,
                null,
                null,
                true);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(VISIBLE, nameView.getVisibility());
        assertTrue(nameView.getText().toString().contains("Proxied"));
        final TextView dividerView = mNotificationInfo.findViewById(R.id.pkg_divider);
        assertEquals(VISIBLE, dividerView.getVisibility());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mConversationChannel, c);
                    latch.countDown();
                },
                null,
                null,
                true);

        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned() {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                null,
                null,
                false);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_bubbleActionVisibleWhenCanBubble()  {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mBubbleEntry,
                null,
                null,
                null,
                true);

        View bubbleView = mNotificationInfo.findViewById(R.id.bubble);
        assertEquals(View.VISIBLE, bubbleView.getVisibility());
    }

    @Test
    public void testBindNotification_bubbleActionVisibleWhenCannotBubble()  {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);

        View bubbleView = mNotificationInfo.findViewById(R.id.bubble);
        assertEquals(View.GONE, bubbleView.getVisibility());
    }

    @Test
    public void testAddToHome() throws Exception {
        when(mShortcutManager.isRequestPinShortcutSupported()).thenReturn(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mBubbleEntry,
                null,
                null,
                null,
                true);


        // Promote it
        mNotificationInfo.findViewById(R.id.home).performClick();
        mTestableLooper.processAllMessages();

        verify(mShortcutManager, times(1)).requestPinShortcut(mShortcutInfo, null);
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    public void testSnooze() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mBubbleEntry,
                null,
                null,
                (View v, int hours) -> {
                    latch.countDown();
                },
                true);


        // Promote it
        mNotificationInfo.findViewById(R.id.snooze).performClick();
        mTestableLooper.processAllMessages();

        assertEquals(0, latch.getCount());
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
    }

    @Test
    public void testBubble_promotesBubble() throws Exception {
        mNotificationChannel.setAllowBubbles(false);
        mConversationChannel.setAllowBubbles(false);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mBubbleEntry,
                null,
                null,
                null,
                true);

        assertFalse(mBubbleEntry.isBubble());

        // Promote it
        mNotificationInfo.findViewById(R.id.bubble).performClick();
        mTestableLooper.processAllMessages();

        verify(mBubbleController, times(1)).onUserCreatedBubbleFromNotification(mBubbleEntry);
        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertTrue(captor.getValue().canBubble());
    }

    @Test
    public void testBubble_demotesBubble() throws Exception {
        mBubbleEntry.getSbn().getNotification().flags |= FLAG_BUBBLE;

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mBubbleEntry,
                null,
                null,
                null,
                true);

        assertTrue(mBubbleEntry.isBubble());

        // Demote it
        mNotificationInfo.findViewById(R.id.bubble).performClick();
        mTestableLooper.processAllMessages();

        verify(mBubbleController, times(1)).onUserDemotedBubbleFromNotification(mBubbleEntry);
        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertFalse(captor.getValue().canBubble());
    }

    @Test
    public void testFavorite_favorite() throws Exception {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);


        Button fave = mNotificationInfo.findViewById(R.id.fave);
        assertEquals(mContext.getString(R.string.notification_conversation_favorite),
                fave.getText().toString());

        fave.performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertTrue(captor.getValue().canBypassDnd());
    }

    @Test
    public void testFavorite_unfavorite() throws Exception {
        mNotificationChannel.setBypassDnd(true);
        mConversationChannel.setBypassDnd(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);

        Button fave = mNotificationInfo.findViewById(R.id.fave);
        assertEquals(mContext.getString(R.string.notification_conversation_unfavorite),
                fave.getText().toString());

        fave.performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertFalse(captor.getValue().canBypassDnd());
    }

    @Test
    public void testDemote() throws Exception {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);


        ImageButton demote = mNotificationInfo.findViewById(R.id.demote);
        demote.performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertTrue(captor.getValue().isDemoted());
    }

    @Test
    public void testMute_mute() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mConversationChannel.setImportance(IMPORTANCE_DEFAULT);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);

        Button mute = mNotificationInfo.findViewById(R.id.mute);
        assertEquals(mContext.getString(R.string.notification_conversation_mute),
                mute.getText().toString());

        mute.performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertEquals(IMPORTANCE_LOW, captor.getValue().getImportance());
    }

    @Test
    public void testMute_unmute() throws Exception {
        mNotificationChannel.setImportance(IMPORTANCE_LOW);
        mNotificationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);


        Button mute = mNotificationInfo.findViewById(R.id.mute);
        assertEquals(mContext.getString(R.string.notification_conversation_unmute),
                mute.getText().toString());

        mute.performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
    }

    @Test
    public void testBindNotification_createsNewChannel() throws Exception {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        verify(mMockINotificationManager, times(1)).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), anyString(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testBindNotification_doesNotCreateNewChannelIfExists() throws Exception {
        mNotificationChannel.setConversationId("", CONVERSATION_ID);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);
        verify(mMockINotificationManager, never()).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), anyString(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testAdjustImportanceTemporarilyAllowsReordering() {
        mNotificationChannel.setImportance(IMPORTANCE_DEFAULT);
        mConversationChannel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                null,
                true);

        mNotificationInfo.findViewById(R.id.mute).performClick();

        verify(mVisualStabilityManager).temporarilyAllowReordering();
    }
}
