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
package com.android.systemui.bubbles;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_DATA;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import static java.util.stream.Collectors.toList;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController.DismissReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
public class BubbleData {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleData" : TAG_BUBBLES;

    private static final Comparator<Bubble> BUBBLES_BY_SORT_KEY_DESCENDING =
            Comparator.comparing(BubbleData::sortKey).reversed();

    private static final Comparator<Map.Entry<String, Long>> GROUPS_BY_MAX_SORT_KEY_DESCENDING =
            Comparator.<Map.Entry<String, Long>, Long>comparing(Map.Entry::getValue).reversed();

    /** Contains information about changes that have been made to the state of bubbles. */
    static final class Update {
        boolean expandedChanged;
        boolean selectionChanged;
        boolean orderChanged;
        boolean expanded;
        @Nullable Bubble selectedBubble;
        @Nullable Bubble addedBubble;
        @Nullable Bubble updatedBubble;
        @Nullable Bubble addedOverflowBubble;
        @Nullable Bubble removedOverflowBubble;
        // Pair with Bubble and @DismissReason Integer
        final List<Pair<Bubble, Integer>> removedBubbles = new ArrayList<>();

        // A read-only view of the bubbles list, changes there will be reflected here.
        final List<Bubble> bubbles;
        final List<Bubble> overflowBubbles;

        private Update(List<Bubble> row, List<Bubble> overflow) {
            bubbles = Collections.unmodifiableList(row);
            overflowBubbles = Collections.unmodifiableList(overflow);
        }

        boolean anythingChanged() {
            return expandedChanged
                    || selectionChanged
                    || addedBubble != null
                    || updatedBubble != null
                    || !removedBubbles.isEmpty()
                    || addedOverflowBubble != null
                    || removedOverflowBubble != null
                    || orderChanged;
        }

        void bubbleRemoved(Bubble bubbleToRemove, @DismissReason int reason) {
            removedBubbles.add(new Pair<>(bubbleToRemove, reason));
        }
    }

    /**
     * This interface reports changes to the state and appearance of bubbles which should be applied
     * as necessary to the UI.
     */
    interface Listener {
        /** Reports changes have have occurred as a result of the most recent operation. */
        void applyUpdate(Update update);
    }

    interface TimeSource {
        long currentTimeMillis();
    }

    private final Context mContext;
    /** Bubbles that are actively in the stack. */
    private final List<Bubble> mBubbles;
    /** Bubbles that aged out to overflow. */
    private final List<Bubble> mOverflowBubbles;
    /** Bubbles that are being loaded but haven't been added to the stack just yet. */
    private final List<Bubble> mPendingBubbles;
    private Bubble mSelectedBubble;
    private boolean mShowingOverflow;
    private boolean mExpanded;
    private final int mMaxBubbles;
    private int mMaxOverflowBubbles;

    // State tracked during an operation -- keeps track of what listener events to dispatch.
    private Update mStateChange;

    private NotificationListenerService.Ranking mTmpRanking;

    private TimeSource mTimeSource = System::currentTimeMillis;

    @Nullable
    private Listener mListener;

    @Nullable
    private BubbleController.NotificationSuppressionChangedListener mSuppressionListener;

    /**
     * We track groups with summaries that aren't visibly displayed but still kept around because
     * the bubble(s) associated with the summary still exist.
     *
     * The summary must be kept around so that developers can cancel it (and hence the bubbles
     * associated with it). This list is used to check if the summary should be hidden from the
     * shade.
     *
     * Key: group key of the NotificationEntry
     * Value: key of the NotificationEntry
     */
    private HashMap<String, String> mSuppressedGroupKeys = new HashMap<>();

