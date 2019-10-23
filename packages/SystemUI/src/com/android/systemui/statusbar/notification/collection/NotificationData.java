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

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.logging.NotifEvent;
import com.android.systemui.statusbar.notification.logging.NotifLog;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {
    private static final String TAG = "NotificationData";

    private final NotificationFilter mNotificationFilter = Dependency.get(NotificationFilter.class);

    /**
     * These dependencies are late init-ed
     */
    private KeyguardEnvironment mEnvironment;
    private NotificationMediaManager mMediaManager;

    private HeadsUpManager mHeadsUpManager;

    private final ArrayMap<String, NotificationEntry> mEntries = new ArrayMap<>();
    private final ArrayList<NotificationEntry> mSortedAndFiltered = new ArrayList<>();
    private final ArrayList<NotificationEntry> mFilteredForUser = new ArrayList<>();

    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);

    private RankingMap mRankingMap;
    private final Ranking mTmpRanking = new Ranking();
    private final boolean mUsePeopleFiltering;
    private final NotifLog mNotifLog;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;

    @Inject
    public NotificationData(
            NotificationSectionsFeatureManager sectionsFeatureManager,
            NotifLog notifLog,
            PeopleNotificationIdentifier peopleNotificationIdentifier) {
        mUsePeopleFiltering = sectionsFeatureManager.isFilteringEnabled();
        mNotifLog = notifLog;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    @VisibleForTesting
    protected final Comparator<NotificationEntry> mRankingComparator =
            new Comparator<NotificationEntry>() {
        @Override
        public int compare(NotificationEntry a, NotificationEntry b) {
            final StatusBarNotification na = a.getSbn();
            final StatusBarNotification nb = b.getSbn();
            int aRank = getRank(a.getKey());
            int bRank = getRank(b.getKey());

            boolean aPeople = isPeopleNotification(a);
            boolean bPeople = isPeopleNotification(b);

            boolean aMedia = isImportantMedia(a);
            boolean bMedia = isImportantMedia(b);

            boolean aSystemMax = isSystemMax(a);
            boolean bSystemMax = isSystemMax(b);

            boolean aHeadsUp = a.isRowHeadsUp();
            boolean bHeadsUp = b.isRowHeadsUp();

            if (mUsePeopleFiltering && aPeople != bPeople) {
                return aPeople ? -1 : 1;
            } else if (aHeadsUp != bHeadsUp) {
                return aHeadsUp ? -1 : 1;
            } else if (aHeadsUp) {
                // Provide consistent ranking with headsUpManager
                return mHeadsUpManager.compare(a, b);
            } else if (aMedia != bMedia) {
                // Upsort current media notification.
                return aMedia ? -1 : 1;
            } else if (aSystemMax != bSystemMax) {
                // Upsort PRIORITY_MAX system notifications
                return aSystemMax ? -1 : 1;
            } else if (a.isHighPriority() != b.isHighPriority()) {
                return -1 * Boolean.compare(a.isHighPriority(), b.isHighPriority());
            } else if (aRank != bRank) {
                return aRank - bRank;
            } else {
                return Long.compare(nb.getNotification().when, na.getNotification().when);
            }
        }
    };

    private KeyguardEnvironment getEnvironment() {
        if (mEnvironment == null) {
            mEnvironment = Dependency.get(KeyguardEnvironment.class);
        }
        return mEnvironment;
    }

    private NotificationMediaManager getMediaManager() {
        if (mMediaManager == null) {
            mMediaManager = Dependency.get(NotificationMediaManager.class);
        }
        return mMediaManager;
    }

    /**
     * Returns the sorted list of active notifications (depending on {@link KeyguardEnvironment}
     *
     * <p>
     * This call doesn't update the list of active notifications. Call {@link #filterAndSort()}
     * when the environment changes.
     * <p>
     * Don't hold on to or modify the returned list.
     */
    public ArrayList<NotificationEntry> getActiveNotifications() {
        return mSortedAndFiltered;
    }

    public ArrayList<NotificationEntry> getNotificationsForCurrentUser() {
        mFilteredForUser.clear();

        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                final StatusBarNotification sbn = entry.getSbn();
                if (!getEnvironment().isNotificationForCurrentProfiles(sbn)) {
                    continue;
                }
                mFilteredForUser.add(entry);
            }
        }
        return mFilteredForUser;
    }

    public NotificationEntry get(String key) {
        return mEntries.get(key);
    }

    public void add(NotificationEntry entry) {
        synchronized (mEntries) {
            mEntries.put(entry.getSbn().getKey(), entry);
        }
        mGroupManager.onEntryAdded(entry);

        updateRankingAndSort(mRankingMap, "addEntry=" + entry.getSbn());
    }

    public NotificationEntry remove(String key, RankingMap ranking) {
        NotificationEntry removed;
        synchronized (mEntries) {
            removed = mEntries.remove(key);
        }
        if (removed == null) return null;
        mGroupManager.onEntryRemoved(removed);
        updateRankingAndSort(ranking, "removeEntry=" + removed.getSbn());
        return removed;
    }

    /** Updates the given notification entry with the provided ranking. */
    public void update(
            NotificationEntry entry,
            RankingMap ranking,
            StatusBarNotification notification,
            String reason) {
        updateRanking(ranking, reason);
        final StatusBarNotification oldNotification = entry.getSbn();
        entry.setSbn(notification);
        mGroupManager.onEntryUpdated(entry, oldNotification);
    }

    /**
     * Update ranking and trigger a re-sort
     */
    public void updateRanking(RankingMap ranking, String reason) {
        updateRankingAndSort(ranking, reason);
    }

    public void updateAppOp(int appOp, int uid, String pkg, String key, boolean showIcon) {
        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                if (uid == entry.getSbn().getUid()
                        && pkg.equals(entry.getSbn().getPackageName())
                        && key.equals(entry.getKey())) {
                    if (showIcon) {
                        entry.mActiveAppOps.add(appOp);
                    } else {
                        entry.mActiveAppOps.remove(appOp);
                    }
                }
            }
        }
    }

    /**
     * Returns true if this notification should be displayed in the high-priority notifications
     * section
     */
    public boolean isHighPriority(StatusBarNotification statusBarNotification) {
        if (mRankingMap != null) {
            getRanking(statusBarNotification.getKey(), mTmpRanking);
            if (mTmpRanking.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                    || hasHighPriorityCharacteristics(
                            mTmpRanking.getChannel(), statusBarNotification)) {
                return true;
            }
            if (mGroupManager.isSummaryOfGroup(statusBarNotification)) {
                final ArrayList<NotificationEntry> logicalChildren =
                        mGroupManager.getLogicalChildren(statusBarNotification);
                for (NotificationEntry child : logicalChildren) {
                    if (isHighPriority(child.getSbn())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasHighPriorityCharacteristics(NotificationChannel channel,
            StatusBarNotification statusBarNotification) {

        if (isImportantOngoing(statusBarNotification.getNotification())
                || statusBarNotification.getNotification().hasMediaSession()
                || hasPerson(statusBarNotification.getNotification())
                || hasStyle(statusBarNotification.getNotification(),
                Notification.MessagingStyle.class)) {
            // Users who have long pressed and demoted to silent should not see the notification
            // in the top section
            if (channel != null && channel.hasUserSetImportance()) {
                return false;
            }
            return true;
        }

        return false;
    }

    private boolean isImportantOngoing(Notification notification) {
        return notification.isForegroundService()
                && mTmpRanking.getImportance() >= NotificationManager.IMPORTANCE_LOW;
    }

    private boolean hasStyle(Notification notification, Class targetStyle) {
        Class<? extends Notification.Style> style = notification.getNotificationStyle();
        return targetStyle.equals(style);
    }

    private boolean hasPerson(Notification notification) {
        // TODO: cache favorite and recent contacts to check contact affinity
        ArrayList<Person> people = notification.extras != null
                ? notification.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
                : new ArrayList<>();
        return people != null && !people.isEmpty();
    }

    public boolean isAmbient(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isAmbient();
        }
        return false;
    }

    public int getVisibilityOverride(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getVisibilityOverride();
        }
        return Ranking.VISIBILITY_NO_OVERRIDE;
    }

    public List<SnoozeCriterion> getSnoozeCriteria(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getSnoozeCriteria();
        }
        return null;
    }

    public NotificationChannel getChannel(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getChannel();
        }
        return null;
    }

    public int getRank(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.getRank();
        }
        return 0;
    }

    private boolean isImportantMedia(NotificationEntry e) {
        int importance = e.getRanking().getImportance();
        boolean media = e.getKey().equals(getMediaManager().getMediaNotificationKey())
                && importance > NotificationManager.IMPORTANCE_MIN;

        return media;
    }

    private boolean isSystemMax(NotificationEntry e) {
        int importance = e.getRanking().getImportance();
        boolean sys = importance  >= NotificationManager.IMPORTANCE_HIGH
                && isSystemNotification(e.getSbn());

        return sys;
    }

    public boolean shouldHide(String key) {
        if (mRankingMap != null) {
            getRanking(key, mTmpRanking);
            return mTmpRanking.isSuspended();
        }
        return false;
    }

    private void updateRankingAndSort(RankingMap rankingMap, String reason) {
        if (rankingMap != null) {
            mRankingMap = rankingMap;
            synchronized (mEntries) {
                final int len = mEntries.size();
                for (int i = 0; i < len; i++) {
                    NotificationEntry entry = mEntries.valueAt(i);
                    Ranking newRanking = new Ranking();
                    if (!getRanking(entry.getKey(), newRanking)) {
                        continue;
                    }
                    entry.setRanking(newRanking);

                    final StatusBarNotification oldSbn = entry.getSbn().cloneLight();
                    final String overrideGroupKey = newRanking.getOverrideGroupKey();
                    if (!Objects.equals(oldSbn.getOverrideGroupKey(), overrideGroupKey)) {
                        entry.getSbn().setOverrideGroupKey(overrideGroupKey);
                        mGroupManager.onEntryUpdated(entry, oldSbn);
                    }
                    entry.setIsHighPriority(isHighPriority(entry.getSbn()));
                }
            }
        }
        filterAndSort(reason);
    }

    /**
     * Get the ranking from the current ranking map.
     *
     * @param key the key to look up
     * @param outRanking the ranking to populate
     *
     * @return {@code true} if the ranking was properly obtained.
     */
    @VisibleForTesting
    protected boolean getRanking(String key, Ranking outRanking) {
        return mRankingMap.getRanking(key, outRanking);
    }

    // TODO: This should not be public. Instead the Environment should notify this class when
    // anything changed, and this class should call back the UI so it updates itself.
    /**
     * Filters and sorts the list of notification entries
     */
    public void filterAndSort(String reason) {
        mNotifLog.log(NotifEvent.FILTER_AND_SORT, reason);
        mSortedAndFiltered.clear();

        synchronized (mEntries) {
            final int len = mEntries.size();
            for (int i = 0; i < len; i++) {
                NotificationEntry entry = mEntries.valueAt(i);

                if (mNotificationFilter.shouldFilterOut(entry)) {
                    continue;
                }

                mSortedAndFiltered.add(entry);
            }
        }

        Collections.sort(mSortedAndFiltered, mRankingComparator);

        int bucket = BUCKET_PEOPLE;
        for (NotificationEntry e : mSortedAndFiltered) {
            assignBucketForEntry(e);
            if (e.getBucket() < bucket) {
                android.util.Log.wtf(TAG, "Detected non-contiguous bucket!");
            }
            bucket = e.getBucket();
        }
    }

    private void assignBucketForEntry(NotificationEntry e) {
        boolean isHeadsUp = e.isRowHeadsUp();
        boolean isMedia = isImportantMedia(e);
        boolean isSystemMax = isSystemMax(e);

        setBucket(e, isHeadsUp, isMedia, isSystemMax);
    }

    private void setBucket(
            NotificationEntry e,
            boolean isHeadsUp,
            boolean isMedia,
            boolean isSystemMax) {
        if (mUsePeopleFiltering && isPeopleNotification(e)) {
            e.setBucket(BUCKET_PEOPLE);
        } else if (isHeadsUp || isMedia || isSystemMax || e.isHighPriority()) {
            e.setBucket(BUCKET_ALERTING);
        } else {
            e.setBucket(BUCKET_SILENT);
        }
    }

    private boolean isPeopleNotification(NotificationEntry e) {
        return mPeopleNotificationIdentifier.isPeopleNotification(e.getSbn());
    }

    public void dump(PrintWriter pw, String indent) {
        int filteredLen = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + filteredLen);
        int active;
        for (active = 0; active < filteredLen; active++) {
            NotificationEntry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }
        synchronized (mEntries) {
            int totalLen = mEntries.size();
            pw.print(indent);
            pw.println("inactive notifications: " + (totalLen - active));
            int inactiveCount = 0;
            for (int i = 0; i < totalLen; i++) {
                NotificationEntry entry = mEntries.valueAt(i);
                if (!mSortedAndFiltered.contains(entry)) {
                    dumpEntry(pw, indent, inactiveCount, entry);
                    inactiveCount++;
                }
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, NotificationEntry e) {
        getRanking(e.getKey(), mTmpRanking);
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.getKey() + " icon=" + e.icon);
        StatusBarNotification n = e.getSbn();
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " importance="
                + mTmpRanking.getImportance());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
    }

    private static boolean isSystemNotification(StatusBarNotification sbn) {
        String sbnPackage = sbn.getPackageName();
        return "android".equals(sbnPackage) || "com.android.systemui".equals(sbnPackage);
    }

    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface KeyguardEnvironment {
        boolean isDeviceProvisioned();
        boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
    }
}
