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

package com.android.systemui.statusbar.notification.collection

import android.app.Notification
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MIN
import android.app.Person
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.internal.annotations.VisibleForTesting

import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationFilter
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.logging.NotifEvent
import com.android.systemui.statusbar.notification.logging.NotifLog
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationGroupManager
import com.android.systemui.statusbar.policy.HeadsUpManager

import java.util.Objects
import java.util.ArrayList

import javax.inject.Inject

import kotlin.Comparator

import dagger.Lazy

private const val TAG = "NotifRankingManager"

/**
 * NotificationRankingManager is responsible for holding on to the most recent [RankingMap], and
 * updating SystemUI's set of [NotificationEntry]s with their own ranking. It also sorts and filters
 * a set of entries (but retains none of them). We also set buckets on the entries here since
 * bucketing is tied closely to sorting.
 *
 * For the curious: this class is one iteration closer to null of what used to be called
 * NotificationData.java.
 */
open class NotificationRankingManager @Inject constructor(
    private val mediaManagerLazy: Lazy<NotificationMediaManager>,
    private val groupManager: NotificationGroupManager,
    private val headsUpManager: HeadsUpManager,
    private val notifFilter: NotificationFilter,
    private val notifLog: NotifLog,
    sectionsFeatureManager: NotificationSectionsFeatureManager
) {

    var rankingMap: RankingMap? = null
        protected set
    private val mediaManager by lazy {
        mediaManagerLazy.get()
    }
    private val usePeopleFiltering: Boolean = sectionsFeatureManager.isFilteringEnabled()
    private val rankingComparator: Comparator<NotificationEntry> = Comparator { a, b ->
        val na = a.sbn
        val nb = b.sbn
        val aRank = a.ranking.rank
        val bRank = b.ranking.rank

        val aMedia = isImportantMedia(a)
        val bMedia = isImportantMedia(b)

        val aSystemMax = a.isSystemMax()
        val bSystemMax = b.isSystemMax()

        val aHeadsUp = a.isRowHeadsUp
        val bHeadsUp = b.isRowHeadsUp

        if (usePeopleFiltering && a.isPeopleNotification() != b.isPeopleNotification()) {
            if (a.isPeopleNotification()) -1 else 1
        } else if (aHeadsUp != bHeadsUp) {
            if (aHeadsUp) -1 else 1
        } else if (aHeadsUp) {
            // Provide consistent ranking with headsUpManager
            headsUpManager.compare(a, b)
        } else if (aMedia != bMedia) {
            // Upsort current media notification.
            if (aMedia) -1 else 1
        } else if (aSystemMax != bSystemMax) {
            // Upsort PRIORITY_MAX system notifications
            if (aSystemMax) -1 else 1
        } else if (a.isHighPriority != b.isHighPriority) {
            -1 * java.lang.Boolean.compare(a.isHighPriority, b.isHighPriority)
        } else if (aRank != bRank) {
            aRank - bRank
        } else {
            nb.notification.`when`.compareTo(na.notification.`when`)
        }
    }

    private fun isImportantMedia(entry: NotificationEntry): Boolean {
        val importance = entry.ranking.importance
        return entry.key == mediaManager.mediaNotificationKey && importance > IMPORTANCE_MIN
    }

    @VisibleForTesting
    protected fun isHighPriority(entry: NotificationEntry): Boolean {
        if (entry.importance >= IMPORTANCE_DEFAULT ||
                hasHighPriorityCharacteristics(entry)) {
            return true
        }

        if (groupManager.isSummaryOfGroup(entry.sbn)) {
            val logicalChildren = groupManager.getLogicalChildren(entry.sbn)
            for (child in logicalChildren) {
                if (isHighPriority(child)) {
                    return true
                }
            }
        }

        return false
    }

    private fun hasHighPriorityCharacteristics(entry: NotificationEntry): Boolean {
        val c = entry.channel
        val n = entry.sbn.notification

        if (((n.isForegroundService && entry.ranking.importance >= IMPORTANCE_LOW) ||
            n.hasMediaSession() ||
            n.hasPerson() ||
            n.hasStyle(Notification.MessagingStyle::class.java))) {
            // Users who have long pressed and demoted to silent should not see the notification
            // in the top section
            if (c != null && c.hasUserSetImportance()) {
                return false
            }

            return true
        }

        return false
    }

    fun updateRanking(
        newRankingMap: RankingMap?,
        entries: Collection<NotificationEntry>,
        reason: String
    ): List<NotificationEntry> {
        val eSeq = entries.asSequence()

        // TODO: may not be ideal to guard on null here, but this code is implementing exactly what
        // NotificationData used to do
        if (newRankingMap != null) {
            rankingMap = newRankingMap
            updateRankingForEntries(eSeq)
        }

        val filtered: Sequence<NotificationEntry>
        synchronized(this) {
            filtered = filterAndSortLocked(eSeq, reason)
        }

        return filtered.toList()
    }

    /** Uses the [rankingComparator] to sort notifications which aren't filtered */
    private fun filterAndSortLocked(
        entries: Sequence<NotificationEntry>,
        reason: String
    ): Sequence<NotificationEntry> {
        notifLog.log(NotifEvent.FILTER_AND_SORT, reason)

        return entries.filter { !notifFilter.shouldFilterOut(it) }
                .sortedWith(rankingComparator)
                .map {
                    assignBucketForEntry(it)
                    it
                }
    }

    private fun assignBucketForEntry(entry: NotificationEntry) {
        val isHeadsUp = entry.isRowHeadsUp
        val isMedia = isImportantMedia(entry)
        val isSystemMax = entry.isSystemMax()
        setBucket(entry, isHeadsUp, isMedia, isSystemMax)
    }

    private fun setBucket(
        entry: NotificationEntry,
        isHeadsUp: Boolean,
        isMedia: Boolean,
        isSystemMax: Boolean
    ) {
        if (usePeopleFiltering && entry.hasAssociatedPeople()) {
            entry.bucket = BUCKET_PEOPLE
        } else if (isHeadsUp || isMedia || isSystemMax || entry.isHighPriority) {
            entry.bucket = BUCKET_ALERTING
        } else {
            entry.bucket = BUCKET_SILENT
        }
    }

    private fun updateRankingForEntries(entries: Sequence<NotificationEntry>) {
        rankingMap?.let { rankingMap ->
            synchronized(entries) {
                entries.forEach { entry ->
                    val newRanking = Ranking()
                    if (!rankingMap.getRanking(entry.key, newRanking)) {
                        return@forEach
                    }
                    entry.ranking = newRanking

                    val oldSbn = entry.sbn.cloneLight()
                    val newOverrideGroupKey = newRanking.overrideGroupKey
                    if (!Objects.equals(oldSbn.overrideGroupKey, newOverrideGroupKey)) {
                        entry.sbn.overrideGroupKey = newOverrideGroupKey
                        // TODO: notify group manager here?
                        groupManager.onEntryUpdated(entry, oldSbn)
                    }
                    entry.setIsHighPriority(isHighPriority(entry))
                }
            }
        }
    }
}

// Convenience functions
private fun NotificationEntry.isSystemMax(): Boolean {
    return importance >= IMPORTANCE_HIGH && sbn.isSystemNotification()
}

private fun StatusBarNotification.isSystemNotification(): Boolean {
    return "android" == packageName || "com.android.systemui" == packageName
}

private fun Notification.hasPerson(): Boolean {
    val people: ArrayList<Person> =
            (extras?.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)) ?: ArrayList()
    return people.isNotEmpty()
}

private fun Notification.hasStyle(targetStyleClass: Class<*>): Boolean {
    return targetStyleClass == notificationStyle
}

private fun NotificationEntry.isPeopleNotification(): Boolean =
        sbn.notification.hasStyle(Notification.MessagingStyle::class.java)
