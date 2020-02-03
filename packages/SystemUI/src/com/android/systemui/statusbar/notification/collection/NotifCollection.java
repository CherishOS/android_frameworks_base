/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_ERROR;
import static android.service.notification.NotificationListenerService.REASON_GROUP_OPTIMIZATION;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED;
import static android.service.notification.NotificationListenerService.REASON_PROFILE_TURNED_OFF;
import static android.service.notification.NotificationListenerService.REASON_SNOOZED;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;
import static android.service.notification.NotificationListenerService.REASON_UNAUTOBUNDLED;
import static android.service.notification.NotificationListenerService.REASON_USER_STOPPED;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.DumpController;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.collection.coalescer.CoalescedEvent;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer.BatchableNotificationHandler;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionLogger;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.util.Assert;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps a record of all of the "active" notifications, i.e. the notifications that are currently
 * posted to the phone. This collection is unsorted, ungrouped, and unfiltered. Just because a
 * notification appears in this collection doesn't mean that it's currently present in the shade
 * (notifications can be hidden for a variety of reasons). Code that cares about what notifications
 * are *visible* right now should register listeners later in the pipeline.
 *
 * Each notification is represented by a {@link NotificationEntry}, which is itself made up of two
 * parts: a {@link StatusBarNotification} and a {@link Ranking}. When notifications are updated,
 * their underlying SBNs and Rankings are swapped out, but the enclosing NotificationEntry (and its
 * associated key) remain the same. In general, an SBN can only be updated when the notification is
 * reposted by the source app; Rankings are updated much more often, usually every time there is an
 * update from any kind from NotificationManager.
 *
 * In general, this collection closely mirrors the list maintained by NotificationManager, but it
 * can occasionally diverge due to lifetime extenders (see
 * {@link #addNotificationLifetimeExtender(NotifLifetimeExtender)}).
 *
 * Interested parties can register listeners
 * ({@link #addCollectionListener(NotifCollectionListener)}) to be informed when notifications
 * events occur.
 */
@MainThread
@Singleton
public class NotifCollection implements Dumpable {
    private final IStatusBarService mStatusBarService;
    private final FeatureFlags mFeatureFlags;
    private final NotifCollectionLogger mLogger;

    private final Map<String, NotificationEntry> mNotificationSet = new ArrayMap<>();
    private final Collection<NotificationEntry> mReadOnlyNotificationSet =
            Collections.unmodifiableCollection(mNotificationSet.values());

    @Nullable private CollectionReadyForBuildListener mBuildListener;
    private final List<NotifCollectionListener> mNotifCollectionListeners = new ArrayList<>();
    private final List<NotifLifetimeExtender> mLifetimeExtenders = new ArrayList<>();

    private boolean mAttached = false;
    private boolean mAmDispatchingToOtherCode;

    @Inject
    public NotifCollection(
            IStatusBarService statusBarService,
            DumpController dumpController,
            FeatureFlags featureFlags,
            NotifCollectionLogger logger) {
        Assert.isMainThread();
        mStatusBarService = statusBarService;
        mLogger = logger;
        dumpController.registerDumpable(TAG, this);
        mFeatureFlags = featureFlags;
    }

    /** Initializes the NotifCollection and registers it to receive notification events. */
    public void attach(GroupCoalescer groupCoalescer) {
        Assert.isMainThread();
        if (mAttached) {
            throw new RuntimeException("attach() called twice");
        }
        mAttached = true;

        groupCoalescer.setNotificationHandler(mNotifHandler);
    }

    /**
     * Sets the class responsible for converting the collection into the list of currently-visible
     * notifications.
     */
    void setBuildListener(CollectionReadyForBuildListener buildListener) {
        Assert.isMainThread();
        mBuildListener = buildListener;
    }

    /** @see NotifPipeline#getActiveNotifs() */
    Collection<NotificationEntry> getActiveNotifs() {
        Assert.isMainThread();
        return mReadOnlyNotificationSet;
    }

    /** @see NotifPipeline#addCollectionListener(NotifCollectionListener) */
    void addCollectionListener(NotifCollectionListener listener) {
        Assert.isMainThread();
        mNotifCollectionListeners.add(listener);
    }

    /** @see NotifPipeline#addNotificationLifetimeExtender(NotifLifetimeExtender) */
    void addNotificationLifetimeExtender(NotifLifetimeExtender extender) {
        Assert.isMainThread();
        checkForReentrantCall();
        if (mLifetimeExtenders.contains(extender)) {
            throw new IllegalArgumentException("Extender " + extender + " already added.");
        }
        mLifetimeExtenders.add(extender);
        extender.setCallback(this::onEndLifetimeExtension);
    }

    /**
     * Dismiss a notification on behalf of the user.
     */
    void dismissNotification(
            NotificationEntry entry,
            @CancellationReason int reason,
            @NonNull DismissedByUserStats stats) {
        Assert.isMainThread();
        Objects.requireNonNull(stats);
        checkForReentrantCall();

        removeNotification(entry.getKey(), null, reason, stats);
    }

    private void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        Assert.isMainThread();

        postNotification(sbn, requireRanking(rankingMap, sbn.getKey()), rankingMap);
        rebuildList();
    }

    private void onNotificationGroupPosted(List<CoalescedEvent> batch) {
        Assert.isMainThread();

        mLogger.logNotifGroupPosted(batch.get(0).getSbn().getGroupKey(), batch.size());

        for (CoalescedEvent event : batch) {
            postNotification(event.getSbn(), event.getRanking(), null);
        }
        rebuildList();
    }

    private void onNotificationRemoved(
            StatusBarNotification sbn,
            RankingMap rankingMap,
            int reason) {
        Assert.isMainThread();

        mLogger.logNotifRemoved(sbn.getKey(), reason);
        removeNotification(sbn.getKey(), rankingMap, reason, null);
    }

    private void onNotificationRankingUpdate(RankingMap rankingMap) {
        Assert.isMainThread();
        applyRanking(rankingMap);
        dispatchNotificationRankingUpdate(rankingMap);
        rebuildList();
    }

    private void postNotification(
            StatusBarNotification sbn,
            Ranking ranking,
            @Nullable RankingMap rankingMap) {
        NotificationEntry entry = mNotificationSet.get(sbn.getKey());

        if (entry == null) {
            // A new notification!
            mLogger.logNotifPosted(sbn.getKey());

            entry = new NotificationEntry(sbn, ranking);
            mNotificationSet.put(sbn.getKey(), entry);
            dispatchOnEntryInit(entry);

            if (rankingMap != null) {
                applyRanking(rankingMap);
            }

            dispatchOnEntryAdded(entry);

        } else {
            // Update to an existing entry
            mLogger.logNotifUpdated(sbn.getKey());

            // Notification is updated so it is essentially re-added and thus alive again.  Don't
            // need to keep its lifetime extended.
            cancelLifetimeExtension(entry);

            entry.setSbn(sbn);
            if (rankingMap != null) {
                applyRanking(rankingMap);
            }

            dispatchOnEntryUpdated(entry);
        }
    }

    private void removeNotification(
            String key,
            @Nullable RankingMap rankingMap,
            @CancellationReason int reason,
            @Nullable DismissedByUserStats dismissedByUserStats) {

        NotificationEntry entry = mNotificationSet.get(key);
        if (entry == null) {
            throw new IllegalStateException("No notification to remove with key " + key);
        }

        entry.mLifetimeExtenders.clear();
        mAmDispatchingToOtherCode = true;
        for (NotifLifetimeExtender extender : mLifetimeExtenders) {
            if (extender.shouldExtendLifetime(entry, reason)) {
                entry.mLifetimeExtenders.add(extender);
            }
        }
        mAmDispatchingToOtherCode = false;

        if (!isLifetimeExtended(entry)) {
            mNotificationSet.remove(entry.getKey());

            if (dismissedByUserStats != null) {
                try {
                    mStatusBarService.onNotificationClear(
                            entry.getSbn().getPackageName(),
                            entry.getSbn().getTag(),
                            entry.getSbn().getId(),
                            entry.getSbn().getUser().getIdentifier(),
                            entry.getSbn().getKey(),
                            dismissedByUserStats.dismissalSurface,
                            dismissedByUserStats.dismissalSentiment,
                            dismissedByUserStats.notificationVisibility);
                } catch (RemoteException e) {
                    // system process is dead if we're here.
                }
            }

            if (rankingMap != null) {
                applyRanking(rankingMap);
            }

            dispatchOnEntryRemoved(entry, reason, dismissedByUserStats != null /* removedByUser */);
            dispatchOnEntryCleanUp(entry);
        }

        rebuildList();
    }

    private void applyRanking(@NonNull RankingMap rankingMap) {
        for (NotificationEntry entry : mNotificationSet.values()) {
            if (!isLifetimeExtended(entry)) {

                // TODO: (b/148791039) We should crash if we are ever handed a ranking with
                //  incomplete entries. Right now, there's a race condition in NotificationListener
                //  that means this might occur when SystemUI is starting up.
                Ranking ranking = new Ranking();
                if (rankingMap.getRanking(entry.getKey(), ranking)) {
                    entry.setRanking(ranking);

                    // TODO: (b/145659174) update the sbn's overrideGroupKey in
                    //  NotificationEntry.setRanking instead of here once we fully migrate to the
                    //  NewNotifPipeline
                    if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
                        final String newOverrideGroupKey = ranking.getOverrideGroupKey();
                        if (!Objects.equals(entry.getSbn().getOverrideGroupKey(),
                                newOverrideGroupKey)) {
                            entry.getSbn().setOverrideGroupKey(newOverrideGroupKey);
                        }
                    }
                }
            }
        }
    }

    private void rebuildList() {
        if (mBuildListener != null) {
            mBuildListener.onBuildList(mReadOnlyNotificationSet);
        }
    }

    private void onEndLifetimeExtension(NotifLifetimeExtender extender, NotificationEntry entry) {
        Assert.isMainThread();
        if (!mAttached) {
            return;
        }
        checkForReentrantCall();

        if (!entry.mLifetimeExtenders.remove(extender)) {
            throw new IllegalStateException(
                    String.format(
                            "Cannot end lifetime extension for extender \"%s\" (%s)",
                            extender.getName(),
                            extender));
        }

        if (!isLifetimeExtended(entry)) {
            // TODO: This doesn't need to be undefined -- we can set either EXTENDER_EXPIRED or
            // save the original reason
            removeNotification(entry.getKey(), null, REASON_UNKNOWN, null);
        }
    }

    private void cancelLifetimeExtension(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifLifetimeExtender extender : entry.mLifetimeExtenders) {
            extender.cancelLifetimeExtension(entry);
        }
        mAmDispatchingToOtherCode = false;
        entry.mLifetimeExtenders.clear();
    }

    private boolean isLifetimeExtended(NotificationEntry entry) {
        return entry.mLifetimeExtenders.size() > 0;
    }

    private void checkForReentrantCall() {
        if (mAmDispatchingToOtherCode) {
            throw new IllegalStateException("Reentrant call detected");
        }
    }

    private static Ranking requireRanking(RankingMap rankingMap, String key) {
        // TODO: Modify RankingMap so that we don't have to make a copy here
        Ranking ranking = new Ranking();
        if (!rankingMap.getRanking(key, ranking)) {
            throw new IllegalArgumentException("Ranking map doesn't contain key: " + key);
        }
        return ranking;
    }

    private void dispatchOnEntryInit(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryInit(entry);
        }
        mAmDispatchingToOtherCode = false;
    }

    private void dispatchOnEntryAdded(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryAdded(entry);
        }
        mAmDispatchingToOtherCode = false;
    }

    private void dispatchOnEntryUpdated(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryUpdated(entry);
        }
        mAmDispatchingToOtherCode = false;
    }

    private void dispatchNotificationRankingUpdate(RankingMap map) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingUpdate(map);
        }
        mAmDispatchingToOtherCode = false;
    }

    private void dispatchOnEntryRemoved(
            NotificationEntry entry,
            @CancellationReason int reason,
            boolean removedByUser) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryRemoved(entry, reason, removedByUser);
        }
        mAmDispatchingToOtherCode = false;
    }

    private void dispatchOnEntryCleanUp(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryCleanUp(entry);
        }
        mAmDispatchingToOtherCode = false;
    }

    private final BatchableNotificationHandler mNotifHandler = new BatchableNotificationHandler() {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            NotifCollection.this.onNotificationPosted(sbn, rankingMap);
        }

        @Override
        public void onNotificationBatchPosted(List<CoalescedEvent> events) {
            NotifCollection.this.onNotificationGroupPosted(events);
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
            NotifCollection.this.onNotificationRemoved(sbn, rankingMap, REASON_UNKNOWN);
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
                int reason) {
            NotifCollection.this.onNotificationRemoved(sbn, rankingMap, reason);
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            NotifCollection.this.onNotificationRankingUpdate(rankingMap);
        }
    };

    private static final String TAG = "NotifCollection";

    @IntDef(prefix = { "REASON_" }, value = {
            REASON_UNKNOWN,
            REASON_CLICK,
            REASON_CANCEL_ALL,
            REASON_ERROR,
            REASON_PACKAGE_CHANGED,
            REASON_USER_STOPPED,
            REASON_PACKAGE_BANNED,
            REASON_APP_CANCEL,
            REASON_APP_CANCEL_ALL,
            REASON_LISTENER_CANCEL,
            REASON_LISTENER_CANCEL_ALL,
            REASON_GROUP_SUMMARY_CANCELED,
            REASON_GROUP_OPTIMIZATION,
            REASON_PACKAGE_SUSPENDED,
            REASON_PROFILE_TURNED_OFF,
            REASON_UNAUTOBUNDLED,
            REASON_CHANNEL_BANNED,
            REASON_SNOOZED,
            REASON_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CancellationReason {}

    public static final int REASON_UNKNOWN = 0;

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final List<NotificationEntry> entries = new ArrayList<>(getActiveNotifs());

        pw.println("\t" + TAG + " unsorted/unfiltered notifications:");
        if (entries.size() == 0) {
            pw.println("\t\t None");
        }
        pw.println(
                ListDumper.dumpList(
                        entries,
                        true,
                        "\t\t"));
    }
}
