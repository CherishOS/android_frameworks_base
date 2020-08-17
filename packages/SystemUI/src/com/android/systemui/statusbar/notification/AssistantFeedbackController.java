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

package com.android.systemui.statusbar.notification;

import static android.service.notification.NotificationListenerService.Ranking;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;

/**
 * Determines whether to show any indicators or controls related to notification assistant.
 *
 * Flags protect any changes from being shown. Notifications that are adjusted by the assistant
 * should show an indicator.
 */
@SysUISingleton
public class AssistantFeedbackController extends ContentObserver {
    private final Uri FEEDBACK_URI
            = Settings.Global.getUriFor(Settings.Global.NOTIFICATION_FEEDBACK_ENABLED);
    private ContentResolver mResolver;

    private boolean mFeedbackEnabled;

    /** Injected constructor */
    @Inject
    public AssistantFeedbackController(Context context) {
        super(new Handler(Looper.getMainLooper()));
        mResolver = context.getContentResolver();
        mResolver.registerContentObserver(FEEDBACK_URI, false, this, UserHandle.USER_ALL);
        update(null);
    }

    @Override
    public void onChange(boolean selfChange, @Nullable Uri uri, int flags) {
        update(uri);
    }

    @VisibleForTesting
    public void update(@Nullable Uri uri) {
        if (uri == null || FEEDBACK_URI.equals(uri)) {
            mFeedbackEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.NOTIFICATION_FEEDBACK_ENABLED, 0)
                    != 0;
        }
    }

    /**
     * Determines whether to show any user controls related to the assistant. This is based on the
     * settings flag {@link Settings.Global.NOTIFICATION_FEEDBACK_ENABLED}
     */
    public boolean isFeedbackEnabled() {
        return mFeedbackEnabled;
    }

    /**
     * Determines whether to show feedback indicator. The feedback indicator will be shown if
     * {@link #isFeedbackEnabled()} is enabled and assistant has changed this notification's rank or
     * importance.
     *
     * @param entry Notification Entry to show feedback for
     */
    public boolean showFeedbackIndicator(NotificationEntry entry) {
        Ranking ranking = entry.getRanking();
        return isFeedbackEnabled()
                && (ranking.getImportance() != ranking.getChannel().getImportance()
                || ranking.getRankingAdjustment() != Ranking.RANKING_UNCHANGED);
    }
}
