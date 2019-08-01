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

package com.android.server.accessibility;

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Region;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import com.android.server.accessibility.AccessibilitySecurityPolicy.AccessibilityUserManager;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class provides APIs for accessibility manager to manage {@link AccessibilityWindowInfo}s and
 * {@link WindowInfo}s.
 */
public class AccessibilityWindowManager {
    private static final String LOG_TAG = "AccessibilityWindowManager";
    private static final boolean DEBUG = false;

    private static int sNextWindowId;

    private final Object mLock;
    private final Handler mHandler;
    private final WindowManagerInternal mWindowManagerInternal;
    private final AccessibilityEventSender mAccessibilityEventSender;
    private final AccessibilitySecurityPolicy mSecurityPolicy;
    private final AccessibilityUserManager mAccessibilityUserManager;

    // Connections and window tokens for cross-user windows
    private final SparseArray<RemoteAccessibilityConnection>
            mGlobalInteractionConnections = new SparseArray<>();
    private final SparseArray<IBinder> mGlobalWindowTokens = new SparseArray<>();

    // Connections and window tokens for per-user windows, indexed as one sparse array per user
    private final SparseArray<SparseArray<RemoteAccessibilityConnection>>
            mInteractionConnections = new SparseArray<>();
    private final SparseArray<SparseArray<IBinder>> mWindowTokens = new SparseArray<>();

    private RemoteAccessibilityConnection mPictureInPictureActionReplacingConnection;

    private int mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private int mFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private int mAccessibilityFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private long mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;

    private boolean mTouchInteractionInProgress;

    // TO-DO [Multi-Display] : make DisplayWindowObserver to plural
    private DisplayWindowsObserver mDisplayWindowsObserver;

