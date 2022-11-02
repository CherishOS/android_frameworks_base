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

import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;

import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.reasonToString;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.getReceiverPackageName;
import static com.android.server.am.BroadcastRecord.getReceiverProcessName;
import static com.android.server.am.BroadcastRecord.getReceiverUid;
import static com.android.server.am.BroadcastRecord.isDeliveryStateTerminal;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_FINISH_RECEIVER;
import static com.android.server.am.OomAdjuster.OOM_ADJ_REASON_START_RECEIVER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.RemoteServiceException.CannotDeliverBroadcastException;
import android.app.UidObserver;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.BroadcastProcessQueue.BroadcastConsumer;
import com.android.server.am.BroadcastProcessQueue.BroadcastPredicate;
import com.android.server.am.BroadcastRecord.DeliveryState;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Alternative {@link BroadcastQueue} implementation which pivots broadcasts to
 * be dispatched on a per-process basis.
 * <p>
 * Each process now has its own broadcast queue represented by a
 * {@link BroadcastProcessQueue} instance. Each queue has a concept of being
 * "runnable at" a particular time in the future, which supports arbitrarily
 * pausing or delaying delivery on a per-process basis.
 * <p>
 * To keep things easy to reason about, there is a <em>very strong</em>
 * preference to have broadcast interactions flow through a consistent set of
 * methods in this specific order:
 * <ol>
 * <li>{@link #updateRunnableList} promotes a per-process queue to be runnable
 * when it has relevant pending broadcasts
 * <li>{@link #updateRunningList} promotes a runnable queue to be running and
 * schedules delivery of the first broadcast
 * <li>{@link #scheduleReceiverColdLocked} requests any needed cold-starts, and
 * results are reported back via {@link #onApplicationAttachedLocked}
 * <li>{@link #scheduleReceiverWarmLocked} requests dispatch of the currently
 * active broadcast to a running app, and results are reported back via
 * {@link #finishReceiverLocked}
 * </ol>
 */
class BroadcastQueueModernImpl extends BroadcastQueue {
    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants fgConstants, BroadcastConstants bgConstants) {
        this(service, handler, fgConstants, bgConstants, new BroadcastSkipPolicy(service),
                new BroadcastHistory(fgConstants));
    }

    BroadcastQueueModernImpl(ActivityManagerService service, Handler handler,
            BroadcastConstants fgConstants, BroadcastConstants bgConstants,
            BroadcastSkipPolicy skipPolicy, BroadcastHistory history) {
        super(service, handler, "modern", skipPolicy, history);

        // For the moment, read agnostic constants from foreground
        mConstants = Objects.requireNonNull(fgConstants);
        mFgConstants = Objects.requireNonNull(fgConstants);
        mBgConstants = Objects.requireNonNull(bgConstants);

        mLocalHandler = new Handler(handler.getLooper(), mLocalCallback);

        // We configure runnable size only once at boot; it'd be too complex to
        // try resizing dynamically at runtime
        mRunning = new BroadcastProcessQueue[mConstants.MAX_RUNNING_PROCESS_QUEUES];
    }

    /**
     * Map from UID to per-process broadcast queues. If a UID hosts more than
     * one process, each additional process is stored as a linked list using
     * {@link BroadcastProcessQueue#next}.
     *
     * @see #getProcessQueue
     * @see #getOrCreateProcessQueue
     */
    @GuardedBy("mService")
    private final SparseArray<BroadcastProcessQueue> mProcessQueues = new SparseArray<>();

    /**
     * Head of linked list containing queues which are "runnable". They're
     * sorted by {@link BroadcastProcessQueue#getRunnableAt()} so that we prefer
     * dispatching of longer-waiting broadcasts first.
     *
     * @see BroadcastProcessQueue#insertIntoRunnableList
     * @see BroadcastProcessQueue#removeFromRunnableList
     */
    private BroadcastProcessQueue mRunnableHead = null;

    /**
     * Array of queues which are currently "running", which may have gaps that
     * are {@code null}.
     *
     * @see #getRunningSize
     * @see #getRunningIndexOf
     */
    @GuardedBy("mService")
    private final BroadcastProcessQueue[] mRunning;

    /**
     * Single queue which is "running" but is awaiting a cold start to be
     * completed via {@link #onApplicationAttachedLocked}. To optimize for
     * system health we only request one cold start at a time.
     */
    @GuardedBy("mService")
    private @Nullable BroadcastProcessQueue mRunningColdStart;

    /**
     * Collection of latches waiting for device to reach specific state. The
     * first argument is a function to test for the desired state, and the
     * second argument is the latch to release once that state is reached.
     * <p>
     * This is commonly used for callers that are blocked waiting for an
     * {@link #isIdleLocked} or {@link #isBeyondBarrierLocked} to be reached,
     * without requiring that they periodically poll for the state change.
     * <p>
     * Finally, the presence of any waiting latches will cause all
     * future-runnable processes to be runnable immediately, to aid in reaching
     * the desired state as quickly as possible.
     */
    @GuardedBy("mService")
    private final ArrayList<Pair<BooleanSupplier, CountDownLatch>> mWaitingFor = new ArrayList<>();

    private final BroadcastConstants mConstants;
    private final BroadcastConstants mFgConstants;
    private final BroadcastConstants mBgConstants;

    /**
     * Timestamp when last {@link #testAllProcessQueues} failure was observed;
     * used for throttling log messages.
     */
    private @UptimeMillisLong long mLastTestFailureTime;

    private static final int MSG_UPDATE_RUNNING_LIST = 1;
    private static final int MSG_DELIVERY_TIMEOUT_SOFT = 2;
    private static final int MSG_DELIVERY_TIMEOUT_HARD = 3;
    private static final int MSG_BG_ACTIVITY_START_TIMEOUT = 4;
    private static final int MSG_CHECK_HEALTH = 5;
    private static final int MSG_FINISH_RECEIVER = 6;

    private void enqueueUpdateRunningList() {
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);
        mLocalHandler.sendEmptyMessage(MSG_UPDATE_RUNNING_LIST);
    }

    private void enqueueFinishReceiver(@NonNull BroadcastProcessQueue queue,
            @DeliveryState int deliveryState, @NonNull String reason) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = queue;
        args.argi1 = deliveryState;
        args.arg2 = reason;
        mLocalHandler.sendMessage(Message.obtain(mLocalHandler, MSG_FINISH_RECEIVER, args));
    }

    private final Handler mLocalHandler;

    private final Handler.Callback mLocalCallback = (msg) -> {
        switch (msg.what) {
            case MSG_UPDATE_RUNNING_LIST: {
                synchronized (mService) {
                    updateRunningListLocked();
                }
                return true;
            }
            case MSG_DELIVERY_TIMEOUT_SOFT: {
                synchronized (mService) {
                    deliveryTimeoutSoftLocked((BroadcastProcessQueue) msg.obj);
                }
                return true;
            }
            case MSG_DELIVERY_TIMEOUT_HARD: {
                synchronized (mService) {
                    deliveryTimeoutHardLocked((BroadcastProcessQueue) msg.obj);
                }
                return true;
            }
            case MSG_BG_ACTIVITY_START_TIMEOUT: {
                synchronized (mService) {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final ProcessRecord app = (ProcessRecord) args.arg1;
                    final BroadcastRecord r = (BroadcastRecord) args.arg2;
                    args.recycle();
                    app.removeAllowBackgroundActivityStartsToken(r);
                }
                return true;
            }
            case MSG_CHECK_HEALTH: {
                synchronized (mService) {
                    checkHealthLocked();
                }
                return true;
            }
            case MSG_FINISH_RECEIVER: {
                synchronized (mService) {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final BroadcastProcessQueue queue = (BroadcastProcessQueue) args.arg1;
                    final int deliveryState = args.argi1;
                    final String reason = (String) args.arg2;
                    args.recycle();
                    finishReceiverLocked(queue, deliveryState, reason);
                }
                return true;
            }
        }
        return false;
    };

    /**
     * Return the total number of active queues contained inside
     * {@link #mRunning}.
     */
    private int getRunningSize() {
        int size = 0;
        for (int i = 0; i < mRunning.length; i++) {
            if (mRunning[i] != null) size++;
        }
        return size;
    }

    /**
     * Return the first index of the given value contained inside
     * {@link #mRunning}, otherwise {@code -1}.
     */
    private int getRunningIndexOf(@Nullable BroadcastProcessQueue test) {
        for (int i = 0; i < mRunning.length; i++) {
            if (mRunning[i] == test) return i;
        }
        return -1;
    }

    /**
     * Consider updating the list of "runnable" queues, specifically with
     * relation to the given queue.
     * <p>
     * Typically called when {@link BroadcastProcessQueue#getRunnableAt()} might
     * have changed, since that influences the order in which we'll promote a
     * "runnable" queue to be "running."
     */
    @GuardedBy("mService")
    private void updateRunnableList(@NonNull BroadcastProcessQueue queue) {
        if (getRunningIndexOf(queue) >= 0) {
            // Already running; they'll be reinserted into the runnable list
            // once they finish running, so no need to update them now
            return;
        }

        final boolean wantQueue = queue.isRunnable();
        final boolean inQueue = (queue == mRunnableHead) || (queue.runnableAtPrev != null)
                || (queue.runnableAtNext != null);
        if (wantQueue) {
            if (inQueue) {
                // We're in a good state, but our position within the linked
                // list might need to move based on a runnableAt change
                final boolean prevLower = (queue.runnableAtPrev != null)
                        ? queue.runnableAtPrev.getRunnableAt() <= queue.getRunnableAt() : true;
                final boolean nextHigher = (queue.runnableAtNext != null)
                        ? queue.runnableAtNext.getRunnableAt() >= queue.getRunnableAt() : true;
                if (!prevLower || !nextHigher) {
                    mRunnableHead = removeFromRunnableList(mRunnableHead, queue);
                    mRunnableHead = insertIntoRunnableList(mRunnableHead, queue);
                }
            } else {
                mRunnableHead = insertIntoRunnableList(mRunnableHead, queue);
            }
        } else if (inQueue) {
            mRunnableHead = removeFromRunnableList(mRunnableHead, queue);
        }

        // If app isn't running, and there's nothing in the queue, clean up
        if (queue.isEmpty() && !queue.isActive() && !queue.isProcessWarm()) {
            removeProcessQueue(queue.processName, queue.uid);
        }
    }

    /**
     * Consider updating the list of "running" queues.
     * <p>
     * This method can promote "runnable" queues to become "running", subject to
     * a maximum of {@link BroadcastConstants#MAX_RUNNING_PROCESS_QUEUES} warm
     * processes and only one pending cold-start.
     */
    @GuardedBy("mService")
    private void updateRunningListLocked() {
        int avail = mRunning.length - getRunningSize();
        if (avail == 0) return;

        final int cookie = traceBegin("updateRunningList");
        final long now = SystemClock.uptimeMillis();

        // If someone is waiting for a state, everything is runnable now
        final boolean waitingFor = !mWaitingFor.isEmpty();

        // We're doing an update now, so remove any future update requests;
        // we'll repost below if needed
        mLocalHandler.removeMessages(MSG_UPDATE_RUNNING_LIST);

        boolean updateOomAdj = false;
        BroadcastProcessQueue queue = mRunnableHead;
        while (queue != null && avail > 0) {
            BroadcastProcessQueue nextQueue = queue.runnableAtNext;
            final long runnableAt = queue.getRunnableAt();

            // When broadcasts are skipped or failed during list traversal, we
            // might encounter a queue that is no longer runnable; skip it
            if (!queue.isRunnable()) {
                queue = nextQueue;
                continue;
            }

            // If queues beyond this point aren't ready to run yet, schedule
            // another pass when they'll be runnable
            if (runnableAt > now && !waitingFor) {
                mLocalHandler.sendEmptyMessageAtTime(MSG_UPDATE_RUNNING_LIST, runnableAt);
                break;
            }

            // We might not have heard about a newly running process yet, so
            // consider refreshing if we think we're cold
            updateWarmProcess(queue);

            final boolean processWarm = queue.isProcessWarm();
            if (!processWarm) {
                // We only offer to run one cold-start at a time to preserve
                // system resources; below we either claim that single slot or
                // skip to look for another warm process
                if (mRunningColdStart == null) {
                    mRunningColdStart = queue;
                } else {
                    // Move to considering next runnable queue
                    queue = nextQueue;
                    continue;
                }
            }

            if (DEBUG_BROADCAST) logv("Promoting " + queue
                    + " from runnable to running; process is " + queue.app);

            // Allocate this available permit and start running!
            final int queueIndex = getRunningIndexOf(null);
            mRunning[queueIndex] = queue;
            avail--;

            // Remove ourselves from linked list of runnable things
            mRunnableHead = removeFromRunnableList(mRunnableHead, queue);

            // Emit all trace events for this process into a consistent track
            queue.runningTraceTrackName = TAG + ".mRunning[" + queueIndex + "]";
            queue.runningOomAdjusted = queue.isPendingManifest();

            // If already warm, we can make OOM adjust request immediately;
            // otherwise we need to wait until process becomes warm
            if (processWarm) {
                notifyStartedRunning(queue);
                updateOomAdj |= queue.runningOomAdjusted;
            }

            // If we're already warm, schedule next pending broadcast now;
            // otherwise we'll wait for the cold start to circle back around
            queue.makeActiveNextPending();
            if (processWarm) {
                queue.traceProcessRunningBegin();
                scheduleReceiverWarmLocked(queue);
            } else {
                queue.traceProcessStartingBegin();
                scheduleReceiverColdLocked(queue);
            }

            // Move to considering next runnable queue
            queue = nextQueue;
        }

        if (updateOomAdj) {
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);
        }

        if (waitingFor) {
            mWaitingFor.removeIf((pair) -> {
                if (pair.first.getAsBoolean()) {
                    pair.second.countDown();
                    return true;
                } else {
                    return false;
                }
            });
        }

        traceEnd(cookie);
    }

    @Override
    public boolean onApplicationAttachedLocked(@NonNull ProcessRecord app) {
        // Process records can be recycled, so always start by looking up the
        // relevant per-process queue
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if (queue != null) {
            queue.setProcess(app);
        }

        boolean didSomething = false;
        if ((mRunningColdStart != null) && (mRunningColdStart == queue)) {
            // We've been waiting for this app to cold start, and it's ready
            // now; dispatch its next broadcast and clear the slot
            mRunningColdStart = null;

            // Now that we're running warm, we can finally request that OOM
            // adjust we've been waiting for
            notifyStartedRunning(queue);
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_RECEIVER);

            queue.traceProcessEnd();
            queue.traceProcessRunningBegin();
            scheduleReceiverWarmLocked(queue);

            // We might be willing to kick off another cold start
            enqueueUpdateRunningList();
            didSomething = true;
        }
        return didSomething;
    }

    @Override
    public void onApplicationTimeoutLocked(@NonNull ProcessRecord app) {
        onApplicationCleanupLocked(app);
    }

    @Override
    public void onApplicationProblemLocked(@NonNull ProcessRecord app) {
        onApplicationCleanupLocked(app);
    }

    @Override
    public void onApplicationCleanupLocked(@NonNull ProcessRecord app) {
        // Process records can be recycled, so always start by looking up the
        // relevant per-process queue
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if (queue != null) {
            queue.setProcess(null);
        }

        if ((mRunningColdStart != null) && (mRunningColdStart == queue)) {
            // We've been waiting for this app to cold start, and it had
            // trouble; clear the slot and fail delivery below
            mRunningColdStart = null;

            queue.traceProcessEnd();

            // We might be willing to kick off another cold start
            enqueueUpdateRunningList();
        }

        if (queue != null) {
            // If queue was running a broadcast, fail it
            if (queue.isActive()) {
                finishReceiverLocked(queue, BroadcastRecord.DELIVERY_FAILURE,
                        "onApplicationCleanupLocked");
            }

            // Skip any pending registered receivers, since the old process
            // would never be around to receive them
            boolean didSomething = queue.forEachMatchingBroadcast((r, i) -> {
                return (r.receivers.get(i) instanceof BroadcastFilter);
            }, mBroadcastConsumerSkip, true);
            if (didSomething || queue.isEmpty()) {
                updateRunnableList(queue);
                enqueueUpdateRunningList();
            }
        }
    }

    @Override
    public int getPreferredSchedulingGroupLocked(@NonNull ProcessRecord app) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((queue != null) && getRunningIndexOf(queue) >= 0) {
            return queue.getPreferredSchedulingGroupLocked();
        }
        return ProcessList.SCHED_GROUP_UNDEFINED;
    }

    @Override
    public void enqueueBroadcastLocked(@NonNull BroadcastRecord r) {
        if (DEBUG_BROADCAST) logv("Enqueuing " + r + " for " + r.receivers.size() + " receivers");

        final int cookie = traceBegin("enqueueBroadcast");
        r.applySingletonPolicy(mService);

        final IntentFilter removeMatchingFilter = (r.options != null)
                ? r.options.getRemoveMatchingFilter() : null;
        if (removeMatchingFilter != null) {
            final Predicate<Intent> removeMatching = removeMatchingFilter.asPredicate();
            forEachMatchingBroadcast(QUEUE_PREDICATE_ANY, (testRecord, testIndex) -> {
                // We only allow caller to remove broadcasts they enqueued
                return (r.callingUid == testRecord.callingUid)
                        && (r.userId == testRecord.userId)
                        && removeMatching.test(testRecord.intent);
            }, mBroadcastConsumerSkipAndCanceled, true);
        }

        applyDeliveryGroupPolicy(r);

        if (r.isReplacePending()) {
            // Leave the skipped broadcasts intact in queue, so that we can
            // replace them at their current position during enqueue below
            forEachMatchingBroadcast(QUEUE_PREDICATE_ANY, (testRecord, testIndex) -> {
                // We only allow caller to replace broadcasts they enqueued
                return (r.callingUid == testRecord.callingUid)
                        && (r.userId == testRecord.userId)
                        && r.intent.filterEquals(testRecord.intent);
            }, mBroadcastConsumerSkipAndCanceled, false);
        }

        r.enqueueTime = SystemClock.uptimeMillis();
        r.enqueueRealTime = SystemClock.elapsedRealtime();
        r.enqueueClockTime = System.currentTimeMillis();

        for (int i = 0; i < r.receivers.size(); i++) {
            final Object receiver = r.receivers.get(i);
            final BroadcastProcessQueue queue = getOrCreateProcessQueue(
                    getReceiverProcessName(receiver), getReceiverUid(receiver));
            queue.enqueueOrReplaceBroadcast(r, i);
            updateRunnableList(queue);
            enqueueUpdateRunningList();
        }

        // If nothing to dispatch, send any pending result immediately
        if (r.receivers.isEmpty()) {
            scheduleResultTo(r);
        }

        traceEnd(cookie);
    }

    private void applyDeliveryGroupPolicy(@NonNull BroadcastRecord r) {
        final int policy = (r.options != null)
                ? r.options.getDeliveryGroupPolicy() : BroadcastOptions.DELIVERY_GROUP_POLICY_ALL;
        final BroadcastConsumer broadcastConsumer;
        switch (policy) {
            case BroadcastOptions.DELIVERY_GROUP_POLICY_ALL:
                // Older broadcasts need to be left as is in this case, so nothing more to do.
                return;
            case BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT:
                broadcastConsumer = mBroadcastConsumerSkipAndCanceled;
                break;
            case BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED:
                final BundleMerger extrasMerger = r.options.getDeliveryGroupExtrasMerger();
                if (extrasMerger == null) {
                    // Extras merger is required to be able to merge the extras. So, if it's not
                    // supplied, then ignore the delivery group policy.
                    return;
                }
                broadcastConsumer = (record, recordIndex) -> {
                    r.intent.mergeExtras(record.intent, extrasMerger);
                    mBroadcastConsumerSkipAndCanceled.accept(record, recordIndex);
                };
                break;
            default:
                logw("Unknown delivery group policy: " + policy);
                return;
        }
        forEachMatchingBroadcast(QUEUE_PREDICATE_ANY, (testRecord, testIndex) -> {
            // We only allow caller to remove broadcasts they enqueued
            return (r.callingUid == testRecord.callingUid)
                    && (r.userId == testRecord.userId)
                    && r.matchesDeliveryGroup(testRecord);
        }, broadcastConsumer, true);
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is cold. This kicks off a cold start and will eventually call
     * through to {@link #scheduleReceiverWarmLocked} once it's ready.
     */
    private void scheduleReceiverColdLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        // Remember that active broadcast was scheduled via a cold start
        queue.setActiveViaColdStart(true);

        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        // Ignore registered receivers from a previous PID
        if (receiver instanceof BroadcastFilter) {
            mRunningColdStart = null;
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_SKIPPED,
                    "BroadcastFilter for cold app");
            return;
        }

        final ApplicationInfo info = ((ResolveInfo) receiver).activityInfo.applicationInfo;
        final ComponentName component = ((ResolveInfo) receiver).activityInfo.getComponentName();

        final int intentFlags = r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND;
        final HostingRecord hostingRecord = new HostingRecord(HostingRecord.HOSTING_TYPE_BROADCAST,
                component, r.intent.getAction(), r.getHostingRecordTriggerType());
        final boolean isActivityCapable = (r.options != null
                && r.options.getTemporaryAppAllowlistDuration() > 0);
        final int zygotePolicyFlags = isActivityCapable ? ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE
                : ZYGOTE_POLICY_FLAG_EMPTY;
        final boolean allowWhileBooting = (r.intent.getFlags()
                & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0;

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to cold " + queue);
        queue.app = mService.startProcessLocked(queue.processName, info, true, intentFlags,
                hostingRecord, zygotePolicyFlags, allowWhileBooting, false);
        if (queue.app == null) {
            mRunningColdStart = null;
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_FAILURE,
                    "startProcessLocked failed");
            return;
        }
    }

    /**
     * Schedule the currently active broadcast on the given queue when we know
     * the process is warm.
     * <p>
     * There is a <em>very strong</em> preference to consistently handle all
     * results by calling through to {@link #finishReceiverLocked}, both in the
     * case where a broadcast is handled by a remote app, and the case where the
     * broadcast was finished locally without the remote app being involved.
     */
    private void scheduleReceiverWarmLocked(@NonNull BroadcastProcessQueue queue) {
        checkState(queue.isActive(), "isActive");

        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        if (r.terminalCount == 0) {
            r.dispatchTime = SystemClock.uptimeMillis();
            r.dispatchRealTime = SystemClock.elapsedRealtime();
            r.dispatchClockTime = System.currentTimeMillis();
        }

        // If someone already finished this broadcast, finish immediately
        final int oldDeliveryState = getDeliveryState(r, index);
        if (isDeliveryStateTerminal(oldDeliveryState)) {
            enqueueFinishReceiver(queue, oldDeliveryState, "already terminal state");
            return;
        }

        // Consider additional cases where we'd want to finish immediately
        if (app.isInFullBackup()) {
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_SKIPPED, "isInFullBackup");
            return;
        }
        if (mSkipPolicy.shouldSkip(r, receiver)) {
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_SKIPPED, "mSkipPolicy");
            return;
        }
        final Intent receiverIntent = r.getReceiverIntent(receiver);
        if (receiverIntent == null) {
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_SKIPPED, "getReceiverIntent");
            return;
        }

        // Ignore registered receivers from a previous PID
        if ((receiver instanceof BroadcastFilter)
                && ((BroadcastFilter) receiver).receiverList.pid != app.getPid()) {
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_SKIPPED,
                    "BroadcastFilter for mismatched PID");
            return;
        }

        // Skip ANR tracking early during boot, when requested, or when we
        // immediately assume delivery success
        final boolean assumeDelivered = (receiver instanceof BroadcastFilter) && !r.ordered;
        if (mService.mProcessesReady && !r.timeoutExempt && !assumeDelivered) {
            queue.lastCpuDelayTime = queue.app.getCpuDelayTime();

            final long timeout = r.isForeground() ? mFgConstants.TIMEOUT : mBgConstants.TIMEOUT;
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_DELIVERY_TIMEOUT_SOFT, queue), timeout);
        }

        if (r.allowBackgroundActivityStarts) {
            app.addOrUpdateAllowBackgroundActivityStartsToken(r, r.mBackgroundActivityStartsToken);

            final long timeout = r.isForeground() ? mFgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT
                    : mBgConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT;
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = app;
            args.arg2 = r;
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_BG_ACTIVITY_START_TIMEOUT, args), timeout);
        }

        if (r.options != null && r.options.getTemporaryAppAllowlistDuration() > 0) {
            mService.tempAllowlistUidLocked(queue.uid,
                    r.options.getTemporaryAppAllowlistDuration(),
                    r.options.getTemporaryAppAllowlistReasonCode(), r.toShortString(),
                    r.options.getTemporaryAppAllowlistType(), r.callingUid);
        }

        if (DEBUG_BROADCAST) logv("Scheduling " + r + " to warm " + app);
        setDeliveryState(queue, app, r, index, receiver, BroadcastRecord.DELIVERY_SCHEDULED,
                "scheduleReceiverWarmLocked");

        final IApplicationThread thread = app.getOnewayThread();
        if (thread != null) {
            try {
                if (receiver instanceof BroadcastFilter) {
                    notifyScheduleRegisteredReceiver(app, r, (BroadcastFilter) receiver);
                    thread.scheduleRegisteredReceiver(
                            ((BroadcastFilter) receiver).receiverList.receiver, receiverIntent,
                            r.resultCode, r.resultData, r.resultExtras, r.ordered, r.initialSticky,
                            r.userId, app.mState.getReportedProcState());

                    // TODO: consider making registered receivers of unordered
                    // broadcasts report results to detect ANRs
                    if (assumeDelivered) {
                        enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_DELIVERED,
                                "assuming delivered");
                    }
                } else {
                    notifyScheduleReceiver(app, r, (ResolveInfo) receiver);
                    thread.scheduleReceiver(receiverIntent, ((ResolveInfo) receiver).activityInfo,
                            null, r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId,
                            app.mState.getReportedProcState());
                }
            } catch (RemoteException e) {
                final String msg = "Failed to schedule " + r + " to " + receiver
                        + " via " + app + ": " + e;
                logw(msg);
                app.scheduleCrashLocked(msg, CannotDeliverBroadcastException.TYPE_ID, null);
                app.setKilled(true);
                enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_FAILURE, "remote app");
            }
        } else {
            enqueueFinishReceiver(queue, BroadcastRecord.DELIVERY_FAILURE,
                    "missing IApplicationThread");
        }
    }

    /**
     * Schedule the final {@link BroadcastRecord#resultTo} delivery for an
     * ordered broadcast; assumes the sender is still a warm process.
     */
    private void scheduleResultTo(@NonNull BroadcastRecord r) {
        if (r.resultTo == null) return;
        final ProcessRecord app = r.resultToApp;
        final IApplicationThread thread = (app != null) ? app.getOnewayThread() : null;
        if (thread != null) {
            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(
                    app, OOM_ADJ_REASON_FINISH_RECEIVER);
            try {
                thread.scheduleRegisteredReceiver(r.resultTo, r.intent,
                        r.resultCode, r.resultData, r.resultExtras, false, r.initialSticky,
                        r.userId, app.mState.getReportedProcState());
            } catch (RemoteException e) {
                final String msg = "Failed to schedule result of " + r + " via " + app + ": " + e;
                logw(msg);
                app.scheduleCrashLocked(msg, CannotDeliverBroadcastException.TYPE_ID, null);
            }
        }
        // Clear so both local and remote references can be GC'ed
        r.resultTo = null;
    }

    private void deliveryTimeoutSoftLocked(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            // Instead of immediately triggering an ANR, extend the timeout by
            // the amount of time the process was runnable-but-waiting; we're
            // only willing to do this once before triggering an hard ANR
            final long cpuDelayTime = queue.app.getCpuDelayTime() - queue.lastCpuDelayTime;
            final long timeout = MathUtils.constrain(cpuDelayTime, 0, mConstants.TIMEOUT);
            mLocalHandler.sendMessageDelayed(
                    Message.obtain(mLocalHandler, MSG_DELIVERY_TIMEOUT_HARD, queue), timeout);
        } else {
            deliveryTimeoutHardLocked(queue);
        }
    }

    private void deliveryTimeoutHardLocked(@NonNull BroadcastProcessQueue queue) {
        finishReceiverLocked(queue, BroadcastRecord.DELIVERY_TIMEOUT,
                "deliveryTimeoutHardLocked");
    }

    @Override
    public boolean finishReceiverLocked(@NonNull ProcessRecord app, int resultCode,
            @Nullable String resultData, @Nullable Bundle resultExtras, boolean resultAbort,
            boolean waitForServices) {
        final BroadcastProcessQueue queue = getProcessQueue(app);
        if ((queue == null) || !queue.isActive()) {
            logw("Ignoring finish; no active broadcast for " + queue);
            return false;
        }

        final BroadcastRecord r = queue.getActive();
        if (r.ordered) {
            r.resultCode = resultCode;
            r.resultData = resultData;
            r.resultExtras = resultExtras;
            if (!r.isNoAbort()) {
                r.resultAbort = resultAbort;
            }

            // When the caller aborted an ordered broadcast, we mark all
            // remaining receivers as skipped
            if (r.resultAbort) {
                for (int i = r.terminalCount + 1; i < r.receivers.size(); i++) {
                    setDeliveryState(null, null, r, i, r.receivers.get(i),
                            BroadcastRecord.DELIVERY_SKIPPED, "resultAbort");
                }
            }
        }

        return finishReceiverLocked(queue, BroadcastRecord.DELIVERY_DELIVERED, "remote app");
    }

    private boolean finishReceiverLocked(@NonNull BroadcastProcessQueue queue,
            @DeliveryState int deliveryState, @NonNull String reason) {
        if (!queue.isActive()) {
            logw("Ignoring finish; no active broadcast for " + queue);
            return false;
        }

        final int cookie = traceBegin("finishReceiver");
        final ProcessRecord app = queue.app;
        final BroadcastRecord r = queue.getActive();
        final int index = queue.getActiveIndex();
        final Object receiver = r.receivers.get(index);

        setDeliveryState(queue, app, r, index, receiver, deliveryState, reason);

        if (deliveryState == BroadcastRecord.DELIVERY_TIMEOUT) {
            r.anrCount++;
            if (app != null && !app.isDebugging()) {
                mService.appNotResponding(queue.app, TimeoutRecord
                        .forBroadcastReceiver("Broadcast of " + r.toShortString()));
            }
        } else {
            mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT_SOFT, queue);
            mLocalHandler.removeMessages(MSG_DELIVERY_TIMEOUT_HARD, queue);
        }

        // If we've made reasonable progress, periodically retire ourselves to
        // avoid starvation of other processes and stack overflow when a
        // broadcast is immediately finished without waiting
        final boolean shouldRetire =
                (queue.getActiveCountSinceIdle() >= mConstants.MAX_RUNNING_ACTIVE_BROADCASTS);

        final boolean res;
        if (queue.isRunnable() && queue.isProcessWarm() && !shouldRetire) {
            // We're on a roll; move onto the next broadcast for this process
            queue.makeActiveNextPending();
            scheduleReceiverWarmLocked(queue);
            res = true;
        } else {
            // We've drained running broadcasts; maybe move back to runnable
            queue.makeActiveIdle();
            queue.traceProcessEnd();

            final int queueIndex = getRunningIndexOf(queue);
            mRunning[queueIndex] = null;
            updateRunnableList(queue);
            enqueueUpdateRunningList();

            // Tell other OS components that app is not actively running, giving
            // a chance to update OOM adjustment
            notifyStoppedRunning(queue);
            res = false;
        }
        traceEnd(cookie);
        return res;
    }

    /**
     * Set the delivery state on the given broadcast, then apply any additional
     * bookkeeping related to ordered broadcasts.
     */
    private void setDeliveryState(@Nullable BroadcastProcessQueue queue,
            @Nullable ProcessRecord app, @NonNull BroadcastRecord r, int index,
            @NonNull Object receiver, @DeliveryState int newDeliveryState, String reason) {
        final int cookie = traceBegin("setDeliveryState");
        final int oldDeliveryState = getDeliveryState(r, index);

        // Only apply state when we haven't already reached a terminal state;
        // this is how we ignore racing timeout messages
        if (!isDeliveryStateTerminal(oldDeliveryState)) {
            r.setDeliveryState(index, newDeliveryState);
        }

        // Emit any relevant tracing results when we're changing the delivery
        // state as part of running from a queue
        if (queue != null) {
            if (newDeliveryState == BroadcastRecord.DELIVERY_SCHEDULED) {
                queue.traceActiveBegin();
            } else if ((oldDeliveryState == BroadcastRecord.DELIVERY_SCHEDULED)
                    && isDeliveryStateTerminal(newDeliveryState)) {
                queue.traceActiveEnd();
            }
        }

        // If we're moving into a terminal state, we might have internal
        // bookkeeping to update for ordered broadcasts
        if (!isDeliveryStateTerminal(oldDeliveryState)
                && isDeliveryStateTerminal(newDeliveryState)) {
            if (newDeliveryState != BroadcastRecord.DELIVERY_DELIVERED) {
                logw("Delivery state of " + r + " to " + receiver
                        + " via " + app + " changed from "
                        + deliveryStateToString(oldDeliveryState) + " to "
                        + deliveryStateToString(newDeliveryState) + " because " + reason);
            }

            r.terminalCount++;
            notifyFinishReceiver(queue, r, index, receiver);

            // When entire ordered broadcast finished, deliver final result
            final boolean recordFinished = (r.terminalCount == r.receivers.size());
            if (recordFinished) {
                scheduleResultTo(r);
            }

            // Our terminal state here might be enough for another process
            // blocked on us to now be runnable
            if (r.ordered || r.prioritized) {
                for (int i = 0; i < r.receivers.size(); i++) {
                    if (!isDeliveryStateTerminal(getDeliveryState(r, i)) || (i == index)) {
                        final Object otherReceiver = r.receivers.get(i);
                        final BroadcastProcessQueue otherQueue = getProcessQueue(
                                getReceiverProcessName(otherReceiver),
                                getReceiverUid(otherReceiver));
                        if (otherQueue != null) {
                            otherQueue.invalidateRunnableAt();
                            updateRunnableList(otherQueue);
                        }
                    }
                }
                enqueueUpdateRunningList();
            }
        }

        traceEnd(cookie);
    }

    private @DeliveryState int getDeliveryState(@NonNull BroadcastRecord r, int index) {
        return r.getDeliveryState(index);
    }

    @Override
    public boolean cleanupDisabledPackageReceiversLocked(@Nullable String packageName,
            @Nullable Set<String> filterByClasses, int userId) {
        final Predicate<BroadcastProcessQueue> queuePredicate;
        final BroadcastPredicate broadcastPredicate;
        if (packageName != null) {
            // Caller provided a package and user ID, so we're focused on queues
            // belonging to a specific UID
            final int uid = mService.mPackageManagerInt.getPackageUid(
                    packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            queuePredicate = (q) -> {
                return q.uid == uid;
            };

            // If caller provided a set of classes, filter to skip only those;
            // otherwise we skip all broadcasts
            if (filterByClasses != null) {
                broadcastPredicate = (r, i) -> {
                    final Object receiver = r.receivers.get(i);
                    if (receiver instanceof ResolveInfo) {
                        final ActivityInfo info = ((ResolveInfo) receiver).activityInfo;
                        return packageName.equals(info.packageName)
                                && filterByClasses.contains(info.name);
                    } else {
                        return false;
                    }
                };
            } else {
                broadcastPredicate = (r, i) -> {
                    final Object receiver = r.receivers.get(i);
                    return packageName.equals(getReceiverPackageName(receiver));
                };
            }
        } else {
            // Caller is cleaning up an entire user ID; skip all broadcasts
            queuePredicate = (q) -> {
                return UserHandle.getUserId(q.uid) == userId;
            };
            broadcastPredicate = BROADCAST_PREDICATE_ANY;
        }
        return forEachMatchingBroadcast(queuePredicate, broadcastPredicate,
                mBroadcastConsumerSkip, true);
    }

    private static final Predicate<BroadcastProcessQueue> QUEUE_PREDICATE_ANY =
            (q) -> true;
    private static final BroadcastPredicate BROADCAST_PREDICATE_ANY =
            (r, i) -> true;

    /**
     * Typical consumer that will skip the given broadcast, usually as a result
     * of it matching a predicate.
     */
    private final BroadcastConsumer mBroadcastConsumerSkip = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_SKIPPED,
                "mBroadcastConsumerSkip");
    };

    /**
     * Typical consumer that will both skip the given broadcast and mark it as
     * cancelled, usually as a result of it matching a predicate.
     */
    private final BroadcastConsumer mBroadcastConsumerSkipAndCanceled = (r, i) -> {
        setDeliveryState(null, null, r, i, r.receivers.get(i), BroadcastRecord.DELIVERY_SKIPPED,
                "mBroadcastConsumerSkipAndCanceled");
        r.resultCode = Activity.RESULT_CANCELED;
        r.resultData = null;
        r.resultExtras = null;
    };

    /**
     * Verify that all known {@link #mProcessQueues} are in the state tested by
     * the given {@link Predicate}.
     */
    private boolean testAllProcessQueues(@NonNull Predicate<BroadcastProcessQueue> test,
            @NonNull String label, @Nullable PrintWriter pw) {
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (!test.test(leaf)) {
                    final long now = SystemClock.uptimeMillis();
                    if (now > mLastTestFailureTime + DateUtils.SECOND_IN_MILLIS) {
                        mLastTestFailureTime = now;
                        logv("Test " + label + " failed due to " + leaf.toShortString(), pw);
                    }
                    return false;
                }
                leaf = leaf.processNameNext;
            }
        }
        logv("Test " + label + " passed", pw);
        return true;
    }

    private boolean forEachMatchingBroadcast(
            @NonNull Predicate<BroadcastProcessQueue> queuePredicate,
            @NonNull BroadcastPredicate broadcastPredicate,
            @NonNull BroadcastConsumer broadcastConsumer, boolean andRemove) {
        boolean didSomething = false;
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                if (queuePredicate.test(leaf)) {
                    if (leaf.forEachMatchingBroadcast(broadcastPredicate,
                            broadcastConsumer, andRemove)) {
                        updateRunnableList(leaf);
                        didSomething = true;
                    }
                }
                leaf = leaf.processNameNext;
            }
        }
        if (didSomething) {
            enqueueUpdateRunningList();
        }
        return didSomething;
    }

    @Override
    public void start(@NonNull ContentResolver resolver) {
        mFgConstants.startObserving(mHandler, resolver);
        mBgConstants.startObserving(mHandler, resolver);

        mService.registerUidObserver(new UidObserver() {
            @Override
            public void onUidCachedChanged(int uid, boolean cached) {
                synchronized (mService) {
                    BroadcastProcessQueue leaf = mProcessQueues.get(uid);
                    while (leaf != null) {
                        leaf.setProcessCached(cached);
                        updateRunnableList(leaf);
                        leaf = leaf.processNameNext;
                    }
                    enqueueUpdateRunningList();
                }
            }
        }, ActivityManager.UID_OBSERVER_CACHED, 0, "android");

        // Kick off periodic health checks
        checkHealthLocked();
    }

    @Override
    public boolean isIdleLocked() {
        return isIdleLocked(null);
    }

    public boolean isIdleLocked(@Nullable PrintWriter pw) {
        return testAllProcessQueues(q -> q.isIdle(), "idle", pw);
    }

    @Override
    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime) {
        return isBeyondBarrierLocked(barrierTime, null);
    }

    public boolean isBeyondBarrierLocked(@UptimeMillisLong long barrierTime,
            @Nullable PrintWriter pw) {
        return testAllProcessQueues(q -> q.isBeyondBarrierLocked(barrierTime), "barrier", pw);
    }

    @Override
    public void waitForIdle(@Nullable PrintWriter pw) {
        waitFor(() -> isIdleLocked(pw));
    }

    @Override
    public void waitForBarrier(@Nullable PrintWriter pw) {
        final long now = SystemClock.uptimeMillis();
        waitFor(() -> isBeyondBarrierLocked(now, pw));
    }

    public void waitFor(@NonNull BooleanSupplier condition) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (mService) {
            mWaitingFor.add(Pair.create(condition, latch));
        }
        enqueueUpdateRunningList();
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String describeStateLocked() {
        return getRunningSize() + " running";
    }

    @Override
    public boolean isDelayBehindServices() {
        // TODO: implement
        return false;
    }

    @Override
    public void backgroundServicesFinishedLocked(int userId) {
        // TODO: implement
    }

    /**
     * Check overall health, confirming things are in a reasonable state and
     * that we're not wedged. If we determine we're in an unhealthy state, dump
     * current state once and stop future health checks to avoid spamming.
     */
    @VisibleForTesting
    void checkHealthLocked() {
        try {
            // Verify all runnable queues are sorted
            BroadcastProcessQueue prev = null;
            BroadcastProcessQueue next = mRunnableHead;
            while (next != null) {
                checkState(next.runnableAtPrev == prev, "runnableAtPrev");
                checkState(next.isRunnable(), "isRunnable " + next);
                if (prev != null) {
                    checkState(next.getRunnableAt() >= prev.getRunnableAt(),
                            "getRunnableAt " + next + " vs " + prev);
                }
                prev = next;
                next = next.runnableAtNext;
            }

            // Verify all running queues are active
            for (BroadcastProcessQueue queue : mRunning) {
                if (queue != null) {
                    checkState(queue.isActive(), "isActive " + queue);
                }
            }

            // Verify that pending cold start hasn't been orphaned
            if (mRunningColdStart != null) {
                checkState(getRunningIndexOf(mRunningColdStart) >= 0,
                        "isOrphaned " + mRunningColdStart);
            }

            // Verify health of all known process queues
            for (int i = 0; i < mProcessQueues.size(); i++) {
                BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
                while (leaf != null) {
                    leaf.checkHealthLocked();
                    leaf = leaf.processNameNext;
                }
            }

            // If no health issues found above, check again in the future
            mLocalHandler.sendEmptyMessageDelayed(MSG_CHECK_HEALTH, DateUtils.MINUTE_IN_MILLIS);

        } catch (Exception e) {
            // Throw up a message to indicate that something went wrong, and
            // dump current state for later inspection
            Slog.wtf(TAG, e);
            dumpToDropBoxLocked(e.toString());
        }
    }

    private void updateWarmProcess(@NonNull BroadcastProcessQueue queue) {
        if (!queue.isProcessWarm()) {
            queue.setProcess(mService.getProcessRecordLocked(queue.processName, queue.uid));
        }
    }

    /**
     * Inform other parts of OS that the given broadcast queue has started
     * running, typically for internal bookkeeping.
     */
    private void notifyStartedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            queue.app.mReceivers.incrementCurReceivers();

            // Don't bump its LRU position if it's in the background restricted.
            if (mService.mInternal.getRestrictionLevel(
                    queue.uid) < ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                mService.updateLruProcessLocked(queue.app, false, null);
            }

            mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(queue.app,
                    OOM_ADJ_REASON_START_RECEIVER);

            if (queue.runningOomAdjusted) {
                queue.app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_RECEIVER);
                mService.enqueueOomAdjTargetLocked(queue.app);
            }
        }
    }

    /**
     * Inform other parts of OS that the given broadcast queue has stopped
     * running, typically for internal bookkeeping.
     */
    private void notifyStoppedRunning(@NonNull BroadcastProcessQueue queue) {
        if (queue.app != null) {
            queue.app.mReceivers.decrementCurReceivers();

            if (queue.runningOomAdjusted) {
                mService.enqueueOomAdjTargetLocked(queue.app);
            }
        }
    }

    /**
     * Inform other parts of OS that the given broadcast was just scheduled for
     * a registered receiver, typically for internal bookkeeping.
     */
    private void notifyScheduleRegisteredReceiver(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r, @NonNull BroadcastFilter receiver) {
        reportUsageStatsBroadcastDispatched(app, r);
    }

    /**
     * Inform other parts of OS that the given broadcast was just scheduled for
     * a manifest receiver, typically for internal bookkeeping.
     */
    private void notifyScheduleReceiver(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r, @NonNull ResolveInfo receiver) {
        reportUsageStatsBroadcastDispatched(app, r);

        final String receiverPackageName = receiver.activityInfo.packageName;
        app.addPackage(receiverPackageName,
                receiver.activityInfo.applicationInfo.longVersionCode, mService.mProcessStats);

        final boolean targetedBroadcast = r.intent.getComponent() != null;
        final boolean targetedSelf = Objects.equals(r.callerPackage, receiverPackageName);
        if (targetedBroadcast && !targetedSelf) {
            mService.mUsageStatsService.reportEvent(receiverPackageName,
                    r.userId, Event.APP_COMPONENT_USED);
        }

        mService.notifyPackageUse(receiverPackageName,
                PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER);

        mService.mPackageManagerInt.setPackageStoppedState(
                receiverPackageName, false, r.userId);
    }

    private void reportUsageStatsBroadcastDispatched(@NonNull ProcessRecord app,
            @NonNull BroadcastRecord r) {
        final long idForResponseEvent = (r.options != null)
                ? r.options.getIdForResponseEvent() : 0L;
        if (idForResponseEvent <= 0) return;

        final String targetPackage;
        if (r.intent.getPackage() != null) {
            targetPackage = r.intent.getPackage();
        } else if (r.intent.getComponent() != null) {
            targetPackage = r.intent.getComponent().getPackageName();
        } else {
            targetPackage = null;
        }
        if (targetPackage == null) return;

        mService.mUsageStatsService.reportBroadcastDispatched(r.callingUid, targetPackage,
                UserHandle.of(r.userId), idForResponseEvent, SystemClock.elapsedRealtime(),
                mService.getUidStateLocked(app.uid));
    }

    /**
     * Inform other parts of OS that the given broadcast was just finished,
     * typically for internal bookkeeping.
     */
    private void notifyFinishReceiver(@Nullable BroadcastProcessQueue queue,
            @NonNull BroadcastRecord r, int index, @NonNull Object receiver) {
        // Report statistics for each individual receiver
        final int uid = getReceiverUid(receiver);
        final int senderUid = (r.callingUid == -1) ? Process.SYSTEM_UID : r.callingUid;
        final String actionName = r.intent.getAction();
        final int receiverType = (receiver instanceof BroadcastFilter)
                ? BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__RUNTIME
                : BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
        final int type;
        if (queue == null) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_UNKNOWN;
        } else if (queue.getActiveViaColdStart()) {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
        } else {
            type = BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
        }
        // With the new per-process queues, there's no delay between being
        // "dispatched" and "scheduled", so we report no "receive delay"
        final long dispatchDelay = r.scheduledTime[index] - r.enqueueTime;
        final long receiveDelay = 0;
        final long finishDelay = r.terminalTime[index] - r.scheduledTime[index];
        FrameworkStatsLog.write(BROADCAST_DELIVERY_EVENT_REPORTED, uid, senderUid, actionName,
                receiverType, type, dispatchDelay, receiveDelay, finishDelay);

        final boolean recordFinished = (r.terminalCount == r.receivers.size());
        if (recordFinished) {
            mService.notifyBroadcastFinishedLocked(r);
            mHistory.addBroadcastToHistoryLocked(r);

            r.finishTime = SystemClock.uptimeMillis();
            r.nextReceiver = r.receivers.size();
            BroadcastQueueImpl.logBootCompletedBroadcastCompletionLatencyIfPossible(r);

            if (r.intent.getComponent() == null && r.intent.getPackage() == null
                    && (r.intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                int manifestCount = 0;
                int manifestSkipCount = 0;
                for (int i = 0; i < r.receivers.size(); i++) {
                    if (r.receivers.get(i) instanceof ResolveInfo) {
                        manifestCount++;
                        if (r.delivery[i] == BroadcastRecord.DELIVERY_SKIPPED) {
                            manifestSkipCount++;
                        }
                    }
                }

                final long dispatchTime = SystemClock.uptimeMillis() - r.enqueueTime;
                mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                        manifestCount, manifestSkipCount, dispatchTime);
            }
        }
    }

    @VisibleForTesting
    @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull ProcessRecord app) {
        return getOrCreateProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @NonNull BroadcastProcessQueue getOrCreateProcessQueue(@NonNull String processName,
            int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            } else if (leaf.processNameNext == null) {
                break;
            }
            leaf = leaf.processNameNext;
        }

        BroadcastProcessQueue created = new BroadcastProcessQueue(mConstants, processName, uid);
        created.app = mService.getProcessRecordLocked(processName, uid);

        if (leaf == null) {
            mProcessQueues.put(uid, created);
        } else {
            leaf.processNameNext = created;
        }
        return created;
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue getProcessQueue(@NonNull ProcessRecord app) {
        return getProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue getProcessQueue(@NonNull String processName, int uid) {
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                return leaf;
            }
            leaf = leaf.processNameNext;
        }
        return null;
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue removeProcessQueue(@NonNull ProcessRecord app) {
        return removeProcessQueue(app.processName, app.info.uid);
    }

    @VisibleForTesting
    @Nullable BroadcastProcessQueue removeProcessQueue(@NonNull String processName,
            int uid) {
        BroadcastProcessQueue prev = null;
        BroadcastProcessQueue leaf = mProcessQueues.get(uid);
        while (leaf != null) {
            if (Objects.equals(leaf.processName, processName)) {
                if (prev != null) {
                    prev.processNameNext = leaf.processNameNext;
                } else {
                    if (leaf.processNameNext != null) {
                        mProcessQueues.put(uid, leaf.processNameNext);
                    } else {
                        mProcessQueues.remove(uid);
                    }
                }
                return leaf;
            }
            prev = leaf;
            leaf = leaf.processNameNext;
        }
        return null;
    }

    @Override
    @NeverCompile
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastQueueProto.QUEUE_NAME, mQueueName);
        mHistory.dumpDebug(proto);
        proto.end(token);
    }

    @Override
    @NeverCompile
    public boolean dumpLocked(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, int opti, boolean dumpConstants, boolean dumpHistory,
            boolean dumpAll, @Nullable String dumpPackage, boolean needSep) {
        final long now = SystemClock.uptimeMillis();
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();
        ipw.println();

        ipw.println("📋 Per-process queues:");
        ipw.increaseIndent();
        for (int i = 0; i < mProcessQueues.size(); i++) {
            BroadcastProcessQueue leaf = mProcessQueues.valueAt(i);
            while (leaf != null) {
                leaf.dumpLocked(now, ipw);
                leaf = leaf.processNameNext;
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("🧍 Runnable:");
        ipw.increaseIndent();
        if (mRunnableHead == null) {
            ipw.println("(none)");
        } else {
            BroadcastProcessQueue queue = mRunnableHead;
            while (queue != null) {
                TimeUtils.formatDuration(queue.getRunnableAt(), now, ipw);
                ipw.print(' ');
                ipw.print(reasonToString(queue.getRunnableAtReason()));
                ipw.print(' ');
                ipw.print(queue.toShortString());
                ipw.println();
                queue = queue.runnableAtNext;
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        ipw.println("🏃 Running:");
        ipw.increaseIndent();
        for (BroadcastProcessQueue queue : mRunning) {
            if ((queue != null) && (queue == mRunningColdStart)) {
                ipw.print("🥶 ");
            } else {
                ipw.print("\u3000 ");
            }
            if (queue != null) {
                ipw.println(queue.toShortString());
            } else {
                ipw.println("(none)");
            }
        }
        ipw.decreaseIndent();
        ipw.println();

        if (dumpConstants) {
            mConstants.dump(ipw);
        }
        if (dumpHistory) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            needSep = mHistory.dumpLocked(ipw, dumpPackage, mQueueName, sdf, dumpAll, needSep);
        }
        return needSep;
    }
}
