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

package com.android.systemui.statusbar.notification.collection.inflation;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.ViewGroup;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Handles inflating and updating views for notifications. */
@Singleton
public class NotificationRowBinderImpl implements NotificationRowBinder {

    private static final String TAG = "NotificationViewManager";

    private final Context mContext;
    private final NotificationMessagingUtil mMessagingUtil;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    private final NotifBindPipeline mNotifBindPipeline;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final Provider<RowInflaterTask> mRowInflaterTaskProvider;
    private final ExpandableNotificationRowComponent.Builder
            mExpandableNotificationRowComponentBuilder;
    private final IconManager mIconManager;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private NotificationRowContentBinder.InflationCallback mInflationCallback;
    private BindRowCallback mBindRowCallback;
    private NotificationClicker mNotificationClicker;

    @Inject
    public NotificationRowBinderImpl(
            Context context,
            NotificationMessagingUtil notificationMessagingUtil,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotifBindPipeline notifBindPipeline,
            RowContentBindStage rowContentBindStage,
            NotificationInterruptStateProvider notificationInterruptionStateProvider,
            Provider<RowInflaterTask> rowInflaterTaskProvider,
            ExpandableNotificationRowComponent.Builder expandableNotificationRowComponentBuilder,
            IconManager iconManager) {
        mContext = context;
        mNotifBindPipeline = notifBindPipeline;
        mRowContentBindStage = rowContentBindStage;
        mMessagingUtil = notificationMessagingUtil;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mNotificationInterruptStateProvider = notificationInterruptionStateProvider;
        mRowInflaterTaskProvider = rowInflaterTaskProvider;
        mExpandableNotificationRowComponentBuilder = expandableNotificationRowComponentBuilder;
        mIconManager = iconManager;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            BindRowCallback bindRowCallback) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mBindRowCallback = bindRowCallback;

        mIconManager.attach();
    }

    public void setInflationCallback(NotificationRowContentBinder.InflationCallback callback) {
        mInflationCallback = callback;
    }

    public void setNotificationClicker(NotificationClicker clicker) {
        mNotificationClicker = clicker;
    }

    /**
     * Inflates the views for the given entry (possibly asynchronously).
     */
    @Override
    public void inflateViews(NotificationEntry entry, Runnable onDismissRunnable)
            throws InflationException {
        ViewGroup parent = mListContainer.getViewParentForNotification(entry);
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                entry.getSbn().getUser().getIdentifier());

        final StatusBarNotification sbn = entry.getSbn();
        if (entry.rowExists()) {
            mIconManager.updateIcons(entry);
            entry.reset();
            updateNotification(entry, pmUser, sbn, entry.getRow());
            entry.getRowController().setOnDismissRunnable(onDismissRunnable);
        } else {
            mIconManager.createIcons(entry);
            mRowInflaterTaskProvider.get().inflate(mContext, parent, entry,
                    row -> {
                        // Setup the controller for the view.
                        ExpandableNotificationRowComponent component =
                                mExpandableNotificationRowComponentBuilder
                                        .expandableNotificationRow(row)
                                        .notificationEntry(entry)
                                        .onDismissRunnable(onDismissRunnable)
                                        .inflationCallback(mInflationCallback)
                                        .rowContentBindStage(mRowContentBindStage)
                                        .onExpandClickListener(mPresenter)
                                        .build();
                        ExpandableNotificationRowController rowController =
                                component.getExpandableNotificationRowController();
                        rowController.init();
                        entry.setRowController(rowController);
                        bindRow(entry, pmUser, sbn, row);
                        updateNotification(entry, pmUser, sbn, row);
                    });
        }
    }

    //TODO: This method associates a row with an entry, but eventually needs to not do that
    private void bindRow(NotificationEntry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row) {
        mListContainer.bindRow(row);
        mNotificationRemoteInputManager.bindRow(row);
        entry.setRow(row);
        row.setEntry(entry);
        mNotifBindPipeline.manageRow(entry, row);
        mBindRowCallback.onBindRow(entry, pmUser, sbn, row);
    }

    /**
     * Updates the views bound to an entry when the entry's ranking changes, either in-place or by
     * reinflating them.
     */
    @Override
    public void onNotificationRankingUpdated(
            NotificationEntry entry,
            @Nullable Integer oldImportance,
            NotificationUiAdjustment oldAdjustment,
            NotificationUiAdjustment newAdjustment) {
        if (NotificationUiAdjustment.needReinflate(oldAdjustment, newAdjustment)) {
            if (entry.rowExists()) {
                entry.reset();
                PackageManager pmUser = StatusBar.getPackageManagerForUser(
                        mContext,
                        entry.getSbn().getUser().getIdentifier());
                updateNotification(entry, pmUser, entry.getSbn(), entry.getRow());
            } else {
                // Once the RowInflaterTask is done, it will pick up the updated entry, so
                // no-op here.
            }
        } else {
            if (oldImportance != null && entry.getImportance() != oldImportance) {
                if (entry.rowExists()) {
                    entry.getRow().onNotificationRankingUpdated();
                }
            }
        }
    }

    private void updateNotification(
            NotificationEntry entry,
            PackageManager pmUser,
            StatusBarNotification sbn,
            ExpandableNotificationRow row) {

        // Extract target SDK version.
        try {
            ApplicationInfo info = pmUser.getApplicationInfo(sbn.getPackageName(), 0);
            entry.targetSdk = info.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        // TODO: should this be happening somewhere else?
        mIconManager.updateIconTags(entry, entry.targetSdk);

        row.setOnActivatedListener(mPresenter);

        final boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(sbn, entry.getImportance());
        final boolean useIncreasedHeadsUp = useIncreasedCollapsedHeight
                && !mPresenter.isPresenterFullyCollapsed();
        final boolean isLowPriority = entry.isAmbient();

        RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        params.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        params.setUseLowPriority(entry.isAmbient());

        if (mNotificationInterruptStateProvider.shouldHeadsUp(entry)) {
            params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        }
        //TODO: Replace this API with RowContentBindParams directly
        row.setNeedsRedaction(mNotificationLockscreenUserManager.needsRedaction(entry));
        params.rebindAllContentViews();
        mRowContentBindStage.requestRebind(entry, en -> {
            row.setUsesIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
            row.setUsesIncreasedHeadsUpHeight(useIncreasedHeadsUp);
            row.setIsLowPriority(isLowPriority);
            mInflationCallback.onAsyncInflationFinished(en);
        });

        // bind the click event to the content area
        Objects.requireNonNull(mNotificationClicker).register(row, sbn);
    }

    /** Callback for when a row is bound to an entry. */
    public interface BindRowCallback {
        /**
         * Called when a new notification and row is created.
         *
         * @param entry  entry for the notification
         * @param pmUser package manager for user
         * @param sbn    notification
         * @param row    row for the notification
         */
        void onBindRow(NotificationEntry entry, PackageManager pmUser,
                StatusBarNotification sbn, ExpandableNotificationRow row);
    }
}
