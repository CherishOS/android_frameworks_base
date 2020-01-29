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

package com.android.server.people.data;

import static android.app.usage.UsageEvents.Event.SHORTCUT_INVOCATION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Person;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.Range;

import com.android.internal.app.ChooserActivity;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class DataManagerTest {

    private static final int USER_ID_PRIMARY = 0;
    private static final int USER_ID_PRIMARY_MANAGED = 10;
    private static final int USER_ID_SECONDARY = 11;
    private static final String TEST_PKG_NAME = "pkg";
    private static final String TEST_SHORTCUT_ID = "sc";
    private static final String CONTACT_URI = "content://com.android.contacts/contacts/lookup/123";
    private static final String PHONE_NUMBER = "+1234567890";

    @Mock private Context mContext;
    @Mock private ShortcutServiceInternal mShortcutServiceInternal;
    @Mock private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock private ShortcutManager mShortcutManager;
    @Mock private UserManager mUserManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private ContentResolver mContentResolver;
    @Mock private ScheduledExecutorService mExecutorService;
    @Mock private ScheduledFuture mScheduledFuture;
    @Mock private StatusBarNotification mStatusBarNotification;
    @Mock private Notification mNotification;

    private DataManager mDataManager;
    private int mCallingUserId;
    private TestInjector mInjector;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        addLocalServiceMock(ShortcutServiceInternal.class, mShortcutServiceInternal);

        addLocalServiceMock(UsageStatsManagerInternal.class, mUsageStatsManagerInternal);

        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());

        when(mContext.getSystemService(Context.SHORTCUT_SERVICE)).thenReturn(mShortcutManager);
        when(mContext.getSystemServiceName(ShortcutManager.class)).thenReturn(
                Context.SHORTCUT_SERVICE);

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemServiceName(UserManager.class)).thenReturn(
                Context.USER_SERVICE);

        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);

        when(mExecutorService.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(
                TimeUnit.class))).thenReturn(mScheduledFuture);

        when(mUserManager.getEnabledProfiles(USER_ID_PRIMARY))
                .thenReturn(Arrays.asList(
                        buildUserInfo(USER_ID_PRIMARY),
                        buildUserInfo(USER_ID_PRIMARY_MANAGED)));
        when(mUserManager.getEnabledProfiles(USER_ID_SECONDARY))
                .thenReturn(Collections.singletonList(buildUserInfo(USER_ID_SECONDARY)));

        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        when(mStatusBarNotification.getNotification()).thenReturn(mNotification);
        when(mStatusBarNotification.getPackageName()).thenReturn(TEST_PKG_NAME);
        when(mStatusBarNotification.getUser()).thenReturn(UserHandle.of(USER_ID_PRIMARY));
        when(mNotification.getShortcutId()).thenReturn(TEST_SHORTCUT_ID);

        mCallingUserId = USER_ID_PRIMARY;

        mInjector = new TestInjector();
        mDataManager = new DataManager(mContext, mInjector);
        mDataManager.initialize();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Test
    public void testAccessConversationFromTheSameProfileGroup() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onUserUnlocked(USER_ID_PRIMARY_MANAGED);
        mDataManager.onUserUnlocked(USER_ID_SECONDARY);

        mDataManager.onShortcutAddedOrUpdated(
                buildShortcutInfo("pkg_1", USER_ID_PRIMARY, "sc_1",
                        buildPerson(true, false)));
        mDataManager.onShortcutAddedOrUpdated(
                buildShortcutInfo("pkg_2", USER_ID_PRIMARY_MANAGED, "sc_2",
                        buildPerson(false, true)));
        mDataManager.onShortcutAddedOrUpdated(
                buildShortcutInfo("pkg_3", USER_ID_SECONDARY, "sc_3", buildPerson()));

        List<ConversationInfo> conversations = new ArrayList<>();
        mDataManager.forAllPackages(
                packageData -> packageData.forAllConversations(conversations::add));

        // USER_ID_SECONDARY is not in the same profile group as USER_ID_PRIMARY.
        assertEquals(2, conversations.size());

        assertEquals("sc_1", conversations.get(0).getShortcutId());
        assertTrue(conversations.get(0).isPersonImportant());
        assertFalse(conversations.get(0).isPersonBot());
        assertFalse(conversations.get(0).isContactStarred());
        assertEquals(PHONE_NUMBER, conversations.get(0).getContactPhoneNumber());

        assertEquals("sc_2", conversations.get(1).getShortcutId());
        assertFalse(conversations.get(1).isPersonImportant());
        assertTrue(conversations.get(1).isPersonBot());
        assertFalse(conversations.get(0).isContactStarred());
        assertEquals(PHONE_NUMBER, conversations.get(0).getContactPhoneNumber());
    }

    @Test
    public void testAccessConversationForUnlockedUsersOnly() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        mDataManager.onShortcutAddedOrUpdated(
                buildShortcutInfo("pkg_1", USER_ID_PRIMARY, "sc_1", buildPerson()));
        mDataManager.onShortcutAddedOrUpdated(
                buildShortcutInfo("pkg_2", USER_ID_PRIMARY_MANAGED, "sc_2", buildPerson()));

        List<ConversationInfo> conversations = new ArrayList<>();
        mDataManager.forAllPackages(
                packageData -> packageData.forAllConversations(conversations::add));

        // USER_ID_PRIMARY_MANAGED is not locked, so only USER_ID_PRIMARY's conversation is stored.
        assertEquals(1, conversations.size());
        assertEquals("sc_1", conversations.get(0).getShortcutId());

        mDataManager.onUserStopped(USER_ID_PRIMARY);
        conversations.clear();
        mDataManager.forAllPackages(
                packageData -> packageData.forAllConversations(conversations::add));
        assertTrue(conversations.isEmpty());
    }

    @Test
    public void testGetShortcut() {
        mDataManager.getShortcut(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID);
        verify(mShortcutServiceInternal).getShortcuts(anyInt(), anyString(), anyLong(),
                eq(TEST_PKG_NAME), eq(Collections.singletonList(TEST_SHORTCUT_ID)),
                eq(null), anyInt(), eq(USER_ID_PRIMARY), anyInt(), anyInt());
    }

    @Test
    public void testGetShareTargets() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut1 =
                buildShortcutInfo("pkg_1", USER_ID_PRIMARY, "sc_1", buildPerson());
        ShareShortcutInfo shareShortcut1 =
                new ShareShortcutInfo(shortcut1, new ComponentName("pkg_1", "activity"));

        ShortcutInfo shortcut2 =
                buildShortcutInfo("pkg_2", USER_ID_PRIMARY, "sc_2", buildPerson());
        ShareShortcutInfo shareShortcut2 =
                new ShareShortcutInfo(shortcut2, new ComponentName("pkg_2", "activity"));
        mDataManager.onShortcutAddedOrUpdated(shortcut2);

        when(mShortcutManager.getShareTargets(any(IntentFilter.class)))
                .thenReturn(Arrays.asList(shareShortcut1, shareShortcut2));

        List<ShareShortcutInfo> shareShortcuts =
                mDataManager.getConversationShareTargets(new IntentFilter());
        // Only "sc_2" is stored as a conversation.
        assertEquals(1, shareShortcuts.size());
        assertEquals("sc_2", shareShortcuts.get(0).getShortcutInfo().getId());
    }

    @Test
    public void testReportAppTargetEvent() throws IntentFilter.MalformedMimeTypeException {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);
        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.onShortcutAddedOrUpdated(shortcut);

        AppTarget appTarget = new AppTarget.Builder(new AppTargetId(TEST_SHORTCUT_ID), shortcut)
                .build();
        AppTargetEvent appTargetEvent =
                new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(ChooserActivity.LAUNCH_LOCATON_DIRECT_SHARE)
                        .build();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SEND, "image/jpg");
        mDataManager.reportAppTargetEvent(appTargetEvent, intentFilter);

        List<Range<Long>> activeShareTimeSlots = new ArrayList<>();
        mDataManager.forAllPackages(packageData ->
                activeShareTimeSlots.addAll(
                        packageData.getEventHistory(TEST_SHORTCUT_ID)
                                .getEventIndex(Event.TYPE_SHARE_IMAGE)
                                .getActiveTimeSlots()));
        assertEquals(1, activeShareTimeSlots.size());
    }

    @Test
    public void testContactsChanged() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.onShortcutAddedOrUpdated(shortcut);

        final String newPhoneNumber = "+1000000000";
        mInjector.mContactsQueryHelper.mIsStarred = true;
        mInjector.mContactsQueryHelper.mPhoneNumber = newPhoneNumber;

        ContentObserver contentObserver = mDataManager.getContactsContentObserverForTesting(
                USER_ID_PRIMARY);
        contentObserver.onChange(false, ContactsContract.Contacts.CONTENT_URI, USER_ID_PRIMARY);

        List<ConversationInfo> conversations = new ArrayList<>();
        mDataManager.forAllPackages(
                packageData -> packageData.forAllConversations(conversations::add));
        assertEquals(1, conversations.size());

        assertEquals(TEST_SHORTCUT_ID, conversations.get(0).getShortcutId());
        assertTrue(conversations.get(0).isContactStarred());
        assertEquals(newPhoneNumber, conversations.get(0).getContactPhoneNumber());
    }

    @Test
    public void testNotificationListener() {
        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.onShortcutAddedOrUpdated(shortcut);

        NotificationListenerService listenerService =
                mDataManager.getNotificationListenerServiceForTesting(USER_ID_PRIMARY);

        listenerService.onNotificationRemoved(mStatusBarNotification, null,
                NotificationListenerService.REASON_CLICK);

        List<Range<Long>> activeNotificationOpenTimeSlots = new ArrayList<>();
        mDataManager.forAllPackages(packageData ->
                activeNotificationOpenTimeSlots.addAll(
                        packageData.getEventHistory(TEST_SHORTCUT_ID)
                                .getEventIndex(Event.TYPE_NOTIFICATION_OPENED)
                                .getActiveTimeSlots()));
        assertEquals(1, activeNotificationOpenTimeSlots.size());
    }

    @Test
    public void testQueryUsageStatsService() {
        UsageEvents.Event e = new UsageEvents.Event(SHORTCUT_INVOCATION,
                System.currentTimeMillis());
        e.mPackage = TEST_PKG_NAME;
        e.mShortcutId = TEST_SHORTCUT_ID;
        List<UsageEvents.Event> events = new ArrayList<>();
        events.add(e);
        UsageEvents usageEvents = new UsageEvents(events, new String[]{});
        when(mUsageStatsManagerInternal.queryEventsForUser(anyInt(), anyLong(), anyLong(),
                anyBoolean())).thenReturn(usageEvents);

        mDataManager.onUserUnlocked(USER_ID_PRIMARY);

        ShortcutInfo shortcut = buildShortcutInfo(TEST_PKG_NAME, USER_ID_PRIMARY, TEST_SHORTCUT_ID,
                buildPerson());
        mDataManager.onShortcutAddedOrUpdated(shortcut);

        mDataManager.queryUsageStatsService(USER_ID_PRIMARY, 0L, Long.MAX_VALUE);

        List<Range<Long>> activeShortcutInvocationTimeSlots = new ArrayList<>();
        mDataManager.forAllPackages(packageData ->
                activeShortcutInvocationTimeSlots.addAll(
                        packageData.getEventHistory(TEST_SHORTCUT_ID)
                                .getEventIndex(Event.TYPE_SHORTCUT_INVOCATION)
                                .getActiveTimeSlots()));
        assertEquals(1, activeShortcutInvocationTimeSlots.size());
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private ShortcutInfo buildShortcutInfo(String packageName, int userId, String id,
            @Nullable Person person) {
        Context mockContext = mock(Context.class);
        when(mockContext.getPackageName()).thenReturn(packageName);
        when(mockContext.getUserId()).thenReturn(userId);
        when(mockContext.getUser()).thenReturn(UserHandle.of(userId));
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(mockContext, id)
                .setShortLabel(id)
                .setIntent(new Intent("TestIntent"));
        if (person != null) {
            builder.setPersons(new Person[] {person});
        }
        return builder.build();
    }

    private Person buildPerson() {
        return buildPerson(true, false);
    }

    private Person buildPerson(boolean isImportant, boolean isBot) {
        return new Person.Builder()
                .setImportant(isImportant)
                .setBot(isBot)
                .setUri(CONTACT_URI)
                .build();
    }

    private UserInfo buildUserInfo(int userId) {
        return new UserInfo(userId, "", 0);
    }

    private class TestContactsQueryHelper extends ContactsQueryHelper {

        private Uri mContactUri;
        private boolean mIsStarred;
        private String mPhoneNumber;

        TestContactsQueryHelper(Context context) {
            super(context);
            mContactUri = Uri.parse(CONTACT_URI);
            mIsStarred = false;
            mPhoneNumber = PHONE_NUMBER;
        }

        @Override
        boolean query(@NonNull String contactUri) {
            return true;
        }

        @Override
        boolean querySince(long sinceTime) {
            return true;
        }

        @Override
        @Nullable
        Uri getContactUri() {
            return mContactUri;
        }

        @Override
        boolean isStarred() {
            return mIsStarred;
        }

        @Override
        @Nullable
        String getPhoneNumber() {
            return mPhoneNumber;
        }
    }

    private class TestInjector extends DataManager.Injector {

        private final TestContactsQueryHelper mContactsQueryHelper =
                new TestContactsQueryHelper(mContext);

        @Override
        ScheduledExecutorService createScheduledExecutor() {
            return mExecutorService;
        }

        @Override
        ContactsQueryHelper createContactsQueryHelper(Context context) {
            return mContactsQueryHelper;
        }

        @Override
        int getCallingUserId() {
            return mCallingUserId;
        }
    }
}
