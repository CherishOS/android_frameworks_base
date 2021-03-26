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

package com.android.systemui.people.widget;

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.Notification.EXTRA_PEOPLE_LIST;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.content.PermissionChecker.PERMISSION_GRANTED;
import static android.content.PermissionChecker.PERMISSION_HARD_DENIED;

import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;
import static com.android.systemui.people.widget.AppWidgetOptionsHelper.OPTIONS_PEOPLE_TILE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.preference.PreferenceManager;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NoManSimulator;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceWidgetManagerTest extends SysuiTestCase {
    private static final long MIN_LINGER_DURATION = 5;

    private static final String TEST_PACKAGE_A = "com.android.systemui.tests";
    private static final String TEST_PACKAGE_B = "com.test.package_b";
    private static final String TEST_CHANNEL_ID = "channel_id";
    private static final String TEST_CHANNEL_NAME = "channel_name";
    private static final String TEST_PARENT_CHANNEL_ID = "parent_channel_id";
    private static final String TEST_CONVERSATION_ID = "conversation_id";
    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int SECOND_WIDGET_ID_WITH_SHORTCUT = 3;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final int WIDGET_ID_WITH_KEY_IN_OPTIONS = 4;
    private static final int WIDGET_ID_WITH_SAME_URI = 5;
    private static final int WIDGET_ID_WITH_DIFFERENT_URI = 6;
    private static final String SHORTCUT_ID = "101";
    private static final String OTHER_SHORTCUT_ID = "102";
    private static final String NOTIFICATION_KEY = "0|com.android.systemui.tests|0|null|0";
    private static final String NOTIFICATION_CONTENT = "message text";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final PeopleTileKey KEY = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID, "username", ICON, new Intent())
                    .setPackageName(TEST_PACKAGE_A)
                    .setUserHandle(new UserHandle(0))
                    .setNotificationKey(NOTIFICATION_KEY + "1")
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .setContactUri(URI)
                    .build();
    private static final PeopleSpaceTile PERSON_TILE_WITH_SAME_URI =
            new PeopleSpaceTile
                    // Different shortcut ID
                    .Builder(OTHER_SHORTCUT_ID, "username", ICON, new Intent())
                    // Different package name
                    .setPackageName(TEST_PACKAGE_B)
                    .setUserHandle(new UserHandle(0))
                    // Same contact uri.
                    .setContactUri(URI)
                    .build();
    private final ShortcutInfo mShortcutInfo = new ShortcutInfo.Builder(mContext,
            SHORTCUT_ID).setLongLabel("name").build();

    private PeopleSpaceWidgetManager mManager;

    @Mock
    private Context mMockContext;

    @Mock
    private NotificationListener mListenerService;

    @Mock
    private AppWidgetManager mAppWidgetManager;
    @Mock
    private IPeopleManager mIPeopleManager;
    @Mock
    private PeopleManager mPeopleManager;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private PackageManager mPackageManager;

    @Captor
    private ArgumentCaptor<NotificationHandler> mListenerCaptor;
    @Captor
    private ArgumentCaptor<Bundle> mBundleArgumentCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();

    private PeopleSpaceWidgetProvider mProvider;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLauncherApps = mock(LauncherApps.class);
        mDependency.injectTestDependency(NotificationEntryManager.class, mNotificationEntryManager);
        mManager = new PeopleSpaceWidgetManager(mContext);
        mManager.setAppWidgetManager(mAppWidgetManager, mIPeopleManager, mPeopleManager,
                mLauncherApps, mNotificationEntryManager, mPackageManager, true);
        mManager.attach(mListenerService);
        mProvider = new PeopleSpaceWidgetProvider();
        mProvider.setPeopleSpaceWidgetManager(mManager);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);

        clearStorage();
        addTileForWidget(PERSON_TILE, WIDGET_ID_WITH_SHORTCUT);
        addTileForWidget(PERSON_TILE_WITH_SAME_URI, WIDGET_ID_WITH_SAME_URI);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoWidgets() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoShortcutInfo() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        Notification notificationWithoutShortcut = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, PERSON))
                )
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(new SbnBuilder()
                        .setNotification(notificationWithoutShortcut)
                        .setPkg(TEST_PACKAGE_A)
                        .build())
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoPackage() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithoutPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithoutPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNonConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateAppWidgetIfConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);
        channel.setConversationId(TEST_PARENT_CHANNEL_ID, TEST_CONVERSATION_ID);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentPackageName() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentPackageName() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateStatusPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status1 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationStatus status2 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_BIRTHDAY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(OTHER_SHORTCUT_ID,
                Arrays.asList(status1, status2));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(anyInt(), any());
        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateStatusPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(SHORTCUT_ID,
                Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getStatuses()).containsExactly(status);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateStatusPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_ANNIVERSARY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(SHORTCUT_ID,
                Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationOnExistingTileAfterRemovingTileForSamePerson()
            throws Exception {
        addSecondWidgetForPersonTile();

        PeopleSpaceUtils.removeSharedPreferencesStorageForTile(
                mContext, KEY, SECOND_WIDGET_ID_WITH_SHORTCUT, EMPTY_STRING);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, never())
                .updateAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                        any());
        verify(mAppWidgetManager, never()).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithoutContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());

        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent())
                .isEqualTo(mContext.getString(R.string.missed_call));
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());

        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfMatchingUriTile()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI),
                        mBundleArgumentCaptor.capture());
        Bundle bundleForSameUriTile = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithSameUri = bundleForSameUriTile.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testRemoveMissedCallNotificationWithContentPostedIfMatchingUriTile()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn.cloneLight(), 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(2)).updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(null);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(2))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI),
                        mBundleArgumentCaptor.capture());
        Bundle bundleForSameUriTile = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithSameUri = bundleForSameUriTile.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(null);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testDoNotRemoveMissedCallIfMatchingUriTileMissingReadContactsPermissionWhenPosted()
            throws Exception {
        when(mPackageManager.checkPermission(any(),
                eq(PERSON_TILE_WITH_SAME_URI.getPackageName()))).thenReturn(
                PERMISSION_HARD_DENIED);
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        // We should only try to remove the notification if the Missed Call was added when posted.
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn.cloneLight(), 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(2)).updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(null);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(0))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI), any());
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfMatchingUriTileFromSender()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        Notification notificationWithPersonOnlyInSender =
                createMessagingStyleNotificationWithoutExtras(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */
                        true).build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notificationWithPersonOnlyInSender)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setUser(new UserHandle(0))
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI),
                        mBundleArgumentCaptor.capture());
        Bundle bundleForSameUriTile = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithSameUri = bundleForSameUriTile.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallNotificationWithContentPostedIfNoPersonsAttached()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        // Notification posted without any Person attached.
        Notification notificationWithoutPersonObject =
                createMessagingStyleNotificationWithoutExtras(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */
                        true).setStyle(new Notification.MessagingStyle("sender")
                        .addMessage(
                                new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT, 10,
                                        "sender"))
                ).build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notificationWithoutPersonObject)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setUser(new UserHandle(0))
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since notification doesn't include a Person reference.
        verify(mAppWidgetManager, times(0))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI),
                        any());
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallNotificationWithContentPostedIfNotMatchingUriTile()
            throws Exception {
        clearStorage();
        addTileForWidget(PERSON_TILE, WIDGET_ID_WITH_SHORTCUT);
        addTileForWidget(PERSON_TILE_WITH_SAME_URI.toBuilder().setContactUri(
                Uri.parse("different_uri")).build(), WIDGET_ID_WITH_DIFFERENT_URI);
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_DIFFERENT_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since missing permission to read contacts.
        verify(mAppWidgetManager, times(0))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_DIFFERENT_URI),
                        any());
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_DIFFERENT_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallIfMatchingUriTileMissingReadContactsPermission()
            throws Exception {
        when(mPackageManager.checkPermission(any(),
                eq(PERSON_TILE_WITH_SAME_URI.getPackageName()))).thenReturn(
                PERMISSION_HARD_DENIED);
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                        mBundleArgumentCaptor.capture());
        Bundle bundle = requireNonNull(mBundleArgumentCaptor.getValue());
        PeopleSpaceTile tileWithMissedCallOrigin = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since missing permission to read contacts.
        verify(mAppWidgetManager, times(0))
                .updateAppWidgetOptions(eq(WIDGET_ID_WITH_SAME_URI),
                        any());
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testUpdateNotificationRemovedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(2)).updateAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT),
                mBundleArgumentCaptor.capture());
        Bundle bundle = mBundleArgumentCaptor.getValue();
        PeopleSpaceTile tile = bundle.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getNotificationKey()).isEqualTo(null);
        assertThat(tile.getNotificationContent()).isEqualTo(null);
        assertThat(tile.getNotificationDataUri()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testDeleteAllWidgetsForConversationsUncachesShortcutAndRemovesListeners()
            throws Exception {
        addSecondWidgetForPersonTile();
        mProvider.onUpdate(mContext, mAppWidgetManager,
                new int[]{WIDGET_ID_WITH_SHORTCUT, SECOND_WIDGET_ID_WITH_SHORTCUT});

        // Delete only one widget for the conversation.
        mManager.deleteWidgets(new int[]{WIDGET_ID_WITH_SHORTCUT});

        // Check deleted storage.
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(widgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>())).containsExactly(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT));
        // Check listener & shortcut caching remain for other widget.
        verify(mPeopleManager, never()).unregisterConversationListener(any());
        verify(mLauncherApps, never()).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));

        // Delete all widgets for the conversation.
        mProvider.onDeleted(mContext, new int[]{SECOND_WIDGET_ID_WITH_SHORTCUT});

        // Check deleted storage.
        SharedPreferences secondWidgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(secondWidgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(secondWidgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(secondWidgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>())).isEmpty();
        // Check listener is removed and shortcut is uncached.
        verify(mPeopleManager, times(1)).unregisterConversationListener(any());
        verify(mLauncherApps, times(1)).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
    }

    @Test
    public void testUpdateWidgetsWithEmptyOptionsAddsPeopleTileToOptions() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(new Bundle());

        mManager.updateWidgets(widgetIdsArray);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testOnAppWidgetOptionsChangedNoWidgetAdded() {
        Bundle newOptions = new Bundle();
        newOptions.putParcelable(OPTIONS_PEOPLE_TILE, PERSON_TILE);
        mManager.onAppWidgetOptionsChanged(SECOND_WIDGET_ID_WITH_SHORTCUT, newOptions);


        // Check that options is not modified
        verify(mAppWidgetManager, never()).updateAppWidgetOptions(
                eq(SECOND_WIDGET_ID_WITH_SHORTCUT), any());
        // Check listener is not added and shortcut is not cached.
        verify(mPeopleManager, never()).registerConversationListener(any(), anyInt(), any(), any(),
                any());
        verify(mLauncherApps, never()).cacheShortcuts(any(), any(), any(), anyInt());
        // Check no added storage.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>()))
                .doesNotContain(SECOND_WIDGET_ID_WITH_SHORTCUT);
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        assertThat(widgetSp.getString(SHORTCUT_ID, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);

    }

    @Test
    public void testOnAppWidgetOptionsChangedWidgetAdded() {
        Bundle newOptions = new Bundle();
        newOptions.putString(PeopleSpaceUtils.SHORTCUT_ID, SHORTCUT_ID);
        newOptions.putInt(USER_ID, 0);
        newOptions.putString(PACKAGE_NAME, TEST_PACKAGE_A);
        when(mAppWidgetManager.getAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(newOptions);

        mManager.onAppWidgetOptionsChanged(SECOND_WIDGET_ID_WITH_SHORTCUT, newOptions);

        verify(mAppWidgetManager, times(2)).updateAppWidgetOptions(
                eq(SECOND_WIDGET_ID_WITH_SHORTCUT), mBundleArgumentCaptor.capture());
        List<Bundle> bundles = mBundleArgumentCaptor.getAllValues();
        Bundle first = bundles.get(0);
        assertThat(first.getString(PeopleSpaceUtils.SHORTCUT_ID, EMPTY_STRING))
                .isEqualTo(EMPTY_STRING);
        assertThat(first.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        assertThat(first.getString(PACKAGE_NAME, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        verify(mLauncherApps, times(1)).cacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));

        Bundle second = bundles.get(1);
        PeopleSpaceTile tile = second.getParcelable(OPTIONS_PEOPLE_TILE);
        assertThat(tile.getId()).isEqualTo(SHORTCUT_ID);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>())).contains(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT));
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, EMPTY_STRING)).isEqualTo(TEST_PACKAGE_A);
        assertThat(widgetSp.getString(PeopleSpaceUtils.SHORTCUT_ID, EMPTY_STRING))
                .isEqualTo(SHORTCUT_ID);
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(0);
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageExistingConversation()
            throws Exception {
        when(mIPeopleManager.getConversation(PACKAGE_NAME, 0, SHORTCUT_ID)).thenReturn(
                getConversationWithShortcutId(SHORTCUT_ID));
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID, 0, PACKAGE_NAME);
        PeopleSpaceTile tile = mManager.getTileFromPersistentStorage(key);
        assertThat(tile.getId()).isEqualTo(key.getShortcutId());
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageNoConversation() {
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID, 0, PACKAGE_NAME);
        PeopleSpaceTile tile = mManager.getTileFromPersistentStorage(key);
        assertThat(tile).isNull();
    }

    @Test
    public void testRequestPinAppWidgetExistingConversation() throws Exception {
        when(mMockContext.getPackageName()).thenReturn(PACKAGE_NAME);
        when(mMockContext.getUserId()).thenReturn(0);
        when(mIPeopleManager.getConversation(PACKAGE_NAME, 0, SHORTCUT_ID))
                .thenReturn(getConversationWithShortcutId(SHORTCUT_ID));
        when(mAppWidgetManager.requestPinAppWidget(any(), any(), any())).thenReturn(true);

        ShortcutInfo info = new ShortcutInfo.Builder(mMockContext, SHORTCUT_ID).build();
        boolean valid = mManager.requestPinAppWidget(info);

        assertThat(valid).isTrue();
        verify(mAppWidgetManager, times(1)).requestPinAppWidget(
                any(), any(), any());
    }

    @Test
    public void testRequestPinAppWidgetNoConversation() throws Exception {
        when(mMockContext.getPackageName()).thenReturn(PACKAGE_NAME);
        when(mMockContext.getUserId()).thenReturn(0);
        when(mIPeopleManager.getConversation(PACKAGE_NAME, 0, SHORTCUT_ID)).thenReturn(null);

        ShortcutInfo info = new ShortcutInfo.Builder(mMockContext, SHORTCUT_ID).build();
        boolean valid = mManager.requestPinAppWidget(info);

        assertThat(valid).isFalse();
        verify(mAppWidgetManager, never()).requestPinAppWidget(any(), any(), any());
    }

    /**
     * Adds another widget for {@code PERSON_TILE} with widget ID: {@code
     * SECOND_WIDGET_ID_WITH_SHORTCUT}.
     */
    private void addSecondWidgetForPersonTile() throws Exception {
        // Set the same Person associated on another People Tile widget ID.
        addTileForWidget(PERSON_TILE, SECOND_WIDGET_ID_WITH_SHORTCUT);
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT,
                SECOND_WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
    }

    private void addTileForWidget(PeopleSpaceTile tile, int widgetId) throws Exception {
        setStorageForTile(tile.getId(), tile.getPackageName(), widgetId, tile.getContactUri());
        Bundle options = new Bundle();
        options.putParcelable(OPTIONS_PEOPLE_TILE, tile);
        when(mAppWidgetManager.getAppWidgetOptions(eq(widgetId)))
                .thenReturn(options);
        when(mIPeopleManager.getConversation(tile.getPackageName(), 0, tile.getId())).thenReturn(
                getConversationWithShortcutId(tile.getId()));
        when(mPackageManager.checkPermission(any(), eq(tile.getPackageName()))).thenReturn(
                PERMISSION_GRANTED);
    }

    /**
     * Returns a single conversation associated with {@code shortcutId}.
     */
    private ConversationChannel getConversationWithShortcutId(String shortcutId) throws Exception {
        return getConversationWithShortcutId(shortcutId, Arrays.asList());
    }

    /**
     * Returns a single conversation associated with {@code shortcutId} and {@code statuses}.
     */
    private ConversationChannel getConversationWithShortcutId(String shortcutId,
            List<ConversationStatus> statuses) throws Exception {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").setPerson(PERSON).build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                0L, false, false, statuses);
        return convo;
    }

    private Notification createMessagingStyleNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Bundle extras = new Bundle();
        ArrayList<Person> person = new ArrayList<Person>();
        person.add(PERSON);
        extras.putParcelableArrayList(EXTRA_PEOPLE_LIST, person);
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setExtras(extras)
                .setShortcutId(shortcutId);
        if (isMessagingStyle) {
            builder.setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(
                            new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT, 10,
                                    PERSON))
            );
        }
        if (isMissedCall) {
            builder.setCategory(CATEGORY_MISSED_CALL);
        }
        return builder.build();
    }

    private Notification.Builder createMessagingStyleNotificationWithoutExtras(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(shortcutId);
        if (isMessagingStyle) {
            builder.setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(
                            new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT, 10,
                                    PERSON))
            );
        }
        if (isMissedCall) {
            builder.setCategory(CATEGORY_MISSED_CALL);
        }
        return builder;
    }


    private StatusBarNotification createNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification notification = createMessagingStyleNotification(
                shortcutId, isMessagingStyle, isMissedCall);
        return new SbnBuilder()
                .setNotification(notification)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setUser(new UserHandle(0))
                .build();
    }

    private void clearStorage() {
        SharedPreferences widgetSp1 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp1.edit().clear().commit();
        SharedPreferences widgetSp2 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITHOUT_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp2.edit().clear().commit();
        SharedPreferences widgetSp3 = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp3.edit().clear().commit();
        SharedPreferences widgetSp4 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS),
                Context.MODE_PRIVATE);
        widgetSp4.edit().clear().commit();
        SharedPreferences widgetSp5 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SAME_URI),
                Context.MODE_PRIVATE);
        widgetSp5.edit().clear().commit();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        sp.edit().clear().commit();
    }

    private void setStorageForTile(String shortcutId, String packageName, int widgetId,
            Uri contactUri) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.putString(PeopleSpaceUtils.PACKAGE_NAME, packageName);
        widgetEditor.putString(PeopleSpaceUtils.SHORTCUT_ID, shortcutId);
        widgetEditor.putInt(PeopleSpaceUtils.USER_ID, 0);
        widgetEditor.apply();

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(String.valueOf(widgetId), contactUri.toString());

        String key = new PeopleTileKey(shortcutId, 0, packageName).toString();
        Set<String> storedWidgetIds = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        storedWidgetIds.add(String.valueOf(widgetId));
        editor.putStringSet(key, storedWidgetIds);

        Set<String> storedWidgetIdsByUri = new HashSet<>(
                sp.getStringSet(contactUri.toString(), new HashSet<>()));
        storedWidgetIdsByUri.add(String.valueOf(widgetId));
        editor.putStringSet(contactUri.toString(), storedWidgetIdsByUri);
        editor.apply();
    }
}
