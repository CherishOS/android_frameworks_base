/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.isDeliveryStateTerminal;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.IndentingPrintWriter;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

import dalvik.annotation.optimization.NeverCompile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Queue of pending {@link BroadcastRecord} entries intended for delivery to a
 * specific process.
 * <p>
 * Each queue has a concept of being "runnable at" a particular time in the
 * future, which supports arbitrarily pausing or delaying delivery on a
 * per-process basis.
 * <p>
 * Internally each queue consists of a pending broadcasts which are waiting to
 * be dispatched, and a single active broadcast which is currently being
 * dispatched.
 * <p>
 * This entire class is marked as {@code NotThreadSafe} since it's the
 * responsibility of the caller to always interact with a relevant lock held.
 */
// @NotThreadSafe
class BroadcastProcessQueue {
    final @NonNull BroadcastConstants constants;
    final @NonNull String processName;
    final int uid;

    /**
     * Linked list connection to another process under this {@link #uid} which
     * has a different {@link #processName}.
     */
    @Nullable BroadcastProcessQueue processNameNext;

    /**
     * Linked list connections to runnable process with lower and higher
     * {@link #getRunnableAt()} times.
     */
    @Nullable BroadcastProcessQueue runnableAtNext;
    @Nullable BroadcastProcessQueue runnableAtPrev;

    /**
     * Currently known details about the target process; typically undefined
     * when the process isn't actively running.
     */
    @Nullable ProcessRecord app;

    /**
     * Track name to use for {@link Trace} events, defined as part of upgrading
     * into a running slot.
     */
    @Nullable String runningTraceTrackName;

    /**
     * Flag indicating if this process should be OOM adjusted, defined as part
     * of upgrading into a running slot.
     */
    boolean runningOomAdjusted;

    /**
     * Snapshotted value of {@link ProcessRecord#getCpuDelayTime()}, typically
     * used when deciding if we should extend the soft ANR timeout.
     */
    long lastCpuDelayTime;

    /**
     * Ordered collection of broadcasts that are waiting to be dispatched to
     * this process, as a pair of {@link BroadcastRecord} and the index into
     * {@link BroadcastRecord#receivers} that represents the receiver.
     */
    private final ArrayDeque<SomeArgs> mPending = new ArrayDeque<>();

    /**
     * Ordered collection of "urgent" broadcasts that are waiting to be
     * dispatched to this process, in the same representation as
     * {@link #mPending}.
     */
    private final ArrayDeque<SomeArgs> mPendingUrgent = new ArrayDeque<>(4);

    /**
     * Ordered collection of "offload" broadcasts that are waiting to be
     * dispatched to this process, in the same representation as
     * {@link #mPending}.
     */
    private final ArrayDeque<SomeArgs> mPendingOffload = new ArrayDeque<>(4);

    /**
     * List of all queues holding broadcasts that are waiting to be dispatched.
     */
    private final List<ArrayDeque<SomeArgs>> mPendingQueues = List.of(
            mPendingUrgent, mPending, mPendingOffload);

    /**
     * Broadcast actively being dispatched to this process.
     */
    private @Nullable BroadcastRecord mActive;

    /**
     * Receiver actively being dispatched to in this process. This is an index
     * into the {@link BroadcastRecord#receivers} list of {@link #mActive}.
     */
    private int mActiveIndex;

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    private int mActiveCountSinceIdle;

    /**
     * Flag indicating that the currently active broadcast is being dispatched
     * was scheduled via a cold start.
     */
    private boolean mActiveViaColdStart;

    /**
     * Number of consecutive urgent broadcasts that have been dispatched
     * since the last non-urgent dispatch.
     */
    private int mActiveCountConsecutiveUrgent;

    /**
     * Number of consecutive normal broadcasts that have been dispatched
     * since the last offload dispatch.
     */
    private int mActiveCountConsecutiveNormal;

    /**
     * Count of pending broadcasts of these various flavors.
     */
    private int mCountForeground;
    private int mCountOrdered;
    private int mCountAlarm;
    private int mCountPrioritized;
    private int mCountInteractive;
    private int mCountResultTo;
    private int mCountInstrumented;
    private int mCountManifest;

    private boolean mPrioritizeEarliest;

    private @UptimeMillisLong long mRunnableAt = Long.MAX_VALUE;
    private @Reason int mRunnableAtReason = REASON_EMPTY;
    private boolean mRunnableAtInvalidated;

    private boolean mProcessCached;
    private boolean mProcessInstrumented;
    private boolean mProcessPersistent;

