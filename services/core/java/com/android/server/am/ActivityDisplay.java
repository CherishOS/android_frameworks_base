/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.REMOVE_MODE_DESTROY_CONTENT;

import static com.android.server.am.ActivityDisplayProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.ActivityDisplayProto.FOCUSED_STACK_ID;
import static com.android.server.am.ActivityDisplayProto.ID;
import static com.android.server.am.ActivityDisplayProto.RESUMED_ACTIVITY;
import static com.android.server.am.ActivityDisplayProto.STACKS;
import static com.android.server.am.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.am.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.am.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStackSupervisor.FindTaskResult;
import static com.android.server.am.ActivityStackSupervisor.TAG_STATES;
import static com.android.server.am.ActivityStackSupervisor.TAG_TASKS;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.DisplayWindowController;
import com.android.server.wm.WindowContainerListener;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Exactly one of these classes per Display in the system. Capable of holding zero or more
 * attached {@link ActivityStack}s.
 */
class ActivityDisplay extends ConfigurationContainer<ActivityStack>
        implements WindowContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityDisplay" : TAG_ATM;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;


    /**
     * Counter for next free stack ID to use for dynamic activity stacks. Unique across displays.
     */
    private static int sNextFreeStackId = 0;

    private ActivityStackSupervisor mSupervisor;
    /** Actual Display this object tracks. */
    int mDisplayId;
    Display mDisplay;

    /**
     * All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes. Any calls
     * changing the list should also call {@link #onStackOrderChanged()}.
     */
    private final ArrayList<ActivityStack> mStacks = new ArrayList<>();
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks = new ArrayList<>();

    /** Array of all UIDs that are present on the display. */
    private IntArray mDisplayAccessUIDs = new IntArray();

    /** All tokens used to put activities on this stack to sleep (including mOffToken) */
    final ArrayList<ActivityTaskManagerInternal.SleepToken> mAllSleepTokens = new ArrayList<>();
    /** The token acquired by ActivityStackSupervisor to put stacks on the display to sleep */
    ActivityTaskManagerInternal.SleepToken mOffToken;

    private boolean mSleeping;

    /**
     * The display is removed from the system and we are just waiting for all activities on it to be
     * finished before removing this object.
     */
    private boolean mRemoved;

    /**
     * A focusable stack that is purposely to be positioned at the top. Although the stack may not
     * have the topmost index, it is used as a preferred candidate to prevent being unable to resume
     * target stack properly when there are other focusable always-on-top stacks.
     */
    private ActivityStack mPreferredTopFocusableStack;

    /**
     * If this is the same as {@link #getFocusedStack} then the activity on the top of the focused
     * stack has been resumed. If stacks are changing position this will hold the old stack until
     * the new stack becomes resumed after which it will be set to current focused stack.
     */
    private ActivityStack mLastFocusedStack;

    // Cached reference to some special stacks we tend to get a lot so we don't need to loop
    // through the list to find them.
    private ActivityStack mHomeStack = null;
    private ActivityStack mRecentsStack = null;
    private ActivityStack mPinnedStack = null;
    private ActivityStack mSplitScreenPrimaryStack = null;

    // Used in updating the display size
    private Point mTmpDisplaySize = new Point();

    private DisplayWindowController mWindowContainerController;

    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();

    ActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
        mSupervisor = supervisor;
        mDisplayId = display.getDisplayId();
        mDisplay = display;
        mWindowContainerController = createWindowContainerController();
        updateBounds();
    }

    protected DisplayWindowController createWindowContainerController() {
        return new DisplayWindowController(mDisplay, this);
    }

    DisplayWindowController getWindowContainerController() {
        return mWindowContainerController;
    }

    void updateBounds() {
        mDisplay.getSize(mTmpDisplaySize);
        setBounds(0, 0, mTmpDisplaySize.x, mTmpDisplaySize.y);
    }

    void addChild(ActivityStack stack, int position) {
        if (position == POSITION_BOTTOM) {
            position = 0;
        } else if (position == POSITION_TOP) {
            position = mStacks.size();
        }
        if (DEBUG_STACK) Slog.v(TAG_STACK, "addChild: attaching " + stack
                + " to displayId=" + mDisplayId + " position=" + position);
        addStackReferenceIfNeeded(stack);
        positionChildAt(stack, position);
        mSupervisor.mService.updateSleepIfNeededLocked();
    }

    void removeChild(ActivityStack stack) {
        if (DEBUG_STACK) Slog.v(TAG_STACK, "removeChild: detaching " + stack
                + " from displayId=" + mDisplayId);
        mStacks.remove(stack);
        if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }
        removeStackReferenceIfNeeded(stack);
        releaseSelfIfNeeded();
        mSupervisor.mService.updateSleepIfNeededLocked();
        onStackOrderChanged();
    }

    void positionChildAtTop(ActivityStack stack, boolean includingParents) {
        positionChildAtTop(stack, includingParents, null /* updateLastFocusedStackReason */);
    }

    void positionChildAtTop(ActivityStack stack, boolean includingParents,
            String updateLastFocusedStackReason) {
        positionChildAt(stack, mStacks.size(), includingParents, updateLastFocusedStackReason);
    }

    void positionChildAtBottom(ActivityStack stack) {
        positionChildAtBottom(stack, null /* updateLastFocusedStackReason */);
    }

    void positionChildAtBottom(ActivityStack stack, String updateLastFocusedStackReason) {
        positionChildAt(stack, 0, false /* includingParents */, updateLastFocusedStackReason);
    }

    private void positionChildAt(ActivityStack stack, int position) {
        positionChildAt(stack, position, false /* includingParents */,
                null /* updateLastFocusedStackReason */);
    }

    private void positionChildAt(ActivityStack stack, int position, boolean includingParents,
            String updateLastFocusedStackReason) {
        // TODO: Keep in sync with WindowContainer.positionChildAt(), once we change that to adjust
        //       the position internally, also update the logic here
        final ActivityStack prevFocusedStack = updateLastFocusedStackReason != null
                ? getFocusedStack() : null;
        final boolean wasContained = mStacks.remove(stack);
        final int insertPosition = getTopInsertPosition(stack, position);
        mStacks.add(insertPosition, stack);

        // The insert position may be adjusted to non-top when there is always-on-top stack. Since
        // the original position is preferred to be top, the stack should have higher priority when
        // we are looking for top focusable stack. The condition {@code wasContained} restricts the
        // preferred stack is set only when moving an existing stack to top instead of adding a new
        // stack that may be too early (e.g. in the middle of launching or reparenting).
        if (wasContained && position >= mStacks.size() - 1 && stack.isFocusableAndVisible()) {
            mPreferredTopFocusableStack = stack;
        } else if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }

        if (updateLastFocusedStackReason != null) {
            final ActivityStack currentFocusedStack = getFocusedStack();
            if (currentFocusedStack != prevFocusedStack) {
                mLastFocusedStack = prevFocusedStack;
                EventLogTags.writeAmFocusedStack(mSupervisor.mCurrentUser, mDisplayId,
                        currentFocusedStack == null ? -1 : currentFocusedStack.getStackId(),
                        mLastFocusedStack == null ? -1 : mLastFocusedStack.getStackId(),
                        updateLastFocusedStackReason);
            }
        }

        // Since positionChildAt() is called during the creation process of pinned stacks,
        // ActivityStack#getWindowContainerController() can be null. In this special case,
        // since DisplayContest#positionStackAt() is called in TaskStack#onConfigurationChanged(),
        // we don't have to call WindowContainerController#positionChildAt() here.
        if (stack.getWindowContainerController() != null) {
            mWindowContainerController.positionChildAt(stack.getWindowContainerController(),
                    insertPosition, includingParents);
        }
        onStackOrderChanged();
    }

    private int getTopInsertPosition(ActivityStack stack, int candidatePosition) {
        int position = mStacks.size();
        if (stack.inPinnedWindowingMode()) {
            // Stack in pinned windowing mode is z-ordered on-top of all other stacks so okay to
            // just return the candidate position.
            return Math.min(position, candidatePosition);
        }
        while (position > 0) {
            final ActivityStack targetStack = mStacks.get(position - 1);
            if (!targetStack.isAlwaysOnTop()) {
                // We reached a stack that isn't always-on-top.
                break;
            }
            if (stack.isAlwaysOnTop() && !targetStack.inPinnedWindowingMode()) {
                // Always on-top non-pinned windowing mode stacks can go anywhere below pinned stack.
                break;
            }
            position--;
        }
        return Math.min(position, candidatePosition);
    }

    <T extends ActivityStack> T getStack(int stackId) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.mStackId == stackId) {
                return (T) stack;
            }
        }
        return null;
    }

    /**
     * @return the topmost stack on the display that is compatible with the input windowing mode and
     * activity type. {@code null} means no compatible stack on the display.
     * @see ConfigurationContainer#isCompatible(int, int)
     */
    <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        if (activityType == ACTIVITY_TYPE_HOME) {
            return (T) mHomeStack;
        } else if (activityType == ACTIVITY_TYPE_RECENTS) {
            return (T) mRecentsStack;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return (T) mPinnedStack;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            return (T) mSplitScreenPrimaryStack;
        }

        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.isCompatible(windowingMode, activityType)) {
                return (T) stack;
            }
        }
        return null;
    }

    private boolean alwaysCreateStack(int windowingMode, int activityType) {
        // Always create a stack for fullscreen, freeform, and split-screen-secondary windowing
        // modes so that we can manage visual ordering and return types correctly.
        return activityType == ACTIVITY_TYPE_STANDARD
                && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_FREEFORM
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
    }

    /**
     * Returns an existing stack compatible with the windowing mode and activity type or creates one
     * if a compatible stack doesn't exist.
     * @see #getStack(int, int)
     * @see #createStack(int, int, boolean)
     */
    <T extends ActivityStack> T getOrCreateStack(int windowingMode, int activityType,
            boolean onTop) {
        if (!alwaysCreateStack(windowingMode, activityType)) {
            T stack = getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return createStack(windowingMode, activityType, onTop);
    }

    /**
     * Returns an existing stack compatible with the input params or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean)
     */
    <T extends ActivityStack> T getOrCreateStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable TaskRecord candidateTask, int activityType,
            boolean onTop) {
        // First preference is the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;
        // Validate that our desired windowingMode will work under the current conditions.
        // UNDEFINED windowing mode is a valid result and means that the new stack will inherit
        // it's display's windowing mode.
        windowingMode = validateWindowingMode(windowingMode, r, candidateTask, activityType);
        return getOrCreateStack(windowingMode, activityType, onTop);
    }

    private int getNextStackId() {
        return sNextFreeStackId++;
    }

    /**
     * Creates a stack matching the input windowing mode and activity type on this display.
     * @param windowingMode The windowing mode the stack should be created in. If
     *                      {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the stack will
     *                      inherit it's parent's windowing mode.
     * @param activityType The activityType the stack should be created in. If
     *                     {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the stack will
     *                     be created in {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop If true the stack will be created at the top of the display, else at the bottom.
     * @return The newly created stack.
     */
    <T extends ActivityStack> T createStack(int windowingMode, int activityType, boolean onTop) {

        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Can't have an undefined stack type yet...so re-map to standard. Anyone that wants
            // anything else should be passing it in anyways...
            activityType = ACTIVITY_TYPE_STANDARD;
        }

        if (activityType != ACTIVITY_TYPE_STANDARD) {
            // For now there can be only one stack of a particular non-standard activity type on a
            // display. So, get that ignoring whatever windowing mode it is currently in.
            T stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
            if (stack != null) {
                throw new IllegalArgumentException("Stack=" + stack + " of activityType="
                        + activityType + " already on display=" + this + ". Can't have multiple.");
            }
        }

        final ActivityTaskManagerService service = mSupervisor.mService;
        if (!isWindowingModeSupported(windowingMode, service.mSupportsMultiWindow,
                service.mSupportsSplitScreenMultiWindow, service.mSupportsFreeformWindowManagement,
                service.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode="
                    + windowingMode);
        }

        final int stackId = getNextStackId();
        return createStackUnchecked(windowingMode, activityType, stackId, onTop);
    }

    @VisibleForTesting
    <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
            int stackId, boolean onTop) {
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return (T) new PinnedActivityStack(this, stackId, mSupervisor, onTop);
        }
        return (T) new ActivityStack(
                        this, stackId, mSupervisor, windowingMode, activityType, onTop);
    }

    /**
     * Get the preferred focusable stack in priority. If the preferred stack does not exist, find a
     * focusable and visible stack from the top of stacks in this display.
     */
    ActivityStack getFocusedStack() {
        if (mPreferredTopFocusableStack != null) {
            return mPreferredTopFocusableStack;
        }

        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.isFocusableAndVisible()) {
                return stack;
            }
        }

        return null;
    }

    ActivityStack getNextFocusableStack() {
        return getNextFocusableStack(null /* currentFocus */, false /* ignoreCurrent */);
    }

    ActivityStack getNextFocusableStack(ActivityStack currentFocus, boolean ignoreCurrent) {
        final int currentWindowingMode = currentFocus != null
                ? currentFocus.getWindowingMode() : WINDOWING_MODE_UNDEFINED;

        ActivityStack candidate = null;
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (ignoreCurrent && stack == currentFocus) {
                continue;
            }
            if (!stack.isFocusableAndVisible()) {
                continue;
            }

            if (currentWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && candidate == null && stack.inSplitScreenPrimaryWindowingMode()) {
                // If the currently focused stack is in split-screen secondary we save off the
                // top primary split-screen stack as a candidate for focus because we might
                // prefer focus to move to an other stack to avoid primary split-screen stack
                // overlapping with a fullscreen stack when a fullscreen stack is higher in z
                // than the next split-screen stack. Assistant stack, I am looking at you...
                // We only move the focus to the primary-split screen stack if there isn't a
                // better alternative.
                candidate = stack;
                continue;
            }
            if (candidate != null && stack.inSplitScreenSecondaryWindowingMode()) {
                // Use the candidate stack since we are now at the secondary split-screen.
                return candidate;
            }
            return stack;
        }
        return candidate;
    }

    ActivityRecord getResumedActivity() {
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack == null) {
            return null;
        }
        // TODO(b/111541062): Move this into ActivityStack#getResumedActivity()
        // Check if the focused stack has the resumed activity
        ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            // If there is no registered resumed activity in the stack or it is not running -
            // try to use previously resumed one.
            resumedActivity = focusedStack.mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                // If previously resumed activity doesn't work either - find the topmost running
                // activity that can be focused.
                resumedActivity = focusedStack.topRunningActivityLocked(true /* focusableOnly */);
            }
        }
        return resumedActivity;
    }

    ActivityStack getLastFocusedStack() {
        return mLastFocusedStack;
    }

    boolean allResumedActivitiesComplete() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord r = mStacks.get(stackNdx).getResumedActivity();
            if (r != null && !r.isState(RESUMED)) {
                return false;
            }
        }
        final ActivityStack currentFocusedStack = getFocusedStack();
        if (DEBUG_STACK) {
            Slog.d(TAG_STACK, "allResumedActivitiesComplete: mLastFocusedStack changing from="
                    + mLastFocusedStack + " to=" + currentFocusedStack);
        }
        mLastFocusedStack = currentFocusedStack;
        return true;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks.
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @param resuming The resuming activity.
     * @param dontWait The resuming activity isn't going to wait for all activities to be paused
     *                 before resuming.
     * @return true if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming, boolean dontWait) {
        boolean someActivityPaused = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            // TODO(b/111541062): Check if resumed activity on this display instead
            if (!mSupervisor.isTopDisplayFocusedStack(stack)
                    && stack.getResumedActivity() != null) {
                if (DEBUG_STATES) Slog.d(TAG_STATES, "pauseBackStacks: stack=" + stack +
                        " mResumedActivity=" + stack.getResumedActivity());
                someActivityPaused |= stack.startPausingLocked(userLeaving, false, resuming,
                        dontWait);
            }
        }
        return someActivityPaused;
    }

    /**
     * Find task for putting the Activity in.
     */
    void findTaskLocked(final ActivityRecord r, final boolean isPreferredDisplay,
            FindTaskResult result) {
        mTmpFindTaskResult.clear();
        for (int stackNdx = getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getChildAt(stackNdx);
            if (!r.hasCompatibleActivityType(stack)) {
                if (DEBUG_TASKS) {
                    Slog.d(TAG_TASKS, "Skipping stack: (mismatch activity/stack) " + stack);
                }
                continue;
            }

            stack.findTaskLocked(r, mTmpFindTaskResult);
            // It is possible to have tasks in multiple stacks with the same root affinity, so
            // we should keep looking after finding an affinity match to see if there is a
            // better match in another stack. Also, task affinity isn't a good enough reason
            // to target a display which isn't the source of the intent, so skip any affinity
            // matches not on the specified display.
            if (mTmpFindTaskResult.mRecord != null) {
                if (mTmpFindTaskResult.mIdealMatch) {
                    result.setTo(mTmpFindTaskResult);
                    return;
                } else if (isPreferredDisplay) {
                    // Note: since the traversing through the stacks is top down, the floating
                    // tasks should always have lower priority than any affinity-matching tasks
                    // in the fullscreen stacks
                    result.setTo(mTmpFindTaskResult);
                }
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }

        for (int j = windowingModes.length - 1 ; j >= 0; --j) {
            final int windowingMode = windowingModes[j];
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack stack = mStacks.get(i);
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    continue;
                }
                if (stack.getWindowingMode() != windowingMode) {
                    continue;
                }
                mSupervisor.removeStack(stack);
            }
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }

        for (int j = activityTypes.length - 1 ; j >= 0; --j) {
            final int activityType = activityTypes[j];
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack stack = mStacks.get(i);
                if (stack.getActivityType() == activityType) {
                    mSupervisor.removeStack(stack);
                }
            }
        }
    }

    void onStackWindowingModeChanged(ActivityStack stack) {
        removeStackReferenceIfNeeded(stack);
        addStackReferenceIfNeeded(stack);
    }

    private void addStackReferenceIfNeeded(ActivityStack stack) {
        final int activityType = stack.getActivityType();
        final int windowingMode = stack.getWindowingMode();

        if (activityType == ACTIVITY_TYPE_HOME) {
            if (mHomeStack != null && mHomeStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack="
                        + mHomeStack + " already exist on display=" + this + " stack=" + stack);
            }
            mHomeStack = stack;
        } else if (activityType == ACTIVITY_TYPE_RECENTS) {
            if (mRecentsStack != null && mRecentsStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: recents stack="
                        + mRecentsStack + " already exist on display=" + this + " stack=" + stack);
            }
            mRecentsStack = stack;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            if (mPinnedStack != null && mPinnedStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack="
                        + mPinnedStack + " already exist on display=" + this
                        + " stack=" + stack);
            }
            mPinnedStack = stack;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            if (mSplitScreenPrimaryStack != null && mSplitScreenPrimaryStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded:"
                        + " split-screen-primary" + " stack=" + mSplitScreenPrimaryStack
                        + " already exist on display=" + this + " stack=" + stack);
            }
            mSplitScreenPrimaryStack = stack;
            onSplitScreenModeActivated();
        }
    }

    private void removeStackReferenceIfNeeded(ActivityStack stack) {
        if (stack == mHomeStack) {
            mHomeStack = null;
        } else if (stack == mRecentsStack) {
            mRecentsStack = null;
        } else if (stack == mPinnedStack) {
            mPinnedStack = null;
        } else if (stack == mSplitScreenPrimaryStack) {
            mSplitScreenPrimaryStack = null;
            // Inform the reset of the system that split-screen mode was dismissed so things like
            // resizing all the other stacks can take place.
            onSplitScreenModeDismissed();
        }
    }

    private void onSplitScreenModeDismissed() {
        mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            // Adjust the windowing mode of any stack in secondary split-screen to fullscreen.
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack otherStack = mStacks.get(i);
                if (!otherStack.inSplitScreenSecondaryWindowingMode()) {
                    continue;
                }
                otherStack.setWindowingMode(WINDOWING_MODE_UNDEFINED, false /* animate */,
                        false /* showRecents */, false /* enteringSplitScreenMode */,
                        true /* deferEnsuringVisibility */);
            }
        } finally {
            final ActivityStack topFullscreenStack =
                    getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullscreenStack != null && mHomeStack != null && !isTopStack(mHomeStack)) {
                // Whenever split-screen is dismissed we want the home stack directly behind the
                // current top fullscreen stack so it shows up when the top stack is finished.
                // TODO: Would be better to use ActivityDisplay.positionChildAt() for this, however
                // ActivityDisplay doesn't have a direct controller to WM side yet. We can switch
                // once we have that.
                mHomeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
            }
            mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    private void onSplitScreenModeActivated() {
        mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            // Adjust the windowing mode of any affected by split-screen to split-screen secondary.
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack otherStack = mStacks.get(i);
                if (otherStack == mSplitScreenPrimaryStack
                        || !otherStack.affectedBySplitScreenResize()) {
                    continue;
                }
                otherStack.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                        false /* animate */, false /* showRecents */,
                        true /* enteringSplitScreenMode */, true /* deferEnsuringVisibility */);
            }
        } finally {
            mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * Returns true if the {@param windowingMode} is supported based on other parameters passed in.
     * @param windowingMode The windowing mode we are checking support for.
     * @param supportsMultiWindow If we should consider support for multi-window mode in general.
     * @param supportsSplitScreen If we should consider support for split-screen multi-window.
     * @param supportsFreeform If we should consider support for freeform multi-window.
     * @param supportsPip If we should consider support for picture-in-picture mutli-window.
     * @param activityType The activity type under consideration.
     * @return true if the windowing mode is supported.
     */
    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow,
            boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip,
            int activityType) {

        if (windowingMode == WINDOWING_MODE_UNDEFINED
                || windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }

        final int displayWindowingMode = getWindowingMode();
        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return supportsSplitScreen
                    && WindowConfiguration.supportSplitScreenWindowingMode(activityType)
                    // Freeform windows and split-screen windows don't mix well, so prevent
                    // split windowing modes on freeform displays.
                    && displayWindowingMode != WINDOWING_MODE_FREEFORM;
        }

        if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) {
            return false;
        }

        if (!supportsPip && windowingMode == WINDOWING_MODE_PINNED) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the windowing mode that an {@link ActivityRecord} would be in if started on this
     * display with the provided parameters.
     *
     * @param r The ActivityRecord in question.
     * @param options Options to start with.
     * @param task The task within-which the activity would start.
     * @param activityType The type of activity to start.
     * @return The resolved (not UNDEFINED) windowing-mode that the activity would be in.
     */
    int resolveWindowingMode(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable TaskRecord task, int activityType) {

        // First preference if the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;

        // If windowing mode is unset, then next preference is the candidate task, then the
        // activity record.
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            if (task != null) {
                windowingMode = task.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED && r != null) {
                windowingMode = r.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED) {
                // Use the display's windowing mode.
                windowingMode = getWindowingMode();
            }
        }
        windowingMode = validateWindowingMode(windowingMode, r, task, activityType);
        return windowingMode != WINDOWING_MODE_UNDEFINED
                ? windowingMode : WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Check that the requested windowing-mode is appropriate for the specified task and/or activity
     * on this display.
     *
     * @param windowingMode The windowing-mode to validate.
     * @param r The {@link ActivityRecord} to check against.
     * @param task The {@link TaskRecord} to check against.
     * @param activityType An activity type.
     * @return The provided windowingMode or the closest valid mode which is appropriate.
     */
    int validateWindowingMode(int windowingMode, @Nullable ActivityRecord r,
        @Nullable TaskRecord task, int activityType) {
        // Make sure the windowing mode we are trying to use makes sense for what is supported.
        final ActivityTaskManagerService service = mSupervisor.mService;
        boolean supportsMultiWindow = service.mSupportsMultiWindow;
        boolean supportsSplitScreen = service.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform = service.mSupportsFreeformWindowManagement;
        boolean supportsPip = service.mSupportsPictureInPicture;
        if (supportsMultiWindow) {
            if (task != null) {
                supportsMultiWindow = task.isResizeable();
                supportsSplitScreen = task.supportsSplitScreenWindowingMode();
                // TODO: Do we need to check for freeform and Pip support here?
            } else if (r != null) {
                supportsMultiWindow = r.isResizeable();
                supportsSplitScreen = r.supportsSplitScreenWindowingMode();
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
            }
        }

        final boolean inSplitScreenMode = hasSplitScreenPrimaryStack();
        if (!inSplitScreenMode
                && windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY) {
            // Switch to the display's windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        } else if (inSplitScreenMode && (windowingMode == WINDOWING_MODE_FULLSCREEN
                        || windowingMode == WINDOWING_MODE_UNDEFINED)
                && supportsSplitScreen) {
            windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }

        if (windowingMode != WINDOWING_MODE_UNDEFINED
                && isWindowingModeSupported(windowingMode, supportsMultiWindow, supportsSplitScreen,
                        supportsFreeform, supportsPip, activityType)) {
            return windowingMode;
        }
        return WINDOWING_MODE_UNDEFINED;
    }

    /**
     * Get the topmost stack on the display. It may be different from focused stack, because
     * some stacks are not focusable (e.g. PiP).
     */
    ActivityStack getTopStack() {
        return mStacks.isEmpty() ? null : mStacks.get(mStacks.size() - 1);
    }

    boolean isTopStack(ActivityStack stack) {
        return stack == getTopStack();
    }

    boolean isTopNotPinnedStack(ActivityStack stack) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack current = mStacks.get(i);
            if (!current.inPinnedWindowingMode()) {
                return current == stack;
            }
        }
        return false;
    }

    ActivityStack getTopStackInWindowingMode(int windowingMode) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack current = mStacks.get(i);
            if (windowingMode == current.getWindowingMode()) {
                return current;
            }
        }
        return null;
    }

    int getIndexOf(ActivityStack stack) {
        return mStacks.indexOf(stack);
    }

    void onLockTaskPackagesUpdated() {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).onLockTaskPackagesUpdated();
        }
    }

    /** We are in the process of exiting split-screen mode. */
    void onExitingSplitScreenMode() {
        // Remove reference to the primary-split-screen stack so it no longer has any effect on the
        // display. For example, we want to be able to create fullscreen stack for standard activity
        // types when exiting split-screen mode.
        mSplitScreenPrimaryStack = null;
    }

    ActivityStack getSplitScreenPrimaryStack() {
        return mSplitScreenPrimaryStack;
    }

    boolean hasSplitScreenPrimaryStack() {
        return mSplitScreenPrimaryStack != null;
    }

    PinnedActivityStack getPinnedStack() {
        return (PinnedActivityStack) mPinnedStack;
    }

    boolean hasPinnedStack() {
        return mPinnedStack != null;
    }

    @Override
    public String toString() {
        return "ActivityDisplay={" + mDisplayId + " numStacks=" + mStacks.size() + "}";
    }

    @Override
    protected int getChildCount() {
        return mStacks.size();
    }

    @Override
    protected ActivityStack getChildAt(int index) {
        return mStacks.get(index);
    }

    @Override
    protected ConfigurationContainer getParent() {
        return mSupervisor;
    }

    boolean isPrivate() {
        return (mDisplay.getFlags() & FLAG_PRIVATE) != 0;
    }

    boolean isUidPresent(int uid) {
        for (ActivityStack stack : mStacks) {
            if (stack.isUidPresent(uid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @see #mRemoved
     */
    boolean isRemoved() {
        return mRemoved;
    }

    void remove() {
        final boolean destroyContentOnRemoval = shouldDestroyContentOnRemove();

        // Stacks could be reparented from the removed display to other display. While
        // reparenting the last stack of the removed display, the remove display is ready to be
        // released (no more ActivityStack). But, we cannot release it at that moment or the
        // related WindowContainer and WindowContainerController will also be removed. So, we
        // set display as removed after reparenting stack finished.
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            // Always finish non-standard type stacks.
            if (destroyContentOnRemoval || !stack.isActivityTypeStandardOrUndefined()) {
                stack.finishAllActivitiesLocked(true /* immediately */);
            } else {
                // If default display is in split-window mode, set windowing mode of the stack to
                // split-screen secondary. Otherwise, set the windowing mode to undefined by
                // default to let stack inherited the windowing mode from the new display.
                int windowingMode = mSupervisor.getDefaultDisplay().hasSplitScreenPrimaryStack()
                        ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY : WINDOWING_MODE_UNDEFINED;
                mSupervisor.moveStackToDisplayLocked(stack.mStackId, DEFAULT_DISPLAY, true);
                stack.setWindowingMode(windowingMode);
            }
        }
        mRemoved = true;

        releaseSelfIfNeeded();

        mSupervisor.getKeyguardController().onDisplayRemoved(mDisplayId);
    }

    private void releaseSelfIfNeeded() {
        if (mStacks.isEmpty() && mRemoved) {
            mWindowContainerController.removeContainer();
            mWindowContainerController = null;
            mSupervisor.removeChild(this);
        }
    }

    /** Update and get all UIDs that are present on the display and have access to it. */
    IntArray getPresentUIDs() {
        mDisplayAccessUIDs.clear();
        for (ActivityStack stack : mStacks) {
            stack.getPresentUIDs(mDisplayAccessUIDs);
        }
        return mDisplayAccessUIDs;
    }

    /**
     * @see Display#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    boolean supportsSystemDecorations() {
        return mDisplay.supportsSystemDecorations();
    }

    private boolean shouldDestroyContentOnRemove() {
        return mDisplay.getRemoveMode() == REMOVE_MODE_DESTROY_CONTENT;
    }

    boolean shouldSleep() {
        return (mStacks.isEmpty() || !mAllSleepTokens.isEmpty())
                && (mSupervisor.mService.mRunningVoice == null);
    }

    void setFocusedApp(ActivityRecord r, boolean moveFocusNow) {
        mWindowContainerController.setFocusedApp(r.appToken, moveFocusNow);
    }

    /**
     * @return the stack currently above the {@param stack}.  Can be null if the {@param stack} is
     *         already top-most.
     */
    ActivityStack getStackAbove(ActivityStack stack) {
        final int stackIndex = mStacks.indexOf(stack) + 1;
        return (stackIndex < mStacks.size()) ? mStacks.get(stackIndex) : null;
    }

    /**
     * Adjusts the {@param stack} behind the last visible stack in the display if necessary.
     * Generally used in conjunction with {@link #moveStackBehindStack}.
     */
    void moveStackBehindBottomMostVisibleStack(ActivityStack stack) {
        if (stack.shouldBeVisible(null)) {
            // Skip if the stack is already visible
            return;
        }

        // Move the stack to the bottom to not affect the following visibility checks
        positionChildAtBottom(stack);

        // Find the next position where the stack should be placed
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final ActivityStack s = mStacks.get(stackNdx);
            if (s == stack) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN ||
                    winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided stack to behind this stack
                positionChildAt(stack, Math.max(0, stackNdx - 1));
                break;
            }
        }
    }

    /**
     * Moves the {@param stack} behind the given {@param behindStack} if possible. If
     * {@param behindStack} is not currently in the display, then then the stack is moved to the
     * back. Generally used in conjunction with {@link #moveStackBehindBottomMostVisibleStack}.
     */
    void moveStackBehindStack(ActivityStack stack, ActivityStack behindStack) {
        if (behindStack == null || behindStack == stack) {
            return;
        }

        // Note that positionChildAt will first remove the given stack before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int stackIndex = mStacks.indexOf(stack);
        final int behindStackIndex = mStacks.indexOf(behindStack);
        final int insertIndex = stackIndex <= behindStackIndex
                ? behindStackIndex - 1 : behindStackIndex;
        positionChildAt(stack, Math.max(0, insertIndex));
    }

    void moveHomeStackToFront(String reason) {
        if (mHomeStack != null) {
            mHomeStack.moveToFront(reason);
        }
    }

    /** Returns true if the focus activity was adjusted to the home stack top activity. */
    boolean moveHomeActivityToTop(String reason) {
        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            return false;
        }
        top.moveFocusableActivityToTop(reason);
        return true;
    }

    @Nullable
    ActivityStack getHomeStack() {
        return mHomeStack;
    }

    @Nullable
    ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(mSupervisor.mCurrentUser);
    }

    @Nullable
    ActivityRecord getHomeActivityForUser(int userId) {
        if (mHomeStack == null) {
            return null;
        }

        final ArrayList<TaskRecord> tasks = mHomeStack.getAllTasks();
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = tasks.get(taskNdx);
            if (!task.isActivityTypeHome()) {
                continue;
            }

            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.isActivityTypeHome()
                        && ((userId == UserHandle.USER_ALL) || (r.userId == userId))) {
                    return r;
                }
            }
        }
        return null;
    }

    boolean isSleeping() {
        return mSleeping;
    }

    void setIsSleeping(boolean asleep) {
        mSleeping = asleep;
    }

    /**
     * Adds a listener to be notified whenever the stack order in the display changes. Currently
     * only used by the {@link RecentsAnimation} to determine whether to interrupt and cancel the
     * current animation when the system state changes.
     */
    void registerStackOrderChangedListener(OnStackOrderChangedListener listener) {
        if (!mStackOrderChangedCallbacks.contains(listener)) {
            mStackOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered stack order change listener.
     */
    void unregisterStackOrderChangedListener(OnStackOrderChangedListener listener) {
        mStackOrderChangedCallbacks.remove(listener);
    }

    private void onStackOrderChanged() {
        for (int i = mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            mStackOrderChangedCallbacks.get(i).onStackOrderChanged();
        }
    }

    /**
     * See {@link DisplayWindowController#deferUpdateImeTarget()}
     */
    public void deferUpdateImeTarget() {
        mWindowContainerController.deferUpdateImeTarget();
    }

    /**
     * See {@link DisplayWindowController#deferUpdateImeTarget()}
     */
    public void continueUpdateImeTarget() {
        mWindowContainerController.continueUpdateImeTarget();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "displayId=" + mDisplayId + " stacks=" + mStacks.size());
        final String myPrefix = prefix + " ";
        if (mHomeStack != null) {
            pw.println(myPrefix + "mHomeStack=" + mHomeStack);
        }
        if (mRecentsStack != null) {
            pw.println(myPrefix + "mRecentsStack=" + mRecentsStack);
        }
        if (mPinnedStack != null) {
            pw.println(myPrefix + "mPinnedStack=" + mPinnedStack);
        }
        if (mSplitScreenPrimaryStack != null) {
            pw.println(myPrefix + "mSplitScreenPrimaryStack=" + mSplitScreenPrimaryStack);
        }
        if (mPreferredTopFocusableStack != null) {
            pw.println(myPrefix + "mPreferredTopFocusableStack=" + mPreferredTopFocusableStack);
        }
        if (mLastFocusedStack != null) {
            pw.println(myPrefix + "mLastFocusedStack=" + mLastFocusedStack);
        }
    }

    public void dumpStacks(PrintWriter pw) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            pw.print(mStacks.get(i).mStackId);
            if (i > 0) {
                pw.print(",");
            }
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER, false /* trim */);
        proto.write(ID, mDisplayId);
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            proto.write(FOCUSED_STACK_ID, focusedStack.mStackId);
            final ActivityRecord focusedActivity = focusedStack.getDisplay().getResumedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
            }
        } else {
            proto.write(FOCUSED_STACK_ID, INVALID_STACK_ID);
        }
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.writeToProto(proto, STACKS);
        }
        proto.end(token);
    }

    /**
     * Callback for when the order of the stacks in the display changes.
     */
    interface OnStackOrderChangedListener {
        void onStackOrderChanged();
    }
}
