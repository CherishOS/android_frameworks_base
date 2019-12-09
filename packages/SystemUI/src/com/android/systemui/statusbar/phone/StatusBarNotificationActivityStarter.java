/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.service.notification.NotificationListenerService.REASON_CLICK;

import static com.android.systemui.statusbar.phone.StatusBar.getActivityOptions;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.view.RemoteAnimationAdapter;
import android.view.View;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.dagger.qualifiers.BgHandler;
import com.android.systemui.dagger.qualifiers.MainHandler;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Status bar implementation of {@link NotificationActivityStarter}.
 */
public class StatusBarNotificationActivityStarter implements NotificationActivityStarter {

    private static final String TAG = "NotifActivityStarter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Lazy<AssistManager> mAssistManagerLazy;
    private final NotificationGroupManager mGroupManager;
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final ShadeController mShadeController;
    private final StatusBar mStatusBar;
    private final KeyguardStateController mKeyguardStateController;
    private final ActivityStarter mActivityStarter;
    private final NotificationEntryManager mEntryManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private final MetricsLogger mMetricsLogger;
    private final Context mContext;
    private final NotificationPanelView mNotificationPanel;
    private final NotificationPresenter mPresenter;
    private final LockPatternUtils mLockPatternUtils;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardManager mKeyguardManager;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final IStatusBarService mBarService;
    private final CommandQueue mCommandQueue;
    private final IDreamManager mDreamManager;
    private final Handler mMainThreadHandler;
    private final Handler mBackgroundHandler;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final BubbleController mBubbleController;

    private boolean mIsCollapsingToShowActivityOverLockscreen;