    private String mCachedToString;
    private String mCachedToShortString;

    /**
     * The duration by which any broadcasts to this process need to be delayed
     */
    private long mForcedDelayedDurationMs;

    public BroadcastProcessQueue(@NonNull BroadcastConstants constants,
            @NonNull String processName, int uid) {
        this.constants = Objects.requireNonNull(constants);
        this.processName = Objects.requireNonNull(processName);
        this.uid = uid;
    }

    private @NonNull ArrayDeque<SomeArgs> getQueueForBroadcast(@NonNull BroadcastRecord record) {
        if (record.isUrgent()) {
            return mPendingUrgent;
        } else if (record.isOffload()) {
            return mPendingOffload;
        } else {
            return mPending;
        }
    }

    /**
     * Enqueue the given broadcast to be dispatched to this process at some
     * future point in time. The target receiver is indicated by the given index
     * into {@link BroadcastRecord#receivers}.
     * <p>
     * If the broadcast is marked as {@link BroadcastRecord#isReplacePending()},
     * then this call will replace any pending dispatch; otherwise it will
     * enqueue as a normal broadcast.
     * <p>
     * When defined, this receiver is considered "blocked" until at least the
     * given count of other receivers have reached a terminal state; typically
     * used for ordered broadcasts and priority traunches.
     */
    public void enqueueOrReplaceBroadcast(@NonNull BroadcastRecord record, int recordIndex,
            @NonNull BroadcastConsumer replacedBroadcastConsumer) {
        if (record.isReplacePending()) {
            final boolean didReplace = replaceBroadcast(record, recordIndex,
                    replacedBroadcastConsumer);
            if (didReplace) {
                return;
            }
        }

        // Caller isn't interested in replacing, or we didn't find any pending
        // item to replace above, so enqueue as a new broadcast
        SomeArgs newBroadcastArgs = SomeArgs.obtain();
        newBroadcastArgs.arg1 = record;
        newBroadcastArgs.argi1 = recordIndex;

        // Cross-broadcast prioritization policy:  some broadcasts might warrant being
        // issued ahead of others that are already pending, for example if this new
        // broadcast is in a different delivery class or is tied to a direct user interaction
        // with implicit responsiveness expectations.
        getQueueForBroadcast(record).addLast(newBroadcastArgs);
        onBroadcastEnqueued(record, recordIndex);
    }