    @Inject
    public BubbleData(Context context) {
        mContext = context;
        mBubbles = new ArrayList<>();
        mOverflowBubbles = new ArrayList<>();
        mPendingBubbles = new ArrayList<>();
        mStateChange = new Update(mBubbles, mOverflowBubbles);
        mMaxBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_rendered);
        mMaxOverflowBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_overflow);
    }

    public void setSuppressionChangedListener(
            BubbleController.NotificationSuppressionChangedListener listener) {
        mSuppressionListener = listener;
    }

    public boolean hasBubbles() {
        return !mBubbles.isEmpty();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public boolean hasAnyBubbleWithKey(String key) {
        return hasBubbleInStackWithKey(key) || hasOverflowBubbleWithKey(key);
    }

    public boolean hasBubbleInStackWithKey(String key) {
        return getBubbleInStackWithKey(key) != null;
    }

    public boolean hasOverflowBubbleWithKey(String key) {
        return getOverflowBubbleWithKey(key) != null;
    }

    @Nullable
    public Bubble getSelectedBubble() {
        return mSelectedBubble;
    }

    public void setExpanded(boolean expanded) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpanded: " + expanded);
        }
        setExpandedInternal(expanded);
        dispatchPendingChanges();
    }

    public void setSelectedBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubble: " + bubble);
        }
        setSelectedBubbleInternal(bubble);
        dispatchPendingChanges();
    }

    public void promoteBubbleFromOverflow(Bubble bubble, BubbleStackView stack,
            BubbleIconFactory factory) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "promoteBubbleFromOverflow: " + bubble);
        }
        moveOverflowBubbleToPending(bubble);
        // Preserve new order for next repack, which sorts by last updated time.
        bubble.markUpdatedAt(mTimeSource.currentTimeMillis());
        bubble.inflate(
                b -> {
                    notificationEntryUpdated(bubble, /* suppressFlyout */
                            false, /* showInShade */ true);
                    setSelectedBubble(bubble);
                },
                mContext, stack, factory);
        dispatchPendingChanges();
    }

    void setShowingOverflow(boolean showingOverflow) {
        mShowingOverflow = showingOverflow;
    }

    private void moveOverflowBubbleToPending(Bubble b) {
        mOverflowBubbles.remove(b);
        mStateChange.removedOverflowBubble = b;
        mPendingBubbles.add(b);
    }

    /**
     * Constructs a new bubble or returns an existing one. Does not add new bubbles to
     * bubble data, must go through {@link #notificationEntryUpdated(Bubble, boolean, boolean)}
     * for that.
     */
    Bubble getOrCreateBubble(NotificationEntry entry) {
        String key = entry.getKey();
        Bubble bubble = getBubbleInStackWithKey(entry.getKey());
        if (bubble != null) {
            bubble.setEntry(entry);
        } else {
            bubble = getOverflowBubbleWithKey(key);
            if (bubble != null) {
                moveOverflowBubbleToPending(bubble);
                bubble.setEntry(entry);
                return bubble;
            }
            // Check for it in pending
            for (int i = 0; i < mPendingBubbles.size(); i++) {
                Bubble b = mPendingBubbles.get(i);
                if (b.getKey().equals(entry.getKey())) {
                    b.setEntry(entry);
                    return b;
                }
            }
            bubble = new Bubble(entry, mSuppressionListener);
            mPendingBubbles.add(bubble);
        }
        return bubble;
    }

    /**
     * When this method is called it is expected that all info in the bubble has completed loading.
     * @see Bubble#inflate(BubbleViewInfoTask.Callback, Context,
     * BubbleStackView, BubbleIconFactory).
     */
    void notificationEntryUpdated(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryUpdated: " + bubble);
        }
        mPendingBubbles.remove(bubble); // No longer pending once we're here
        Bubble prevBubble = getBubbleInStackWithKey(bubble.getKey());
        suppressFlyout |= !bubble.getEntry().getRanking().visuallyInterruptive();

        if (prevBubble == null) {
            // Create a new bubble
            bubble.setSuppressFlyout(suppressFlyout);
            doAdd(bubble);
            trim();
        } else {
            // Updates an existing bubble
            bubble.setSuppressFlyout(suppressFlyout);
            doUpdate(bubble);
        }
        if (bubble.shouldAutoExpand()) {
            setSelectedBubbleInternal(bubble);
            if (!mExpanded) {
                setExpandedInternal(true);
            }
        } else if (mSelectedBubble == null) {
            setSelectedBubbleInternal(bubble);
        }

        boolean isBubbleExpandedAndSelected = mExpanded && mSelectedBubble == bubble;
        boolean suppress = isBubbleExpandedAndSelected || !showInShade || !bubble.showInShade();
        bubble.setSuppressNotification(suppress);
        bubble.setShowDot(!isBubbleExpandedAndSelected /* show */);

        dispatchPendingChanges();
    }

    public void notificationEntryRemoved(NotificationEntry entry, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryRemoved: entry=" + entry + " reason=" + reason);
        }
        doRemove(entry.getKey(), reason);
        dispatchPendingChanges();
    }

    /**
     * Adds a group key indicating that the summary for this group should be suppressed.
     *
     * @param groupKey the group key of the group whose summary should be suppressed.
     * @param notifKey the notification entry key of that summary.
     */
    void addSummaryToSuppress(String groupKey, String notifKey) {
        mSuppressedGroupKeys.put(groupKey, notifKey);
    }

    /**
     * Retrieves the notif entry key of the summary associated with the provided group key.
     *
     * @param groupKey the group to look up
     * @return the key for the {@link NotificationEntry} that is the summary of this group.
     */
    String getSummaryKey(String groupKey) {
        return mSuppressedGroupKeys.get(groupKey);
    }

    /**
     * Removes a group key indicating that summary for this group should no longer be suppressed.
     */
    void removeSuppressedSummary(String groupKey) {
        mSuppressedGroupKeys.remove(groupKey);
    }

    /**
     * Whether the summary for the provided group key is suppressed.
     */
    boolean isSummarySuppressed(String groupKey) {
        return mSuppressedGroupKeys.containsKey(groupKey);
    }

    /**
     * Retrieves any bubbles that are part of the notification group represented by the provided
     * group key.
     */
    ArrayList<Bubble> getBubblesInGroup(@Nullable String groupKey) {
        ArrayList<Bubble> bubbleChildren = new ArrayList<>();
        if (groupKey == null) {
            return bubbleChildren;
        }
        for (Bubble b : mBubbles) {
            if (groupKey.equals(b.getEntry().getSbn().getGroupKey())) {
                bubbleChildren.add(b);
            }
        }
        return bubbleChildren;
    }

    private void doAdd(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doAdd: " + bubble);
        }
        int minInsertPoint = 0;
        boolean newGroup = !hasBubbleWithGroupId(bubble.getGroupId());
        if (isExpanded()) {
            // first bubble of a group goes to the beginning, otherwise within the existing group
            minInsertPoint = newGroup ? 0 : findFirstIndexForGroup(bubble.getGroupId());
        }
        if (insertBubble(minInsertPoint, bubble) < mBubbles.size() - 1) {
            mStateChange.orderChanged = true;
        }
        mStateChange.addedBubble = bubble;

        if (!isExpanded()) {
            mStateChange.orderChanged |= packGroup(findFirstIndexForGroup(bubble.getGroupId()));
            // Top bubble becomes selected.
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void trim() {
        if (mBubbles.size() > mMaxBubbles) {
            mBubbles.stream()
                    // sort oldest first (ascending lastActivity)
                    .sorted(Comparator.comparingLong(Bubble::getLastActivity))
                    // skip the selected bubble
                    .filter((b) -> !b.equals(mSelectedBubble))
                    .findFirst()
                    .ifPresent((b) -> doRemove(b.getKey(), BubbleController.DISMISS_AGED));
        }
    }

    private void doUpdate(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doUpdate: " + bubble);
        }
        mStateChange.updatedBubble = bubble;
        if (!isExpanded()) {
            // while collapsed, update causes re-pack
            int prevPos = mBubbles.indexOf(bubble);
            mBubbles.remove(bubble);
            int newPos = insertBubble(0, bubble);
            if (prevPos != newPos) {
                packGroup(newPos);
                mStateChange.orderChanged = true;
            }
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void doRemove(String key, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doRemove: " + key);
        }
        //  If it was pending remove it
        for (int i = 0; i < mPendingBubbles.size(); i++) {
            if (mPendingBubbles.get(i).getKey().equals(key)) {
                mPendingBubbles.remove(mPendingBubbles.get(i));
            }
        }
        int indexToRemove = indexForKey(key);
        if (indexToRemove == -1) {
            if (hasOverflowBubbleWithKey(key)
                && (reason == BubbleController.DISMISS_NOTIF_CANCEL
                || reason == BubbleController.DISMISS_GROUP_CANCELLED
                || reason == BubbleController.DISMISS_NO_LONGER_BUBBLE
                || reason == BubbleController.DISMISS_BLOCKED)) {

                Bubble b = getOverflowBubbleWithKey(key);
                if (DEBUG_BUBBLE_DATA) {
                    Log.d(TAG, "Cancel overflow bubble: " + b);
                }
                mOverflowBubbles.remove(b);
                mStateChange.bubbleRemoved(b, reason);
                mStateChange.removedOverflowBubble = b;
            }
            return;
        }
        Bubble bubbleToRemove = mBubbles.get(indexToRemove);
        if (mBubbles.size() == 1) {
            // Going to become empty, handle specially.
            setExpandedInternal(false);
            setSelectedBubbleInternal(null);
        }
        if (indexToRemove < mBubbles.size() - 1) {
            // Removing anything but the last bubble means positions will change.
            mStateChange.orderChanged = true;
        }
        mBubbles.remove(indexToRemove);
        mStateChange.bubbleRemoved(bubbleToRemove, reason);
        if (!isExpanded()) {
            mStateChange.orderChanged |= repackAll();
        }

        overflowBubble(reason, bubbleToRemove);

        // Note: If mBubbles.isEmpty(), then mSelectedBubble is now null.
        if (Objects.equals(mSelectedBubble, bubbleToRemove)) {
            // Move selection to the new bubble at the same position.
            int newIndex = Math.min(indexToRemove, mBubbles.size() - 1);
            Bubble newSelected = mBubbles.get(newIndex);
            setSelectedBubbleInternal(newSelected);
        }
        maybeSendDeleteIntent(reason, bubbleToRemove.getEntry());
    }

    void overflowBubble(@DismissReason int reason, Bubble bubble) {
        if (bubble.getPendingIntentCanceled()
                || !(reason == BubbleController.DISMISS_AGED
                || reason == BubbleController.DISMISS_USER_GESTURE)) {
            return;
        }
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "Overflowing: " + bubble);
        }
        mOverflowBubbles.add(0, bubble);
        mStateChange.addedOverflowBubble = bubble;
        bubble.stopInflation();
        if (mOverflowBubbles.size() == mMaxOverflowBubbles + 1) {
            // Remove oldest bubble.
            Bubble oldest = mOverflowBubbles.get(mOverflowBubbles.size() - 1);
            if (DEBUG_BUBBLE_DATA) {
                Log.d(TAG, "Overflow full. Remove: " + oldest);
            }
            mOverflowBubbles.remove(oldest);
            mStateChange.removedOverflowBubble = oldest;
            mStateChange.bubbleRemoved(oldest, BubbleController.DISMISS_OVERFLOW_MAX_REACHED);
        }
    }

    public void dismissAll(@DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "dismissAll: reason=" + reason);
        }
        if (mBubbles.isEmpty()) {
            return;
        }
        setExpandedInternal(false);
        setSelectedBubbleInternal(null);
        while (!mBubbles.isEmpty()) {
            doRemove(mBubbles.get(0).getKey(), reason);
        }
        dispatchPendingChanges();
    }

    /**
     * Indicates that the provided display is no longer in use and should be cleaned up.
     *
     * @param displayId the id of the display to clean up.
     */
    void notifyDisplayEmpty(int displayId) {
        for (Bubble b : mBubbles) {
            if (b.getDisplayId() == displayId) {
                if (b.getExpandedView() != null) {
                    b.getExpandedView().notifyDisplayEmpty();
                }
                return;
            }
        }
    }

    private void dispatchPendingChanges() {
        if (mListener != null && mStateChange.anythingChanged()) {
            mListener.applyUpdate(mStateChange);
        }
        mStateChange = new Update(mBubbles, mOverflowBubbles);
    }

    /**
     * Requests a change to the selected bubble.
     *
     * @param bubble the new selected bubble
     */
    private void setSelectedBubbleInternal(@Nullable Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubbleInternal: " + bubble);
        }
        if (!mShowingOverflow && Objects.equals(bubble, mSelectedBubble)) {
            return;
        }
        // Otherwise, if we are showing the overflow menu, return to the previously selected bubble.

        if (bubble != null && !mBubbles.contains(bubble) && !mOverflowBubbles.contains(bubble)) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return;
        }
        if (mExpanded && bubble != null) {
            bubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
        mStateChange.selectedBubble = bubble;
        mStateChange.selectionChanged = true;
    }

    /**
     * Requests a change to the expanded state.
     *
     * @param shouldExpand the new requested state
     */
    private void setExpandedInternal(boolean shouldExpand) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpandedInternal: shouldExpand=" + shouldExpand);
        }
        if (mExpanded == shouldExpand) {
            return;
        }
        if (shouldExpand) {
            if (mBubbles.isEmpty()) {
                Log.e(TAG, "Attempt to expand stack when empty!");
                return;
            }
            if (mSelectedBubble == null) {
                Log.e(TAG, "Attempt to expand stack without selected bubble!");
                return;
            }
            mSelectedBubble.markUpdatedAt(mTimeSource.currentTimeMillis());
            mSelectedBubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
            mStateChange.orderChanged |= repackAll();
        } else if (!mBubbles.isEmpty()) {
            // Apply ordering and grouping rules from expanded -> collapsed, then save
            // the result.
            mStateChange.orderChanged |= repackAll();
            // Save the state which should be returned to when expanded (with no other changes)

            if (mShowingOverflow) {
                // Show previously selected bubble instead of overflow menu on next expansion.
                setSelectedBubbleInternal(mSelectedBubble);
            }
            if (mBubbles.indexOf(mSelectedBubble) > 0) {
                // Move the selected bubble to the top while collapsed.
                if (!mSelectedBubble.isOngoing() && mBubbles.get(0).isOngoing()) {
                    // The selected bubble cannot be raised to the first position because
                    // there is an ongoing bubble there. Instead, force the top ongoing bubble
                    // to become selected.
                    setSelectedBubbleInternal(mBubbles.get(0));
                } else {
                    // Raise the selected bubble (and it's group) up to the front so the selected
                    // bubble remains on top.
                    mBubbles.remove(mSelectedBubble);
                    mBubbles.add(0, mSelectedBubble);
                    mStateChange.orderChanged |= packGroup(0);
                }
            }
        }
        mExpanded = shouldExpand;
        mStateChange.expanded = shouldExpand;
        mStateChange.expandedChanged = true;
    }

    private static long sortKey(Bubble bubble) {
        long key = bubble.getLastUpdateTime();
        if (bubble.isOngoing()) {
            // Set 2nd highest bit (signed long int), to partition between ongoing and regular
            key |= 0x4000000000000000L;
        }
        return key;
    }

    /**
     * Locates and inserts the bubble into a sorted position. The is inserted
     * based on sort key, groupId is not considered. A call to {@link #packGroup(int)} may be
     * required to keep grouping intact.
     *
     * @param minPosition the first insert point to consider
     * @param newBubble   the bubble to insert
     * @return the position where the bubble was inserted
     */
    private int insertBubble(int minPosition, Bubble newBubble) {
        long newBubbleSortKey = sortKey(newBubble);
        String previousGroupId = null;

        for (int pos = minPosition; pos < mBubbles.size(); pos++) {
            Bubble bubbleAtPos = mBubbles.get(pos);
            String groupIdAtPos = bubbleAtPos.getGroupId();
            boolean atStartOfGroup = !groupIdAtPos.equals(previousGroupId);

            if (atStartOfGroup && newBubbleSortKey > sortKey(bubbleAtPos)) {
                // Insert before the start of first group which has older bubbles.
                mBubbles.add(pos, newBubble);
                return pos;
            }
            previousGroupId = groupIdAtPos;
        }
        mBubbles.add(newBubble);
        return mBubbles.size() - 1;
    }

    private boolean hasBubbleWithGroupId(String groupId) {
        return mBubbles.stream().anyMatch(b -> b.getGroupId().equals(groupId));
    }

    private int findFirstIndexForGroup(String appId) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubbleAtPos = mBubbles.get(i);
            if (bubbleAtPos.getGroupId().equals(appId)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Starting at the given position, moves all bubbles with the same group id to follow. Bubbles
     * at positions lower than {@code position} are unchanged. Relative order within the group
     * unchanged. Relative order of any other bubbles are also unchanged.
     *
     * @param position the position of the first bubble for the group
     * @return true if the position of any bubbles has changed as a result
     */
    private boolean packGroup(int position) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "packGroup: position=" + position);
        }
        Bubble groupStart = mBubbles.get(position);
        final String groupAppId = groupStart.getGroupId();
        List<Bubble> moving = new ArrayList<>();

        // Walk backward, collect bubbles within the group
        for (int i = mBubbles.size() - 1; i > position; i--) {
            if (mBubbles.get(i).getGroupId().equals(groupAppId)) {
                moving.add(0, mBubbles.get(i));
            }
        }
        if (moving.isEmpty()) {
            return false;
        }
        mBubbles.removeAll(moving);
        mBubbles.addAll(position + 1, moving);
        return true;
    }

    /**
     * This applies a full sort and group pass to all existing bubbles. The bubbles are grouped
     * by groupId. Each group is then sorted by the max(lastUpdated) time of its bubbles. Bubbles
     * within each group are then sorted by lastUpdated descending.
     *
     * @return true if the position of any bubbles changed as a result
     */
    private boolean repackAll() {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "repackAll()");
        }
        if (mBubbles.isEmpty()) {
            return false;
        }
        Map<String, Long> groupLastActivity = new HashMap<>();
        for (Bubble bubble : mBubbles) {
            long maxSortKeyForGroup = groupLastActivity.getOrDefault(bubble.getGroupId(), 0L);
            long sortKeyForBubble = sortKey(bubble);
            if (sortKeyForBubble > maxSortKeyForGroup) {
                groupLastActivity.put(bubble.getGroupId(), sortKeyForBubble);
            }
        }

        // Sort groups by their most recently active bubble
        List<String> groupsByMostRecentActivity =
                groupLastActivity.entrySet().stream()
                        .sorted(GROUPS_BY_MAX_SORT_KEY_DESCENDING)
                        .map(Map.Entry::getKey)
                        .collect(toList());

        List<Bubble> repacked = new ArrayList<>(mBubbles.size());

        // For each group, add bubbles, freshest to oldest
        for (String appId : groupsByMostRecentActivity) {
            mBubbles.stream()
                    .filter((b) -> b.getGroupId().equals(appId))
                    .sorted(BUBBLES_BY_SORT_KEY_DESCENDING)
                    .forEachOrdered(repacked::add);
        }
        if (repacked.equals(mBubbles)) {
            return false;
        }
        mBubbles.clear();
        mBubbles.addAll(repacked);
        return true;
    }

    private void maybeSendDeleteIntent(@DismissReason int reason, NotificationEntry entry) {
        if (reason == BubbleController.DISMISS_USER_GESTURE) {
            Notification.BubbleMetadata bubbleMetadata = entry.getBubbleMetadata();
            PendingIntent deleteIntent = bubbleMetadata != null
                    ? bubbleMetadata.getDeleteIntent()
                    : null;
            if (deleteIntent != null) {
                try {
                    deleteIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send delete intent for bubble with key: "
                            + entry.getKey());
                }
            }
        }
    }

    private int indexForKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The set of bubbles in row.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public List<Bubble> getBubbles() {
        return Collections.unmodifiableList(mBubbles);
    }
    /**
     * The set of bubbles in overflow.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public List<Bubble> getOverflowBubbles() {
        return Collections.unmodifiableList(mOverflowBubbles);
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    Bubble getAnyBubbleWithkey(String key) {
        Bubble b = getBubbleInStackWithKey(key);
        if (b == null) {
            b = getOverflowBubbleWithKey(key);
        }
        return b;
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    Bubble getBubbleInStackWithKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    @Nullable
    Bubble getBubbleWithView(View view) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getIconView() != null && bubble.getIconView().equals(view)) {
                return bubble;
            }
        }
        return null;
    }

    @VisibleForTesting(visibility = PRIVATE)
    Bubble getOverflowBubbleWithKey(String key) {
        for (int i = 0; i < mOverflowBubbles.size(); i++) {
            Bubble bubble = mOverflowBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    @VisibleForTesting(visibility = PRIVATE)
    void setTimeSource(TimeSource timeSource) {
        mTimeSource = timeSource;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Set maximum number of bubbles allowed in overflow.
     * This method should only be used in tests, not in production.
     */
    @VisibleForTesting
    void setMaxOverflowBubbles(int maxOverflowBubbles) {
        mMaxOverflowBubbles = maxOverflowBubbles;
    }

    /**
     * Description of current bubble data state.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("selected: ");
        pw.println(mSelectedBubble != null
                ? mSelectedBubble.getKey()
                : "null");
        pw.print("expanded: ");
        pw.println(mExpanded);
        pw.print("count:    ");
        pw.println(mBubbles.size());
        for (Bubble bubble : mBubbles) {
            bubble.dump(fd, pw, args);
        }
        pw.print("summaryKeys: ");
        pw.println(mSuppressedGroupKeys.size());
        for (String key : mSuppressedGroupKeys.keySet()) {
            pw.println("   suppressing: " + key);
        }
    }
}
