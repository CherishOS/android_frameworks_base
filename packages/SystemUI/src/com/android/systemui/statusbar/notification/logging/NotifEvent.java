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

package com.android.systemui.statusbar.notification.logging;

import android.annotation.IntDef;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;

import com.android.systemui.log.RichEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event related to notifications. {@link NotifLog} stores and prints these events for debugging
 * and triaging purposes. We do not store a copy of the status bar notification nor ranking
 * here to mitigate memory usage.
 */
public class NotifEvent extends RichEvent {
    public static final int TOTAL_EVENT_TYPES = 11;

    /**
     * Creates a NotifEvent with an event type that matches with an index in the array
     * getSupportedEvents() and {@link EventType}.
     *
     * The status bar notification and ranking objects are stored as shallow copies of the current
     * state of the event when this event occurred.
     */
    public NotifEvent(int logLevel, int type, String reason, StatusBarNotification sbn,
            Ranking ranking) {
        super(logLevel, type, reason);
        mMessage += getExtraInfo(sbn, ranking);
    }

    private String getExtraInfo(StatusBarNotification sbn, Ranking ranking) {
        StringBuilder extraInfo = new StringBuilder();

        if (sbn != null) {
            extraInfo.append(" Sbn=");
            extraInfo.append(sbn);
        }

        if (ranking != null) {
            extraInfo.append(" Ranking=");
            extraInfo.append(ranking);
        }

        return extraInfo.toString();
    }

    /**
     * Event labels for NotifEvents
     * Index corresponds to the {@link EventType}
     */
    @Override
    public String[] getEventLabels() {
        final String[] events = new String[]{
                "NotifAdded",
                "NotifRemoved",
                "NotifUpdated",
                "Filter",
                "Sort",
                "FilterAndSort",
                "NotifVisibilityChanged",
                "LifetimeExtended",
                "RemoveIntercepted",
                "InflationAborted",
                "Inflated"
        };

        if (events.length != TOTAL_EVENT_TYPES) {
            throw new IllegalStateException("NotifEvents events.length should match "
                    + TOTAL_EVENT_TYPES
                    + " events.length=" + events.length
                    + " TOTAL_EVENT_LENGTH=" + TOTAL_EVENT_TYPES);
        }
        return events;
    }

    /**
     * Builds a NotifEvent.
     */
    public static class NotifEventBuilder extends RichEvent.Builder<NotifEventBuilder> {
        private StatusBarNotification mSbn;
        private Ranking mRanking;

        @Override
        public NotifEventBuilder getBuilder() {
            return this;
        }

        /**
         * Stores the status bar notification object. A shallow copy is stored in the NotifEvent's
         * constructor.
         */
        public NotifEventBuilder setSbn(StatusBarNotification sbn) {
            mSbn = sbn;
            return this;
        }

        /**
         * Stores the ranking object. A shallow copy is stored in the NotifEvent's
         * constructor.
         */
        public NotifEventBuilder setRanking(Ranking ranking) {
            mRanking = ranking;
            return this;
        }

        @Override
        public RichEvent build() {
            return new NotifEvent(mLogLevel, mType, mReason, mSbn, mRanking);
        }
    }

    @IntDef({NOTIF_ADDED,
            NOTIF_REMOVED,
            NOTIF_UPDATED,
            FILTER,
            SORT,
            FILTER_AND_SORT,
            NOTIF_VISIBILITY_CHANGED,
            LIFETIME_EXTENDED,
            REMOVE_INTERCEPTED,
            INFLATION_ABORTED,
            INFLATED
    })

    /**
     * Types of NotifEvents
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}
    public static final int NOTIF_ADDED = 0;
    public static final int NOTIF_REMOVED = 1;
    public static final int NOTIF_UPDATED = 2;
    public static final int FILTER = 3;
    public static final int SORT = 4;
    public static final int FILTER_AND_SORT = 5;
    public static final int NOTIF_VISIBILITY_CHANGED = 6;
    public static final int LIFETIME_EXTENDED = 7;
    // unable to remove notif - removal intercepted by {@link NotificationRemoveInterceptor}
    public static final int REMOVE_INTERCEPTED = 8;
    public static final int INFLATION_ABORTED = 9;
    public static final int INFLATED = 10;
}
