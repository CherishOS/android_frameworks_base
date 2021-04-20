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

import static android.Manifest.permission.READ_CONTACTS;

import static com.android.systemui.people.NotificationHelper.getContactUri;
import static com.android.systemui.people.NotificationHelper.getHighestPriorityNotification;
import static com.android.systemui.people.NotificationHelper.shouldMatchNotificationByUri;
import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.SHORTCUT_ID;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.augmentTileFromNotification;
import static com.android.systemui.people.PeopleSpaceUtils.getMessagesCount;
import static com.android.systemui.people.PeopleSpaceUtils.getNotificationsByUri;
import static com.android.systemui.people.PeopleSpaceUtils.removeNotificationFields;
import static com.android.systemui.people.PeopleSpaceUtils.updateAppWidgetOptionsAndView;
import static com.android.systemui.people.PeopleSpaceUtils.updateAppWidgetViews;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.people.NotificationHelper;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.people.PeopleTileViewHelper;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Manager for People Space widget. */
@Singleton
public class PeopleSpaceWidgetManager {
    private static final String TAG = "PeopleSpaceWidgetMgr";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final Object mLock = new Object();
    private final Context mContext;
    private LauncherApps mLauncherApps;
    private AppWidgetManager mAppWidgetManager;
    private IPeopleManager mIPeopleManager;
    private SharedPreferences mSharedPrefs;
    private PeopleManager mPeopleManager;
    private NotificationEntryManager mNotificationEntryManager;
    private PackageManager mPackageManager;
    private PeopleSpaceWidgetProvider mPeopleSpaceWidgetProvider;
    private INotificationManager mINotificationManager;
    private UserManager mUserManager;
    public UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    @GuardedBy("mLock")
    public static Map<PeopleTileKey, PeopleSpaceWidgetProvider.TileConversationListener>
            mListeners = new HashMap<>();

    @GuardedBy("mLock")
    // Map of notification key mapped to widget IDs previously updated by the contact Uri field.
    // This is required because on notification removal, the contact Uri field is stripped and we
    // only have the notification key to determine which widget IDs should be updated.
    private Map<String, Set<String>> mNotificationKeyToWidgetIdsMatchedByUri = new HashMap<>();
    private boolean mIsForTesting;

    @Inject
    public PeopleSpaceWidgetManager(Context context) {
        if (DEBUG) Log.d(TAG, "constructor");
        mContext = context;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mIPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mPeopleManager = mContext.getSystemService(PeopleManager.class);
        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mPackageManager = mContext.getPackageManager();
        mPeopleSpaceWidgetProvider = new PeopleSpaceWidgetProvider();
        mINotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mUserManager = context.getSystemService(UserManager.class);
    }

    /**
     * AppWidgetManager setter used for testing.
     */
    @VisibleForTesting
    public void setAppWidgetManager(
            AppWidgetManager appWidgetManager, IPeopleManager iPeopleManager,
            PeopleManager peopleManager, LauncherApps launcherApps,
            NotificationEntryManager notificationEntryManager, PackageManager packageManager,
            boolean isForTesting, PeopleSpaceWidgetProvider peopleSpaceWidgetProvider,
            UserManager userManager, INotificationManager notificationManager) {
        mAppWidgetManager = appWidgetManager;
        mIPeopleManager = iPeopleManager;
        mPeopleManager = peopleManager;
        mLauncherApps = launcherApps;
        mNotificationEntryManager = notificationEntryManager;
        mPackageManager = packageManager;
        mIsForTesting = isForTesting;
        mPeopleSpaceWidgetProvider = peopleSpaceWidgetProvider;
        mUserManager = userManager;
        mINotificationManager = notificationManager;
    }

