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

package com.android.wm.shell.pip.tv;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.DisplayInfo;

import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipTaskOrganizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class PipController implements Pip, PipTaskOrganizer.PipTransitionCallback {
    private static final String TAG = "PipController";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Unknown or invalid state
     */
    public static final int STATE_UNKNOWN = -1;
    /**
     * State when there's no PIP.
     */
    public static final int STATE_NO_PIP = 0;
    /**
     * State when PIP is shown. This is used as default PIP state.
     */
    public static final int STATE_PIP = 1;
    /**
     * State when PIP menu dialog is shown.
     */
    public static final int STATE_PIP_MENU = 2;

    private static final int TASK_ID_NO_PIP = -1;
    private static final int INVALID_RESOURCE_TYPE = -1;

    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH = 0x1;

    /**
     * PIPed activity is playing a media and it can be paused.
     */
    static final int PLAYBACK_STATE_PLAYING = 0;
    /**
     * PIPed activity has a paused media and it can be played.
     */
    static final int PLAYBACK_STATE_PAUSED = 1;
    /**
     * Users are unable to control PIPed activity's media playback.
     */
    static final int PLAYBACK_STATE_UNAVAILABLE = 2;

    private static final int CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS = 3000;

    private int mSuspendPipResizingReason;

    private Context mContext;
    private PipBoundsHandler mPipBoundsHandler;
    private PipTaskOrganizer mPipTaskOrganizer;
    private IActivityTaskManager mActivityTaskManager;
    private MediaSessionManager mMediaSessionManager;
    private int mState = STATE_NO_PIP;
    private int mResumeResizePinnedStackRunnableState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private List<Listener> mListeners = new ArrayList<>();
    private List<MediaListener> mMediaListeners = new ArrayList<>();
    private Rect mPipBounds;
    private Rect mDefaultPipBounds = new Rect();
    private Rect mMenuModePipBounds;
    private int mLastOrientation = Configuration.ORIENTATION_UNDEFINED;
    private boolean mInitialized;
    private int mPipTaskId = TASK_ID_NO_PIP;
    private int mPinnedStackId = INVALID_STACK_ID;
    private ComponentName mPipComponentName;
    private MediaController mPipMediaController;
    private String[] mLastPackagesResourceGranted;
    private PipNotification mPipNotification;
    private ParceledListSlice<RemoteAction> mCustomActions;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    private int mResizeAnimationDuration;

    // Used to calculate the movement bounds
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();

    // Keeps track of the IME visibility to adjust the PiP when the IME is visible
    private boolean mImeVisible;
    private int mImeHeightAdjustment;

    private final Runnable mResizePinnedStackRunnable =
            () -> resizePinnedStack(mResumeResizePinnedStackRunnableState);
    private final Runnable mClosePipRunnable = () -> closePip();
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_RESOURCE_GRANTED.equals(action)) {
                String[] packageNames = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                int resourceType = intent.getIntExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE,
                        INVALID_RESOURCE_TYPE);
                if (packageNames != null && packageNames.length > 0
                        && resourceType == Intent.EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC) {
                    handleMediaResourceGranted(packageNames);
                }
            }

        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveMediaSessionListener =
            controllers -> updateMediaController(controllers);
    private final PinnedStackListenerForwarder.PinnedStackListener mPinnedStackListener =
            new PipControllerPinnedStackListener();

    @Override
    public void registerSessionListenerForCurrentUser() {
        // TODO Need confirm if TV have to re-registers when switch user
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveMediaSessionListener);
        mMediaSessionManager.addOnActiveSessionsChangedListener(mActiveMediaSessionListener, null,
                UserHandle.USER_CURRENT, null);
    }

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipControllerPinnedStackListener extends
            PinnedStackListenerForwarder.PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mHandler.post(() -> {
                mPipBoundsHandler.onImeVisibilityChanged(imeVisible, imeHeight);
                if (mState == STATE_PIP) {
                    if (mImeVisible != imeVisible) {
                        if (imeVisible) {
                            // Save the IME height adjustment, and offset to not occlude the IME
                            mPipBounds.offset(0, -imeHeight);
                            mImeHeightAdjustment = imeHeight;
                        } else {
                            // Apply the inverse adjustment when the IME is hidden
                            mPipBounds.offset(0, mImeHeightAdjustment);
                        }
                        mImeVisible = imeVisible;
                        resizePinnedStack(STATE_PIP);
                    }
                }
            });
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            mHandler.post(() -> {
                // Populate the inset / normal bounds and DisplayInfo from mPipBoundsHandler first.
                mPipBoundsHandler.onMovementBoundsChanged(mTmpInsetBounds, mPipBounds,
                        mDefaultPipBounds, mTmpDisplayInfo);
            });
        }

        @Override
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
            mCustomActions = actions;
            mHandler.post(() -> {
                for (int i = mListeners.size() - 1; i >= 0; --i) {
                    mListeners.get(i).onPipMenuActionsChanged(mCustomActions);
                }
            });
        }
    }

    public PipController(Context context,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            WindowManagerShellWrapper windowManagerShellWrapper
    ) {
        if (!mInitialized) {
            mInitialized = true;
            mContext = context;
            mPipNotification = new PipNotification(context, this);
            mPipBoundsHandler = pipBoundsHandler;
            // Ensure that we have the display info in case we get calls to update the bounds
            // before the listener calls back
            final DisplayInfo displayInfo = new DisplayInfo();
            context.getDisplay().getDisplayInfo(displayInfo);
            mPipBoundsHandler.onDisplayInfoChanged(displayInfo);

            mResizeAnimationDuration = context.getResources()
                    .getInteger(R.integer.config_pipResizeAnimationDuration);
            mPipTaskOrganizer = pipTaskOrganizer;
            mPipTaskOrganizer.registerPipTransitionCallback(this);
            mActivityTaskManager = ActivityTaskManager.getService();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter, UserHandle.USER_ALL);

            // Initialize the last orientation and apply the current configuration
            Configuration initialConfig = mContext.getResources().getConfiguration();
            mLastOrientation = initialConfig.orientation;
            loadConfigurationsAndApply(initialConfig);

            mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
            mWindowManagerShellWrapper = windowManagerShellWrapper;
            try {
                mWindowManagerShellWrapper.addPinnedStackListener(mPinnedStackListener);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register pinned stack listener", e);
            }
        }

        // TODO(b/169395392) Refactor PipMenuActivity to PipMenuView
        PipMenuActivity.setPipController(this);
    }

    private void loadConfigurationsAndApply(Configuration newConfig) {
        if (mLastOrientation != newConfig.orientation) {
            // Don't resize the pinned stack on orientation change. TV does not care about this case
            // and this could clobber the existing animation to the new bounds calculated by WM.
            mLastOrientation = newConfig.orientation;
            return;
        }

        Resources res = mContext.getResources();
        mMenuModePipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_menu_bounds));

        // Reset the PIP bounds and apply. PIP bounds can be changed by two reasons.
        //   1. Configuration changed due to the language change (RTL <-> RTL)
        //   2. SystemUI restarts after the crash
        mPipBounds = mDefaultPipBounds;
        resizePinnedStack(getPinnedTaskInfo() == null ? STATE_NO_PIP : STATE_PIP);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        loadConfigurationsAndApply(newConfig);
        mPipNotification.onConfigurationChanged(mContext);
    }

    /**
     * Shows the picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    public void showPictureInPictureMenu() {
        if (DEBUG) Log.d(TAG, "showPictureInPictureMenu(), current state=" + getStateDescription());

        if (getState() == STATE_PIP) {
            resizePinnedStack(STATE_PIP_MENU);
        }
    }

    /**
     * Closes PIP (PIPed activity and PIP system UI).
     */
    public void closePip() {
        if (DEBUG) Log.d(TAG, "closePip(), current state=" + getStateDescription());

        closePipInternal(true);
    }

    private void closePipInternal(boolean removePipStack) {
        if (DEBUG) {
            Log.d(TAG,
                    "closePipInternal() removePipStack=" + removePipStack + ", current state="
                            + getStateDescription());
        }

        mState = STATE_NO_PIP;
        mPipTaskId = TASK_ID_NO_PIP;
        mPipMediaController = null;
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveMediaSessionListener);
        if (removePipStack) {
            try {
                mActivityTaskManager.removeTask(mPinnedStackId);
            } catch (RemoteException e) {
                Log.e(TAG, "removeTask failed", e);
            } finally {
                mPinnedStackId = INVALID_STACK_ID;
            }
        }
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipActivityClosed();
        }
        mHandler.removeCallbacks(mClosePipRunnable);
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    public void movePipToFullscreen() {
        if (DEBUG) Log.d(TAG, "movePipToFullscreen(), current state=" + getStateDescription());

        mPipTaskId = TASK_ID_NO_PIP;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onMoveToFullscreen();
        }
        resizePinnedStack(STATE_NO_PIP);
    }

    @Override
    public void onActivityPinned(String packageName) {
        if (DEBUG) Log.d(TAG, "onActivityPinned()");

        RootTaskInfo taskInfo = getPinnedTaskInfo();
        if (taskInfo == null) {
            Log.w(TAG, "Cannot find pinned stack");
            return;
        }
        if (DEBUG) Log.d(TAG, "PINNED_STACK:" + taskInfo);
        mPinnedStackId = taskInfo.taskId;
        mPipTaskId = taskInfo.childTaskIds[taskInfo.childTaskIds.length - 1];
        mPipComponentName = ComponentName.unflattenFromString(
                taskInfo.childTaskNames[taskInfo.childTaskNames.length - 1]);
        // Set state to STATE_PIP so we show it when the pinned stack animation ends.
        mState = STATE_PIP;
        mMediaSessionManager.addOnActiveSessionsChangedListener(
                mActiveMediaSessionListener, null);
        updateMediaController(mMediaSessionManager.getActiveSessions(null));
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            mListeners.get(i).onPipEntered(packageName);
        }
    }

    @Override
    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
            boolean clearedTask) {
        if (task.configuration.windowConfiguration.getWindowingMode()
                != WINDOWING_MODE_PINNED) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");

        // If PIPed activity is launched again by Launcher or intent, make it fullscreen.
        movePipToFullscreen();
    }

    @Override
    public void onTaskStackChanged() {
        if (DEBUG) Log.d(TAG, "onTaskStackChanged()");

        if (getState() != STATE_NO_PIP) {
            boolean hasPip = false;

            RootTaskInfo taskInfo = getPinnedTaskInfo();
            if (taskInfo == null || taskInfo.childTaskIds == null) {
                Log.w(TAG, "There is nothing in pinned stack");
                closePipInternal(false);
                return;
            }
            for (int i = taskInfo.childTaskIds.length - 1; i >= 0; --i) {
                if (taskInfo.childTaskIds[i] == mPipTaskId) {
                    // PIP task is still alive.
                    hasPip = true;
                    break;
                }
            }
            if (!hasPip) {
                // PIP task doesn't exist anymore in PINNED_STACK.
                closePipInternal(true);
                return;
            }
        }
        if (getState() == STATE_PIP) {
            if (mPipBounds != mDefaultPipBounds) {
                mPipBounds = mDefaultPipBounds;
                resizePinnedStack(STATE_PIP);
            }
        }
    }

    /**
     * Suspends resizing operation on the Pip until {@link #resumePipResizing} is called
     *
     * @param reason The reason for suspending resizing operations on the Pip.
     */
    public void suspendPipResizing(int reason) {
        if (DEBUG) {
            Log.d(TAG,
                    "suspendPipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));
        }

        mSuspendPipResizingReason |= reason;
    }

    /**
     * Resumes resizing operation on the Pip that was previously suspended.
     *
     * @param reason The reason resizing operations on the Pip was suspended.
     */
    public void resumePipResizing(int reason) {
        if ((mSuspendPipResizingReason & reason) == 0) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG,
                    "resumePipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));
        }
        mSuspendPipResizingReason &= ~reason;
        mHandler.post(mResizePinnedStackRunnable);
    }

    /**
     * Resize the Pip to the appropriate size for the input state.
     *
     * @param state In Pip state also used to determine the new size for the Pip.
     */
    public void resizePinnedStack(int state) {
        if (DEBUG) {
            Log.d(TAG, "resizePinnedStack() state=" + stateToName(state) + ", current state="
                    + getStateDescription(), new Exception());
        }

        boolean wasStateNoPip = (mState == STATE_NO_PIP);
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipResizeAboutToStart();
        }
        if (mSuspendPipResizingReason != 0) {
            mResumeResizePinnedStackRunnableState = state;
            if (DEBUG) {
                Log.d(TAG, "resizePinnedStack() deferring"
                        + " mSuspendPipResizingReason=" + mSuspendPipResizingReason
                        + " mResumeResizePinnedStackRunnableState="
                        + stateToName(mResumeResizePinnedStackRunnableState));
            }
            return;
        }
        mState = state;
        final Rect newBounds;
        switch (mState) {
            case STATE_NO_PIP:
                newBounds = null;
                // If the state was already STATE_NO_PIP, then do not resize the stack below as it
                // will not exist
                if (wasStateNoPip) {
                    return;
                }
                break;
            case STATE_PIP_MENU:
                newBounds = mMenuModePipBounds;
                break;
            case STATE_PIP: // fallthrough
            default:
                newBounds = mPipBounds;
                break;
        }
        if (newBounds != null) {
            mPipTaskOrganizer.scheduleAnimateResizePip(newBounds, mResizeAnimationDuration, null);
        } else {
            mPipTaskOrganizer.exitPip(mResizeAnimationDuration);
        }
    }

    /**
     * @return the current state, or the pending state if the state change was previously suspended.
     */
    private int getState() {
        if (mSuspendPipResizingReason != 0) {
            return mResumeResizePinnedStackRunnableState;
        }
        return mState;
    }

    /**
     * Shows PIP menu UI by launching {@link PipMenuActivity}. It also locates the pinned
     * stack to the centered PIP bound {@link R.config_centeredPictureInPictureBounds}.
     */
    private void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu(), current state=" + getStateDescription());

        mState = STATE_PIP_MENU;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onShowPipMenu();
        }
        Intent intent = new Intent(mContext, PipMenuActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(PipMenuActivity.EXTRA_CUSTOM_ACTIONS, mCustomActions);
        mContext.startActivity(intent);
    }

    /**
     * Adds a {@link Listener} to PipController.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a {@link Listener} from PipController.
     */
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Adds a {@link MediaListener} to PipController.
     */
    public void addMediaListener(MediaListener listener) {
        mMediaListeners.add(listener);
    }

    /**
     * Removes a {@link MediaListener} from PipController.
     */
    public void removeMediaListener(MediaListener listener) {
        mMediaListeners.remove(listener);
    }

    /**
     * Returns {@code true} if PIP is shown.
     */
    public boolean isPipShown() {
        return mState != STATE_NO_PIP;
    }

    private RootTaskInfo getPinnedTaskInfo() {
        RootTaskInfo taskInfo = null;
        try {
            taskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
        } catch (RemoteException e) {
            Log.e(TAG, "getRootTaskInfo failed", e);
        }
        return taskInfo;
    }

    private void handleMediaResourceGranted(String[] packageNames) {
        if (getState() == STATE_NO_PIP) {
            mLastPackagesResourceGranted = packageNames;
        } else {
            boolean requestedFromLastPackages = false;
            if (mLastPackagesResourceGranted != null) {
                for (String packageName : mLastPackagesResourceGranted) {
                    for (String newPackageName : packageNames) {
                        if (TextUtils.equals(newPackageName, packageName)) {
                            requestedFromLastPackages = true;
                            break;
                        }
                    }
                }
            }
            mLastPackagesResourceGranted = packageNames;
            if (!requestedFromLastPackages) {
                closePip();
            }
        }
    }

    private void updateMediaController(List<MediaController> controllers) {
        MediaController mediaController = null;
        if (controllers != null && getState() != STATE_NO_PIP && mPipComponentName != null) {
            for (int i = controllers.size() - 1; i >= 0; i--) {
                MediaController controller = controllers.get(i);
                // We assumes that an app with PIPable activity
                // keeps the single instance of media controller especially when PIP is on.
                if (controller.getPackageName().equals(mPipComponentName.getPackageName())) {
                    mediaController = controller;
                    break;
                }
            }
        }
        if (mPipMediaController != mediaController) {
            mPipMediaController = mediaController;
            for (int i = mMediaListeners.size() - 1; i >= 0; i--) {
                mMediaListeners.get(i).onMediaControllerChanged();
            }
            if (mPipMediaController == null) {
                mHandler.postDelayed(mClosePipRunnable,
                        CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS);
            } else {
                mHandler.removeCallbacks(mClosePipRunnable);
            }
        }
    }

    /**
     * Gets the {@link android.media.session.MediaController} for the PIPed activity.
     */
    public MediaController getMediaController() {
        return mPipMediaController;
    }

    @Override
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {

    }

    /**
     * Returns the PIPed activity's playback state.
     * This returns one of {@link #PLAYBACK_STATE_PLAYING}, {@link #PLAYBACK_STATE_PAUSED},
     * or {@link #PLAYBACK_STATE_UNAVAILABLE}.
     */
    public int getPlaybackState() {
        if (mPipMediaController == null || mPipMediaController.getPlaybackState() == null) {
            return PLAYBACK_STATE_UNAVAILABLE;
        }
        int state = mPipMediaController.getPlaybackState().getState();
        boolean isPlaying = (state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT);
        long actions = mPipMediaController.getPlaybackState().getActions();
        if (!isPlaying && ((actions & PlaybackState.ACTION_PLAY) != 0)) {
            return PLAYBACK_STATE_PAUSED;
        } else if (isPlaying && ((actions & PlaybackState.ACTION_PAUSE) != 0)) {
            return PLAYBACK_STATE_PLAYING;
        }
        return PLAYBACK_STATE_UNAVAILABLE;
    }

    @Override
    public void onPipTransitionStarted(ComponentName activity, int direction, Rect pipBounds) {
    }

    @Override
    public void onPipTransitionFinished(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    @Override
    public void onPipTransitionCanceled(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    private void onPipTransitionFinishedOrCanceled() {
        if (DEBUG) Log.d(TAG, "onPipTransitionFinishedOrCanceled()");

        if (getState() == STATE_PIP_MENU) {
            showPipMenu();
        }
    }

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Invoked when an activity is pinned and PIP manager is set corresponding information.
         * Classes must use this instead of {@link android.app.ITaskStackListener.onActivityPinned}
         * because there's no guarantee for the PIP manager be return relavent information
         * correctly. (e.g. {@link Pip.isPipShown}).
         */
        void onPipEntered(String packageName);
        /** Invoked when a PIPed activity is closed. */
        void onPipActivityClosed();
        /** Invoked when the PIP menu gets shown. */
        void onShowPipMenu();
        /** Invoked when the PIP menu actions change. */
        void onPipMenuActionsChanged(ParceledListSlice<RemoteAction> actions);
        /** Invoked when the PIPed activity is about to return back to the fullscreen. */
        void onMoveToFullscreen();
        /** Invoked when we are above to start resizing the Pip. */
        void onPipResizeAboutToStart();
    }

    /**
     * A listener interface to receive change in PIP's media controller
     */
    public interface MediaListener {
        /** Invoked when the MediaController on PIPed activity is changed. */
        void onMediaControllerChanged();
    }

    private String getStateDescription() {
        if (mSuspendPipResizingReason == 0) {
            return stateToName(mState);
        }
        return stateToName(mResumeResizePinnedStackRunnableState) + " (while " + stateToName(mState)
                + " is suspended)";
    }

    private static String stateToName(int state) {
        switch (state) {
            case STATE_NO_PIP:
                return "NO_PIP";

            case STATE_PIP:
                return "PIP";

            case STATE_PIP_MENU:
                return "PIP_MENU";

            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