    /**
     * Searches from newest to oldest in the pending broadcast queues, and at the first matching
     * pending broadcast it finds, replaces it in-place and returns -- does not attempt to handle
     * "duplicate" broadcasts in the queue.
     * <p>
     * @return {@code true} if it found and replaced an existing record in the queue;
     * {@code false} otherwise.
     */
    private boolean replaceBroadcast(@NonNull BroadcastRecord record, int recordIndex,
            @NonNull BroadcastConsumer replacedBroadcastConsumer) {
        final int count = mPendingQueues.size();
        for (int i = 0; i < count; ++i) {
            final ArrayDeque<SomeArgs> queue = mPendingQueues.get(i);
            if (replaceBroadcastInQueue(queue, record, recordIndex, replacedBroadcastConsumer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Searches from newest to oldest, and at the first matching pending broadcast
     * it finds, replaces it in-place and returns -- does not attempt to handle
     * "duplicate" broadcasts in the queue.
     * <p>
     * @return {@code true} if it found and replaced an existing record in the queue;
     * {@code false} otherwise.
     */
    private boolean replaceBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastRecord record, int recordIndex,
            @NonNull BroadcastConsumer replacedBroadcastConsumer) {
        final Iterator<SomeArgs> it = queue.descendingIterator();
        final Object receiver = record.receivers.get(recordIndex);
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord testRecord = (BroadcastRecord) args.arg1;
            final int testRecordIndex = args.argi1;
            final Object testReceiver = testRecord.receivers.get(testRecordIndex);
            if ((record.callingUid == testRecord.callingUid)
                    && (record.userId == testRecord.userId)
                    && record.intent.filterEquals(testRecord.intent)
                    && isReceiverEquals(receiver, testReceiver)
                    && testRecord.allReceiversPending()) {
                // Exact match found; perform in-place swap
                args.arg1 = record;
                args.argi1 = recordIndex;
                onBroadcastDequeued(testRecord, testRecordIndex);
                onBroadcastEnqueued(record, recordIndex);
                replacedBroadcastConsumer.accept(testRecord, testRecordIndex);
                return true;
            }
        }
        return false;
    }

    /**
     * Functional interface that tests a {@link BroadcastRecord} that has been
     * previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastPredicate {
        boolean test(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Functional interface that consumes a {@link BroadcastRecord} that has
     * been previously enqueued in {@link BroadcastProcessQueue}.
     */
    @FunctionalInterface
    public interface BroadcastConsumer {
        void accept(@NonNull BroadcastRecord r, int index);
    }

    /**
     * Invoke given consumer for any broadcasts matching given predicate. If
     * requested, matching broadcasts will also be removed from this queue.
     * <p>
     * Predicates that choose to remove a broadcast <em>must</em> finish
     * delivery of the matched broadcast, to ensure that situations like ordered
     * broadcasts are handled consistently.
     */
    public boolean forEachMatchingBroadcast(@NonNull BroadcastPredicate predicate,
            @NonNull BroadcastConsumer consumer, boolean andRemove) {
        boolean didSomething = false;
        didSomething |= forEachMatchingBroadcastInQueue(mPending,
                predicate, consumer, andRemove);
        didSomething |= forEachMatchingBroadcastInQueue(mPendingUrgent,
                predicate, consumer, andRemove);
        didSomething |= forEachMatchingBroadcastInQueue(mPendingOffload,
                predicate, consumer, andRemove);
        return didSomething;
    }

    private boolean forEachMatchingBroadcastInQueue(@NonNull ArrayDeque<SomeArgs> queue,
            @NonNull BroadcastPredicate predicate, @NonNull BroadcastConsumer consumer,
            boolean andRemove) {
        boolean didSomething = false;
        final Iterator<SomeArgs> it = queue.iterator();
        while (it.hasNext()) {
            final SomeArgs args = it.next();
            final BroadcastRecord record = (BroadcastRecord) args.arg1;
            final int recordIndex = args.argi1;
            if (predicate.test(record, recordIndex)) {
                consumer.accept(record, recordIndex);
                if (andRemove) {
                    args.recycle();
                    it.remove();
                    onBroadcastDequeued(record, recordIndex);
                }
                didSomething = true;
            }
        }
        // TODO: also check any active broadcast once we have a better "nonce"
        // representing each scheduled broadcast to avoid races
        return didSomething;
    }

    /**
     * Update the actively running "warm" process for this process.
     */
    public void setProcess(@Nullable ProcessRecord app) {
        this.app = app;
        if (app != null) {
            setProcessInstrumented(app.getActiveInstrumentation() != null);
            setProcessPersistent(app.isPersistent());
        } else {
            setProcessInstrumented(false);
            setProcessPersistent(false);
        }
    }

    /**
     * Update if this process is in the "cached" state, typically signaling that
     * broadcast dispatch should be paused or delayed.
     */
    public void setProcessCached(boolean cached) {
        if (mProcessCached != cached) {
            mProcessCached = cached;
            invalidateRunnableAt();
        }
    }

    /**
     * Update if this process is in the "instrumented" state, typically
     * signaling that broadcast dispatch should bypass all pauses or delays, to
     * avoid holding up test suites.
     */
    public void setProcessInstrumented(boolean instrumented) {
        if (mProcessInstrumented != instrumented) {
            mProcessInstrumented = instrumented;
            invalidateRunnableAt();
        }
    }

    /**
     * Update if this process is in the "persistent" state, which signals broadcast dispatch should
     * bypass all pauses or delays to prevent the system from becoming out of sync with itself.
     */
    public void setProcessPersistent(boolean persistent) {
        if (mProcessPersistent != persistent) {
            mProcessPersistent = persistent;
            invalidateRunnableAt();
        }
    }

    /**
     * Return if we know of an actively running "warm" process for this queue.
     */
    public boolean isProcessWarm() {
        return (app != null) && (app.getOnewayThread() != null) && !app.isKilled();
    }

    public int getPreferredSchedulingGroupLocked() {
        if (mCountForeground > 0) {
            // We have a foreground broadcast somewhere down the queue, so
            // boost priority until we drain them all
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else if ((mActive != null) && mActive.isForeground()) {
            // We have a foreground broadcast right now, so boost priority
            return ProcessList.SCHED_GROUP_DEFAULT;
        } else if (!isIdle()) {
            return ProcessList.SCHED_GROUP_BACKGROUND;
        } else {
            return ProcessList.SCHED_GROUP_UNDEFINED;
        }
    }

    /**
     * Count of {@link #mActive} broadcasts that have been dispatched since this
     * queue was last idle.
     */
    public int getActiveCountSinceIdle() {
        return mActiveCountSinceIdle;
    }

    public void setActiveViaColdStart(boolean activeViaColdStart) {
        mActiveViaColdStart = activeViaColdStart;
    }

    public boolean getActiveViaColdStart() {
        return mActiveViaColdStart;
    }

    /**
     * Get package name of the first application loaded into this process.
     */
    @Nullable
    public String getPackageName() {
        return app == null ? null : app.getApplicationInfo().packageName;
    }

    /**
     * Set the currently active broadcast to the next pending broadcast.
     */
    public void makeActiveNextPending() {
        // TODO: what if the next broadcast isn't runnable yet?
        final SomeArgs next = removeNextBroadcast();
        mActive = (BroadcastRecord) next.arg1;
        mActiveIndex = next.argi1;
        mActiveCountSinceIdle++;
        mActiveViaColdStart = false;
        next.recycle();
        onBroadcastDequeued(mActive, mActiveIndex);
    }

    /**
     * Set the currently running broadcast to be idle.
     */
    public void makeActiveIdle() {
        mActive = null;
        mActiveIndex = 0;
        mActiveCountSinceIdle = 0;
        mActiveViaColdStart = false;
        invalidateRunnableAt();
    }

    /**
     * Update summary statistics when the given record has been enqueued.
     */
    private void onBroadcastEnqueued(@NonNull BroadcastRecord record, int recordIndex) {
        if (record.isForeground()) {
            mCountForeground++;
        }
        if (record.ordered) {
            mCountOrdered++;
        }
        if (record.alarm) {
            mCountAlarm++;
        }
        if (record.prioritized) {
            mCountPrioritized++;
        }
        if (record.interactive) {
            mCountInteractive++;
        }
        if (record.resultTo != null) {
            mCountResultTo++;
        }
        if (record.callerInstrumented) {
            mCountInstrumented++;
        }
        if (record.receivers.get(recordIndex) instanceof ResolveInfo) {
            mCountManifest++;
        }
        invalidateRunnableAt();
    }

    /**
     * Update summary statistics when the given record has been dequeued.
     */
    private void onBroadcastDequeued(@NonNull BroadcastRecord record, int recordIndex) {
        if (record.isForeground()) {
            mCountForeground--;
        }
        if (record.ordered) {
            mCountOrdered--;
        }
        if (record.alarm) {
            mCountAlarm--;
        }
        if (record.prioritized) {
            mCountPrioritized--;
        }
        if (record.interactive) {
            mCountInteractive--;
        }
        if (record.resultTo != null) {
            mCountResultTo--;
        }
        if (record.callerInstrumented) {
            mCountInstrumented--;
        }
        if (record.receivers.get(recordIndex) instanceof ResolveInfo) {
            mCountManifest--;
        }
        invalidateRunnableAt();
    }

    public void traceProcessStartingBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, toShortString() + " starting", hashCode());
    }

    public void traceProcessRunningBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, toShortString() + " running", hashCode());
    }

    public void traceProcessEnd() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, hashCode());
    }

    public void traceActiveBegin() {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, mActive.toShortString() + " scheduled", hashCode());
    }

    public void traceActiveEnd() {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                runningTraceTrackName, hashCode());
    }

    /**
     * Return the broadcast being actively dispatched in this process.
     */
    public @NonNull BroadcastRecord getActive() {
        return Objects.requireNonNull(mActive);
    }

    /**
     * Return the index into {@link BroadcastRecord#receivers} of the receiver
     * being actively dispatched in this process.
     */
    public int getActiveIndex() {
        Objects.requireNonNull(mActive);
        return mActiveIndex;
    }

    public boolean isEmpty() {
        return mPending.isEmpty() && mPendingUrgent.isEmpty() && mPendingOffload.isEmpty();
    }

    public boolean isActive() {
        return mActive != null;
    }

    void forceDelayBroadcastDelivery(long delayedDurationMs) {
        mForcedDelayedDurationMs = delayedDurationMs;
    }

    /**
     * Will thrown an exception if there are no pending broadcasts; relies on
     * {@link #isEmpty()} being false.
     */
    private @Nullable SomeArgs removeNextBroadcast() {
        final ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        if (queue == mPendingUrgent) {
            mActiveCountConsecutiveUrgent++;
        } else if (queue == mPending) {
            mActiveCountConsecutiveUrgent = 0;
            mActiveCountConsecutiveNormal++;
        } else if (queue == mPendingOffload) {
            mActiveCountConsecutiveUrgent = 0;
            mActiveCountConsecutiveNormal = 0;
        }
        return !isQueueEmpty(queue) ? queue.removeFirst() : null;
    }

    @Nullable ArrayDeque<SomeArgs> queueForNextBroadcast() {
        final ArrayDeque<SomeArgs> nextNormal = queueForNextBroadcast(
                mPending, mPendingOffload,
                mActiveCountConsecutiveNormal, constants.MAX_CONSECUTIVE_NORMAL_DISPATCHES);
        final ArrayDeque<SomeArgs> nextBroadcastQueue = queueForNextBroadcast(
                mPendingUrgent, nextNormal,
                mActiveCountConsecutiveUrgent, constants.MAX_CONSECUTIVE_URGENT_DISPATCHES);
        return nextBroadcastQueue;
    }

    private @Nullable ArrayDeque<SomeArgs> queueForNextBroadcast(
            @Nullable ArrayDeque<SomeArgs> highPriorityQueue,
            @Nullable ArrayDeque<SomeArgs> lowPriorityQueue,
            int consecutiveHighPriorityCount,
            int maxHighPriorityDispatchLimit) {
        // nothing high priority pending, no further decisionmaking
        if (isQueueEmpty(highPriorityQueue)) {
            return lowPriorityQueue;
        }
        // nothing but high priority pending, also no further decisionmaking
        if (isQueueEmpty(lowPriorityQueue)) {
            return highPriorityQueue;
        }

        // Starvation mitigation: although we prioritize high priority queues by default,
        // we allow low priority queues to make steady progress even if broadcasts in
        // high priority queue are arriving faster than they can be dispatched.
        //
        // We do not try to defer to the next broadcast in low priority queues if that broadcast
        // is ordered and still blocked on delivery to other recipients.
        final SomeArgs nextLPArgs = lowPriorityQueue.peekFirst();
        final BroadcastRecord nextLPRecord = (BroadcastRecord) nextLPArgs.arg1;
        final int nextLPRecordIndex = nextLPArgs.argi1;
        final BroadcastRecord nextHPRecord = (BroadcastRecord) highPriorityQueue.peekFirst().arg1;
        final boolean shouldConsiderLPQueue = (mPrioritizeEarliest
                || consecutiveHighPriorityCount >= maxHighPriorityDispatchLimit);
        final boolean isLPQueueEligible = shouldConsiderLPQueue
                && nextLPRecord.enqueueTime <= nextHPRecord.enqueueTime
                && !blockedOnOrderedDispatch(nextLPRecord, nextLPRecordIndex);
        return isLPQueueEligible ? lowPriorityQueue : highPriorityQueue;
    }

    private static boolean isQueueEmpty(@Nullable ArrayDeque<SomeArgs> queue) {
        return (queue == null || queue.isEmpty());
    }

    /**
     * When {@code prioritizeEarliest} is set to {@code true}, then earliest enqueued
     * broadcasts would be prioritized for dispatching, even if there are urgent broadcasts
     * waiting. This is typically used in case there are callers waiting for "barrier" to be
     * reached.
     */
    @VisibleForTesting
    void setPrioritizeEarliest(boolean prioritizeEarliest) {
        mPrioritizeEarliest = prioritizeEarliest;
    }

    /**
     * Returns null if there are no pending broadcasts
     */
    @Nullable SomeArgs peekNextBroadcast() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return !isQueueEmpty(queue) ? queue.peekFirst() : null;
    }

    @VisibleForTesting
    @Nullable BroadcastRecord peekNextBroadcastRecord() {
        ArrayDeque<SomeArgs> queue = queueForNextBroadcast();
        return !isQueueEmpty(queue) ? (BroadcastRecord) queue.peekFirst().arg1 : null;
    }

    /**
     * Quickly determine if this queue has broadcasts waiting to be delivered to
     * manifest receivers, which indicates we should request an OOM adjust.
     */
    public boolean isPendingManifest() {
        return mCountManifest > 0;
    }

    /**
     * Report whether this queue is currently handling an urgent broadcast.
     */
    public boolean isPendingUrgent() {
        BroadcastRecord next = peekNextBroadcastRecord();
        return (next != null) ? next.isUrgent() : false;
    }

    /**
     * Quickly determine if this queue has broadcasts that are still waiting to
     * be delivered at some point in the future.
     */
    public boolean isIdle() {
        return !isActive() && isEmpty();
    }

    /**
     * Quickly determine if this queue has broadcasts enqueued before the given
     * barrier timestamp that are still waiting to be delivered.
     */
    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime) {
        final SomeArgs next = mPending.peekFirst();
        final SomeArgs nextUrgent = mPendingUrgent.peekFirst();
        final SomeArgs nextOffload = mPendingOffload.peekFirst();

        // Empty records are always past any barrier
        final boolean activeBeyond = (mActive == null)
                || mActive.enqueueTime > barrierTime;
        final boolean nextBeyond = (next == null)
                || ((BroadcastRecord) next.arg1).enqueueTime > barrierTime;
        final boolean nextUrgentBeyond = (nextUrgent == null)
                || ((BroadcastRecord) nextUrgent.arg1).enqueueTime > barrierTime;
        final boolean nextOffloadBeyond = (nextOffload == null)
                || ((BroadcastRecord) nextOffload.arg1).enqueueTime > barrierTime;

        return activeBeyond && nextBeyond && nextUrgentBeyond && nextOffloadBeyond;
    }

    public boolean isRunnable() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAt != Long.MAX_VALUE;
    }

    /**
     * Return time at which this process is considered runnable. This is
     * typically the time at which the next pending broadcast was first
     * enqueued, but it also reflects any pauses or delays that should be
     * applied to the process.
     * <p>
     * Returns {@link Long#MAX_VALUE} when this queue isn't currently runnable,
     * typically when the queue is empty or when paused.
     */
    public @UptimeMillisLong long getRunnableAt() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAt;
    }

    /**
     * Return the "reason" behind the current {@link #getRunnableAt()} value,
     * such as indicating why the queue is being delayed or paused.
     */
    public @Reason int getRunnableAtReason() {
        if (mRunnableAtInvalidated) updateRunnableAt();
        return mRunnableAtReason;
    }

    public void invalidateRunnableAt() {
        mRunnableAtInvalidated = true;
    }

    static final int REASON_EMPTY = 0;
    static final int REASON_CACHED = 1;
    static final int REASON_NORMAL = 2;
    static final int REASON_MAX_PENDING = 3;
    static final int REASON_BLOCKED = 4;
    static final int REASON_INSTRUMENTED = 5;
    static final int REASON_PERSISTENT = 6;
    static final int REASON_FORCE_DELAYED = 7;
    static final int REASON_CONTAINS_FOREGROUND = 10;
    static final int REASON_CONTAINS_ORDERED = 11;
    static final int REASON_CONTAINS_ALARM = 12;
    static final int REASON_CONTAINS_PRIORITIZED = 13;
    static final int REASON_CONTAINS_INTERACTIVE = 14;
    static final int REASON_CONTAINS_RESULT_TO = 15;
    static final int REASON_CONTAINS_INSTRUMENTED = 16;
    static final int REASON_CONTAINS_MANIFEST = 17;

    @IntDef(flag = false, prefix = { "REASON_" }, value = {
            REASON_EMPTY,
            REASON_CACHED,
            REASON_NORMAL,
            REASON_MAX_PENDING,
            REASON_BLOCKED,
            REASON_INSTRUMENTED,
            REASON_PERSISTENT,
            REASON_FORCE_DELAYED,
            REASON_CONTAINS_FOREGROUND,
            REASON_CONTAINS_ORDERED,
            REASON_CONTAINS_ALARM,
            REASON_CONTAINS_PRIORITIZED,
            REASON_CONTAINS_INTERACTIVE,
            REASON_CONTAINS_RESULT_TO,
            REASON_CONTAINS_INSTRUMENTED,
            REASON_CONTAINS_MANIFEST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    static @NonNull String reasonToString(@Reason int reason) {
        switch (reason) {
            case REASON_EMPTY: return "EMPTY";
            case REASON_CACHED: return "CACHED";
            case REASON_NORMAL: return "NORMAL";
            case REASON_MAX_PENDING: return "MAX_PENDING";
            case REASON_BLOCKED: return "BLOCKED";
            case REASON_INSTRUMENTED: return "INSTRUMENTED";
            case REASON_PERSISTENT: return "PERSISTENT";
            case REASON_FORCE_DELAYED: return "FORCE_DELAYED";
            case REASON_CONTAINS_FOREGROUND: return "CONTAINS_FOREGROUND";
            case REASON_CONTAINS_ORDERED: return "CONTAINS_ORDERED";
            case REASON_CONTAINS_ALARM: return "CONTAINS_ALARM";
            case REASON_CONTAINS_PRIORITIZED: return "CONTAINS_PRIORITIZED";
            case REASON_CONTAINS_INTERACTIVE: return "CONTAINS_INTERACTIVE";
            case REASON_CONTAINS_RESULT_TO: return "CONTAINS_RESULT_TO";
            case REASON_CONTAINS_INSTRUMENTED: return "CONTAINS_INSTRUMENTED";
            case REASON_CONTAINS_MANIFEST: return "CONTAINS_MANIFEST";
            default: return Integer.toString(reason);
        }
    }

    private boolean blockedOnOrderedDispatch(BroadcastRecord r, int index) {
        final int blockedUntilTerminalCount = r.blockedUntilTerminalCount[index];

        // We might be blocked waiting for other receivers to finish,
        // typically for an ordered broadcast or priority traunches
        if (r.terminalCount < blockedUntilTerminalCount
                && !isDeliveryStateTerminal(r.getDeliveryState(index))) {
            return true;
        }
        return false;
    }

    /**
     * Update {@link #getRunnableAt()} if it's currently invalidated.
     */
    private void updateRunnableAt() {
        final SomeArgs next = peekNextBroadcast();
        mRunnableAtInvalidated = false;
        if (next != null) {
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final int index = next.argi1;
            final long runnableAt = r.enqueueTime;

            // If we're specifically queued behind other ordered dispatch activity,
            // we aren't runnable yet
            if (blockedOnOrderedDispatch(r, index)) {
                mRunnableAt = Long.MAX_VALUE;
                mRunnableAtReason = REASON_BLOCKED;
                return;
            }

            if (mForcedDelayedDurationMs > 0) {
                mRunnableAt = runnableAt + mForcedDelayedDurationMs;
                mRunnableAtReason = REASON_FORCE_DELAYED;
            } else if (mCountForeground > 0) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_FOREGROUND;
            } else if (mCountInteractive > 0) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_INTERACTIVE;
            } else if (mCountInstrumented > 0) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_CONTAINS_INSTRUMENTED;
            } else if (mProcessInstrumented) {
                mRunnableAt = runnableAt + constants.DELAY_URGENT_MILLIS;
                mRunnableAtReason = REASON_INSTRUMENTED;
            } else if (mCountOrdered > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ORDERED;
            } else if (mCountAlarm > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_ALARM;
            } else if (mCountPrioritized > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_PRIORITIZED;
            } else if (mCountResultTo > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_RESULT_TO;
            } else if (mCountManifest > 0) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_CONTAINS_MANIFEST;
            } else if (mProcessPersistent) {
                mRunnableAt = runnableAt;
                mRunnableAtReason = REASON_PERSISTENT;
            } else if (mProcessCached) {
                mRunnableAt = runnableAt + constants.DELAY_CACHED_MILLIS;
                mRunnableAtReason = REASON_CACHED;
            } else {
                mRunnableAt = runnableAt + constants.DELAY_NORMAL_MILLIS;
                mRunnableAtReason = REASON_NORMAL;
            }

            // If we have too many broadcasts pending, bypass any delays that
            // might have been applied above to aid draining
            if (mPending.size() + mPendingUrgent.size()
                    + mPendingOffload.size() >= constants.MAX_PENDING_BROADCASTS) {
                mRunnableAt = Math.min(mRunnableAt, runnableAt);
                mRunnableAtReason = REASON_MAX_PENDING;
            }
        } else {
            mRunnableAt = Long.MAX_VALUE;
            mRunnableAtReason = REASON_EMPTY;
        }
    }

    /**
     * Check overall health, confirming things are in a reasonable state and
     * that we're not wedged.
     */
    public void checkHealthLocked() {
        if (mRunnableAtReason == REASON_BLOCKED) {
            final SomeArgs next = peekNextBroadcast();
            Objects.requireNonNull(next, "peekNextBroadcast");

            // If blocked more than 10 minutes, we're likely wedged
            final BroadcastRecord r = (BroadcastRecord) next.arg1;
            final long waitingTime = SystemClock.uptimeMillis() - r.enqueueTime;
            checkState(waitingTime < (10 * DateUtils.MINUTE_IN_MILLIS), "waitingTime");
        }
    }

    /**
     * Insert the given queue into a sorted linked list of "runnable" queues.
     *
     * @param head the current linked list head
     * @param item the queue to insert
     * @return a potentially updated linked list head
     */
    @VisibleForTesting
    static @Nullable BroadcastProcessQueue insertIntoRunnableList(
            @Nullable BroadcastProcessQueue head, @NonNull BroadcastProcessQueue item) {
        if (head == null) {
            return item;
        }
        final long itemRunnableAt = item.getRunnableAt();
        BroadcastProcessQueue test = head;
        BroadcastProcessQueue tail = null;
        while (test != null) {
            if (test.getRunnableAt() >= itemRunnableAt) {
                item.runnableAtNext = test;
                item.runnableAtPrev = test.runnableAtPrev;
                if (item.runnableAtNext != null) {
                    item.runnableAtNext.runnableAtPrev = item;
                }
                if (item.runnableAtPrev != null) {
                    item.runnableAtPrev.runnableAtNext = item;
                }
                return (test == head) ? item : head;
            }
            tail = test;
            test = test.runnableAtNext;
        }
        item.runnableAtPrev = tail;
        item.runnableAtPrev.runnableAtNext = item;
        return head;
    }

    /**
     * Remove the given queue from a sorted linked list of "runnable" queues.
     *
     * @param head the current linked list head
     * @param item the queue to remove
     * @return a potentially updated linked list head
     */
    @VisibleForTesting
    static @Nullable BroadcastProcessQueue removeFromRunnableList(
            @Nullable BroadcastProcessQueue head, @NonNull BroadcastProcessQueue item) {
        if (head == item) {
            head = item.runnableAtNext;
        }
        if (item.runnableAtNext != null) {
            item.runnableAtNext.runnableAtPrev = item.runnableAtPrev;
        }
        if (item.runnableAtPrev != null) {
            item.runnableAtPrev.runnableAtNext = item.runnableAtNext;
        }
        item.runnableAtNext = null;
        item.runnableAtPrev = null;
        return head;
    }

    @Override
    public String toString() {
        if (mCachedToString == null) {
            mCachedToString = "BroadcastProcessQueue{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + processName + "/" + UserHandle.formatUid(uid) + "}";
        }
        return mCachedToString;
    }

    public String toShortString() {
        if (mCachedToShortString == null) {
            mCachedToShortString = processName + "/" + UserHandle.formatUid(uid);
        }
        return mCachedToShortString;
    }

    @NeverCompile
    public void dumpLocked(@UptimeMillisLong long now, @NonNull IndentingPrintWriter pw) {
        if ((mActive == null) && isEmpty()) return;

        pw.print(toShortString());
        if (isRunnable()) {
            pw.print(" runnable at ");
            TimeUtils.formatDuration(getRunnableAt(), now, pw);
        } else {
            pw.print(" not runnable");
        }
        pw.print(" because ");
        pw.print(reasonToString(mRunnableAtReason));
        pw.println();
        pw.increaseIndent();
        if (mActive != null) {
            dumpRecord("ACTIVE", now, pw, mActive, mActiveIndex);
        }
        for (SomeArgs args : mPendingUrgent) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord("URGENT", now, pw, r, args.argi1);
        }
        for (SomeArgs args : mPending) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord(null, now, pw, r, args.argi1);
        }
        for (SomeArgs args : mPendingOffload) {
            final BroadcastRecord r = (BroadcastRecord) args.arg1;
            dumpRecord("OFFLOAD", now, pw, r, args.argi1);
        }
        pw.decreaseIndent();
        pw.println();
    }

    @NeverCompile
    private void dumpRecord(@Nullable String flavor, @UptimeMillisLong long now,
            @NonNull IndentingPrintWriter pw, @NonNull BroadcastRecord record, int recordIndex) {
        TimeUtils.formatDuration(record.enqueueTime, now, pw);
        pw.print(' ');
        pw.println(record.toShortString());
        pw.print("    ");
        final int deliveryState = record.delivery[recordIndex];
        pw.print(deliveryStateToString(deliveryState));
        if (deliveryState == BroadcastRecord.DELIVERY_SCHEDULED) {
            pw.print(" at ");
            TimeUtils.formatDuration(record.scheduledTime[recordIndex], now, pw);
        }
        if (flavor != null) {
            pw.print(' ');
            pw.print(flavor);
        }
        final Object receiver = record.receivers.get(recordIndex);
        if (receiver instanceof BroadcastFilter) {
            final BroadcastFilter filter = (BroadcastFilter) receiver;
            pw.print(" for registered ");
            pw.print(Integer.toHexString(System.identityHashCode(filter)));
        } else /* if (receiver instanceof ResolveInfo) */ {
            final ResolveInfo info = (ResolveInfo) receiver;
            pw.print(" for manifest ");
            pw.print(info.activityInfo.name);
        }
        pw.println();
        final int blockedUntilTerminalCount = record.blockedUntilTerminalCount[recordIndex];
        if (blockedUntilTerminalCount != -1) {
            pw.print("    blocked until ");
            pw.print(blockedUntilTerminalCount);
            pw.print(", currently at ");
            pw.print(record.terminalCount);
            pw.print(" of ");
            pw.println(record.receivers.size());
        }
    }
}
