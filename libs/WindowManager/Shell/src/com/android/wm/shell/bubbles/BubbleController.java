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

package com.android.wm.shell.bubbles;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_BOTTOM;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_LEFT;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_NONE;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_RIGHT;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_AGED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_BLOCKED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_GROUP_CANCELLED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_INVALID_INTENT;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NOTIF_CANCEL;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NO_BUBBLE_UP;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NO_LONGER_BUBBLE;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_PACKAGE_REMOVED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_SHORTCUT_REMOVED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_USER_CHANGED;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseSetArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
public class BubbleController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleController" : TAG_BUBBLES;

    // TODO(b/173386799) keep in sync with Launcher3 and also don't do a broadcast
    public static final String TASKBAR_CHANGED_BROADCAST = "taskbarChanged";
    public static final String EXTRA_TASKBAR_CREATED = "taskbarCreated";
    public static final String EXTRA_BUBBLE_OVERFLOW_OPENED = "bubbleOverflowOpened";
    public static final String EXTRA_TASKBAR_VISIBLE = "taskbarVisible";
    public static final String EXTRA_TASKBAR_POSITION = "taskbarPosition";
    public static final String EXTRA_TASKBAR_ICON_SIZE = "taskbarIconSize";
    public static final String EXTRA_TASKBAR_BUBBLE_XY = "taskbarBubbleXY";
    public static final String EXTRA_TASKBAR_SIZE = "taskbarSize";
    public static final String LEFT_POSITION = "Left";
    public static final String RIGHT_POSITION = "Right";
    public static final String BOTTOM_POSITION = "Bottom";

    private final Context mContext;
    private final BubblesImpl mImpl = new BubblesImpl();
    private Bubbles.BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private final BubbleDataRepository mDataRepository;
    private BubbleLogger mLogger;
    private BubbleData mBubbleData;
    private View mBubbleScrim;
    @Nullable private BubbleStackView mStackView;
    private BubbleIconFactory mBubbleIconFactory;
    private BubblePositioner mBubblePositioner;
    private Bubbles.SysuiProxy mSysuiProxy;

    // Tracks the id of the current (foreground) user.
    private int mCurrentUserId;
    // Saves notification keys of active bubbles when users are switched.
    private final SparseSetArray<String> mSavedBubbleKeysPerUser;

    // Used when ranking updates occur and we check if things should bubble / unbubble
    private NotificationListenerService.Ranking mTmpRanking;

    // Callback that updates BubbleOverflowActivity on data change.
    @Nullable private BubbleData.Listener mOverflowListener = null;

    // Only load overflow data from disk once
    private boolean mOverflowDataLoaded = false;

    /**
     * When the shade status changes to SHADE (from anything but SHADE, like LOCKED) we'll select
     * this bubble and expand the stack.
     */
    @Nullable private BubbleEntry mNotifEntryToExpandOnShadeUnlock;

    private IStatusBarService mBarService;
    private WindowManager mWindowManager;

    // Used to post to main UI thread
    private final ShellExecutor mMainExecutor;

    /** LayoutParams used to add the BubbleStackView to the window manager. */
    private WindowManager.LayoutParams mWmLayoutParams;
    /** Whether or not the BubbleStackView has been added to the WindowManager. */
    private boolean mAddedToWindowManager = false;

    /** Last known orientation, used to detect orientation changes in {@link #onConfigChanged}. */
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    /**
     * Last known screen density, used to detect display size changes in {@link #onConfigChanged}.
     */
    private int mDensityDpi = Configuration.DENSITY_DPI_UNDEFINED;

    /**
     * Last known font scale, used to detect font size changes in {@link #onConfigChanged}.
     */
    private float mFontScale = 0;

    /** Last known direction, used to detect layout direction changes @link #onConfigChanged}. */
    private int mLayoutDirection = View.LAYOUT_DIRECTION_UNDEFINED;

    private boolean mInflateSynchronously;

    private ShellTaskOrganizer mTaskOrganizer;

    /**
     * Whether the IME is visible, as reported by the BubbleStackView. If it is, we'll make the
     * Bubbles window NOT_FOCUSABLE so that touches on the Bubbles UI doesn't steal focus from the
     * ActivityView and hide the IME.
     */
    private boolean mImeVisible = false;

    /** true when user is in status bar unlock shade. */
    private boolean mIsStatusBarShade = true;

    /**
     * Injected constructor.
     */
    public static Bubbles create(Context context,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            @Nullable IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            UiEventLogger uiEventLogger,
            ShellTaskOrganizer organizer,
            ShellExecutor mainExecutor,
            Handler mainHandler) {
        BubbleLogger logger = new BubbleLogger(uiEventLogger);
        BubblePositioner positioner = new BubblePositioner(context, windowManager);
        BubbleData data = new BubbleData(context, logger, positioner, mainExecutor);
        return new BubbleController(context, data, synchronizer, floatingContentCoordinator,
                new BubbleDataRepository(context, launcherApps, mainExecutor),
                statusBarService, windowManager, windowManagerShellWrapper, launcherApps,
                logger, organizer, positioner, mainExecutor, mainHandler).mImpl;
    }

    /**
     * Testing constructor.
     */
    @VisibleForTesting
    public BubbleController(Context context,
            BubbleData data,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            BubbleDataRepository dataRepository,
            @Nullable IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            BubbleLogger bubbleLogger,
            ShellTaskOrganizer organizer,
            BubblePositioner positioner,
            ShellExecutor mainExecutor,
            Handler mainHandler) {
        mContext = context;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mDataRepository = dataRepository;
        mLogger = bubbleLogger;
        mMainExecutor = mainExecutor;

        mBubblePositioner = positioner;
        mBubbleData = data;
        mBubbleData.setListener(mBubbleDataListener);
        mBubbleData.setSuppressionChangedListener(bubble -> {
            // Make sure NoMan knows it's not showing in the shade anymore so anyone querying it
            // can tell.
            try {
                mBarService.onBubbleNotificationSuppressionChanged(bubble.getKey(),
                        !bubble.showInShade());
            } catch (RemoteException e) {
                // Bad things have happened
            }
        });
        mBubbleData.setPendingIntentCancelledListener(bubble -> {
            if (bubble.getBubbleIntent() == null) {
                return;
            }
            if (bubble.isIntentActive() || mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                bubble.setPendingIntentCanceled();
                return;
            }
            mMainExecutor.execute(() -> removeBubble(bubble.getKey(), DISMISS_INVALID_INTENT));
        });

        try {
            windowManagerShellWrapper.addPinnedStackListener(new BubblesImeListener());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mSurfaceSynchronizer = synchronizer;

        mWindowManager = windowManager;
        mBarService = statusBarService == null
                ? IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE))
                : statusBarService;

        mSavedBubbleKeysPerUser = new SparseSetArray<>();
        mCurrentUserId = ActivityManager.getCurrentUser();
        mBubbleData.setCurrentUserId(mCurrentUserId);

        mBubbleIconFactory = new BubbleIconFactory(context);
        mTaskOrganizer = organizer;

        launcherApps.registerCallback(new LauncherApps.Callback() {
            @Override
            public void onPackageAdded(String s, UserHandle userHandle) {}

            @Override
            public void onPackageChanged(String s, UserHandle userHandle) {}

            @Override
            public void onPackageRemoved(String s, UserHandle userHandle) {
                // Remove bubbles with this package name, since it has been uninstalled and attempts
                // to open a bubble from an uninstalled app can cause issues.
                mBubbleData.removeBubblesWithPackageName(s, DISMISS_PACKAGE_REMOVED);
            }

            @Override
            public void onPackagesAvailable(String[] strings, UserHandle userHandle, boolean b) {}

            @Override
            public void onPackagesUnavailable(String[] packages, UserHandle userHandle,
                    boolean b) {
                for (String packageName : packages) {
                    // Remove bubbles from unavailable apps. This can occur when the app is on
                    // external storage that has been removed.
                    mBubbleData.removeBubblesWithPackageName(packageName, DISMISS_PACKAGE_REMOVED);
                }
            }

            @Override
            public void onShortcutsChanged(String packageName, List<ShortcutInfo> validShortcuts,
                    UserHandle user) {
                super.onShortcutsChanged(packageName, validShortcuts, user);

                // Remove bubbles whose shortcuts aren't in the latest list of valid shortcuts.
                mBubbleData.removeBubblesWithInvalidShortcuts(
                        packageName, validShortcuts, DISMISS_SHORTCUT_REMOVED);
            }
        }, mainHandler);
    }

    @VisibleForTesting
    public Bubbles getImpl() {
        return mImpl;
    }

    /**
     * Hides the current input method, wherever it may be focused, via InputMethodManagerInternal.
     */
    void hideCurrentInputMethod() {
        try {
            mBarService.hideCurrentInputMethodForBubbles();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void openBubbleOverflow() {
        ensureStackViewCreated();
        mBubbleData.setShowingOverflow(true);
        mBubbleData.setSelectedBubble(mBubbleData.getOverflow());
        mBubbleData.setExpanded(true);
    }

    /** Called when any taskbar state changes (e.g. visibility, position, sizes). */
    private void onTaskbarChanged(Bundle b) {
        if (b == null) {
            return;
        }
        boolean isVisible = b.getBoolean(EXTRA_TASKBAR_VISIBLE, false /* default */);
        String position = b.getString(EXTRA_TASKBAR_POSITION, RIGHT_POSITION /* default */);
        @BubblePositioner.TaskbarPosition int taskbarPosition = TASKBAR_POSITION_NONE;
        switch (position) {
            case LEFT_POSITION:
                taskbarPosition = TASKBAR_POSITION_LEFT;
                break;
            case RIGHT_POSITION:
                taskbarPosition = TASKBAR_POSITION_RIGHT;
                break;
            case BOTTOM_POSITION:
                taskbarPosition = TASKBAR_POSITION_BOTTOM;
                break;
        }
        int[] itemPosition = b.getIntArray(EXTRA_TASKBAR_BUBBLE_XY);
        int iconSize = b.getInt(EXTRA_TASKBAR_ICON_SIZE);
        int taskbarSize = b.getInt(EXTRA_TASKBAR_SIZE);
        Log.w(TAG, "onTaskbarChanged:"
                + " isVisible: " + isVisible
                + " position: " + position
                + " itemPosition: " + itemPosition[0] + "," + itemPosition[1]
                + " iconSize: " + iconSize);
        PointF point = new PointF(itemPosition[0], itemPosition[1]);
        mBubblePositioner.setPinnedLocation(isVisible ? point : null);
        mBubblePositioner.updateForTaskbar(iconSize, taskbarPosition, isVisible, taskbarSize);
        if (mStackView != null) {
            if (isVisible && b.getBoolean(EXTRA_TASKBAR_CREATED, false /* default */)) {
                // If taskbar was created, add and remove the window so that bubbles display on top
                removeFromWindowManagerMaybe();
                addToWindowManagerMaybe();
            }
            mStackView.updateStackPosition();
            mBubbleIconFactory = new BubbleIconFactory(mContext);
            mStackView.onDisplaySizeChanged();
        }
        if (b.getBoolean(EXTRA_BUBBLE_OVERFLOW_OPENED, false)) {
            openBubbleOverflow();
        }
    }

    /**
     * Called when the status bar has become visible or invisible (either permanently or
     * temporarily).
     */
    private void onStatusBarVisibilityChanged(boolean visible) {
        if (mStackView != null) {
            // Hide the stack temporarily if the status bar has been made invisible, and the stack
            // is collapsed. An expanded stack should remain visible until collapsed.
            mStackView.setTemporarilyInvisible(!visible && !isStackExpanded());
        }
    }

    private void onZenStateChanged() {
        for (Bubble b : mBubbleData.getBubbles()) {
            b.setShowDot(b.showInShade());
        }
    }

    private void onStatusBarStateChanged(boolean isShade) {
        mIsStatusBarShade = isShade;
        if (!mIsStatusBarShade) {
            collapseStack();
        }

        if (mNotifEntryToExpandOnShadeUnlock != null) {
            expandStackAndSelectBubble(mNotifEntryToExpandOnShadeUnlock);
            mNotifEntryToExpandOnShadeUnlock = null;
        }

        updateStack();
    }

    private void onUserChanged(int newUserId) {
        saveBubbles(mCurrentUserId);
        mBubbleData.dismissAll(DISMISS_USER_CHANGED);
        restoreBubbles(newUserId);
        mCurrentUserId = newUserId;
        mBubbleData.setCurrentUserId(newUserId);
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    public void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    /** Set a listener to be notified of when overflow view update. */
    public void setOverflowListener(BubbleData.Listener listener) {
        mOverflowListener = listener;
    }

    /**
     * @return Bubbles for updating overflow.
     */
    List<Bubble> getOverflowBubbles() {
        return mBubbleData.getOverflowBubbles();
    }

    /** The task listener for events in bubble tasks. */
    public ShellTaskOrganizer getTaskOrganizer() {
        return mTaskOrganizer;
    }

    /** Contains information to help position things on the screen.  */
    BubblePositioner getPositioner() {
        return mBubblePositioner;
    }

    Bubbles.SysuiProxy getSysuiProxy() {
        return mSysuiProxy;
    }

    /**
     * BubbleStackView is lazily created by this method the first time a Bubble is added. This
     * method initializes the stack view and adds it to the StatusBar just above the scrim.
     */
    private void ensureStackViewCreated() {
        if (mStackView == null) {
            mStackView = new BubbleStackView(
                    mContext, this, mBubbleData, mSurfaceSynchronizer, mFloatingContentCoordinator,
                    mMainExecutor);
            mStackView.onOrientationChanged();
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
            }
            mStackView.setUnbubbleConversationCallback(mSysuiProxy::onUnbubbleConversation);
        }

        addToWindowManagerMaybe();
    }

    /** Adds the BubbleStackView to the WindowManager if it's not already there. */
    private void addToWindowManagerMaybe() {
        // If the stack is null, or already added, don't add it.
        if (mStackView == null || mAddedToWindowManager) {
            return;
        }

        mWmLayoutParams = new WindowManager.LayoutParams(
                // Fill the screen so we can use translation animations to position the bubble
                // stack. We'll use touchable regions to ignore touches that are not on the bubbles
                // themselves.
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        mWmLayoutParams.setTrustedOverlay();
        mWmLayoutParams.setFitInsetsTypes(0);
        mWmLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mWmLayoutParams.token = new Binder();
        mWmLayoutParams.setTitle("Bubbles!");
        mWmLayoutParams.packageName = mContext.getPackageName();
        mWmLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        try {
            mAddedToWindowManager = true;
            mBubbleData.getOverflow().initialize(this);
            mStackView.addView(mBubbleScrim);
            mWindowManager.addView(mStackView, mWmLayoutParams);
            // Position info is dependent on us being attached to a window
            mBubblePositioner.update(mOrientation);
        } catch (IllegalStateException e) {
            // This means the stack has already been added. This shouldn't happen...
            e.printStackTrace();
        }
    }

    void onImeVisibilityChanged(boolean imeVisible) {
        mImeVisible = imeVisible;
    }

    /** Removes the BubbleStackView from the WindowManager if it's there. */
    private void removeFromWindowManagerMaybe() {
        if (!mAddedToWindowManager) {
            return;
        }

        try {
            mAddedToWindowManager = false;
            if (mStackView != null) {
                mWindowManager.removeView(mStackView);
                mStackView.removeView(mBubbleScrim);
                mBubbleData.getOverflow().cleanUpExpandedState();
            } else {
                Log.w(TAG, "StackView added to WindowManager, but was null when removing!");
            }
        } catch (IllegalArgumentException e) {
            // This means the stack has already been removed - it shouldn't happen, but ignore if it
            // does, since we wanted it removed anyway.
            e.printStackTrace();
        }
    }

    /**
     * Called by the BubbleStackView and whenever all bubbles have animated out, and none have been
     * added in the meantime.
     */
    void onAllBubblesAnimatedOut() {
        if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
            removeFromWindowManagerMaybe();
        }
    }

    /**
     * Records the notification key for any active bubbles. These are used to restore active
     * bubbles when the user returns to the foreground.
     *
     * @param userId the id of the user
     */
    private void saveBubbles(@UserIdInt int userId) {
        // First clear any existing keys that might be stored.
        mSavedBubbleKeysPerUser.remove(userId);
        // Add in all active bubbles for the current user.
        for (Bubble bubble: mBubbleData.getBubbles()) {
            mSavedBubbleKeysPerUser.add(userId, bubble.getKey());
        }
    }

    /**
     * Promotes existing notifications to Bubbles if they were previously bubbles.
     *
     * @param userId the id of the user
     */
    private void restoreBubbles(@UserIdInt int userId) {
        ArraySet<String> savedBubbleKeys = mSavedBubbleKeysPerUser.get(userId);
        if (savedBubbleKeys == null) {
            // There were no bubbles saved for this used.
            return;
        }
        for (BubbleEntry e : mSysuiProxy.getShouldRestoredEntries(savedBubbleKeys)) {
            if (canLaunchInActivityView(mContext, e)) {
                updateBubble(e, true /* suppressFlyout */, false /* showInShade */);
            }
        }
        // Finally, remove the entries for this user now that bubbles are restored.
        mSavedBubbleKeysPerUser.remove(mCurrentUserId);
    }

    private void updateForThemeChanges() {
        if (mStackView != null) {
            mStackView.onThemeChanged();
        }
        mBubbleIconFactory = new BubbleIconFactory(mContext);
        // Reload each bubble
        for (Bubble b: mBubbleData.getBubbles()) {
            b.inflate(null /* callback */, mContext, this, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
        for (Bubble b: mBubbleData.getOverflowBubbles()) {
            b.inflate(null /* callback */, mContext, this, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
    }

    private void onConfigChanged(Configuration newConfig) {
        if (mBubblePositioner != null) {
            // This doesn't trigger any changes, always update it
            mBubblePositioner.update(newConfig.orientation);
        }
        if (mStackView != null && newConfig != null) {
            if (newConfig.orientation != mOrientation) {
                mOrientation = newConfig.orientation;
                mStackView.onOrientationChanged();
            }
            if (newConfig.densityDpi != mDensityDpi) {
                mDensityDpi = newConfig.densityDpi;
                mBubbleIconFactory = new BubbleIconFactory(mContext);
                mStackView.onDisplaySizeChanged();
            }
            if (newConfig.fontScale != mFontScale) {
                mFontScale = newConfig.fontScale;
                mStackView.updateFontScale(mFontScale);
            }
            if (newConfig.getLayoutDirection() != mLayoutDirection) {
                mLayoutDirection = newConfig.getLayoutDirection();
                mStackView.onLayoutDirectionChanged(mLayoutDirection);
            }
        }
    }

    private void setBubbleScrim(View view, BiConsumer<Executor, Looper> callback) {
        mBubbleScrim = view;
        callback.accept(mMainExecutor, mMainExecutor.executeBlockingForResult(() -> {
            return Looper.myLooper();
        }, Looper.class));
    }

    private void setSysuiProxy(Bubbles.SysuiProxy proxy) {
        mSysuiProxy = proxy;
    }

    @VisibleForTesting
    public void setExpandListener(Bubbles.BubbleExpandListener listener) {
        mExpandListener = ((isExpanding, key) -> {
            if (listener != null) {
                listener.onBubbleExpandChanged(isExpanding, key);
            }
        });
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    @VisibleForTesting
    public boolean hasBubbles() {
        if (mStackView == null) {
            return false;
        }
        return mBubbleData.hasBubbles() || mBubbleData.isShowingOverflow();
    }

    @VisibleForTesting
    public boolean isStackExpanded() {
        return mBubbleData.isExpanded();
    }

    @VisibleForTesting
    public void collapseStack() {
        mBubbleData.setExpanded(false /* expanded */);
    }

    @VisibleForTesting
    public boolean isBubbleNotificationSuppressedFromShade(String key, String groupKey) {
        boolean isSuppressedBubble = (mBubbleData.hasAnyBubbleWithKey(key)
                && !mBubbleData.getAnyBubbleWithkey(key).showInShade());

        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey);
        boolean isSummary = key.equals(mBubbleData.getSummaryKey(groupKey));
        return (isSummary && isSuppressedSummary) || isSuppressedBubble;
    }

    private void removeSuppressedSummaryIfNecessary(String groupKey, Consumer<String> callback,
            Executor callbackExecutor) {
        if (mBubbleData.isSummarySuppressed(groupKey)) {
            mBubbleData.removeSuppressedSummary(groupKey);
            if (callback != null) {
                callbackExecutor.execute(() -> {
                    callback.accept(mBubbleData.getSummaryKey(groupKey));
                });
            }
        }
    }

    private boolean isBubbleExpanded(String key) {
        return isStackExpanded() && mBubbleData != null && mBubbleData.getSelectedBubble() != null
                && mBubbleData.getSelectedBubble().getKey().equals(key);
    }

    /** Promote the provided bubble from the overflow view. */
    public void promoteBubbleFromOverflow(Bubble bubble) {
        mLogger.log(bubble, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_BACK_TO_STACK);
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.setShouldAutoExpand(true);
        bubble.markAsAccessedAt(System.currentTimeMillis());
        setIsBubble(bubble, true /* isBubble */);
    }

    @VisibleForTesting
    public void expandStackAndSelectBubble(BubbleEntry entry) {
        if (mIsStatusBarShade) {
            mNotifEntryToExpandOnShadeUnlock = null;

            String key = entry.getKey();
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(key);
            if (bubble != null) {
                mBubbleData.setSelectedBubble(bubble);
                mBubbleData.setExpanded(true);
            } else {
                bubble = mBubbleData.getOverflowBubbleWithKey(key);
                if (bubble != null) {
                    promoteBubbleFromOverflow(bubble);
                } else if (entry.canBubble()) {
                    // It can bubble but it's not -- it got aged out of the overflow before it
                    // was dismissed or opened, make it a bubble again.
                    setIsBubble(entry, true /* isBubble */, true /* autoExpand */);
                }
            }
        } else {
            // Wait until we're unlocked to expand, so that the user can see the expand animation
            // and also to work around bugs with expansion animation + shade unlock happening at the
            // same time.
            mNotifEntryToExpandOnShadeUnlock = entry;
        }
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     */
    @VisibleForTesting
    public void updateBubble(BubbleEntry notif) {
        updateBubble(notif, false /* suppressFlyout */, true /* showInShade */);
    }

    /**
     * Fills the overflow bubbles by loading them from disk.
     */
    void loadOverflowBubblesFromDisk() {
        if (!mBubbleData.getOverflowBubbles().isEmpty() || mOverflowDataLoaded) {
            // we don't need to load overflow bubbles from disk if it is already in memory
            return;
        }
        mOverflowDataLoaded = true;
        mDataRepository.loadBubbles((bubbles) -> {
            bubbles.forEach(bubble -> {
                if (mBubbleData.hasAnyBubbleWithKey(bubble.getKey())) {
                    // if the bubble is already active, there's no need to push it to overflow
                    return;
                }
                bubble.inflate((b) -> mBubbleData.overflowBubble(DISMISS_AGED, bubble),
                        mContext, this, mStackView, mBubbleIconFactory, true /* skipInflation */);
            });
            return null;
        });
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     * @param suppressFlyout this bubble suppress flyout or not.
     * @param showInShade this bubble show in shade or not.
     */
    @VisibleForTesting
    public void updateBubble(BubbleEntry notif, boolean suppressFlyout, boolean showInShade) {
        // If this is an interruptive notif, mark that it's interrupted
        mSysuiProxy.setNotificationInterruption(notif.getKey());
        if (!notif.getRanking().visuallyInterruptive()
                && (notif.getBubbleMetadata() != null
                    && !notif.getBubbleMetadata().getAutoExpandBubble())
                && mBubbleData.hasOverflowBubbleWithKey(notif.getKey())) {
            // Update the bubble but don't promote it out of overflow
            Bubble b = mBubbleData.getOverflowBubbleWithKey(notif.getKey());
            b.setEntry(notif);
        } else {
            Bubble bubble = mBubbleData.getOrCreateBubble(notif, null /* persistedBubble */);
            inflateAndAdd(bubble, suppressFlyout, showInShade);
        }
    }

    void inflateAndAdd(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        // Lazy init stack view when a bubble is created
        ensureStackViewCreated();
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.inflate(b -> mBubbleData.notificationEntryUpdated(b, suppressFlyout, showInShade),
                mContext, this, mStackView, mBubbleIconFactory, false /* skipInflation */);
    }

    /**
     * Removes the bubble with the given key.
     * <p>
     * Must be called from the main thread.
     */
    @VisibleForTesting
    @MainThread
    public void removeBubble(String key, int reason) {
        if (mBubbleData.hasAnyBubbleWithKey(key)) {
            mBubbleData.dismissBubbleWithKey(key, reason);
        }
    }

    private void onEntryAdded(BubbleEntry entry) {
        if (canLaunchInActivityView(mContext, entry)) {
            updateBubble(entry);
        }
    }

    private void onEntryUpdated(BubbleEntry entry, boolean shouldBubbleUp) {
        // shouldBubbleUp checks canBubble & for bubble metadata
        boolean shouldBubble = shouldBubbleUp && canLaunchInActivityView(mContext, entry);
        if (!shouldBubble && mBubbleData.hasAnyBubbleWithKey(entry.getKey())) {
            // It was previously a bubble but no longer a bubble -- lets remove it
            removeBubble(entry.getKey(), DISMISS_NO_LONGER_BUBBLE);
        } else if (shouldBubble && entry.isBubble()) {
            updateBubble(entry);
        }
    }

    private void onEntryRemoved(BubbleEntry entry) {
        if (isSummaryOfBubbles(entry)) {
            final String groupKey = entry.getStatusBarNotification().getGroupKey();
            mBubbleData.removeSuppressedSummary(groupKey);

            // Remove any associated bubble children with the summary
            final List<Bubble> bubbleChildren = getBubblesInGroup(groupKey);
            for (int i = 0; i < bubbleChildren.size(); i++) {
                removeBubble(bubbleChildren.get(i).getKey(), DISMISS_GROUP_CANCELLED);
            }
        } else {
            removeBubble(entry.getKey(), DISMISS_NOTIF_CANCEL);
        }
    }

    private void onRankingUpdated(RankingMap rankingMap) {
        if (mTmpRanking == null) {
            mTmpRanking = new NotificationListenerService.Ranking();
        }
        String[] orderedKeys = rankingMap.getOrderedKeys();
        for (int i = 0; i < orderedKeys.length; i++) {
            String key = orderedKeys[i];
            BubbleEntry entry = mSysuiProxy.getPendingOrActiveEntry(key);
            rankingMap.getRanking(key, mTmpRanking);
            boolean isActiveBubble = mBubbleData.hasAnyBubbleWithKey(key);
            if (isActiveBubble && !mTmpRanking.canBubble()) {
                // If this entry is no longer allowed to bubble, dismiss with the BLOCKED reason.
                // This means that the app or channel's ability to bubble has been revoked.
                mBubbleData.dismissBubbleWithKey(key, DISMISS_BLOCKED);
            } else if (isActiveBubble && !mSysuiProxy.shouldBubbleUp(key)) {
                // If this entry is allowed to bubble, but cannot currently bubble up, dismiss it.
                // This happens when DND is enabled and configured to hide bubbles. Dismissing with
                // the reason DISMISS_NO_BUBBLE_UP will retain the underlying notification, so that
                // the bubble will be re-created if shouldBubbleUp returns true.
                mBubbleData.dismissBubbleWithKey(key, DISMISS_NO_BUBBLE_UP);
            } else if (entry != null && mTmpRanking.isBubble() && !isActiveBubble) {
                entry.setFlagBubble(true);
                onEntryUpdated(entry, true /* shouldBubbleUp */);
            }
        }
    }

    /**
     * Retrieves any bubbles that are part of the notification group represented by the provided
     * group key.
     */
    private ArrayList<Bubble> getBubblesInGroup(@Nullable String groupKey) {
        ArrayList<Bubble> bubbleChildren = new ArrayList<>();
        if (groupKey == null) {
            return bubbleChildren;
        }
        for (Bubble bubble : mBubbleData.getActiveBubbles()) {
            // TODO(178620678): Prevent calling into SysUI since this can be a part of a blocking
            //                  call from SysUI to Shell
            final BubbleEntry entry = mSysuiProxy.getPendingOrActiveEntry(bubble.getKey());
            if (entry != null && groupKey.equals(entry.getStatusBarNotification().getGroupKey())) {
                bubbleChildren.add(bubble);
            }
        }
        return bubbleChildren;
    }

    private void setIsBubble(@NonNull final BubbleEntry entry, final boolean isBubble,
            final boolean autoExpand) {
        Objects.requireNonNull(entry);
        entry.setFlagBubble(isBubble);
        try {
            int flags = 0;
            if (autoExpand) {
                flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
                flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            }
            mBarService.onNotificationBubbleChanged(entry.getKey(), isBubble, flags);
        } catch (RemoteException e) {
            // Bad things have happened
        }
    }

    private void setIsBubble(@NonNull final Bubble b, final boolean isBubble) {
        Objects.requireNonNull(b);
        b.setIsBubble(isBubble);
        final BubbleEntry entry = mSysuiProxy.getPendingOrActiveEntry(b.getKey());
        if (entry != null) {
            // Updating the entry to be a bubble will trigger our normal update flow
            setIsBubble(entry, isBubble, b.shouldAutoExpand());
        } else if (isBubble) {
            // If bubble doesn't exist, it's a persisted bubble so we need to add it to the
            // stack ourselves
            Bubble bubble = mBubbleData.getOrCreateBubble(null, b /* persistedBubble */);
            inflateAndAdd(bubble, bubble.shouldAutoExpand() /* suppressFlyout */,
                    !bubble.shouldAutoExpand() /* showInShade */);
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {
            ensureStackViewCreated();

            // Lazy load overflow bubbles from disk
            loadOverflowBubblesFromDisk();

            mStackView.updateOverflowButtonDot();

            // Update bubbles in overflow.
            if (mOverflowListener != null) {
                mOverflowListener.applyUpdate(update);
            }

            // Collapsing? Do this first before remaining steps.
            if (update.expandedChanged && !update.expanded) {
                mStackView.setExpanded(false);
                mSysuiProxy.requestNotificationShadeTopUi(false, TAG);
            }

            // Do removals, if any.
            ArrayList<Pair<Bubble, Integer>> removedBubbles =
                    new ArrayList<>(update.removedBubbles);
            ArrayList<Bubble> bubblesToBeRemovedFromRepository = new ArrayList<>();
            for (Pair<Bubble, Integer> removed : removedBubbles) {
                final Bubble bubble = removed.first;
                @Bubbles.DismissReason final int reason = removed.second;

                if (mStackView != null) {
                    mStackView.removeBubble(bubble);
                }

                // Leave the notification in place if we're dismissing due to user switching, or
                // because DND is suppressing the bubble. In both of those cases, we need to be able
                // to restore the bubble from the notification later.
                if (reason == DISMISS_USER_CHANGED || reason == DISMISS_NO_BUBBLE_UP) {
                    continue;
                }
                if (reason == DISMISS_NOTIF_CANCEL) {
                    bubblesToBeRemovedFromRepository.add(bubble);
                }
                if (!mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                    if (!mBubbleData.hasOverflowBubbleWithKey(bubble.getKey())
                            && (!bubble.showInShade()
                            || reason == DISMISS_NOTIF_CANCEL
                            || reason == DISMISS_GROUP_CANCELLED)) {
                        // The bubble is now gone & the notification is hidden from the shade, so
                        // time to actually remove it
                        mSysuiProxy.notifyRemoveNotification(bubble.getKey(), REASON_CANCEL);
                    } else {
                        if (bubble.isBubble()) {
                            setIsBubble(bubble, false /* isBubble */);
                        }
                        mSysuiProxy.updateNotificationBubbleButton(bubble.getKey());
                    }

                }
                final BubbleEntry entry = mSysuiProxy.getPendingOrActiveEntry(bubble.getKey());
                if (entry != null) {
                    final String groupKey = entry.getStatusBarNotification().getGroupKey();
                    if (getBubblesInGroup(groupKey).isEmpty()) {
                        // Time to potentially remove the summary
                        mSysuiProxy.notifyMaybeCancelSummary(bubble.getKey());
                    }
                }
            }
            mDataRepository.removeBubbles(mCurrentUserId, bubblesToBeRemovedFromRepository);

            if (update.addedBubble != null && mStackView != null) {
                mDataRepository.addBubble(mCurrentUserId, update.addedBubble);
                mStackView.addBubble(update.addedBubble);
            }

            if (update.updatedBubble != null && mStackView != null) {
                mStackView.updateBubble(update.updatedBubble);
            }

            // At this point, the correct bubbles are inflated in the stack.
            // Make sure the order in bubble data is reflected in bubble row.
            if (update.orderChanged && mStackView != null) {
                mDataRepository.addBubbles(mCurrentUserId, update.bubbles);
                mStackView.updateBubbleOrder(update.bubbles);
            }

            if (update.selectionChanged && mStackView != null) {
                mStackView.setSelectedBubble(update.selectedBubble);
                if (update.selectedBubble != null) {
                    mSysuiProxy.updateNotificationSuppression(update.selectedBubble.getKey());
                }
            }

            // Expanding? Apply this last.
            if (update.expandedChanged && update.expanded) {
                if (mStackView != null) {
                    mStackView.setExpanded(true);
                    mSysuiProxy.requestNotificationShadeTopUi(true, TAG);
                }
            }

            mSysuiProxy.notifyInvalidateNotifications("BubbleData.Listener.applyUpdate");
            updateStack();
        }
    };

    private boolean handleDismissalInterception(BubbleEntry entry,
            @Nullable List<BubbleEntry> children, IntConsumer removeCallback) {
        if (isSummaryOfBubbles(entry)) {
            handleSummaryDismissalInterception(entry, children, removeCallback);
        } else {
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(entry.getKey());
            if (bubble == null || !entry.isBubble()) {
                bubble = mBubbleData.getOverflowBubbleWithKey(entry.getKey());
            }
            if (bubble == null) {
                return false;
            }
            bubble.setSuppressNotification(true);
            bubble.setShowDot(false /* show */);
        }
        // Update the shade
        mSysuiProxy.notifyInvalidateNotifications("BubbleController.handleDismissalInterception");
        return true;
    }

    private boolean isSummaryOfBubbles(BubbleEntry entry) {
        String groupKey = entry.getStatusBarNotification().getGroupKey();
        ArrayList<Bubble> bubbleChildren = getBubblesInGroup(groupKey);
        boolean isSuppressedSummary = (mBubbleData.isSummarySuppressed(groupKey)
                && mBubbleData.getSummaryKey(groupKey).equals(entry.getKey()));
        boolean isSummary = entry.getStatusBarNotification().getNotification().isGroupSummary();
        return (isSuppressedSummary || isSummary) && !bubbleChildren.isEmpty();
    }

    private void handleSummaryDismissalInterception(
            BubbleEntry summary, @Nullable List<BubbleEntry> children, IntConsumer removeCallback) {
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                BubbleEntry child = children.get(i);
                if (mBubbleData.hasAnyBubbleWithKey(child.getKey())) {
                    // Suppress the bubbled child
                    // As far as group manager is concerned, once a child is no longer shown
                    // in the shade, it is essentially removed.
                    Bubble bubbleChild = mBubbleData.getAnyBubbleWithkey(child.getKey());
                    if (bubbleChild != null) {
                        mSysuiProxy.removeNotificationEntry(bubbleChild.getKey());
                        bubbleChild.setSuppressNotification(true);
                        bubbleChild.setShowDot(false /* show */);
                    }
                } else {
                    // non-bubbled children can be removed
                    removeCallback.accept(i);
                }
            }
        }

        // And since all children are removed, remove the summary.
        removeCallback.accept(-1);

        // TODO: (b/145659174) remove references to mSuppressedGroupKeys once fully migrated
        mBubbleData.addSummaryToSuppress(summary.getStatusBarNotification().getGroupKey(),
                summary.getKey());
    }

    /**
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides.
     * Updates stack description for TalkBack focus.
     */
    public void updateStack() {
        if (mStackView == null) {
            return;
        }

        if (!mIsStatusBarShade) {
            // Bubbles don't appear over the locked shade.
            mStackView.setVisibility(INVISIBLE);
        } else if (hasBubbles()) {
            // If we're unlocked, show the stack if we have bubbles. If we don't have bubbles, the
            // stack will be set to INVISIBLE in onAllBubblesAnimatedOut after the bubbles animate
            // out.
            mStackView.setVisibility(VISIBLE);
        }

        mStackView.updateContentDescription();
    }

    /**
     * The task id of the expanded view, if the stack is expanded and not occluded by the
     * status bar, otherwise returns {@link ActivityTaskManager#INVALID_TASK_ID}.
     */
    private int getExpandedTaskId() {
        if (mStackView == null) {
            return INVALID_TASK_ID;
        }
        final BubbleViewProvider expandedViewProvider = mStackView.getExpandedBubble();
        if (expandedViewProvider != null && isStackExpanded()
                && !mStackView.isExpansionAnimating()
                && !mSysuiProxy.isNotificationShadeExpand()) {
            return expandedViewProvider.getTaskId();
        }
        return INVALID_TASK_ID;
    }

    @VisibleForTesting
    public BubbleStackView getStackView() {
        return mStackView;
    }

    /**
     * Description of current bubble state.
     */
    private void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BubbleController state:");
        mBubbleData.dump(fd, pw, args);
        pw.println();
        if (mStackView != null) {
            mStackView.dump(fd, pw, args);
        }
        pw.println();
    }

    /**
     * Whether an intent is properly configured to display in an {@link android.app.ActivityView}.
     *
     * Keep checks in sync with NotificationManagerService#canLaunchInActivityView. Typically
     * that should filter out any invalid bubbles, but should protect SysUI side just in case.
     *
     * @param context the context to use.
     * @param entry the entry to bubble.
     */
    static boolean canLaunchInActivityView(Context context, BubbleEntry entry) {
        PendingIntent intent = entry.getBubbleMetadata() != null
                ? entry.getBubbleMetadata().getIntent()
                : null;
        if (entry.getBubbleMetadata() != null
                && entry.getBubbleMetadata().getShortcutId() != null) {
            return true;
        }
        if (intent == null) {
            Log.w(TAG, "Unable to create bubble -- no intent: " + entry.getKey());
            return false;
        }
        PackageManager packageManager = getPackageManagerForUser(
                context, entry.getStatusBarNotification().getUser().getIdentifier());
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(packageManager, 0);
        if (info == null) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " couldn't find activity info for intent: "
                    + intent);
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " activity is not resizable for intent: "
                    + intent);
            return false;
        }
        return true;
    }

    static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                                Context.CONTEXT_RESTRICTED,
                                new UserHandle(userId));
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    /** PinnedStackListener that dispatches IME visibility updates to the stack. */
    //TODO(b/170442945): Better way to do this / insets listener?
    private class BubblesImeListener extends PinnedStackListenerForwarder.PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            if (mStackView != null) {
                mStackView.onImeVisibilityChanged(imeVisible, imeHeight);
            }
        }
    }

    private class BubblesImpl implements Bubbles {
        @Override
        public boolean isBubbleNotificationSuppressedFromShade(String key, String groupKey) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return BubbleController.this.isBubbleNotificationSuppressedFromShade(key, groupKey);
            }, Boolean.class);
        }

        @Override
        public boolean isBubbleExpanded(String key) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return BubbleController.this.isBubbleExpanded(key);
            }, Boolean.class);
        }

        @Override
        public boolean isStackExpanded() {
            return mMainExecutor.executeBlockingForResult(() -> {
                return BubbleController.this.isStackExpanded();
            }, Boolean.class);
        }

        @Override
        public void removeSuppressedSummaryIfNecessary(String groupKey, Consumer<String> callback,
                Executor callbackExecutor) {
            mMainExecutor.execute(() -> {
                BubbleController.this.removeSuppressedSummaryIfNecessary(groupKey, callback,
                        callbackExecutor);
            });
        }

        @Override
        public void collapseStack() {
            mMainExecutor.execute(() -> {
                BubbleController.this.collapseStack();
            });
        }

        @Override
        public void updateForThemeChanges() {
            mMainExecutor.execute(() -> {
                BubbleController.this.updateForThemeChanges();
            });
        }

        @Override
        public void expandStackAndSelectBubble(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.expandStackAndSelectBubble(entry);
            });
        }

        @Override
        public void onTaskbarChanged(Bundle b) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onTaskbarChanged(b);
            });
        }

        @Override
        public void openBubbleOverflow() {
            mMainExecutor.execute(() -> {
                BubbleController.this.openBubbleOverflow();
            });
        }

        @Override
        public boolean handleDismissalInterception(BubbleEntry entry,
                @Nullable List<BubbleEntry> children, IntConsumer removeCallback) {
            return mMainExecutor.executeBlockingForResult(() -> {
                return BubbleController.this.handleDismissalInterception(entry, children,
                        removeCallback);
            }, Boolean.class);
        }

        @Override
        public void setSysuiProxy(SysuiProxy proxy) {
            mMainExecutor.execute(() -> {
                BubbleController.this.setSysuiProxy(proxy);
            });
        }

        @Override
        public void setBubbleScrim(View view, BiConsumer<Executor, Looper> callback) {
            mMainExecutor.execute(() -> {
                BubbleController.this.setBubbleScrim(view, callback);
            });
        }

        @Override
        public void setExpandListener(BubbleExpandListener listener) {
            mMainExecutor.execute(() -> {
                BubbleController.this.setExpandListener(listener);
            });
        }

        @Override
        public void onEntryAdded(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryAdded(entry);
            });
        }

        @Override
        public void onEntryUpdated(BubbleEntry entry, boolean shouldBubbleUp) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryUpdated(entry, shouldBubbleUp);
            });
        }

        @Override
        public void onEntryRemoved(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryRemoved(entry);
            });
        }

        @Override
        public void onRankingUpdated(RankingMap rankingMap) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onRankingUpdated(rankingMap);
            });
        }

        @Override
        public void onStatusBarVisibilityChanged(boolean visible) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onStatusBarVisibilityChanged(visible);
            });
        }

        @Override
        public void onZenStateChanged() {
            mMainExecutor.execute(() -> {
                BubbleController.this.onZenStateChanged();
            });
        }

        @Override
        public void onStatusBarStateChanged(boolean isShade) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onStatusBarStateChanged(isShade);
            });
        }

        @Override
        public void onUserChanged(int newUserId) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onUserChanged(newUserId);
            });
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onConfigChanged(newConfig);
            });
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            try {
                mMainExecutor.executeBlocking(() -> {
                    BubbleController.this.dump(fd, pw, args);
                });
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to dump BubbleController in 2s");
            }
        }
    }
}