    private StatusBarNotificationActivityStarter(Context context, CommandQueue commandQueue,
            Lazy<AssistManager> assistManagerLazy, NotificationPanelView panel,
            NotificationPresenter presenter, NotificationEntryManager entryManager,
            HeadsUpManagerPhone headsUpManager, ActivityStarter activityStarter,
            ActivityLaunchAnimator activityLaunchAnimator, IStatusBarService statusBarService,
            StatusBarStateController statusBarStateController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            KeyguardManager keyguardManager,
            IDreamManager dreamManager, NotificationRemoteInputManager remoteInputManager,
            StatusBarRemoteInputCallback remoteInputCallback, NotificationGroupManager groupManager,
            NotificationLockscreenUserManager lockscreenUserManager,
            ShadeController shadeController, StatusBar statusBar,
            KeyguardStateController keyguardStateController,
            NotificationInterruptionStateProvider notificationInterruptionStateProvider,
            MetricsLogger metricsLogger, LockPatternUtils lockPatternUtils,
            Handler mainThreadHandler, Handler backgroundHandler,
            ActivityIntentHelper activityIntentHelper, BubbleController bubbleController) {
        mContext = context;
        mNotificationPanel = panel;
        mPresenter = presenter;
        mHeadsUpManager = headsUpManager;
        mActivityLaunchAnimator = activityLaunchAnimator;
        mBarService = statusBarService;
        mCommandQueue = commandQueue;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardManager = keyguardManager;
        mDreamManager = dreamManager;
        mRemoteInputManager = remoteInputManager;
        mLockscreenUserManager = lockscreenUserManager;
        mShadeController = shadeController;
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mStatusBar = statusBar;
        mKeyguardStateController = keyguardStateController;
        mActivityStarter = activityStarter;
        mEntryManager = entryManager;
        mStatusBarStateController = statusBarStateController;
        mNotificationInterruptionStateProvider = notificationInterruptionStateProvider;
        mMetricsLogger = metricsLogger;
        mAssistManagerLazy = assistManagerLazy;
        mGroupManager = groupManager;
        mLockPatternUtils = lockPatternUtils;
        mBackgroundHandler = backgroundHandler;
        mEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onPendingEntryAdded(NotificationEntry entry) {
                handleFullScreenIntent(entry);
            }
        });
        mStatusBarRemoteInputCallback = remoteInputCallback;
        mMainThreadHandler = mainThreadHandler;
        mActivityIntentHelper = activityIntentHelper;
        mBubbleController = bubbleController;
    }

    /**
     * Called when a notification is clicked.
     *
     * @param sbn notification that was clicked
     * @param row row for that notification
     */
    @Override
    public void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row) {
        RemoteInputController controller = mRemoteInputManager.getController();
        if (controller.isRemoteInputActive(row.getEntry())
                && !TextUtils.isEmpty(row.getActiveRemoteInputText())) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            controller.closeRemoteInputs();
            return;
        }
        Notification notification = sbn.getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final boolean isBubble = row.getEntry().isBubble();

        // This code path is now executed for notification without a contentIntent.
        // The only valid case is Bubble notifications. Guard against other cases
        // entering here.
        if (intent == null && !isBubble) {
            Log.e(TAG, "onNotificationClicked called for non-clickable notification!");
            return;
        }

        boolean isActivityIntent = intent != null && intent.isActivity() && !isBubble;
        final boolean afterKeyguardGone = isActivityIntent
                && mActivityIntentHelper.wouldLaunchResolverActivity(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        final boolean wasOccluded = mStatusBar.isOccluded();
        boolean showOverLockscreen = mKeyguardStateController.isShowing() && intent != null
                && mActivityIntentHelper.wouldShowOverLockscreen(intent.getIntent(),
                mLockscreenUserManager.getCurrentUserId());
        ActivityStarter.OnDismissAction postKeyguardAction =
                () -> handleNotificationClickAfterKeyguardDismissed(
                        sbn, row, controller, intent,
                        isActivityIntent, wasOccluded, showOverLockscreen);
        if (showOverLockscreen) {
            mIsCollapsingToShowActivityOverLockscreen = true;
            postKeyguardAction.onDismiss();
        } else {
            mActivityStarter.dismissKeyguardThenExecute(
                    postKeyguardAction, null /* cancel */, afterKeyguardGone);
        }
    }

    private boolean handleNotificationClickAfterKeyguardDismissed(
            StatusBarNotification sbn,
            ExpandableNotificationRow row,
            RemoteInputController controller,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean wasOccluded,
            boolean showOverLockscreen) {
        // TODO: Some of this code may be able to move to NotificationEntryManager.
        if (mHeadsUpManager != null && mHeadsUpManager.isAlerting(sbn.getKey())) {
            // Release the HUN notification to the shade.

            if (mPresenter.isPresenterFullyCollapsed()) {
                HeadsUpUtil.setIsClickedHeadsUpNotification(row, true);
            }
            //
            // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
            // become canceled shortly by NoMan, but we can't assume that.
            mHeadsUpManager.removeNotification(sbn.getKey(),
                    true /* releaseImmediately */);
        }
        StatusBarNotification parentToCancel = null;
        if (shouldAutoCancel(sbn) && mGroupManager.isOnlyChildInGroup(sbn)) {
            StatusBarNotification summarySbn =
                    mGroupManager.getLogicalGroupSummary(sbn).getSbn();
            if (shouldAutoCancel(summarySbn)) {
                parentToCancel = summarySbn;
            }
        }
        final StatusBarNotification parentToCancelFinal = parentToCancel;
        final Runnable runnable = () -> handleNotificationClickAfterPanelCollapsed(
                sbn, row, controller, intent,
                isActivityIntent, wasOccluded, parentToCancelFinal);

        if (showOverLockscreen) {
            mShadeController.addPostCollapseAction(runnable);
            mShadeController.collapsePanel(true /* animate */);
        } else if (mKeyguardStateController.isShowing()
                && mStatusBar.isOccluded()) {
            mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
            mShadeController.collapsePanel();
        } else {
            mBackgroundHandler.postAtFrontOfQueue(runnable);
        }
        return !mNotificationPanel.isFullyCollapsed();
    }

    private void handleNotificationClickAfterPanelCollapsed(
            StatusBarNotification sbn,
            ExpandableNotificationRow row,
            RemoteInputController controller,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean wasOccluded,
            StatusBarNotification parentToCancelFinal) {
        String notificationKey = sbn.getKey();
        try {
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }
        // If we are launching a work activity and require to launch
        // separate work challenge, we defer the activity action and cancel
        // notification until work challenge is unlocked.
        if (isActivityIntent) {
            final int userId = intent.getCreatorUserHandle().getIdentifier();
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                    && mKeyguardManager.isDeviceLocked(userId)) {
                // TODO(b/28935539): should allow certain activities to
                // bypass work challenge
                if (mStatusBarRemoteInputCallback.startWorkChallengeIfNecessary(userId,
                        intent.getIntentSender(), notificationKey)) {
                    // Show work challenge, do not run PendingIntent and
                    // remove notification
                    collapseOnMainThread();
                    return;
                }
            }
        }
        Intent fillInIntent = null;
        NotificationEntry entry = row.getEntry();
        final boolean isBubble = entry.isBubble();
        CharSequence remoteInputText = null;
        if (!TextUtils.isEmpty(entry.remoteInputText)) {
            remoteInputText = entry.remoteInputText;
        }
        if (!TextUtils.isEmpty(remoteInputText) && !controller.isSpinning(notificationKey)) {
            fillInIntent = new Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT,
                    remoteInputText.toString());
        }
        if (isBubble) {
            expandBubbleStackOnMainThread(notificationKey);
        } else {
            startNotificationIntent(intent, fillInIntent, row, wasOccluded, isActivityIntent);
        }
        if (isActivityIntent || isBubble) {
            mAssistManagerLazy.get().hideAssist();
        }
        if (shouldCollapse()) {
            collapseOnMainThread();
        }

        final int count = mEntryManager.getActiveNotificationsCount();
        final int rank = entry.getRanking().getRank();
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(entry);
        final NotificationVisibility nv = NotificationVisibility.obtain(notificationKey,
                rank, count, true, location);
        try {
            mBarService.onNotificationClick(notificationKey, nv);
        } catch (RemoteException ex) {
            // system process is dead if we're here.
        }
        if (!isBubble) {
            if (parentToCancelFinal != null) {
                removeNotification(parentToCancelFinal);
            }
            if (shouldAutoCancel(sbn)
                    || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(
                    notificationKey)) {
                // Automatically remove all notifications that we may have kept around longer
                removeNotification(sbn);
            }
        }
        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    private void expandBubbleStackOnMainThread(String notificationKey) {
        if (Looper.getMainLooper().isCurrentThread()) {
            mBubbleController.expandStackAndSelectBubble(notificationKey);
        } else {
            mMainThreadHandler.post(
                    () -> mBubbleController.expandStackAndSelectBubble(notificationKey));
        }
    }

    private void startNotificationIntent(PendingIntent intent, Intent fillInIntent,
            View row, boolean wasOccluded, boolean isActivityIntent) {
        RemoteAnimationAdapter adapter = mActivityLaunchAnimator.getLaunchAnimation(row,
                wasOccluded);
        try {
            if (adapter != null) {
                ActivityTaskManager.getService()
                        .registerRemoteAnimationForNextActivityStart(
                                intent.getCreatorPackage(), adapter);
            }
            int launchResult = intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                    null, null, getActivityOptions(adapter));
            mActivityLaunchAnimator.setLaunchResult(launchResult, isActivityIntent);
        } catch (RemoteException | PendingIntent.CanceledException e) {
            // the stack trace isn't very helpful here.
            // Just log the exception message.
            Log.w(TAG, "Sending contentIntent failed: " + e);
            // TODO: Dismiss Keyguard.
        }
    }

    @Override
    public void startNotificationGutsIntent(final Intent intent, final int appUid,
            ExpandableNotificationRow row) {
        mActivityStarter.dismissKeyguardThenExecute(() -> {
            AsyncTask.execute(() -> {
                int launchResult = TaskStackBuilder.create(mContext)
                        .addNextIntentWithParentStack(intent)
                        .startActivities(getActivityOptions(
                                mActivityLaunchAnimator.getLaunchAnimation(
                                        row, mStatusBar.isOccluded())),
                                new UserHandle(UserHandle.getUserId(appUid)));
                mActivityLaunchAnimator.setLaunchResult(launchResult, true /* isActivityIntent */);
                if (shouldCollapse()) {
                    // Putting it back on the main thread, since we're touching views
                    mMainThreadHandler.post(() -> mCommandQueue.animateCollapsePanels(
                            CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */));
                }
            });
            return true;
        }, null, false /* afterKeyguardGone */);
    }

    private void handleFullScreenIntent(NotificationEntry entry) {
        if (mNotificationInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry)) {
            if (shouldSuppressFullScreenIntent(entry)) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: suppressed by DND: " + entry.getKey());
                }
            } else if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: not important enough: " + entry.getKey());
                }
            } else {
                // Stop screensaver if the notification has a fullscreen intent.
                // (like an incoming phone call)
                Dependency.get(UiOffloadThread.class).submit(() -> {
                    try {
                        mDreamManager.awaken();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });

                // not immersive & a fullscreen alert should be shown
                if (DEBUG) {
                    Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                            entry.getKey());
                    entry.getSbn().getNotification().fullScreenIntent.send();
                    entry.notifyFullScreenIntentLaunched();
                    mMetricsLogger.count("note_fullscreen", 1);
                } catch (PendingIntent.CanceledException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public boolean isCollapsingToShowActivityOverLockscreen() {
        return mIsCollapsingToShowActivityOverLockscreen;
    }

    private static boolean shouldAutoCancel(StatusBarNotification sbn) {
        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_AUTO_CANCEL) != Notification.FLAG_AUTO_CANCEL) {
            return false;
        }
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return false;
        }
        return true;
    }

    private void collapseOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            mShadeController.collapsePanel();
        } else {
            mMainThreadHandler.post(mShadeController::collapsePanel);
        }
    }

    private boolean shouldCollapse() {
        return mStatusBarStateController.getState() != StatusBarState.SHADE
                || !mActivityLaunchAnimator.isAnimationPending();
    }

    private boolean shouldSuppressFullScreenIntent(NotificationEntry entry) {
        if (mPresenter.isDeviceInVrMode()) {
            return true;
        }

        return entry.shouldSuppressFullScreenIntent();
    }

    private void removeNotification(StatusBarNotification notification) {
        // We have to post it to the UI thread for synchronization
        mMainThreadHandler.post(() -> {
            Runnable removeRunnable =
                    () -> mEntryManager.performRemoveNotification(notification, REASON_CLICK);
            if (mPresenter.isCollapsing()) {
                // To avoid lags we're only performing the remove
                // after the shade was collapsed
                mShadeController.addPostCollapseAction(removeRunnable);
            } else {
                removeRunnable.run();
            }
        });
    }

    /**
     * Public builder for {@link StatusBarNotificationActivityStarter}.
     */
    @Singleton
    public static class Builder {
        private final Context mContext;
        private final CommandQueue mCommandQueue;
        private final Lazy<AssistManager> mAssistManagerLazy;
        private final NotificationEntryManager mEntryManager;
        private final HeadsUpManagerPhone mHeadsUpManager;
        private final ActivityStarter mActivityStarter;
        private final IStatusBarService mStatusBarService;
        private final StatusBarStateController mStatusBarStateController;
        private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
        private final KeyguardManager mKeyguardManager;
        private final IDreamManager mDreamManager;
        private final NotificationRemoteInputManager mRemoteInputManager;
        private final StatusBarRemoteInputCallback mRemoteInputCallback;
        private final NotificationGroupManager mGroupManager;
        private final NotificationLockscreenUserManager mLockscreenUserManager;
        private final KeyguardStateController mKeyguardStateController;
        private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
        private final MetricsLogger mMetricsLogger;
        private final LockPatternUtils mLockPatternUtils;
        private final Handler mMainThreadHandler;
        private final Handler mBackgroundHandler;
        private final ActivityIntentHelper mActivityIntentHelper;
        private final BubbleController mBubbleController;
        private final SuperStatusBarViewFactory mSuperStatusBarViewFactory;
        private ShadeController mShadeController;
        private NotificationPresenter mNotificationPresenter;
        private ActivityLaunchAnimator mActivityLaunchAnimator;
        private StatusBar mStatusBar;

        @Inject
        public Builder(Context context,
                CommandQueue commandQueue,
                Lazy<AssistManager> assistManagerLazy,
                NotificationEntryManager entryManager,
                HeadsUpManagerPhone headsUpManager,
                ActivityStarter activityStarter,
                IStatusBarService statusBarService,
                StatusBarStateController statusBarStateController,
                StatusBarKeyguardViewManager statusBarKeyguardViewManager,
                KeyguardManager keyguardManager,
                IDreamManager dreamManager,
                NotificationRemoteInputManager remoteInputManager,
                StatusBarRemoteInputCallback remoteInputCallback,
                NotificationGroupManager groupManager,
                NotificationLockscreenUserManager lockscreenUserManager,
                KeyguardStateController keyguardStateController,
                NotificationInterruptionStateProvider notificationInterruptionStateProvider,
                MetricsLogger metricsLogger,
                LockPatternUtils lockPatternUtils,
                @MainHandler Handler mainThreadHandler,
                @BgHandler Handler backgroundHandler,
                ActivityIntentHelper activityIntentHelper,
                BubbleController bubbleController,
                SuperStatusBarViewFactory superStatusBarViewFactory) {
            mContext = context;
            mCommandQueue = commandQueue;
            mAssistManagerLazy = assistManagerLazy;
            mEntryManager = entryManager;
            mHeadsUpManager = headsUpManager;
            mActivityStarter = activityStarter;
            mStatusBarService = statusBarService;
            mStatusBarStateController = statusBarStateController;
            mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
            mKeyguardManager = keyguardManager;
            mDreamManager = dreamManager;
            mRemoteInputManager = remoteInputManager;
            mRemoteInputCallback = remoteInputCallback;
            mGroupManager = groupManager;
            mLockscreenUserManager = lockscreenUserManager;
            mKeyguardStateController = keyguardStateController;
            mNotificationInterruptionStateProvider = notificationInterruptionStateProvider;
            mMetricsLogger = metricsLogger;
            mLockPatternUtils = lockPatternUtils;
            mMainThreadHandler = mainThreadHandler;
            mBackgroundHandler = backgroundHandler;
            mActivityIntentHelper = activityIntentHelper;
            mBubbleController = bubbleController;
            mSuperStatusBarViewFactory = superStatusBarViewFactory;
        }

        /** Sets the status bar to use as {@link StatusBar} and {@link ShadeController}. */
        public Builder setStatusBar(StatusBar statusBar) {
            mStatusBar = statusBar;
            mShadeController = statusBar;
            return this;
        }

        public Builder setNotificationPresenter(NotificationPresenter notificationPresenter) {
            mNotificationPresenter = notificationPresenter;
            return this;
        }

        public Builder setActivityLaunchAnimator(ActivityLaunchAnimator activityLaunchAnimator) {
            mActivityLaunchAnimator = activityLaunchAnimator;
            return this;
        }

        public StatusBarNotificationActivityStarter build() {
            return new StatusBarNotificationActivityStarter(mContext,
                    mCommandQueue, mAssistManagerLazy,
                    mSuperStatusBarViewFactory.getNotificationPanelView(),
                    mNotificationPresenter,
                    mEntryManager,
                    mHeadsUpManager,
                    mActivityStarter,
                    mActivityLaunchAnimator,
                    mStatusBarService,
                    mStatusBarStateController,
                    mStatusBarKeyguardViewManager,
                    mKeyguardManager,
                    mDreamManager,
                    mRemoteInputManager,
                    mRemoteInputCallback,
                    mGroupManager,
                    mLockscreenUserManager,
                    mShadeController,
                    mStatusBar,
                    mKeyguardStateController,
                    mNotificationInterruptionStateProvider,
                    mMetricsLogger,
                    mLockPatternUtils,
                    mMainThreadHandler,
                    mBackgroundHandler,
                    mActivityIntentHelper,
                    mBubbleController);
        }
    }
}