    /**
     * Updates People Space widgets.
     */
    public void updateWidgets(int[] widgetIds) {
        try {
            if (DEBUG) Log.d(TAG, "updateWidgets called");
            if (widgetIds.length == 0) {
                if (DEBUG) Log.d(TAG, "no widgets to update");
                return;
            }

            if (DEBUG) Log.d(TAG, "updating " + widgetIds.length + " widgets");
            synchronized (mLock) {
                updateSingleConversationWidgets(widgetIds);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /**
     * Updates {@code appWidgetIds} with their associated conversation stored, handling a
     * notification being posted or removed.
     */
    public void updateSingleConversationWidgets(int[] appWidgetIds) {
        Map<Integer, PeopleSpaceTile> widgetIdToTile = new HashMap<>();
        for (int appWidgetId : appWidgetIds) {
            PeopleSpaceTile tile = getTileForExistingWidget(appWidgetId);
            if (tile == null) {
                if (DEBUG) Log.d(TAG, "Matching conversation not found for shortcut ID");
                //TODO: Delete app widget id when crash is fixed (b/172932636)
                continue;
            }
            Bundle options = mAppWidgetManager.getAppWidgetOptions(appWidgetId);
            updateAppWidgetViews(mAppWidgetManager, mContext, appWidgetId, tile, options);
            widgetIdToTile.put(appWidgetId, tile);
        }
        PeopleSpaceUtils.getBirthdaysOnBackgroundThread(
                mContext, mAppWidgetManager, widgetIdToTile, appWidgetIds);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * Widget already exists, so fetch {@link PeopleTileKey} from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileForExistingWidget(int appWidgetId) {
        // First, check if tile is cached in AppWidgetOptions.
        PeopleSpaceTile tile = AppWidgetOptionsHelper.getPeopleTile(mAppWidgetManager, appWidgetId);
        if (tile != null) {
            if (DEBUG) Log.d(TAG, "People Tile is cached for widget: " + appWidgetId);
            return tile;
        }

        // If tile is null, we need to retrieve from persistent storage.
        if (DEBUG) Log.d(TAG, "Fetching key from sharedPreferences: " + appWidgetId);
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(appWidgetId),
                Context.MODE_PRIVATE);
        PeopleTileKey key = new PeopleTileKey(
                widgetSp.getString(SHORTCUT_ID, EMPTY_STRING),
                widgetSp.getInt(USER_ID, INVALID_USER_ID),
                widgetSp.getString(PACKAGE_NAME, EMPTY_STRING));

        return getTileFromPersistentStorage(key);
    }

    /**
     * Returns a {@link PeopleSpaceTile} based on the {@code appWidgetId}.
     * If a {@link PeopleTileKey} is not provided, fetch one from {@link SharedPreferences}.
     */
    @Nullable
    public PeopleSpaceTile getTileFromPersistentStorage(PeopleTileKey key) {
        if (!key.isValid()) {
            Log.e(TAG, "PeopleTileKey invalid: " + key);
            return null;
        }

        if (mIPeopleManager == null || mLauncherApps == null) {
            Log.d(TAG, "System services are null");
            return null;
        }

        try {
            if (DEBUG) Log.d(TAG, "Retrieving Tile from storage: " + key.toString());
            ConversationChannel channel = mIPeopleManager.getConversation(
                    key.getPackageName(), key.getUserId(), key.getShortcutId());
            if (channel == null) {
                Log.d(TAG, "Could not retrieve conversation from storage");
                return null;
            }

            return new PeopleSpaceTile.Builder(channel, mLauncherApps).build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve conversation for tile: " + e);
            return null;
        }
    }

    /**
     * Check if any existing People tiles match the incoming notification change, and store the
     * change in the tile if so.
     */
    public void updateWidgetsWithNotificationChanged(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction) {
        if (DEBUG) Log.d(TAG, "updateWidgetsWithNotificationChanged called");
        if (mIsForTesting) {
            updateWidgetsWithNotificationChangedInBackground(sbn, notificationAction);
            return;
        }
        ThreadUtils.postOnBackgroundThread(
                () -> updateWidgetsWithNotificationChangedInBackground(sbn, notificationAction));
    }

    private void updateWidgetsWithNotificationChangedInBackground(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        try {
            PeopleTileKey key = new PeopleTileKey(
                    sbn.getShortcutId(), sbn.getUser().getIdentifier(), sbn.getPackageName());
            if (!key.isValid()) {
                Log.d(TAG, "Invalid key from sbn");
                return;
            }
            int[] widgetIds = mAppWidgetManager.getAppWidgetIds(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
            );
            if (widgetIds.length == 0) {
                Log.d(TAG, "No app widget ids returned");
                return;
            }
            synchronized (mLock) {
                Set<String> tilesUpdated = getMatchingKeyWidgetIds(key);
                Set<String> tilesUpdatedByUri = getMatchingUriWidgetIds(sbn, action);
                if (DEBUG) {
                    Log.d(TAG, "Widgets by key to be updated:" + tilesUpdated.toString());
                    Log.d(TAG, "Widgets by URI to be updated:" + tilesUpdatedByUri.toString());
                }
                tilesUpdated.addAll(tilesUpdatedByUri);
                updateWidgetIdsBasedOnNotifications(tilesUpdated);
            }
        } catch (Exception e) {
            Log.e(TAG, "Throwing exception: " + e);
        }
    }

    /** Updates {@code widgetIdsToUpdate} with {@code action}. */
    private void updateWidgetIdsBasedOnNotifications(Set<String> widgetIdsToUpdate) {
        Log.d(TAG, "Fetching grouped notifications");
        try {
            Map<PeopleTileKey, Set<NotificationEntry>> groupedNotifications =
                    getGroupedConversationNotifications();

            widgetIdsToUpdate
                    .stream()
                    .map(Integer::parseInt)
                    .collect(Collectors.toMap(
                            Function.identity(),
                            id -> getAugmentedTileForExistingWidget(id, groupedNotifications)))
                    .forEach((id, tile) ->
                            updateAppWidgetOptionsAndView(mAppWidgetManager, mContext, id, tile));
        } catch (Exception e) {
            Log.e(TAG, "Exception updating widgets: " + e);
        }
    }

    /**
     * Augments {@code tile} based on notifications returned from {@code notificationEntryManager}.
     */
    public PeopleSpaceTile augmentTileFromNotificationEntryManager(PeopleSpaceTile tile) {
        Log.d(TAG, "Augmenting tile from NotificationEntryManager widget: " + tile.getId());
        Map<PeopleTileKey, Set<NotificationEntry>> notifications =
                getGroupedConversationNotifications();
        String contactUri = null;
        if (tile.getContactUri() != null) {
            contactUri = tile.getContactUri().toString();
        }
        return augmentTileFromNotifications(tile, contactUri, notifications);
    }

    /** Returns active and pending notifications grouped by {@link PeopleTileKey}. */
    public Map<PeopleTileKey, Set<NotificationEntry>> getGroupedConversationNotifications() {
        List<NotificationEntry> notifications =
                new ArrayList<>(mNotificationEntryManager.getVisibleNotifications());
        Iterable<NotificationEntry> pendingNotifications =
                mNotificationEntryManager.getPendingNotificationsIterator();
        for (NotificationEntry entry : pendingNotifications) {
            notifications.add(entry);
        }
        if (DEBUG) Log.d(TAG, "Number of total notifications: " + notifications.size());
        Map<PeopleTileKey, Set<NotificationEntry>> groupedNotifications =
                notifications
                        .stream()
                        .filter(entry -> NotificationHelper.isValid(entry)
                                && NotificationHelper.isMissedCallOrHasContent(entry))
                        .collect(Collectors.groupingBy(
                                PeopleTileKey::new,
                                Collectors.mapping(Function.identity(), Collectors.toSet())));
        if (DEBUG) {
            Log.d(TAG, "Number of grouped conversation notifications keys: "
                    + groupedNotifications.keySet().size());
        }
        return groupedNotifications;
    }

    /** Augments {@code tile} based on {@code notifications}, matching {@code contactUri}. */
    public PeopleSpaceTile augmentTileFromNotifications(PeopleSpaceTile tile, String contactUri,
            Map<PeopleTileKey, Set<NotificationEntry>> notifications) {
        if (DEBUG) Log.d(TAG, "Augmenting tile from notifications. Tile id: " + tile.getId());
        boolean hasReadContactsPermission =  mPackageManager.checkPermission(READ_CONTACTS,
                tile.getPackageName()) == PackageManager.PERMISSION_GRANTED;

        List<NotificationEntry> notificationsByUri = new ArrayList<>();
        if (hasReadContactsPermission) {
            notificationsByUri = getNotificationsByUri(mPackageManager, contactUri, notifications);
            if (!notificationsByUri.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "Number of notifications matched by contact URI: "
                            + notificationsByUri.size());
                }
            }
        }

        PeopleTileKey key = new PeopleTileKey(tile);
        Set<NotificationEntry> allNotifications = notifications.get(key);
        if (allNotifications == null) {
            allNotifications = new HashSet<>();
        }
        if (allNotifications.isEmpty() && notificationsByUri.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No existing notifications for tile: " + key);
            return removeNotificationFields(tile);
        }

        // Merge notifications matched by key and by contact URI.
        allNotifications.addAll(notificationsByUri);
        if (DEBUG) Log.d(TAG, "Total notifications matching tile: " + allNotifications.size());

        int messagesCount = getMessagesCount(allNotifications);
        NotificationEntry highestPriority = getHighestPriorityNotification(allNotifications);

        if (DEBUG) Log.d(TAG, "Augmenting tile from notification, key: " + key.toString());
        return augmentTileFromNotification(mContext, tile, highestPriority, messagesCount);
    }

    /** Returns an augmented tile for an existing widget. */
    @Nullable
    public Optional<PeopleSpaceTile> getAugmentedTileForExistingWidget(int widgetId,
            Map<PeopleTileKey, Set<NotificationEntry>> notifications) {
        Log.d(TAG, "Augmenting tile for widget: " + widgetId);
        PeopleSpaceTile tile = getTileForExistingWidget(widgetId);
        if (tile == null) {
            return Optional.empty();
        }
        String contactUriString = mSharedPrefs.getString(String.valueOf(widgetId), null);
        // Should never be null, but using ofNullable for extra safety.
        return Optional.ofNullable(
                augmentTileFromNotifications(tile, contactUriString, notifications));
    }

    /** Returns stored widgets for the conversation specified. */
    public Set<String> getMatchingKeyWidgetIds(PeopleTileKey key) {
        if (!key.isValid()) {
            return new HashSet<>();
        }
        return new HashSet<>(mSharedPrefs.getStringSet(key.toString(), new HashSet<>()));
    }

    /**
     * Updates in-memory map of tiles with matched Uris, dependent on the {@code action}.
     *
     * <p>If the notification was added, adds the notification based on the contact Uri within
     * {@code sbn}.
     * <p>If the notification was removed, removes the notification based on the in-memory map of
     * widgets previously updated by Uri (since the contact Uri is stripped from the {@code sbn}).
     */
    @Nullable
    private Set<String> getMatchingUriWidgetIds(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction action) {
        if (action.equals(PeopleSpaceUtils.NotificationAction.POSTED)) {
            Set<String> widgetIdsUpdatedByUri = fetchMatchingUriWidgetIds(sbn);
            if (widgetIdsUpdatedByUri != null && !widgetIdsUpdatedByUri.isEmpty()) {
                mNotificationKeyToWidgetIdsMatchedByUri.put(sbn.getKey(), widgetIdsUpdatedByUri);
                return widgetIdsUpdatedByUri;
            }
        } else {
            // Remove the notification on any widgets where the notification was added
            // purely based on the Uri.
            Set<String> widgetsPreviouslyUpdatedByUri =
                    mNotificationKeyToWidgetIdsMatchedByUri.remove(sbn.getKey());
            if (widgetsPreviouslyUpdatedByUri != null && !widgetsPreviouslyUpdatedByUri.isEmpty()) {
                return widgetsPreviouslyUpdatedByUri;
            }
        }
        return new HashSet<>();
    }

    /** Fetches widget Ids that match the contact URI in {@code sbn}. */
    @Nullable
    private Set<String> fetchMatchingUriWidgetIds(StatusBarNotification sbn) {
        // Check if it's a missed call notification
        if (!shouldMatchNotificationByUri(sbn)) {
            if (DEBUG) Log.d(TAG, "Should not supplement conversation");
            return null;
        }

        // Try to get the Contact Uri from the Missed Call notification directly.
        String contactUri = getContactUri(sbn);
        if (contactUri == null) {
            if (DEBUG) Log.d(TAG, "No contact uri");
            return null;
        }

        // Supplement any tiles with the same Uri.
        Set<String> storedWidgetIdsByUri =
                new HashSet<>(mSharedPrefs.getStringSet(contactUri, new HashSet<>()));
        if (storedWidgetIdsByUri.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No tiles for contact");
            return null;
        }
        return storedWidgetIdsByUri;
    }

    /**
     * Update the tiles associated with the incoming conversation update.
     */
    public void updateWidgetsWithConversationChanged(ConversationChannel conversation) {
        ShortcutInfo info = conversation.getShortcutInfo();
        synchronized (mLock) {
            PeopleTileKey key = new PeopleTileKey(
                    info.getId(), info.getUserId(), info.getPackage());
            Set<String> storedWidgetIds = getMatchingKeyWidgetIds(key);
            for (String widgetIdString : storedWidgetIds) {
                if (DEBUG) {
                    Log.d(TAG,
                            "Conversation update for widget " + widgetIdString + " , "
                                    + info.getLabel());
                }
                updateStorageAndViewWithConversationData(conversation,
                        Integer.parseInt(widgetIdString));
            }
        }
    }

    /**
     * Update {@code appWidgetId} with the new data provided by {@code conversation}.
     */
    private void updateStorageAndViewWithConversationData(ConversationChannel conversation,
            int appWidgetId) {
        PeopleSpaceTile storedTile = getTileForExistingWidget(appWidgetId);
        if (storedTile == null) {
            if (DEBUG) Log.d(TAG, "Could not find stored tile to add conversation to");
            return;
        }
        ShortcutInfo info = conversation.getShortcutInfo();
        Uri uri = null;
        if (info.getPersons() != null && info.getPersons().length > 0) {
            Person person = info.getPersons()[0];
            uri = person.getUri() == null ? null : Uri.parse(person.getUri());
        }
        storedTile = storedTile.toBuilder()
                .setUserName(info.getLabel())
                .setUserIcon(
                        PeopleSpaceTile.convertDrawableToIcon(mLauncherApps.getShortcutIconDrawable(
                                info, 0)))
                .setContactUri(uri)
                .setStatuses(conversation.getStatuses())
                .setLastInteractionTimestamp(conversation.getLastEventTimestamp())
                .setIsImportantConversation(conversation.getParentNotificationChannel() != null
                        && conversation.getParentNotificationChannel().isImportantConversation())
                .build();
        updateAppWidgetOptionsAndView(mAppWidgetManager, mContext, appWidgetId, storedTile);
    }

    /**
     * Attaches the manager to the pipeline, making it ready to receive events. Should only be
     * called once.
     */
    public void attach(NotificationListener listenerService) {
        if (DEBUG) Log.d(TAG, "attach");
        listenerService.addNotificationHandler(mListener);
    }

    private final NotificationHandler mListener = new NotificationHandler() {
        @Override
        public void onNotificationPosted(
                StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.POSTED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap
        ) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap,
                int reason) {
            updateWidgetsWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRankingUpdate(
                NotificationListenerService.RankingMap rankingMap) {
        }

        @Override
        public void onNotificationsInitialized() {
            if (DEBUG) Log.d(TAG, "onNotificationsInitialized");
        }

        @Override
        public void onNotificationChannelModified(
                String pkgName,
                UserHandle user,
                NotificationChannel channel,
                int modificationType) {
            if (channel.isConversation()) {
                updateWidgets(mAppWidgetManager.getAppWidgetIds(
                        new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
                ));
            }
        }
    };

    /**
     * Checks if this widget has been added externally, and this the first time we are learning
     * about the widget. If so, the widget adder should have populated options with PeopleTileKey
     * arguments.
     */
    public void onAppWidgetOptionsChanged(int appWidgetId, Bundle newOptions) {
        // Check if this widget has been added externally, and this the first time we are
        // learning about the widget. If so, the widget adder should have populated options with
        // PeopleTileKey arguments.
        if (DEBUG) Log.d(TAG, "onAppWidgetOptionsChanged called for widget: " + appWidgetId);
        PeopleTileKey optionsKey = AppWidgetOptionsHelper.getPeopleTileKeyFromBundle(newOptions);
        if (optionsKey.isValid()) {
            if (DEBUG) {
                Log.d(TAG, "PeopleTileKey was present in Options, shortcutId: "
                        + optionsKey.getShortcutId());
            }
            AppWidgetOptionsHelper.removePeopleTileKey(mAppWidgetManager, appWidgetId);
            addNewWidget(appWidgetId, optionsKey);
        }
        // Update views for new widget dimensions.
        updateWidgets(new int[]{appWidgetId});
    }

    /** Adds a widget based on {@code key} mapped to {@code appWidgetId}. */
    public void addNewWidget(int appWidgetId, PeopleTileKey key) {
        if (DEBUG) Log.d(TAG, "addNewWidget called with key for appWidgetId: " + appWidgetId);
        PeopleSpaceTile tile = getTileFromPersistentStorage(key);
        if (tile == null) {
            return;
        }
        tile = augmentTileFromNotificationEntryManager(tile);

        PeopleTileKey existingKeyIfStored;
        synchronized (mLock) {
            existingKeyIfStored = getKeyFromStorageByWidgetId(appWidgetId);
        }
        // Delete previous storage if the widget already existed and is just reconfigured.
        if (existingKeyIfStored.isValid()) {
            if (DEBUG) Log.d(TAG, "Remove previous storage for widget: " + appWidgetId);
            deleteWidgets(new int[]{appWidgetId});
        } else {
            // Widget newly added.
            mUiEventLogger.log(
                    PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_ADDED);
        }

        synchronized (mLock) {
            if (DEBUG) Log.d(TAG, "Add storage for : " + tile.getId());
            PeopleSpaceUtils.setSharedPreferencesStorageForTile(mContext, key, appWidgetId,
                    tile.getContactUri());
        }
        try {
            if (DEBUG) Log.d(TAG, "Caching shortcut for PeopleTile: " + tile.getId());
            mLauncherApps.cacheShortcuts(tile.getPackageName(),
                    Collections.singletonList(tile.getId()),
                    tile.getUserHandle(), LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
        } catch (Exception e) {
            Log.w(TAG, "Exception caching shortcut:" + e);
        }

        PeopleSpaceUtils.updateAppWidgetOptionsAndView(
                mAppWidgetManager, mContext, appWidgetId, tile);
        mPeopleSpaceWidgetProvider.onUpdate(mContext, mAppWidgetManager, new int[]{appWidgetId});
    }

    /** Registers a conversation listener for {@code appWidgetId} if not already registered. */
    public void registerConversationListenerIfNeeded(int widgetId,
            PeopleSpaceWidgetProvider.TileConversationListener newListener) {
        // Retrieve storage needed for registration.
        PeopleTileKey key;
        synchronized (mLock) {
            key = getKeyFromStorageByWidgetId(widgetId);
            if (!key.isValid()) {
                if (DEBUG) Log.w(TAG, "Could not register listener for widget: " + widgetId);
                return;
            }
        }
        synchronized (mListeners) {
            if (mListeners.containsKey(key)) {
                if (DEBUG) Log.d(TAG, "Already registered listener");
                return;
            }
            if (DEBUG) Log.d(TAG, "Register listener for " + widgetId + " with " + key);
            mListeners.put(key, newListener);
        }
        mPeopleManager.registerConversationListener(key.getPackageName(),
                key.getUserId(),
                key.getShortcutId(), newListener,
                mContext.getMainExecutor());
    }

    /**
     * Attempts to get a key from storage for {@code widgetId}, returning null if an invalid key is
     * found.
     */
    private PeopleTileKey getKeyFromStorageByWidgetId(int widgetId) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        PeopleTileKey key = new PeopleTileKey(
                widgetSp.getString(SHORTCUT_ID, EMPTY_STRING),
                widgetSp.getInt(USER_ID, INVALID_USER_ID),
                widgetSp.getString(PACKAGE_NAME, EMPTY_STRING));
        return key;
    }

    /** Deletes all storage, listeners, and caching for {@code appWidgetIds}. */
    public void deleteWidgets(int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            if (DEBUG) Log.d(TAG, "Widget removed: " + widgetId);
            mUiEventLogger.log(PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_DELETED);
            // Retrieve storage needed for widget deletion.
            PeopleTileKey key;
            Set<String> storedWidgetIdsForKey;
            String contactUriString;
            synchronized (mLock) {
                SharedPreferences widgetSp = mContext.getSharedPreferences(String.valueOf(widgetId),
                        Context.MODE_PRIVATE);
                key = new PeopleTileKey(
                        widgetSp.getString(SHORTCUT_ID, null),
                        widgetSp.getInt(USER_ID, INVALID_USER_ID),
                        widgetSp.getString(PACKAGE_NAME, null));
                if (!key.isValid()) {
                    if (DEBUG) Log.e(TAG, "Could not delete " + widgetId);
                    return;
                }
                storedWidgetIdsForKey = new HashSet<>(
                        mSharedPrefs.getStringSet(key.toString(), new HashSet<>()));
                contactUriString = mSharedPrefs.getString(String.valueOf(widgetId), null);
            }
            synchronized (mLock) {
                PeopleSpaceUtils.removeSharedPreferencesStorageForTile(mContext, key, widgetId,
                        contactUriString);
            }
            // If another tile with the conversation is still stored, we need to keep the listener.
            if (DEBUG) Log.d(TAG, "Stored widget IDs: " + storedWidgetIdsForKey.toString());
            if (storedWidgetIdsForKey.contains(String.valueOf(widgetId))
                    && storedWidgetIdsForKey.size() == 1) {
                if (DEBUG) Log.d(TAG, "Remove caching and listener");
                unregisterConversationListener(key, widgetId);
                uncacheConversationShortcut(key);
            }
        }
    }

