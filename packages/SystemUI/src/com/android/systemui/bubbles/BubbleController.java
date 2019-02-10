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

package com.android.systemui.bubbles;

import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.util.StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING;
import static android.util.StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE;
import static android.util.StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__DOCUMENT_LAUNCH_NOT_ALWAYS;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.NotificationAlertingManager.alertAgain;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.StatsLog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationInflater;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
@Singleton
public class BubbleController implements BubbleExpandedView.OnBubbleBlockedListener {
    private static final int MAX_BUBBLES = 5; // TODO: actually enforce this

    private static final String TAG = "BubbleController";

    // Enables some subset of notifs to automatically become bubbles
    private static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;

    // Secure settings flags
    // Feature level flag
    private static final String ENABLE_BUBBLES = "experiment_enable_bubbles";
    // Auto bubble flags set whether different notification types should be presented as a bubble
    private static final String ENABLE_AUTO_BUBBLE_MESSAGES = "experiment_autobubble_messaging";
    private static final String ENABLE_AUTO_BUBBLE_ONGOING = "experiment_autobubble_ongoing";
    private static final String ENABLE_AUTO_BUBBLE_ALL = "experiment_autobubble_all";
    // Use an activity view for an auto-bubbled notification if it has an appropriate content intent
    private static final String ENABLE_BUBBLE_CONTENT_INTENT = "experiment_bubble_content_intent";

    private final Context mContext;
    private final NotificationEntryManager mNotificationEntryManager;
    private final IActivityTaskManager mActivityTaskManager;
    private final BubbleTaskStackListener mTaskStackListener;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;
    private LayoutInflater mInflater;

    private final Map<String, BubbleView> mBubbles = new HashMap<>();
    private BubbleStackView mStackView;
    private final Point mDisplaySize;

    // Bubbles get added to the status bar view
    private final StatusBarWindowController mStatusBarWindowController;
    private StatusBarStateListener mStatusBarStateListener;

    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider =
            Dependency.get(NotificationInterruptionStateProvider.class);

    private INotificationManager mNotificationManagerService;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

    /**
     * Listener to be notified when some states of the bubbles change.
     */
    public interface BubbleStateChangeListener {
        /**
         * Called when the stack has bubbles or no longer has bubbles.
         */
        void onHasBubblesChanged(boolean hasBubbles);
    }

    /**
     * Listener to find out about stack expansion / collapse events.
     */
    public interface BubbleExpandListener {
        /**
         * Called when the expansion state of the bubble stack changes.
         * @param isExpanding whether it's expanding or collapsing
         * @param key the notification key associated with bubble being expanded
         */
        void onBubbleExpandChanged(boolean isExpanding, String key);
    }

    /**
     * Listens for the current state of the status bar and updates the visibility state
     * of bubbles as needed.
     */
    private class StatusBarStateListener implements StatusBarStateController.StateListener {
        private int mState;
        /**
         * Returns the current status bar state.
         */
        public int getCurrentState() {
            return mState;
        }

        @Override
        public void onStateChanged(int newState) {
            mState = newState;
            updateVisibility();
        }
    }

    @Inject
    public BubbleController(Context context, StatusBarWindowController statusBarWindowController) {
        mContext = context;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mNotificationEntryManager.addNotificationEntryListener(mEntryListener);

        try {
            mNotificationManagerService = INotificationManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            e.printStackTrace();
        }

        mStatusBarWindowController = statusBarWindowController;
        mStatusBarStateListener = new StatusBarStateListener();
        Dependency.get(StatusBarStateController.class).addCallback(mStatusBarStateListener);

        mActivityTaskManager = ActivityTaskManager.getService();
        mTaskStackListener = new BubbleTaskStackListener();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
    }

    /**
     * Set a listener to be notified when some states of the bubbles change.
     */
    public void setBubbleStateChangeListener(BubbleStateChangeListener listener) {
        mStateChangeListener = listener;
    }