    /**
     * This class implements {@link WindowManagerInternal.WindowsForAccessibilityCallback} to
     * receive {@link WindowInfo}s from window manager when there's an accessibility change in
     * window and holds window lists information per display.
     */
    private final class DisplayWindowsObserver implements
            WindowManagerInternal.WindowsForAccessibilityCallback {

        private final int mDisplayId;
        private final SparseArray<AccessibilityWindowInfo> mA11yWindowInfoById =
                new SparseArray<>();
        private final SparseArray<WindowInfo> mWindowInfoById = new SparseArray<>();
        private final List<WindowInfo> mCachedWindowInfos = new ArrayList<>();
        private List<AccessibilityWindowInfo> mWindows;
        private boolean mTrackingWindows = false;
        private boolean mHasWatchOutsideTouchWindow;

        /**
         * Constructor for DisplayWindowsObserver.
         */
        DisplayWindowsObserver(int displayId) {
            mDisplayId = displayId;
        }

        /**
         * Starts tracking windows changes from window manager by registering callback.
         *
         * @return true if callback registers successful.
         */
        boolean startTrackingWindowsLocked() {
            boolean result = true;

            if (!mTrackingWindows) {
                // Turns on the flag before setup the callback.
                // In some cases, onWindowsForAccessibilityChanged will be called immediately in
                // setWindowsForAccessibilityCallback. We'll lost windows if flag is false.
                mTrackingWindows = true;
                result = mWindowManagerInternal.setWindowsForAccessibilityCallback(
                        mDisplayId, this);
                if (!result) {
                    mTrackingWindows = false;
                    Slog.w(LOG_TAG, "set windowsObserver callbacks fail, displayId:"
                            + mDisplayId);
                }
            }
            return result;
        }

        /**
         * Stops tracking windows changes from window manager, and clear all windows info.
         */
        void stopTrackingWindowsLocked() {
            if (mTrackingWindows) {
                mWindowManagerInternal.setWindowsForAccessibilityCallback(
                        mDisplayId, null);
                mTrackingWindows = false;
                clearWindowsLocked();
            }
        }

        /**
         * Returns true if windows changes tracking.
         *
         * @return true if windows changes tracking
         */
        boolean isTrackingWindowsLocked() {
            return mTrackingWindows;
        }

        /**
         * Returns accessibility windows.
         * @return accessibility windows.
         */
        @Nullable
        List<AccessibilityWindowInfo> getWindowListLocked() {
            return mWindows;
        }

        /**
         * Returns accessibility window info according to given windowId.
         *
         * @param windowId The windowId
         * @return The accessibility window info
         */
        @Nullable
        AccessibilityWindowInfo findA11yWindowInfoByIdLocked(int windowId) {
            return mA11yWindowInfoById.get(windowId);
        }

        /**
         * Returns the window info according to given windowId.
         *
         * @param windowId The windowId
         * @return The window info
         */
        @Nullable
        WindowInfo findWindowInfoByIdLocked(int windowId) {
            return mWindowInfoById.get(windowId);
        }

        /**
         * Returns {@link AccessibilityWindowInfo} of PIP window.
         *
         * @return PIP accessibility window info
         */
        @Nullable
        AccessibilityWindowInfo getPictureInPictureWindowLocked() {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    final AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.isInPictureInPictureMode()) {
                        return window;
                    }
                }
            }
            return null;
        }

        /**
         * Sets the active flag of the window according to given windowId, others set to inactive.
         *
         * @param windowId The windowId
         */
        void setActiveWindowLocked(int windowId) {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == windowId) {
                        window.setActive(true);
                        mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                                AccessibilityEvent.obtainWindowsChangedEvent(windowId,
                                        AccessibilityEvent.WINDOWS_CHANGE_ACTIVE));
                    } else {
                        window.setActive(false);
                    }
                }
            }
        }

        /**
         * Sets the window accessibility focused according to given windowId, others set
         * unfocused.
         *
         * @param windowId The windowId
         */
        void setAccessibilityFocusedWindowLocked(int windowId) {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == windowId) {
                        window.setAccessibilityFocused(true);
                        mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                                AccessibilityEvent.obtainWindowsChangedEvent(
                                        windowId, WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));

                    } else {
                        window.setAccessibilityFocused(false);
                    }
                }
            }
        }

        /**
         * Computes partial interactive region of given windowId.
         *
         * @param windowId The windowId
         * @param outRegion The output to which to write the bounds.
         * @return true if outRegion is not empty.
         */
        boolean computePartialInteractiveRegionForWindowLocked(int windowId,
                @NonNull Region outRegion) {
            if (mWindows == null) {
                return false;
            }

            // Windows are ordered in z order so start from the bottom and find
            // the window of interest. After that all windows that cover it should
            // be subtracted from the resulting region. Note that for accessibility
            // we are returning only interactive windows.
            Region windowInteractiveRegion = null;
            boolean windowInteractiveRegionChanged = false;

            final int windowCount = mWindows.size();
            final Region currentWindowRegions = new Region();
            for (int i = windowCount - 1; i >= 0; i--) {
                AccessibilityWindowInfo currentWindow = mWindows.get(i);
                if (windowInteractiveRegion == null) {
                    if (currentWindow.getId() == windowId) {
                        currentWindow.getRegionInScreen(currentWindowRegions);
                        outRegion.set(currentWindowRegions);
                        windowInteractiveRegion = outRegion;
                        continue;
                    }
                } else if (currentWindow.getType()
                        != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    currentWindow.getRegionInScreen(currentWindowRegions);
                    if (windowInteractiveRegion.op(currentWindowRegions, Region.Op.DIFFERENCE)) {
                        windowInteractiveRegionChanged = true;
                    }
                }
            }

            return windowInteractiveRegionChanged;
        }

        List<Integer> getWatchOutsideTouchWindowIdLocked(int targetWindowId) {
            final WindowInfo targetWindow = mWindowInfoById.get(targetWindowId);
            if (targetWindow != null && mHasWatchOutsideTouchWindow) {
                final List<Integer> outsideWindowsId = new ArrayList<>();
                for (int i = 0; i < mWindowInfoById.size(); i++) {
                    final WindowInfo window = mWindowInfoById.valueAt(i);
                    if (window != null && window.layer < targetWindow.layer
                            && window.hasFlagWatchOutsideTouch) {
                        outsideWindowsId.add(mWindowInfoById.keyAt(i));
                    }
                }
                return outsideWindowsId;
            }
            return Collections.emptyList();
        }

        /**
         * Callbacks from window manager when there's an accessibility change in windows.
         *
         * @param forceSend Send the windows for accessibility even if they haven't changed.
         * @param windows The windows for accessibility.
         */
        @Override
        public void onWindowsForAccessibilityChanged(boolean forceSend,
                @NonNull List<WindowInfo> windows) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Display Id = " + mDisplayId);
                    Slog.i(LOG_TAG, "Windows changed: " + windows);
                }
                if (shouldUpdateWindowsLocked(forceSend, windows)) {
                    cacheWindows(windows);
                    // Lets the policy update the focused and active windows.
                    updateWindowsLocked(mAccessibilityUserManager.getCurrentUserIdLocked(),
                            windows);
                    // Someone may be waiting for the windows - advertise it.
                    mLock.notifyAll();
                }
            }
        }

        private boolean shouldUpdateWindowsLocked(boolean forceSend,
                @NonNull List<WindowInfo> windows) {
            if (forceSend) {
                return true;
            }

            final int windowCount = windows.size();
            // We computed the windows and if they changed notify the client.
            if (mCachedWindowInfos.size() != windowCount) {
                // Different size means something changed.
                return true;
            } else if (!mCachedWindowInfos.isEmpty() || !windows.isEmpty()) {
                // Since we always traverse windows from high to low layer
                // the old and new windows at the same index should be the
                // same, otherwise something changed.
                for (int i = 0; i < windowCount; i++) {
                    WindowInfo oldWindow = mCachedWindowInfos.get(i);
                    WindowInfo newWindow = windows.get(i);
                    // We do not care for layer changes given the window
                    // order does not change. This brings no new information
                    // to the clients.
                    if (windowChangedNoLayer(oldWindow, newWindow)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private void cacheWindows(List<WindowInfo> windows) {
            final int oldWindowCount = mCachedWindowInfos.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                mCachedWindowInfos.remove(i).recycle();
            }
            final int newWindowCount = windows.size();
            for (int i = 0; i < newWindowCount; i++) {
                WindowInfo newWindow = windows.get(i);
                mCachedWindowInfos.add(WindowInfo.obtain(newWindow));
            }
        }

        private boolean windowChangedNoLayer(WindowInfo oldWindow, WindowInfo newWindow) {
            if (oldWindow == newWindow) {
                return false;
            }
            if (oldWindow == null) {
                return true;
            }
            if (newWindow == null) {
                return true;
            }
            if (oldWindow.type != newWindow.type) {
                return true;
            }
            if (oldWindow.focused != newWindow.focused) {
                return true;
            }
            if (oldWindow.token == null) {
                if (newWindow.token != null) {
                    return true;
                }
            } else if (!oldWindow.token.equals(newWindow.token)) {
                return true;
            }
            if (oldWindow.parentToken == null) {
                if (newWindow.parentToken != null) {
                    return true;
                }
            } else if (!oldWindow.parentToken.equals(newWindow.parentToken)) {
                return true;
            }
            if (oldWindow.activityToken == null) {
                if (newWindow.activityToken != null) {
                    return true;
                }
            } else if (!oldWindow.activityToken.equals(newWindow.activityToken)) {
                return true;
            }
            if (!oldWindow.regionInScreen.equals(newWindow.regionInScreen)) {
                return true;
            }
            if (oldWindow.childTokens != null && newWindow.childTokens != null
                    && !oldWindow.childTokens.equals(newWindow.childTokens)) {
                return true;
            }
            if (!TextUtils.equals(oldWindow.title, newWindow.title)) {
                return true;
            }
            if (oldWindow.accessibilityIdOfAnchor != newWindow.accessibilityIdOfAnchor) {
                return true;
            }
            if (oldWindow.inPictureInPicture != newWindow.inPictureInPicture) {
                return true;
            }
            if (oldWindow.hasFlagWatchOutsideTouch != newWindow.hasFlagWatchOutsideTouch) {
                return true;
            }
            if (oldWindow.displayId != newWindow.displayId) {
                return true;
            }
            return false;
        }

        /**
         * Clears all {@link AccessibilityWindowInfo}s and {@link WindowInfo}s.
         */
        private void clearWindowsLocked() {
            final List<WindowInfo> windows = Collections.emptyList();
            final int activeWindowId = mActiveWindowId;
            // UserId is useless in updateWindowsLocked, when we update a empty window list.
            // Just pass current userId here.
            updateWindowsLocked(mAccessibilityUserManager.getCurrentUserIdLocked(), windows);
            // Do not reset mActiveWindowId here. mActiveWindowId will be clear after accessibility
            // interaction connection removed.
            mActiveWindowId = activeWindowId;
            mWindows = null;
        }

        /**
         * Updates windows info according to specified userId and windows.
         *
         * @param userId The userId to update
         * @param windows The windows to update
         */
        private void updateWindowsLocked(int userId, @NonNull List<WindowInfo> windows) {
            if (mWindows == null) {
                mWindows = new ArrayList<>();
            }

            final List<AccessibilityWindowInfo> oldWindowList = new ArrayList<>(mWindows);
            final SparseArray<AccessibilityWindowInfo> oldWindowsById = mA11yWindowInfoById.clone();

            mWindows.clear();
            mA11yWindowInfoById.clear();

            for (int i = 0; i < mWindowInfoById.size(); i++) {
                mWindowInfoById.valueAt(i).recycle();
            }
            mWindowInfoById.clear();
            mHasWatchOutsideTouchWindow = false;
            mFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            if (!mTouchInteractionInProgress) {
                mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }

            // If the active window goes away while the user is touch exploring we
            // reset the active window id and wait for the next hover event from
            // under the user's finger to determine which one is the new one. It
            // is possible that the finger is not moving and the input system
            // filters out such events.
            boolean activeWindowGone = true;

            final int windowCount = windows.size();

            // We'll clear accessibility focus if the window with focus is no longer visible to
            // accessibility services
            boolean shouldClearAccessibilityFocus =
                    mAccessibilityFocusedWindowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            if (windowCount > 0) {
                for (int i = 0; i < windowCount; i++) {
                    final WindowInfo windowInfo = windows.get(i);
                    final AccessibilityWindowInfo window;
                    if (isTrackingWindowsLocked()) {
                        window = populateReportedWindowLocked(userId, windowInfo);
                    } else {
                        window = null;
                    }
                    if (window != null) {

                        // Flip layers in list to be consistent with AccessibilityService#getWindows
                        window.setLayer(windowCount - 1 - window.getLayer());

                        final int windowId = window.getId();
                        if (window.isFocused()) {
                            mFocusedWindowId = windowId;
                            if (!mTouchInteractionInProgress) {
                                mActiveWindowId = windowId;
                                window.setActive(true);
                            } else if (windowId == mActiveWindowId) {
                                activeWindowGone = false;
                            }
                        }
                        if (!mHasWatchOutsideTouchWindow && windowInfo.hasFlagWatchOutsideTouch) {
                            mHasWatchOutsideTouchWindow = true;
                        }
                        mWindows.add(window);
                        mA11yWindowInfoById.put(windowId, window);
                        mWindowInfoById.put(windowId, WindowInfo.obtain(windowInfo));
                    }
                }

                if (mTouchInteractionInProgress && activeWindowGone) {
                    mActiveWindowId = mFocusedWindowId;
                }

                // Focused window may change the active one, so set the
                // active window once we decided which it is.
                final int accessibilityWindowCount = mWindows.size();
                for (int i = 0; i < accessibilityWindowCount; i++) {
                    final AccessibilityWindowInfo window = mWindows.get(i);
                    if (window.getId() == mActiveWindowId) {
                        window.setActive(true);
                    }
                    if (window.getId() == mAccessibilityFocusedWindowId) {
                        window.setAccessibilityFocused(true);
                        shouldClearAccessibilityFocus = false;
                    }
                }
            }

            sendEventsForChangedWindowsLocked(oldWindowList, oldWindowsById);

            final int oldWindowCount = oldWindowList.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                oldWindowList.remove(i).recycle();
            }

            if (shouldClearAccessibilityFocus) {
                clearAccessibilityFocusLocked(mAccessibilityFocusedWindowId);
            }
        }

        private void sendEventsForChangedWindowsLocked(List<AccessibilityWindowInfo> oldWindows,
                SparseArray<AccessibilityWindowInfo> oldWindowsById) {
            List<AccessibilityEvent> events = new ArrayList<>();
            // Sends events for all removed windows.
            final int oldWindowsCount = oldWindows.size();
            for (int i = 0; i < oldWindowsCount; i++) {
                final AccessibilityWindowInfo window = oldWindows.get(i);
                if (mA11yWindowInfoById.get(window.getId()) == null) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                            window.getId(), AccessibilityEvent.WINDOWS_CHANGE_REMOVED));
                }
            }

            // Looks for other changes.
            final int newWindowCount = mWindows.size();
            for (int i = 0; i < newWindowCount; i++) {
                final AccessibilityWindowInfo newWindow = mWindows.get(i);
                final AccessibilityWindowInfo oldWindow = oldWindowsById.get(newWindow.getId());
                if (oldWindow == null) {
                    events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                            newWindow.getId(), AccessibilityEvent.WINDOWS_CHANGE_ADDED));
                } else {
                    int changes = newWindow.differenceFrom(oldWindow);
                    if (changes !=  0) {
                        events.add(AccessibilityEvent.obtainWindowsChangedEvent(
                                newWindow.getId(), changes));
                    }
                }
            }

            final int numEvents = events.size();
            for (int i = 0; i < numEvents; i++) {
                mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(events.get(i));
            }
        }

        private AccessibilityWindowInfo populateReportedWindowLocked(int userId,
                WindowInfo window) {
            final int windowId = findWindowIdLocked(userId, window.token);
            if (windowId < 0) {
                return null;
            }

            final AccessibilityWindowInfo reportedWindow = AccessibilityWindowInfo.obtain();

            reportedWindow.setId(windowId);
            reportedWindow.setType(getTypeForWindowManagerWindowType(window.type));
            reportedWindow.setLayer(window.layer);
            reportedWindow.setFocused(window.focused);
            reportedWindow.setRegionInScreen(window.regionInScreen);
            reportedWindow.setTitle(window.title);
            reportedWindow.setAnchorId(window.accessibilityIdOfAnchor);
            reportedWindow.setPictureInPicture(window.inPictureInPicture);
            reportedWindow.setDisplayId(window.displayId);

            final int parentId = findWindowIdLocked(userId, window.parentToken);
            if (parentId >= 0) {
                reportedWindow.setParentId(parentId);
            }

            if (window.childTokens != null) {
                final int childCount = window.childTokens.size();
                for (int i = 0; i < childCount; i++) {
                    final IBinder childToken = window.childTokens.get(i);
                    final int childId = findWindowIdLocked(userId, childToken);
                    if (childId >= 0) {
                        reportedWindow.addChild(childId);
                    }
                }
            }

            return reportedWindow;
        }

        private int getTypeForWindowManagerWindowType(int windowType) {
            switch (windowType) {
                case WindowManager.LayoutParams.TYPE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_STARTING:
                case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_BASE_APPLICATION:
                case WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION:
                case WindowManager.LayoutParams.TYPE_PHONE:
                case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                case WindowManager.LayoutParams.TYPE_TOAST:
                case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_APPLICATION;
                }

                case WindowManager.LayoutParams.TYPE_INPUT_METHOD:
                case WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG: {
                    return AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                }

                case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR:
                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL:
                case WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL:
                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                case WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
                case WindowManager.LayoutParams.TYPE_SCREENSHOT: {
                    return AccessibilityWindowInfo.TYPE_SYSTEM;
                }

                case WindowManager.LayoutParams.TYPE_DOCK_DIVIDER: {
                    return AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER;
                }

                case TYPE_ACCESSIBILITY_OVERLAY: {
                    return AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
                }

                default: {
                    return -1;
                }
            }
        }

        /**
         * Dumps all {@link AccessibilityWindowInfo}s here.
         */
        void dumpLocked(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (mWindows != null) {
                final int windowCount = mWindows.size();
                for (int j = 0; j < windowCount; j++) {
                    if (j == 0) {
                        pw.append("Display[");
                        pw.append(Integer.toString(mDisplayId));
                        pw.append("] : ");
                        pw.println();
                    }
                    if (j > 0) {
                        pw.append(',');
                        pw.println();
                    }
                    pw.append("Window[");
                    AccessibilityWindowInfo window = mWindows.get(j);
                    pw.append(window.toString());
                    pw.append(']');
                }
                pw.println();
            }
        }
    }
    /**
     * Interface to send {@link AccessibilityEvent}.
     */
    public interface AccessibilityEventSender {
        /**
         * Sends {@link AccessibilityEvent} for current user.
         */
        void sendAccessibilityEventForCurrentUserLocked(AccessibilityEvent event);
    }

    /**
     * Wrapper of accessibility interaction connection for window.
     */
    final class RemoteAccessibilityConnection implements IBinder.DeathRecipient {
        private final int mUid;
        private final String mPackageName;
        private final int mWindowId;
        private final int mUserId;
        private final IAccessibilityInteractionConnection mConnection;

        RemoteAccessibilityConnection(int windowId,
                IAccessibilityInteractionConnection connection,
                String packageName, int uid, int userId) {
            mWindowId = windowId;
            mPackageName = packageName;
            mUid = uid;
            mUserId = userId;
            mConnection = connection;
        }

        int getUid() {
            return  mUid;
        }

        String getPackageName() {
            return mPackageName;
        }

        IAccessibilityInteractionConnection getRemote() {
            return mConnection;
        }

        void linkToDeath() throws RemoteException {
            mConnection.asBinder().linkToDeath(this, 0);
        }

        void unlinkToDeath() {
            mConnection.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            synchronized (mLock) {
                removeAccessibilityInteractionConnectionLocked(mWindowId, mUserId);
            }
        }
    }

    /**
     * Constructor for AccessibilityManagerService.
     */
    public AccessibilityWindowManager(@NonNull Object lock, @NonNull Handler handler,
            @NonNull WindowManagerInternal windowManagerInternal,
            @NonNull AccessibilityEventSender accessibilityEventSender,
            @NonNull AccessibilitySecurityPolicy securityPolicy,
            @NonNull AccessibilityUserManager accessibilityUserManager) {
        mLock = lock;
        mHandler = handler;
        mWindowManagerInternal = windowManagerInternal;
        mAccessibilityEventSender = accessibilityEventSender;
        mSecurityPolicy = securityPolicy;
        mAccessibilityUserManager = accessibilityUserManager;
        mDisplayWindowsObserver = new DisplayWindowsObserver(Display.DEFAULT_DISPLAY);
    }

    /**
     * Starts tracking windows changes from window manager.
     */
    public void startTrackingWindows() {
        synchronized (mLock) {
            mDisplayWindowsObserver.startTrackingWindowsLocked();
        }
    }

    /**
     * Stops tracking windows changes from window manager, and clear all windows info.
     */
    public void stopTrackingWindows() {
        synchronized (mLock) {
            mDisplayWindowsObserver.stopTrackingWindowsLocked();
        }
    }

    /**
     * Returns true if windows changes tracking.
     *
     * @return true if windows changes tracking
     */
    public boolean isTrackingWindowsLocked() {
        return mDisplayWindowsObserver.isTrackingWindowsLocked();
    }

    /**
     * Returns accessibility windows.
     */
    @Nullable
    public List<AccessibilityWindowInfo> getWindowListLocked() {
        return mDisplayWindowsObserver.getWindowListLocked();
    }

    /**
     * Adds accessibility interaction connection according to given window token, package name and
     * window token.
     *
     * @param windowToken The window token of accessibility interaction connection
     * @param connection The accessibility interaction connection
     * @param packageName The package name
     * @param userId The userId
     * @return The windowId of added connection
     * @throws RemoteException
     */
    public int addAccessibilityInteractionConnection(@NonNull IWindow windowToken,
            @NonNull IAccessibilityInteractionConnection connection, @NonNull String packageName,
            int userId) throws RemoteException {
        final int windowId;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            final int resolvedUid = UserHandle.getUid(resolvedUserId, UserHandle.getCallingAppId());

            // Makes sure the reported package is one the caller has access to.
            packageName = mSecurityPolicy.resolveValidReportedPackageLocked(
                    packageName, UserHandle.getCallingAppId(), resolvedUserId);

            windowId = sNextWindowId++;
            // If the window is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, UserHandle.USER_ALL);
                wrapper.linkToDeath();
                mGlobalInteractionConnections.put(windowId, wrapper);
                mGlobalWindowTokens.put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId + " and  token: "
                            + windowToken.asBinder());
                }
            } else {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        windowId, connection, packageName, resolvedUid, resolvedUserId);
                wrapper.linkToDeath();
                getInteractionConnectionsForUserLocked(userId).put(windowId, wrapper);
                getWindowTokensForUserLocked(userId).put(windowId, windowToken.asBinder());
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + windowId
                            + " and  token: " + windowToken.asBinder());
                }
            }
        }
        // TODO [Multi-Display] : using correct display Id to replace DEFAULT_DISPLAY
        mWindowManagerInternal.computeWindowsForAccessibility(Display.DEFAULT_DISPLAY);
        return windowId;
    }

    /**
     * Removes accessibility interaction connection according to given window token.
     *
     * @param window The window token of accessibility interaction connection
     */
    public void removeAccessibilityInteractionConnection(@NonNull IWindow window) {
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                    UserHandle.getCallingUserId());
            IBinder token = window.asBinder();
            final int removedWindowId = removeAccessibilityInteractionConnectionInternalLocked(
                    token, mGlobalWindowTokens, mGlobalInteractionConnections);
            if (removedWindowId >= 0) {
                onAccessibilityInteractionConnectionRemovedLocked(removedWindowId);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed global connection for pid:" + Binder.getCallingPid()
                            + " with windowId: " + removedWindowId + " and token: "
                            + window.asBinder());
                }
                return;
            }
            final int userCount = mWindowTokens.size();
            for (int i = 0; i < userCount; i++) {
                final int userId = mWindowTokens.keyAt(i);
                final int removedWindowIdForUser =
                        removeAccessibilityInteractionConnectionInternalLocked(token,
                                getWindowTokensForUserLocked(userId),
                                getInteractionConnectionsForUserLocked(userId));
                if (removedWindowIdForUser >= 0) {
                    onAccessibilityInteractionConnectionRemovedLocked(removedWindowIdForUser);
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Removed user connection for pid:" + Binder.getCallingPid()
                                + " with windowId: " + removedWindowIdForUser + " and userId:"
                                + userId + " and token: " + window.asBinder());
                    }
                    return;
                }
            }
        }
    }

    /**
     * Resolves a connection wrapper for a window id.
     *
     * @param userId The user id for any user-specific windows
     * @param windowId The id of the window of interest
     *
     * @return a connection to the window
     */
    @Nullable
    public RemoteAccessibilityConnection getConnectionLocked(int userId, int windowId) {
        if (DEBUG) {
            Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
        }
        RemoteAccessibilityConnection connection = mGlobalInteractionConnections.get(windowId);
        if (connection == null && isValidUserForInteractionConnectionsLocked(userId)) {
            connection = getInteractionConnectionsForUserLocked(userId).get(windowId);
        }
        if (connection != null && connection.getRemote() != null) {
            return connection;
        }
        if (DEBUG) {
            Slog.e(LOG_TAG, "No interaction connection to window: " + windowId);
        }
        return null;
    }

    private int removeAccessibilityInteractionConnectionInternalLocked(IBinder windowToken,
            SparseArray<IBinder> windowTokens, SparseArray<RemoteAccessibilityConnection>
                    interactionConnections) {
        final int count = windowTokens.size();
        for (int i = 0; i < count; i++) {
            if (windowTokens.valueAt(i) == windowToken) {
                final int windowId = windowTokens.keyAt(i);
                windowTokens.removeAt(i);
                RemoteAccessibilityConnection wrapper = interactionConnections.get(windowId);
                wrapper.unlinkToDeath();
                interactionConnections.remove(windowId);
                return windowId;
            }
        }
        return -1;
    }

    /**
     * Removes accessibility interaction connection according to given windowId and userId.
     *
     * @param windowId The windowId of accessibility interaction connection
     * @param userId The userId to remove
     */
    private void removeAccessibilityInteractionConnectionLocked(int windowId, int userId) {
        if (userId == UserHandle.USER_ALL) {
            mGlobalWindowTokens.remove(windowId);
            mGlobalInteractionConnections.remove(windowId);
        } else {
            if (isValidUserForWindowTokensLocked(userId)) {
                getWindowTokensForUserLocked(userId).remove(windowId);
            }
            if (isValidUserForInteractionConnectionsLocked(userId)) {
                getInteractionConnectionsForUserLocked(userId).remove(windowId);
            }
        }
        onAccessibilityInteractionConnectionRemovedLocked(windowId);
        if (DEBUG) {
            Slog.i(LOG_TAG, "Removing interaction connection to windowId: " + windowId);
        }
    }

    /**
     * Invoked when accessibility interaction connection of window is removed.
     *
     * @param windowId Removed windowId
     */
    private void onAccessibilityInteractionConnectionRemovedLocked(int windowId) {
        // Active window will not update, if windows callback is unregistered.
        // Update active window to invalid, when its a11y interaction connection is removed.
        if (!isTrackingWindowsLocked() && windowId >= 0 && mActiveWindowId == windowId) {
            mActiveWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }
    }

    /**
     * Gets window token according to given userId and windowId.
     *
     * @param userId The userId
     * @param windowId The windowId
     * @return The window token
     */
    @Nullable
    public IBinder getWindowTokenForUserAndWindowIdLocked(int userId, int windowId) {
        IBinder windowToken = mGlobalWindowTokens.get(windowId);
        if (windowToken == null && isValidUserForWindowTokensLocked(userId)) {
            windowToken = getWindowTokensForUserLocked(userId).get(windowId);
        }
        return windowToken;
    }

    /**
     * Returns the userId that owns the given window token, {@link UserHandle#USER_NULL}
     * if not found.
     *
     * @param windowToken The winodw token
     * @return The userId
     */
    public int getWindowOwnerUserId(@NonNull IBinder windowToken) {
        return mWindowManagerInternal.getWindowOwnerUserId(windowToken);
    }

    /**
     * Returns windowId of given userId and window token.
     *
     * @param userId The userId
     * @param token The window token
     * @return The windowId
     */
    public int findWindowIdLocked(int userId, @NonNull IBinder token) {
        final int globalIndex = mGlobalWindowTokens.indexOfValue(token);
        if (globalIndex >= 0) {
            return mGlobalWindowTokens.keyAt(globalIndex);
        }
        if (isValidUserForWindowTokensLocked(userId)) {
            final int userIndex = getWindowTokensForUserLocked(userId).indexOfValue(token);
            if (userIndex >= 0) {
                return getWindowTokensForUserLocked(userId).keyAt(userIndex);
            }
        }
        return -1;
    }

    /**
     * Computes partial interactive region of given windowId.
     *
     * @param windowId The windowId
     * @param outRegion The output to which to write the bounds.
     * @return true if outRegion is not empty.
     */
    public boolean computePartialInteractiveRegionForWindowLocked(int windowId,
            @NonNull Region outRegion) {
        return mDisplayWindowsObserver.computePartialInteractiveRegionForWindowLocked(windowId,
            outRegion);
    }

    /**
     * Updates active windowId and accessibility focused windowId according to given accessibility
     * event and action.
     *
     * @param userId The userId
     * @param windowId The windowId of accessibility event
     * @param nodeId The accessibility node id of accessibility event
     * @param eventType The accessibility event type
     * @param eventAction The accessibility event action
     */
    public void updateActiveAndAccessibilityFocusedWindowLocked(int userId, int windowId,
            long nodeId, int eventType, int eventAction) {
        // The active window is either the window that has input focus or
        // the window that the user is currently touching. If the user is
        // touching a window that does not have input focus as soon as the
        // the user stops touching that window the focused window becomes
        // the active one. Here we detect the touched window and make it
        // active. In updateWindowsLocked() we update the focused window
        // and if the user is not touching the screen, we make the focused
        // window the active one.
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                // If no service has the capability to introspect screen,
                // we do not register callback in the window manager for
                // window changes, so we have to ask the window manager
                // what the focused window is to update the active one.
                // The active window also determined events from which
                // windows are delivered.
                synchronized (mLock) {
                    if (!isTrackingWindowsLocked()) {
                        mFocusedWindowId = findFocusedWindowId(userId);
                        if (windowId == mFocusedWindowId) {
                            mActiveWindowId = windowId;
                        }
                    }
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER: {
                // Do not allow delayed hover events to confuse us
                // which the active window is.
                synchronized (mLock) {
                    if (mTouchInteractionInProgress && mActiveWindowId != windowId) {
                        setActiveWindowLocked(windowId);
                    }
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                synchronized (mLock) {
                    if (mAccessibilityFocusedWindowId != windowId) {
                        clearAccessibilityFocusLocked(mAccessibilityFocusedWindowId);
                        setAccessibilityFocusedWindowLocked(windowId);
                        mAccessibilityFocusNodeId = nodeId;
                    }
                }
            } break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                synchronized (mLock) {
                    if (mAccessibilityFocusNodeId == nodeId) {
                        mAccessibilityFocusNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                    }
                    // Clear the window with focus if it no longer has focus and we aren't
                    // just moving focus from one view to the other in the same window.
                    if ((mAccessibilityFocusNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)
                            && (mAccessibilityFocusedWindowId == windowId)
                            && (eventAction != AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                        mAccessibilityFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                    }
                }
            } break;
        }
    }

    /**
     * Callbacks from AccessibilityManagerService when touch explorer turn on and
     * motion down detected.
     */
    public void onTouchInteractionStart() {
        synchronized (mLock) {
            mTouchInteractionInProgress = true;
        }
    }

    /**
     * Callbacks from AccessibilityManagerService when touch explorer turn on and
     * gesture or motion up detected.
     */
    public void onTouchInteractionEnd() {
        synchronized (mLock) {
            mTouchInteractionInProgress = false;
            // We want to set the active window to be current immediately
            // after the user has stopped touching the screen since if the
            // user types with the IME he should get a feedback for the
            // letter typed in the text view which is in the input focused
            // window. Note that we always deliver hover accessibility events
            // (they are a result of user touching the screen) so change of
            // the active window before all hover accessibility events from
            // the touched window are delivered is fine.
            final int oldActiveWindow = mActiveWindowId;
            setActiveWindowLocked(mFocusedWindowId);

            // If there is no service that can operate with interactive windows
            // then we keep the old behavior where a window loses accessibility
            // focus if it is no longer active. This still changes the behavior
            // for services that do not operate with interactive windows and run
            // at the same time as the one(s) which does. In practice however,
            // there is only one service that uses accessibility focus and it
            // is typically the one that operates with interactive windows, So,
            // this is fine. Note that to allow a service to work across windows
            // we have to allow accessibility focus stay in any of them. Sigh...
            final boolean accessibilityFocusOnlyInActiveWindow = !isTrackingWindowsLocked();
            if (oldActiveWindow != mActiveWindowId
                    && mAccessibilityFocusedWindowId == oldActiveWindow
                    && accessibilityFocusOnlyInActiveWindow) {
                clearAccessibilityFocusLocked(oldActiveWindow);
            }
        }
    }

    /**
     * Gets the id of the current active window.
     *
     * @return The userId
     */
    public int getActiveWindowId(int userId) {
        if (mActiveWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID
                && !mTouchInteractionInProgress) {
            mActiveWindowId = findFocusedWindowId(userId);
        }
        return mActiveWindowId;
    }

    private void setActiveWindowLocked(int windowId) {
        if (mActiveWindowId != windowId) {
            mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                    AccessibilityEvent.obtainWindowsChangedEvent(
                            mActiveWindowId, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE));

            mActiveWindowId = windowId;
            mDisplayWindowsObserver.setActiveWindowLocked(windowId);
        }
    }

    private void setAccessibilityFocusedWindowLocked(int windowId) {
        if (mAccessibilityFocusedWindowId != windowId) {
            mAccessibilityEventSender.sendAccessibilityEventForCurrentUserLocked(
                    AccessibilityEvent.obtainWindowsChangedEvent(
                            mAccessibilityFocusedWindowId,
                            WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED));

            mAccessibilityFocusedWindowId = windowId;
            mDisplayWindowsObserver.setAccessibilityFocusedWindowLocked(windowId);
        }
    }

    /**
     * Returns accessibility window info according to given windowId.
     *
     * @param windowId The windowId
     * @return The accessibility window info
     */
    @Nullable
    public AccessibilityWindowInfo findA11yWindowInfoByIdLocked(int windowId) {
        return mDisplayWindowsObserver.findA11yWindowInfoByIdLocked(windowId);
    }

    /**
     * Returns the window info according to given windowId.
     *
     * @param windowId The windowId
     * @return The window info
     */
    @Nullable
    public WindowInfo findWindowInfoByIdLocked(int windowId) {
        return mDisplayWindowsObserver.findWindowInfoByIdLocked(windowId);
    }

    /**
     * Returns focused windowId or accessibility focused windowId according to given focusType.
     *
     * @param focusType {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     * {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}
     * @return The focused windowId
     */
    public int getFocusedWindowId(int focusType) {
        if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
            return mFocusedWindowId;
        } else if (focusType == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
            return mAccessibilityFocusedWindowId;
        }
        return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    }

    /**
     * Returns {@link AccessibilityWindowInfo} of PIP window.
     *
     * @return PIP accessibility window info
     */
    @Nullable
    public AccessibilityWindowInfo getPictureInPictureWindowLocked() {
        return mDisplayWindowsObserver.getPictureInPictureWindowLocked();
    }

    /**
     * Sets an IAccessibilityInteractionConnection to replace the actions of a picture-in-picture
     * window.
     */
    public void setPictureInPictureActionReplacingConnection(
            @Nullable IAccessibilityInteractionConnection connection) throws RemoteException {
        synchronized (mLock) {
            if (mPictureInPictureActionReplacingConnection != null) {
                mPictureInPictureActionReplacingConnection.unlinkToDeath();
                mPictureInPictureActionReplacingConnection = null;
            }
            if (connection != null) {
                RemoteAccessibilityConnection wrapper = new RemoteAccessibilityConnection(
                        AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID,
                        connection, "foo.bar.baz", Process.SYSTEM_UID, UserHandle.USER_ALL);
                mPictureInPictureActionReplacingConnection = wrapper;
                wrapper.linkToDeath();
            }
        }
    }

    /**
     * Returns accessibility interaction connection for picture-in-picture window.
     */
    @Nullable
    public RemoteAccessibilityConnection getPictureInPictureActionReplacingConnection() {
        return mPictureInPictureActionReplacingConnection;
    }

    /**
     * Invokes {@link IAccessibilityInteractionConnection#notifyOutsideTouch()} for windows that
     * have watch outside touch flag and its layer is upper than target window.
     */
    public void notifyOutsideTouch(int userId, int targetWindowId) {
        final List<Integer> outsideWindowsIds;
        final List<RemoteAccessibilityConnection> connectionList = new ArrayList<>();
        synchronized (mLock) {
            outsideWindowsIds =
                mDisplayWindowsObserver.getWatchOutsideTouchWindowIdLocked(targetWindowId);
            for (int i = 0; i < outsideWindowsIds.size(); i++) {
                connectionList.add(getConnectionLocked(userId, outsideWindowsIds.get(i)));
            }
        }
        for (int i = 0; i < connectionList.size(); i++) {
            final RemoteAccessibilityConnection connection = connectionList.get(i);
            if (connection != null) {
                try {
                    connection.getRemote().notifyOutsideTouch();
                } catch (RemoteException re) {
                    if (DEBUG) {
                        Slog.e(LOG_TAG, "Error calling notifyOutsideTouch()");
                    }
                }
            }
        }
    }

    /**
     * Gets current input focused window token from window manager, and returns its windowId.
     *
     * @param userId The userId
     * @return The input focused windowId, or -1 if not found
     */
    private int findFocusedWindowId(int userId) {
        final IBinder token = mWindowManagerInternal.getFocusedWindowToken();
        synchronized (mLock) {
            return findWindowIdLocked(userId, token);
        }
    }

    private boolean isValidUserForInteractionConnectionsLocked(int userId) {
        return mInteractionConnections.indexOfKey(userId) >= 0;
    }

    private boolean isValidUserForWindowTokensLocked(int userId) {
        return mWindowTokens.indexOfKey(userId) >= 0;
    }

    private SparseArray<RemoteAccessibilityConnection> getInteractionConnectionsForUserLocked(
            int userId) {
        SparseArray<RemoteAccessibilityConnection> connection = mInteractionConnections.get(
                userId);
        if (connection == null) {
            connection = new SparseArray<>();
            mInteractionConnections.put(userId, connection);
        }
        return connection;
    }

    private SparseArray<IBinder> getWindowTokensForUserLocked(int userId) {
        SparseArray<IBinder> windowTokens = mWindowTokens.get(userId);
        if (windowTokens == null) {
            windowTokens = new SparseArray<>();
            mWindowTokens.put(userId, windowTokens);
        }
        return windowTokens;
    }

    private void clearAccessibilityFocusLocked(int windowId) {
        mHandler.sendMessage(obtainMessage(
                AccessibilityWindowManager::clearAccessibilityFocusMainThread,
                AccessibilityWindowManager.this,
                mAccessibilityUserManager.getCurrentUserIdLocked(), windowId));
    }

    private void clearAccessibilityFocusMainThread(int userId, int windowId) {
        final RemoteAccessibilityConnection connection;
        synchronized (mLock) {
            connection = getConnectionLocked(userId, windowId);
            if (connection == null) {
                return;
            }
        }
        try {
            connection.getRemote().clearAccessibilityFocus();
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling clearAccessibilityFocus()");
            }
        }
    }

    /**
     * Dumps all {@link AccessibilityWindowInfo}s here.
     */
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        mDisplayWindowsObserver.dumpLocked(fd, pw, args);
    }
}