    /** Unregisters the conversation listener for {@code appWidgetId}. */
    private void unregisterConversationListener(PeopleTileKey key, int appWidgetId) {
        PeopleSpaceWidgetProvider.TileConversationListener registeredListener;
        synchronized (mListeners) {
            registeredListener = mListeners.get(key);
            if (registeredListener == null) {
                if (DEBUG) Log.d(TAG, "Cannot find listener to unregister");
                return;
            }
            if (DEBUG) Log.d(TAG, "Unregister listener for " + appWidgetId + " with " + key);
            mListeners.remove(key);
        }
        mPeopleManager.unregisterConversationListener(registeredListener);
    }

    /** Uncaches the conversation shortcut. */
    private void uncacheConversationShortcut(PeopleTileKey key) {
        try {
            if (DEBUG) Log.d(TAG, "Uncaching shortcut for PeopleTile: " + key.getShortcutId());
            mLauncherApps.uncacheShortcuts(key.getPackageName(),
                    Collections.singletonList(key.getShortcutId()),
                    UserHandle.of(key.getUserId()),
                    LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS);
        } catch (Exception e) {
            Log.d(TAG, "Exception uncaching shortcut:" + e);
        }
    }

    /**
     * Builds a request to pin a People Tile app widget, with a preview and storing necessary
     * information as the callback.
     */
    public boolean requestPinAppWidget(ShortcutInfo shortcutInfo, Bundle options) {
        if (DEBUG) Log.d(TAG, "Requesting pin widget, shortcutId: " + shortcutInfo.getId());

        RemoteViews widgetPreview = getPreview(shortcutInfo.getId(),
                shortcutInfo.getUserHandle(), shortcutInfo.getPackage(), options);
        if (widgetPreview == null) {
            Log.w(TAG, "Skipping pinning widget: no tile for shortcutId: " + shortcutInfo.getId());
            return false;
        }
        Bundle extras = new Bundle();
        extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, widgetPreview);