    /**
     * Set a listener to be notified of bubble expand events.
     */
    public void setExpandListener(BubbleExpandListener listener) {
        mExpandListener = ((isExpanding, key) -> {
            if (listener != null) {
                listener.onBubbleExpandChanged(isExpanding, key);
            }
            mStatusBarWindowController.setBubbleExpanded(isExpanding);
        });
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    public boolean hasBubbles() {
        for (BubbleView bv : mBubbles.values()) {
            if (!bv.getEntry().isBubbleDismissed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isStackExpanded() {
        return mStackView != null && mStackView.isExpanded();
    }

    /**
     * Tell the stack of bubbles to collapse.
     */
    public void collapseStack() {
        if (mStackView != null) {
            mStackView.collapseStack();
        }
    }

    /**
     * Tell the stack of bubbles to be dismissed, this will remove all of the bubbles in the stack.
     */
    void dismissStack() {
        if (mStackView == null) {
            return;
        }
        Set<String> keys = mBubbles.keySet();
        for (String key: keys) {
            mBubbles.get(key).getEntry().setBubbleDismissed(true);
        }
        mStackView.stackDismissed();

        updateVisibility();
        mNotificationEntryManager.updateNotifications();
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     * @param updatePosition whether this update should promote the bubble to the top of the stack.
     */
    public void updateBubble(NotificationEntry notif, boolean updatePosition) {
        if (mBubbles.containsKey(notif.key)) {
            // It's an update
            BubbleView bubble = mBubbles.get(notif.key);
            mStackView.updateBubble(bubble, notif, updatePosition);
        } else {
            if (mStackView == null) {
                mStackView = new BubbleStackView(mContext);
                ViewGroup sbv = mStatusBarWindowController.getStatusBarView();
                // XXX: Bug when you expand the shade on top of expanded bubble, there is no scrim
                // between bubble and the shade
                int bubblePosition = sbv.indexOfChild(sbv.findViewById(R.id.scrim_behind)) + 1;
                sbv.addView(mStackView, bubblePosition,
                        new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
                if (mExpandListener != null) {
                    mStackView.setExpandListener(mExpandListener);
                }
                mStackView.setOnBlockedListener(this);
            }
            // It's new
            BubbleView bubble = (BubbleView) mInflater.inflate(
                    R.layout.bubble_view, mStackView, false /* attachToRoot */);
            bubble.setNotif(notif);
            PendingIntent bubbleIntent = getValidBubbleIntent(notif);
            if (bubbleIntent != null) {
                bubble.setBubbleIntent(bubbleIntent);
            }
            mBubbles.put(bubble.getKey(), bubble);
            mStackView.addBubble(bubble);
        }
        updateVisibility();
    }

    /**
     * Removes the bubble associated with the {@param uri}.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    void removeBubble(String key) {
        BubbleView bv = mBubbles.remove(key);
        if (mStackView != null && bv != null) {
            mStackView.removeBubble(bv);
            bv.destroyActivityView(mStackView);
        }

        NotificationEntry entry = bv != null ? bv.getEntry() : null;
        if (entry != null) {
            entry.setBubbleDismissed(true);
            mNotificationEntryManager.updateNotifications();
        }
        updateVisibility();
    }

    @Override
    public void onBubbleBlocked(NotificationEntry entry) {
        Object[] bubbles = mBubbles.values().toArray();
        for (int i = 0; i < bubbles.length; i++) {
            NotificationEntry e = ((BubbleView) bubbles[i]).getEntry();
            boolean samePackage = entry.notification.getPackageName().equals(
                    e.notification.getPackageName());
            if (samePackage) {
                removeBubble(entry.key);
            }
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (shouldAutoBubbleForFlags(mContext, entry) || shouldBubble(entry)) {
                // TODO: handle group summaries
                // It's a new notif, it shows in the shade and as a bubble
                entry.setIsBubble(true);
                entry.setShowInShadeWhenBubble(true);
            }
        }

        @Override
        public void onEntryInflated(NotificationEntry entry,
                @NotificationInflater.InflationFlag int inflatedFlags) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (entry.isBubble() && mNotificationInterruptionStateProvider.shouldBubbleUp(entry)) {
                updateBubble(entry, true /* updatePosition */);
            }
        }

        @Override
        public void onPreEntryUpdated(NotificationEntry entry) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && alertAgain(entry, entry.notification.getNotification())) {
                entry.setShowInShadeWhenBubble(true);
                entry.setBubbleDismissed(false); // updates come back as bubbles even if dismissed
                if (mBubbles.containsKey(entry.key)) {
                    mBubbles.get(entry.key).updateDotVisibility();
                }
                updateBubble(entry, true /* updatePosition */);
            }
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry,
                @Nullable NotificationVisibility visibility,
                boolean removedByUser) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            entry.setShowInShadeWhenBubble(false);
            if (mBubbles.containsKey(entry.key)) {
                mBubbles.get(entry.key).updateDotVisibility();
            }
            if (!removedByUser) {
                // This was a cancel so we should remove the bubble
                removeBubble(entry.key);
            }
        }
    };

    /**
     * Lets any listeners know if bubble state has changed.
     */
    private void updateBubblesShowing() {
        if (mStackView == null) {
            return;
        }

        boolean hadBubbles = mStatusBarWindowController.getBubblesShowing();
        boolean hasBubblesShowing = hasBubbles() && mStackView.getVisibility() == VISIBLE;
        mStatusBarWindowController.setBubblesShowing(hasBubblesShowing);
        if (mStateChangeListener != null && hadBubbles != hasBubblesShowing) {
            mStateChangeListener.onHasBubblesChanged(hasBubblesShowing);
        }
    }

    /**
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides. Will notify any
     * {@link BubbleStateChangeListener}s if visibility changes.
     */
    public void updateVisibility() {
        if (mStatusBarStateListener.getCurrentState() == SHADE && hasBubbles()) {
            // Bubbles only appear in unlocked shade
            mStackView.setVisibility(hasBubbles() ? VISIBLE : INVISIBLE);
        } else if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
            collapseStack();
        }
        updateBubblesShowing();
    }

    /**
     * Rect indicating the touchable region for the bubble stack / expanded stack.
     */
    public Rect getTouchableRegion() {
        if (mStackView == null || mStackView.getVisibility() != VISIBLE) {
            return null;
        }
        mStackView.getBoundsOnScreen(mTempRect);
        return mTempRect;
    }

    @VisibleForTesting
    BubbleStackView getStackView() {
        return mStackView;
    }

    @Nullable
    private PendingIntent getValidBubbleIntent(NotificationEntry notif) {
        Notification notification = notif.notification.getNotification();
        String packageName = notif.notification.getPackageName();
        Notification.BubbleMetadata data = notif.getBubbleMetadata();
        if (data != null && canLaunchInActivityView(data.getIntent(),
                true /* enable logging for bubbles */, packageName)) {
            return data.getIntent();
        }
        if (shouldUseContentIntent(mContext)
                && canLaunchInActivityView(notification.contentIntent,
                false /* disable logging for notifications */, packageName)) {
            Log.d(TAG, "[addBubble " + notif.key
                    + "]: No appOverlayIntent, using contentIntent.");
            return notification.contentIntent;
        }
        Log.d(TAG, "[addBubble " + notif.key + "]: No supported intent for ActivityView.");
        return null;
    }

    /**
     * Whether an intent is properly configured to display in an {@link android.app.ActivityView}.
     *
     * @param intent the pending intent of the bubble.
     * @param enableLogging whether bubble developer error should be logged.
     * @param packageName the notification package name for this bubble.
     * @return
     */
    private boolean canLaunchInActivityView(PendingIntent intent, boolean enableLogging,
                                            String packageName) {
        if (intent == null) {
            return false;
        }
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(mContext.getPackageManager(), 0);
        if (info == null) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING);
            }
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE);
            }
            return false;
        }
        if (info.documentLaunchMode != DOCUMENT_LAUNCH_ALWAYS) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__DOCUMENT_LAUNCH_NOT_ALWAYS);
            }
            return false;
        }
        return (info.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) != 0;
    }

    /**
     * Whether the notification has been developer configured to bubble and is allowed by the user.
     */
    @VisibleForTesting
    protected boolean shouldBubble(NotificationEntry entry) {
        StatusBarNotification n = entry.notification;
        boolean hasOverlayIntent = n.getNotification().getBubbleMetadata() != null
                && n.getNotification().getBubbleMetadata().getIntent() != null;
        return hasOverlayIntent && entry.canBubble;
    }

    /**
     * Whether the notification should automatically bubble or not. Gated by secure settings flags.
     */
    @VisibleForTesting
    protected boolean shouldAutoBubbleForFlags(Context context, NotificationEntry entry) {
        if (entry.isBubbleDismissed()) {
            return false;
        }
        StatusBarNotification n = entry.notification;

        boolean autoBubbleMessages = shouldAutoBubbleMessages(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleOngoing = shouldAutoBubbleOngoing(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleAll = shouldAutoBubbleAll(context) || DEBUG_ENABLE_AUTO_BUBBLE;

        boolean hasRemoteInput = false;
        if (n.getNotification().actions != null) {
            for (Notification.Action action : n.getNotification().actions) {
                if (action.getRemoteInputs() != null) {
                    hasRemoteInput = true;
                    break;
                }
            }
        }
        boolean isCall = Notification.CATEGORY_CALL.equals(n.getNotification().category)
                && n.isOngoing();
        boolean isMusic = n.getNotification().hasMediaSession();
        boolean isImportantOngoing = isMusic || isCall;

        Class<? extends Notification.Style> style = n.getNotification().getNotificationStyle();
        boolean isMessageType = Notification.CATEGORY_MESSAGE.equals(n.getNotification().category);
        boolean isMessageStyle = Notification.MessagingStyle.class.equals(style);
        return (((isMessageType && hasRemoteInput) || isMessageStyle) && autoBubbleMessages)
                || (isImportantOngoing && autoBubbleOngoing)
                || autoBubbleAll;
    }

    /**
     * This task stack listener is responsible for responding to tasks moved to the front
     * which are on the default (main) display. When this happens, expanded bubbles must be
     * collapsed so the user may interact with the app which was just moved to the front.
     * <p>
     * This listener is registered with SystemUI's ActivityManagerWrapper which dispatches
     * these calls via a main thread Handler.
     */
    @MainThread
    private class BubbleTaskStackListener extends TaskStackChangeListener {

        @Nullable
        private ActivityManager.StackInfo findStackInfo(int taskId) throws RemoteException {
            final List<ActivityManager.StackInfo> stackInfoList =
                    mActivityTaskManager.getAllStackInfos();
            // Iterate through stacks from top to bottom.
            final int stackCount = stackInfoList.size();
            for (int stackIndex = 0; stackIndex < stackCount; stackIndex++) {
                final ActivityManager.StackInfo stackInfo = stackInfoList.get(stackIndex);
                // Iterate through tasks from top to bottom.
                for (int taskIndex = stackInfo.taskIds.length - 1; taskIndex >= 0; taskIndex--) {
                    if (stackInfo.taskIds[taskIndex] == taskId) {
                        return stackInfo;
                    }
                }
            }
            return null;
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            ActivityManager.StackInfo stackInfo = null;
            try {
                stackInfo = findStackInfo(taskId);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
            if (stackInfo != null && stackInfo.displayId == Display.DEFAULT_DISPLAY
                    && mStackView != null) {
                mStackView.collapseStack();
            }
        }

        /**
         * This is a workaround for the case when the activity had to be created in a new task.
         * Existing code in ActivityStackSupervisor checks the display where the activity
         * ultimately ended up, displays an error message toast, and calls this method instead of
         * onTaskMovedToFront.
         */
        // TODO(b/124058588): add requestedDisplayId to this callback, ignore unless matches
        @Override
        public void onActivityLaunchOnSecondaryDisplayFailed() {
            if (mStackView != null) {
                mStackView.collapseStack();
            }
        }
    }

    private static boolean shouldAutoBubbleMessages(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_MESSAGES, 0) != 0;
    }

    private static boolean shouldAutoBubbleOngoing(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_ONGOING, 0) != 0;
    }

    private static boolean shouldAutoBubbleAll(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_ALL, 0) != 0;
    }

    private static boolean shouldUseContentIntent(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLE_CONTENT_INTENT, 0) != 0;
    }

    private static boolean areBubblesEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLES, 1) != 0;
    }
}
