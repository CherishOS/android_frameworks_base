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

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
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
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Handles inflating and updating views for notifications. */
@Singleton
public class NotificationRowBinderImpl implements NotificationRowBinder {

    private static final String TAG = "NotificationViewManager";

    private final NotificationGroupManager mGroupManager;
    private final NotificationGutsManager mGutsManager;
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private final Context mContext;
    private final NotificationRowContentBinder mRowContentBinder;
    private final NotificationMessagingUtil mMessagingUtil;
    private final ExpandableNotificationRow.ExpansionLogger mExpansionLogger =
            this::logNotificationExpansion;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    private final boolean mAllowLongPress;
    private final KeyguardBypassController mKeyguardBypassController;
    private final StatusBarStateController mStatusBarStateController;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private HeadsUpManager mHeadsUpManager;
    private NotificationRowContentBinder.InflationCallback mInflationCallback;
    private ExpandableNotificationRow.OnAppOpsClickListener mOnAppOpsClickListener;
    private BindRowCallback mBindRowCallback;
    private NotificationClicker mNotificationClicker;
    private final Provider<RowInflaterTask> mRowInflaterTaskProvider;
    private final NotificationLogger mNotificationLogger;

    @Inject
    public NotificationRowBinderImpl(
            Context context,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationRowContentBinder rowContentBinder,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            KeyguardBypassController keyguardBypassController,
            StatusBarStateController statusBarStateController,
            NotificationGroupManager notificationGroupManager,
            NotificationGutsManager notificationGutsManager,
            NotificationInterruptionStateProvider notificationInterruptionStateProvider,
            Provider<RowInflaterTask> rowInflaterTaskProvider,
            NotificationLogger logger) {
        mContext = context;
        mRowContentBinder = rowContentBinder;
        mMessagingUtil = new NotificationMessagingUtil(context);
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mAllowLongPress = allowLongPress;
        mKeyguardBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
        mGroupManager = notificationGroupManager;
        mGutsManager = notificationGutsManager;
        mNotificationInterruptionStateProvider = notificationInterruptionStateProvider;
        mRowInflaterTaskProvider = rowInflaterTaskProvider;
        mNotificationLogger = logger;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            HeadsUpManager headsUpManager,
            BindRowCallback bindRowCallback) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mHeadsUpManager = headsUpManager;
        mBindRowCallback = bindRowCallback;
        mOnAppOpsClickListener = mGutsManager::openGuts;
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
    public void inflateViews(
            NotificationEntry entry,
            Runnable onDismissRunnable)
            throws InflationException {
        ViewGroup parent = mListContainer.getViewParentForNotification(entry);
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                entry.getSbn().getUser().getIdentifier());

        final StatusBarNotification sbn = entry.getSbn();
        if (entry.rowExists()) {
            entry.updateIcons(mContext, sbn);
            entry.reset();
            updateNotification(entry, pmUser, sbn, entry.getRow());
            entry.getRow().setOnDismissRunnable(onDismissRunnable);
        } else {
            entry.createIcons(mContext, sbn);
            mRowInflaterTaskProvider.get().inflate(mContext, parent, entry,
                    row -> {
                        bindRow(entry, pmUser, sbn, row, onDismissRunnable);
                        updateNotification(entry, pmUser, sbn, row);
                    });
        }
    }

    private void bindRow(NotificationEntry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row,
            Runnable onDismissRunnable) {
        // Get the app name.
        // Note that Notification.Builder#bindHeaderAppName has similar logic
        // but since this field is used in the guts, it must be accurate.
        // Therefore we will only show the application label, or, failing that, the
        // package name. No substitutions.
        final String pkg = sbn.getPackageName();
        String appname = pkg;
        try {
            final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info != null) {
                appname = String.valueOf(pmUser.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing
        }

        row.initialize(
                appname,
                sbn.getKey(),
                mExpansionLogger,
                mKeyguardBypassController,
                mGroupManager,
                mHeadsUpManager,
                mRowContentBinder,
                mPresenter);

        // TODO: Either move these into ExpandableNotificationRow#initialize or out of row entirely
        row.setStatusBarStateController(mStatusBarStateController);
        row.setInflationCallback(mInflationCallback);
        row.setAppOpsOnClickListener(mOnAppOpsClickListener);
        if (mAllowLongPress) {
            row.setLongPressListener(mGutsManager::openGuts);
        }
        mListContainer.bindRow(row);
        mNotificationRemoteInputManager.bindRow(row);

        row.setOnDismissRunnable(onDismissRunnable);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (ENABLE_REMOTE_INPUT) {
            row.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

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

    //TODO: This method associates a row with an entry, but eventually needs to not do that
    private void updateNotification(
            NotificationEntry entry,
            PackageManager pmUser,
            StatusBarNotification sbn,
            ExpandableNotificationRow row) {
        row.setIsLowPriority(entry.isAmbient());

        // Extract target SDK version.
        try {
            ApplicationInfo info = pmUser.getApplicationInfo(sbn.getPackageName(), 0);
            entry.targetSdk = info.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        // TODO: should updates to the entry be happening somewhere else?
        entry.setIconTag(R.id.icon_is_pre_L, entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        entry.setRow(row);
        row.setOnActivatedListener(mPresenter);

        boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(sbn, entry.getImportance());
        boolean useIncreasedHeadsUp = useIncreasedCollapsedHeight
                && !mPresenter.isPresenterFullyCollapsed();
        row.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        row.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        row.setEntry(entry);

        if (mNotificationInterruptionStateProvider.shouldHeadsUp(entry)) {
            row.setInflationFlags(FLAG_CONTENT_VIEW_HEADS_UP);
        }
        row.setNeedsRedaction(mNotificationLockscreenUserManager.needsRedaction(entry));
        row.inflateViews();

        // bind the click event to the content area
        Objects.requireNonNull(mNotificationClicker).register(row, sbn);
    }

    private void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        mNotificationLogger.onExpansionChanged(key, userAction, expanded);
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