        PendingIntent successCallback =
                PeopleSpaceWidgetPinnedReceiver.getPendingIntent(mContext, shortcutInfo);

        ComponentName componentName = new ComponentName(mContext, PeopleSpaceWidgetProvider.class);
        return mAppWidgetManager.requestPinAppWidget(componentName, extras, successCallback);
    }

    /** Returns a list of map entries corresponding to user's priority conversations. */
    @NonNull
    public List<PeopleSpaceTile> getPriorityTiles()
            throws Exception {
        List<ConversationChannelWrapper> conversations =
                mINotificationManager.getConversations(true).getList();
        // Add priority conversations to tiles list.
        Stream<ShortcutInfo> priorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() != null
                        && c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());
        List<PeopleSpaceTile> priorityTiles = PeopleSpaceUtils.getSortedTiles(mIPeopleManager,
                mLauncherApps, mUserManager,
                priorityConversations);
        return priorityTiles;
    }

    /** Returns a list of map entries corresponding to user's recent conversations. */
    @NonNull
    public List<PeopleSpaceTile> getRecentTiles()
            throws Exception {
        if (DEBUG) Log.d(TAG, "Add recent conversations");
        List<ConversationChannelWrapper> conversations =
                mINotificationManager.getConversations(false).getList();
        Stream<ShortcutInfo> nonPriorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() == null
                        || !c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());

        List<ConversationChannel> recentConversationsList =
                mIPeopleManager.getRecentConversations().getList();
        Stream<ShortcutInfo> recentConversations = recentConversationsList
                .stream()
                .map(c -> c.getShortcutInfo());

        Stream<ShortcutInfo> mergedStream = Stream.concat(nonPriorityConversations,
                recentConversations);
        List<PeopleSpaceTile> recentTiles =
                PeopleSpaceUtils.getSortedTiles(mIPeopleManager, mLauncherApps, mUserManager,
                        mergedStream);
        return recentTiles;
    }

    /**
     * Returns a {@link RemoteViews} preview of a Conversation's People Tile. Returns null if one
     * is not available.
     */
    public RemoteViews getPreview(String shortcutId, UserHandle userHandle, String packageName,
            Bundle options) {
        PeopleSpaceTile tile;
        ConversationChannel channel;
        try {
            channel = mIPeopleManager.getConversation(
                    packageName, userHandle.getIdentifier(), shortcutId);
            tile = PeopleSpaceUtils.getTile(channel, mLauncherApps);
        } catch (Exception e) {
            Log.w(TAG, "Exception getting tiles: " + e);
            return null;
        }
        if (tile == null) {
            if (DEBUG) Log.i(TAG, "No tile was returned");
            return null;
        }

        PeopleSpaceTile augmentedTile = augmentTileFromNotificationEntryManager(tile);

        if (DEBUG) Log.i(TAG, "Returning tile preview for shortcutId: " + shortcutId);
        return new PeopleTileViewHelper(mContext, augmentedTile, 0, options).getViews();
    }
}
