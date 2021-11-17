/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.text.format.DateUtils.DAY_IN_MILLIS;

import static com.android.server.tare.EconomicPolicy.REGULATION_BASIC_INCOME;
import static com.android.server.tare.EconomicPolicy.REGULATION_BIRTHRIGHT;
import static com.android.server.tare.EconomicPolicy.REGULATION_DEMOTION;
import static com.android.server.tare.EconomicPolicy.REGULATION_PROMOTION;
import static com.android.server.tare.EconomicPolicy.REGULATION_WEALTH_RECLAMATION;
import static com.android.server.tare.EconomicPolicy.TYPE_ACTION;
import static com.android.server.tare.EconomicPolicy.TYPE_REWARD;
import static com.android.server.tare.EconomicPolicy.eventToString;
import static com.android.server.tare.EconomicPolicy.getEventType;
import static com.android.server.tare.TareUtils.appToString;
import static com.android.server.tare.TareUtils.getCurrentTimeMillis;
import static com.android.server.tare.TareUtils.narcToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.utils.AlarmQueue;

import libcore.util.EmptyArray;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Other half of the IRS. The agent handles the nitty gritty details, interacting directly with
 * ledgers, carrying out specific events such as wealth reclamation, granting initial balances or
 * replenishing balances, and tracking ongoing events.
 */
class Agent {
    private static final String TAG = "TARE-" + Agent.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private static final String ALARM_TAG_AFFORDABILITY_CHECK = "*tare.affordability_check*";

    private final Object mLock;
    private final Handler mHandler;
    private final InternalResourceService mIrs;
    private final Scribe mScribe;

    private final AppStandbyInternal mAppStandbyInternal;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, SparseArrayMap<String, OngoingEvent>>
            mCurrentOngoingEvents = new SparseArrayMap<>();

    /**
     * Set of {@link ActionAffordabilityNote ActionAffordabilityNotes} keyed by userId-pkgName.
     *
     * Note: it would be nice/better to sort by base price since that doesn't change and simply
     * look at the change in the "insertion" of what would be affordable, but since CTP
     * is factored into the final price, the sorting order (by modified price) could be different
     * and that method wouldn't work >:(
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, ArraySet<ActionAffordabilityNote>>
            mActionAffordabilityNotes = new SparseArrayMap<>();

    /**
     * Queue to track and manage when apps will cross the closest affordability threshold (in
     * both directions).
     */
    @GuardedBy("mLock")
    private final BalanceThresholdAlarmQueue mBalanceThresholdAlarmQueue;

    /**
     * Comparator to use to sort apps before we distribute ARCs so that we try to give the most
     * important apps ARCs first.
     */
    @VisibleForTesting
    final Comparator<PackageInfo> mPackageDistributionComparator =
            new Comparator<PackageInfo>() {
                @Override
                public int compare(PackageInfo pi1, PackageInfo pi2) {
                    final ApplicationInfo appInfo1 = pi1.applicationInfo;
                    final ApplicationInfo appInfo2 = pi2.applicationInfo;
                    // Put any packages that don't declare an application at the end. A missing
                    // <application> tag likely means the app won't be doing any work anyway.
                    if (appInfo1 == null) {
                        if (appInfo2 == null) {
                            return 0;
                        }
                        return 1;
                    } else if (appInfo2 == null) {
                        return -1;
                    }
                    // Privileged apps eat first. They're likely required for the device to
                    // function properly.
                    // TODO: include headless system apps
                    if (appInfo1.isPrivilegedApp()) {
                        if (!appInfo2.isPrivilegedApp()) {
                            return -1;
                        }
                    } else if (appInfo2.isPrivilegedApp()) {
                        return 1;
                    }

                    // Sort by most recently used.
                    final long timeSinceLastUsedMs1 =
                            mAppStandbyInternal.getTimeSinceLastUsedByUser(
                                    pi1.packageName, UserHandle.getUserId(pi1.applicationInfo.uid));
                    final long timeSinceLastUsedMs2 =
                            mAppStandbyInternal.getTimeSinceLastUsedByUser(
                                    pi2.packageName, UserHandle.getUserId(pi2.applicationInfo.uid));
                    if (timeSinceLastUsedMs1 < timeSinceLastUsedMs2) {
                        return -1;
                    } else if (timeSinceLastUsedMs1 > timeSinceLastUsedMs2) {
                        return 1;
                    }
                    return 0;
                }
            };

    private static final int MSG_CHECK_BALANCE = 0;

    Agent(@NonNull InternalResourceService irs, @NonNull Scribe scribe) {
        mLock = irs.getLock();
        mIrs = irs;
        mScribe = scribe;
        mHandler = new AgentHandler(TareHandlerThread.get().getLooper());
        mAppStandbyInternal = LocalServices.getService(AppStandbyInternal.class);
        mBalanceThresholdAlarmQueue = new BalanceThresholdAlarmQueue(
                mIrs.getContext(), TareHandlerThread.get().getLooper());
    }

    private class TotalDeltaCalculator implements Consumer<OngoingEvent> {
        private Ledger mLedger;
        private long mNowElapsed;
        private long mNow;
        private long mTotal;

        void reset(@NonNull Ledger ledger, long nowElapsed, long now) {
            mLedger = ledger;
            mNowElapsed = nowElapsed;
            mNow = now;
            mTotal = 0;
        }

        @Override
        public void accept(OngoingEvent ongoingEvent) {
            mTotal += getActualDeltaLocked(ongoingEvent, mLedger, mNowElapsed, mNow);
        }
    }

    @GuardedBy("mLock")
    private final TotalDeltaCalculator mTotalDeltaCalculator = new TotalDeltaCalculator();

    /** Get an app's current balance, factoring in any currently ongoing events. */
    @GuardedBy("mLock")
    long getBalanceLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
        long balance = ledger.getCurrentBalance();
        SparseArrayMap<String, OngoingEvent> ongoingEvents =
                mCurrentOngoingEvents.get(userId, pkgName);
        if (ongoingEvents != null) {
            final long nowElapsed = SystemClock.elapsedRealtime();
            final long now = getCurrentTimeMillis();
            mTotalDeltaCalculator.reset(ledger, nowElapsed, now);
            ongoingEvents.forEach(mTotalDeltaCalculator);
            balance += mTotalDeltaCalculator.mTotal;
        }
        return balance;
    }

    @GuardedBy("mLock")
    void noteInstantaneousEventLocked(final int userId, @NonNull final String pkgName,
            final int eventId, @Nullable String tag) {
        if (mIrs.isSystem(userId, pkgName)) {
            // Events are free for the system. Don't bother recording them.
            return;
        }

        final long now = getCurrentTimeMillis();
        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();

        final int eventType = getEventType(eventId);
        switch (eventType) {
            case TYPE_ACTION:
                final long actionCost = economicPolicy.getCostOfAction(eventId, userId, pkgName);

                recordTransactionLocked(userId, pkgName, ledger,
                        new Ledger.Transaction(now, now, eventId, tag, -actionCost), true);
                break;

            case TYPE_REWARD:
                final EconomicPolicy.Reward reward = economicPolicy.getReward(eventId);
                if (reward != null) {
                    final long rewardSum = ledger.get24HourSum(eventId, now);
                    final long rewardVal = Math.max(0,
                            Math.min(reward.maxDailyReward - rewardSum, reward.instantReward));
                    recordTransactionLocked(userId, pkgName, ledger,
                            new Ledger.Transaction(now, now, eventId, tag, rewardVal), true);
                }
                break;

            default:
                Slog.w(TAG, "Unsupported event type: " + eventType);
        }
        scheduleBalanceCheckLocked(userId, pkgName);
    }

    @GuardedBy("mLock")
    void noteOngoingEventLocked(final int userId, @NonNull final String pkgName, final int eventId,
            @Nullable String tag, final long startElapsed) {
        noteOngoingEventLocked(userId, pkgName, eventId, tag, startElapsed, true);
    }

    @GuardedBy("mLock")
    private void noteOngoingEventLocked(final int userId, @NonNull final String pkgName,
            final int eventId, @Nullable String tag, final long startElapsed,
            final boolean updateBalanceCheck) {
        if (mIrs.isSystem(userId, pkgName)) {
            // Events are free for the system. Don't bother recording them.
            return;
        }

        SparseArrayMap<String, OngoingEvent> ongoingEvents =
                mCurrentOngoingEvents.get(userId, pkgName);
        if (ongoingEvents == null) {
            ongoingEvents = new SparseArrayMap<>();
            mCurrentOngoingEvents.add(userId, pkgName, ongoingEvents);
        }
        OngoingEvent ongoingEvent = ongoingEvents.get(eventId, tag);

        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();
        final int eventType = getEventType(eventId);
        switch (eventType) {
            case TYPE_ACTION:
                final long actionCost = economicPolicy.getCostOfAction(eventId, userId, pkgName);

                if (ongoingEvent == null) {
                    ongoingEvents.add(eventId, tag,
                            new OngoingEvent(eventId, tag, null, startElapsed, -actionCost));
                } else {
                    ongoingEvent.refCount++;
                }
                break;

            case TYPE_REWARD:
                final EconomicPolicy.Reward reward = economicPolicy.getReward(eventId);
                if (reward != null) {
                    if (ongoingEvent == null) {
                        ongoingEvents.add(eventId, tag, new OngoingEvent(
                                eventId, tag, reward, startElapsed, reward.ongoingRewardPerSecond));
                    } else {
                        ongoingEvent.refCount++;
                    }
                }
                break;

            default:
                Slog.w(TAG, "Unsupported event type: " + eventType);
        }

        if (updateBalanceCheck) {
            scheduleBalanceCheckLocked(userId, pkgName);
        }
    }

    @GuardedBy("mLock")
    void onDeviceStateChangedLocked() {
        onPricingChangedLocked();
    }

    @GuardedBy("mLock")
    void onPricingChangedLocked() {
        final long now = getCurrentTimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();

        mCurrentOngoingEvents.forEach((userId, pkgName, ongoingEvents) -> {
            final ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                    mActionAffordabilityNotes.get(userId, pkgName);
            final boolean[] wasAffordable;
            if (actionAffordabilityNotes != null) {
                final int size = actionAffordabilityNotes.size();
                wasAffordable = new boolean[size];
                for (int i = 0; i < size; ++i) {
                    final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(i);
                    final long originalBalance =
                            mScribe.getLedgerLocked(userId, pkgName).getCurrentBalance();
                    wasAffordable[i] = originalBalance >= note.getCachedModifiedPrice();
                }
            } else {
                wasAffordable = EmptyArray.BOOLEAN;
            }
            ongoingEvents.forEach((ongoingEvent) -> {
                // Disable balance check & affordability notifications here because we're in the
                // middle of updating ongoing action costs/prices and sending out notifications
                // or rescheduling the balance check alarm would be a waste since we'll have to
                // redo them again after all of our internal state is updated.
                stopOngoingActionLocked(userId, pkgName, ongoingEvent.eventId,
                        ongoingEvent.tag, nowElapsed, now, false, false);
                noteOngoingEventLocked(userId, pkgName, ongoingEvent.eventId, ongoingEvent.tag,
                        nowElapsed, false);
            });
            if (actionAffordabilityNotes != null) {
                final int size = actionAffordabilityNotes.size();
                for (int i = 0; i < size; ++i) {
                    final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(i);
                    note.recalculateModifiedPrice(economicPolicy, userId, pkgName);
                    final long newBalance =
                            mScribe.getLedgerLocked(userId, pkgName).getCurrentBalance();
                    final boolean isAffordable = newBalance >= note.getCachedModifiedPrice();
                    if (wasAffordable[i] != isAffordable) {
                        note.setNewAffordability(isAffordable);
                        mIrs.postAffordabilityChanged(userId, pkgName, note);
                    }
                }
            }
            scheduleBalanceCheckLocked(userId, pkgName);
        });
    }

    @GuardedBy("mLock")
    void onAppStatesChangedLocked(final int userId, @NonNull ArraySet<String> pkgNames) {
        final long now = getCurrentTimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();

        for (int i = 0; i < pkgNames.size(); ++i) {
            final String pkgName = pkgNames.valueAt(i);
            SparseArrayMap<String, OngoingEvent> ongoingEvents =
                    mCurrentOngoingEvents.get(userId, pkgName);
            if (ongoingEvents != null) {
                final ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                        mActionAffordabilityNotes.get(userId, pkgName);
                final boolean[] wasAffordable;
                if (actionAffordabilityNotes != null) {
                    final int size = actionAffordabilityNotes.size();
                    wasAffordable = new boolean[size];
                    for (int n = 0; n < size; ++n) {
                        final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(n);
                        final long originalBalance =
                                mScribe.getLedgerLocked(userId, pkgName).getCurrentBalance();
                        wasAffordable[n] = originalBalance >= note.getCachedModifiedPrice();
                    }
                } else {
                    wasAffordable = EmptyArray.BOOLEAN;
                }
                ongoingEvents.forEach((ongoingEvent) -> {
                    // Disable balance check & affordability notifications here because we're in the
                    // middle of updating ongoing action costs/prices and sending out notifications
                    // or rescheduling the balance check alarm would be a waste since we'll have to
                    // redo them again after all of our internal state is updated.
                    stopOngoingActionLocked(userId, pkgName, ongoingEvent.eventId,
                            ongoingEvent.tag, nowElapsed, now, false, false);
                    noteOngoingEventLocked(userId, pkgName, ongoingEvent.eventId, ongoingEvent.tag,
                            nowElapsed, false);
                });
                if (actionAffordabilityNotes != null) {
                    final int size = actionAffordabilityNotes.size();
                    for (int n = 0; n < size; ++n) {
                        final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(n);
                        note.recalculateModifiedPrice(economicPolicy, userId, pkgName);
                        final long newBalance =
                                mScribe.getLedgerLocked(userId, pkgName).getCurrentBalance();
                        final boolean isAffordable = newBalance >= note.getCachedModifiedPrice();
                        if (wasAffordable[n] != isAffordable) {
                            note.setNewAffordability(isAffordable);
                            mIrs.postAffordabilityChanged(userId, pkgName, note);
                        }
                    }
                }
                scheduleBalanceCheckLocked(userId, pkgName);
            }
        }
    }

    @GuardedBy("mLock")
    void stopOngoingActionLocked(final int userId, @NonNull final String pkgName, final int eventId,
            @Nullable String tag, final long nowElapsed, final long now) {
        stopOngoingActionLocked(userId, pkgName, eventId, tag, nowElapsed, now, true, true);
    }

    /**
     * @param updateBalanceCheck          Whether or not to reschedule the affordability/balance
     *                                    check alarm.
     * @param notifyOnAffordabilityChange Whether or not to evaluate the app's ability to afford
     *                                    registered bills and notify listeners about any changes.
     */
    @GuardedBy("mLock")
    private void stopOngoingActionLocked(final int userId, @NonNull final String pkgName,
            final int eventId, @Nullable String tag, final long nowElapsed, final long now,
            final boolean updateBalanceCheck, final boolean notifyOnAffordabilityChange) {
        if (mIrs.isSystem(userId, pkgName)) {
            // Events are free for the system. Don't bother recording them.
            return;
        }

        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);

        SparseArrayMap<String, OngoingEvent> ongoingEvents =
                mCurrentOngoingEvents.get(userId, pkgName);
        if (ongoingEvents == null) {
            // This may occur if TARE goes from disabled to enabled while an event is already
            // occurring.
            Slog.w(TAG, "No ongoing transactions for " + appToString(userId, pkgName));
            return;
        }
        final OngoingEvent ongoingEvent = ongoingEvents.get(eventId, tag);
        if (ongoingEvent == null) {
            // This may occur if TARE goes from disabled to enabled while an event is already
            // occurring.
            Slog.w(TAG, "Nonexistent ongoing transaction "
                    + eventToString(eventId) + (tag == null ? "" : ":" + tag)
                    + " for " + appToString(userId, pkgName) + " ended");
            return;
        }
        ongoingEvent.refCount--;
        if (ongoingEvent.refCount <= 0) {
            final long startElapsed = ongoingEvent.startTimeElapsed;
            final long startTime = now - (nowElapsed - startElapsed);
            final long actualDelta = getActualDeltaLocked(ongoingEvent, ledger, nowElapsed, now);
            recordTransactionLocked(userId, pkgName, ledger,
                    new Ledger.Transaction(startTime, now, eventId, tag, actualDelta),
                    notifyOnAffordabilityChange);

            ongoingEvents.delete(eventId, tag);
        }
        if (updateBalanceCheck) {
            scheduleBalanceCheckLocked(userId, pkgName);
        }
    }

    @GuardedBy("mLock")
    private long getActualDeltaLocked(@NonNull OngoingEvent ongoingEvent, @NonNull Ledger ledger,
            long nowElapsed, long now) {
        final long startElapsed = ongoingEvent.startTimeElapsed;
        final long durationSecs = (nowElapsed - startElapsed) / 1000;
        final long computedDelta = durationSecs * ongoingEvent.deltaPerSec;
        if (ongoingEvent.reward == null) {
            return computedDelta;
        }
        final long rewardSum = ledger.get24HourSum(ongoingEvent.eventId, now);
        return Math.max(0,
                Math.min(ongoingEvent.reward.maxDailyReward - rewardSum, computedDelta));
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void recordTransactionLocked(final int userId, @NonNull final String pkgName,
            @NonNull Ledger ledger, @NonNull Ledger.Transaction transaction,
            final boolean notifyOnAffordabilityChange) {
        if (transaction.delta == 0) {
            // Skip recording transactions with a delta of 0 to save on space.
            return;
        }
        if (mIrs.isSystem(userId, pkgName)) {
            Slog.wtfStack(TAG,
                    "Tried to adjust system balance for " + appToString(userId, pkgName));
            return;
        }
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();
        final long maxCirculationAllowed = mIrs.getMaxCirculationLocked();
        final long curNarcsInCirculation = mScribe.getNarcsInCirculationLocked();
        final long newArcsInCirculation = curNarcsInCirculation + transaction.delta;
        if (transaction.delta > 0 && newArcsInCirculation > maxCirculationAllowed) {
            // Set lower bound at 0 so we don't accidentally take away credits when we were trying
            // to _give_ the app credits.
            final long newDelta = Math.max(0, maxCirculationAllowed - curNarcsInCirculation);
            Slog.i(TAG, "Would result in too many credits in circulation. Decreasing transaction "
                    + eventToString(transaction.eventId)
                    + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for " + appToString(userId, pkgName)
                    + " by " + narcToString(transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.eventId, transaction.tag, newDelta);
        }
        final long originalBalance = ledger.getCurrentBalance();
        if (transaction.delta > 0
                && originalBalance + transaction.delta > economicPolicy.getMaxSatiatedBalance()) {
            // Set lower bound at 0 so we don't accidentally take away credits when we were trying
            // to _give_ the app credits.
            final long newDelta =
                    Math.max(0, economicPolicy.getMaxSatiatedBalance() - originalBalance);
            Slog.i(TAG, "Would result in becoming too rich. Decreasing transaction "
                    + eventToString(transaction.eventId)
                    + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for " + appToString(userId, pkgName)
                    + " by " + narcToString(transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.eventId, transaction.tag, newDelta);
        }
        ledger.recordTransaction(transaction);
        mScribe.adjustNarcsInCirculationLocked(transaction.delta);
        if (transaction.delta != 0 && notifyOnAffordabilityChange) {
            final ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                    mActionAffordabilityNotes.get(userId, pkgName);
            if (actionAffordabilityNotes != null) {
                final long newBalance = ledger.getCurrentBalance();
                for (int i = 0; i < actionAffordabilityNotes.size(); ++i) {
                    final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(i);
                    final boolean isAffordable = newBalance >= note.getCachedModifiedPrice();
                    if (note.isCurrentlyAffordable() != isAffordable) {
                        note.setNewAffordability(isAffordable);
                        mIrs.postAffordabilityChanged(userId, pkgName, note);
                    }
                }
            }
        }
    }

    /**
     * Reclaim a percentage of unused ARCs from every app that hasn't been used recently. The
     * reclamation will not reduce an app's balance below its minimum balance as dictated by
     * {@code scaleMinBalance}.
     *
     * @param percentage      A value between 0 and 1 to indicate how much of the unused balance
     *                        should be reclaimed.
     * @param minUnusedTimeMs The minimum amount of time (in milliseconds) that must have
     *                        transpired since the last user usage event before we will consider
     *                        reclaiming ARCs from the app.
     * @param scaleMinBalance Whether or not to used the scaled minimum app balance. If false,
     *                        this will use the constant min balance floor given by
     *                        {@link EconomicPolicy#getMinSatiatedBalance(int, String)}. If true,
     *                        this will use the scaled balance given by
     *                        {@link InternalResourceService#getMinBalanceLocked(int, String)}.
     */
    @GuardedBy("mLock")
    void reclaimUnusedAssetsLocked(double percentage, long minUnusedTimeMs,
            boolean scaleMinBalance) {
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();
        final SparseArrayMap<String, Ledger> ledgers = mScribe.getLedgersLocked();
        final long now = getCurrentTimeMillis();
        for (int u = 0; u < ledgers.numMaps(); ++u) {
            final int userId = ledgers.keyAt(u);
            for (int p = 0; p < ledgers.numElementsForKey(userId); ++p) {
                final Ledger ledger = ledgers.valueAt(u, p);
                final long curBalance = ledger.getCurrentBalance();
                if (curBalance <= 0) {
                    continue;
                }
                final String pkgName = ledgers.keyAt(u, p);
                // AppStandby only counts elapsed time for things like this
                // TODO: should we use clock time instead?
                final long timeSinceLastUsedMs =
                        mAppStandbyInternal.getTimeSinceLastUsedByUser(pkgName, userId);
                if (timeSinceLastUsedMs >= minUnusedTimeMs) {
                    final long minBalance;
                    if (!scaleMinBalance) {
                        // Use a constant floor instead of the scaled floor from the IRS.
                        minBalance = economicPolicy.getMinSatiatedBalance(userId, pkgName);
                    } else {
                        minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
                    }
                    long toReclaim = (long) (curBalance * percentage);
                    if (curBalance - toReclaim < minBalance) {
                        toReclaim = curBalance - minBalance;
                    }
                    if (toReclaim > 0) {
                        if (DEBUG) {
                            Slog.i(TAG, "Reclaiming unused wealth! Taking " + toReclaim
                                    + " from " + appToString(userId, pkgName));
                        }

                        recordTransactionLocked(userId, pkgName, ledger,
                                new Ledger.Transaction(
                                        now, now, REGULATION_WEALTH_RECLAMATION, null, -toReclaim),
                                true);
                    }
                }
            }
        }
    }

    /**
     * Reclaim a percentage of unused ARCs from an app that was just removed from an exemption list.
     * The amount reclaimed will depend on how recently the app was used. The reclamation will not
     * reduce an app's balance below its current minimum balance.
     */
    @GuardedBy("mLock")
    void onAppUnexemptedLocked(final int userId, @NonNull final String pkgName) {
        final long curBalance = getBalanceLocked(userId, pkgName);
        final long minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
        if (curBalance <= minBalance) {
            return;
        }
        // AppStandby only counts elapsed time for things like this
        // TODO: should we use clock time instead?
        final long timeSinceLastUsedMs =
                mAppStandbyInternal.getTimeSinceLastUsedByUser(pkgName, userId);
        // The app is no longer exempted. We should take away some of credits so it's more in line
        // with other non-exempt apps. However, don't take away as many credits if the app was used
        // recently.
        final double percentageToReclaim;
        if (timeSinceLastUsedMs < DAY_IN_MILLIS) {
            percentageToReclaim = .25;
        } else if (timeSinceLastUsedMs < 2 * DAY_IN_MILLIS) {
            percentageToReclaim = .5;
        } else if (timeSinceLastUsedMs < 3 * DAY_IN_MILLIS) {
            percentageToReclaim = .75;
        } else {
            percentageToReclaim = 1;
        }
        final long overage = curBalance - minBalance;
        final long toReclaim = (long) (overage * percentageToReclaim);
        if (toReclaim > 0) {
            if (DEBUG) {
                Slog.i(TAG, "Reclaiming bonus wealth! Taking " + toReclaim
                        + " from " + appToString(userId, pkgName));
            }

            final long now = getCurrentTimeMillis();
            final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
            recordTransactionLocked(userId, pkgName, ledger,
                    new Ledger.Transaction(now, now, REGULATION_DEMOTION, null, -toReclaim),
                    true);
        }
    }

    /** Returns true if an app should be given credits in the general distributions. */
    private boolean shouldGiveCredits(@NonNull PackageInfo packageInfo) {
        final ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        // Skip apps that wouldn't be doing any work. Giving them ARCs would be wasteful.
        if (applicationInfo == null || !applicationInfo.hasCode()) {
            return false;
        }
        final int userId = UserHandle.getUserId(packageInfo.applicationInfo.uid);
        // No point allocating ARCs to the system. It can do whatever it wants.
        return !mIrs.isSystem(userId, packageInfo.packageName);
    }

    @GuardedBy("mLock")
    void distributeBasicIncomeLocked(int batteryLevel) {
        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        pkgs.sort(mPackageDistributionComparator);

        final long now = getCurrentTimeMillis();
        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo pkgInfo = pkgs.get(i);
            if (!shouldGiveCredits(pkgInfo)) {
                continue;
            }
            final int userId = UserHandle.getUserId(pkgInfo.applicationInfo.uid);
            final String pkgName = pkgInfo.packageName;
            final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
            final long minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
            final double perc = batteryLevel / 100d;
            // TODO: maybe don't give credits to bankrupt apps until battery level >= 50%
            if (ledger.getCurrentBalance() < minBalance) {
                final long shortfall = minBalance - getBalanceLocked(userId, pkgName);
                recordTransactionLocked(userId, pkgName, ledger,
                        new Ledger.Transaction(now, now, REGULATION_BASIC_INCOME,
                                null, (long) (perc * shortfall)), true);
            }
        }
    }

    /** Give each app an initial balance. */
    @GuardedBy("mLock")
    void grantBirthrightsLocked() {
        UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        final int[] userIds = userManagerInternal.getUserIds();
        for (int userId : userIds) {
            grantBirthrightsLocked(userId);
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightsLocked(final int userId) {
        final List<PackageInfo> pkgs = mIrs.getInstalledPackages(userId);
        final long maxBirthright =
                mIrs.getMaxCirculationLocked() / mIrs.getInstalledPackages().size();
        final long now = getCurrentTimeMillis();

        pkgs.sort(mPackageDistributionComparator);

        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo packageInfo = pkgs.get(i);
            if (!shouldGiveCredits(packageInfo)) {
                continue;
            }
            final String pkgName = packageInfo.packageName;
            final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
            if (ledger.getCurrentBalance() > 0) {
                // App already got credits somehow. Move along.
                Slog.wtf(TAG, "App " + pkgName + " had credits before economy was set up");
                continue;
            }

            recordTransactionLocked(userId, pkgName, ledger,
                    new Ledger.Transaction(now, now, REGULATION_BIRTHRIGHT, null,
                            Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))),
                    true);
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() > 0) {
            Slog.wtf(TAG, "App " + pkgName + " had credits as soon as it was installed");
            // App already got credits somehow. Move along.
            return;
        }

        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final int numPackages = pkgs.size();
        final long maxBirthright = mIrs.getMaxCirculationLocked() / numPackages;
        final long now = getCurrentTimeMillis();

        recordTransactionLocked(userId, pkgName, ledger,
                new Ledger.Transaction(now, now, REGULATION_BIRTHRIGHT, null,
                        Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))), true);
    }

    @GuardedBy("mLock")
    void onAppExemptedLocked(final int userId, @NonNull final String pkgName) {
        final long minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
        final long missing = minBalance - getBalanceLocked(userId, pkgName);
        if (missing <= 0) {
            return;
        }

        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
        final long now = getCurrentTimeMillis();

        recordTransactionLocked(userId, pkgName, ledger,
                new Ledger.Transaction(now, now, REGULATION_PROMOTION, null, missing), true);
    }

    @GuardedBy("mLock")
    void onPackageRemovedLocked(final int userId, @NonNull final String pkgName) {
        reclaimAssetsLocked(userId, pkgName);
        mBalanceThresholdAlarmQueue.removeAlarmForKey(new Package(userId, pkgName));
    }

    /**
     * Reclaims any ARCs granted to the app, making them available to other apps. Also deletes the
     * app's ledger and stops any ongoing event tracking.
     */
    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() != 0) {
            mScribe.adjustNarcsInCirculationLocked(-ledger.getCurrentBalance());
        }
        mScribe.discardLedgerLocked(userId, pkgName);
        mCurrentOngoingEvents.delete(userId, pkgName);
    }

    @GuardedBy("mLock")
    void onUserRemovedLocked(final int userId, @NonNull final List<String> pkgNames) {
        reclaimAssetsLocked(userId, pkgNames);
        mBalanceThresholdAlarmQueue.removeAlarmsForUserId(userId);
    }

    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final List<String> pkgNames) {
        for (int i = 0; i < pkgNames.size(); ++i) {
            reclaimAssetsLocked(userId, pkgNames.get(i));
        }
    }

    @VisibleForTesting
    static class TrendCalculator implements Consumer<OngoingEvent> {
        static final long WILL_NOT_CROSS_THRESHOLD = -1;

        private long mCurBalance;
        /**
         * The maximum change in credits per second towards the upper threshold
         * {@link #mUpperThreshold}. A value of 0 means the current ongoing events will never
         * result in the app crossing the upper threshold.
         */
        private long mMaxDeltaPerSecToUpperThreshold;
        /**
         * The maximum change in credits per second towards the lower threshold
         * {@link #mLowerThreshold}. A value of 0 means the current ongoing events will never
         * result in the app crossing the lower threshold.
         */
        private long mMaxDeltaPerSecToLowerThreshold;
        private long mUpperThreshold;
        private long mLowerThreshold;

        void reset(long curBalance,
                @Nullable ArraySet<ActionAffordabilityNote> actionAffordabilityNotes) {
            mCurBalance = curBalance;
            mMaxDeltaPerSecToUpperThreshold = mMaxDeltaPerSecToLowerThreshold = 0;
            mUpperThreshold = Long.MIN_VALUE;
            mLowerThreshold = Long.MAX_VALUE;
            if (actionAffordabilityNotes != null) {
                for (int i = 0; i < actionAffordabilityNotes.size(); ++i) {
                    final ActionAffordabilityNote note = actionAffordabilityNotes.valueAt(i);
                    final long price = note.getCachedModifiedPrice();
                    if (price <= mCurBalance) {
                        mLowerThreshold = (mLowerThreshold == Long.MAX_VALUE)
                                ? price : Math.max(mLowerThreshold, price);
                    } else {
                        mUpperThreshold = (mUpperThreshold == Long.MIN_VALUE)
                                ? price : Math.min(mUpperThreshold, price);
                    }
                }
            }
        }

        /**
         * Returns the amount of time (in millisecond) it will take for the app to cross the next
         * lowest action affordability note (compared to its current balance) based on current
         * ongoing events.
         * Returns {@link #WILL_NOT_CROSS_THRESHOLD} if the app will never cross the lowest
         * threshold.
         */
        long getTimeToCrossLowerThresholdMs() {
            if (mMaxDeltaPerSecToLowerThreshold == 0) {
                // Will never cross upper threshold based on current events.
                return WILL_NOT_CROSS_THRESHOLD;
            }
            // deltaPerSec is a negative value, so do threshold-balance to cancel out the negative.
            final long minSeconds =
                    (mLowerThreshold - mCurBalance) / mMaxDeltaPerSecToLowerThreshold;
            return minSeconds * 1000;
        }

        /**
         * Returns the amount of time (in millisecond) it will take for the app to cross the next
         * highest action affordability note (compared to its current balance) based on current
         * ongoing events.
         * Returns {@link #WILL_NOT_CROSS_THRESHOLD} if the app will never cross the upper
         * threshold.
         */
        long getTimeToCrossUpperThresholdMs() {
            if (mMaxDeltaPerSecToUpperThreshold == 0) {
                // Will never cross upper threshold based on current events.
                return WILL_NOT_CROSS_THRESHOLD;
            }
            final long minSeconds =
                    (mUpperThreshold - mCurBalance) / mMaxDeltaPerSecToUpperThreshold;
            return minSeconds * 1000;
        }

        @Override
        public void accept(OngoingEvent ongoingEvent) {
            if (mCurBalance >= mLowerThreshold && ongoingEvent.deltaPerSec < 0) {
                mMaxDeltaPerSecToLowerThreshold += ongoingEvent.deltaPerSec;
            } else if (mCurBalance < mUpperThreshold && ongoingEvent.deltaPerSec > 0) {
                mMaxDeltaPerSecToUpperThreshold += ongoingEvent.deltaPerSec;
            }
        }
    }

    @GuardedBy("mLock")
    private final TrendCalculator mTrendCalculator = new TrendCalculator();

    @GuardedBy("mLock")
    private void scheduleBalanceCheckLocked(final int userId, @NonNull final String pkgName) {
        SparseArrayMap<String, OngoingEvent> ongoingEvents =
                mCurrentOngoingEvents.get(userId, pkgName);
        if (ongoingEvents == null) {
            // No ongoing transactions. No reason to schedule
            mBalanceThresholdAlarmQueue.removeAlarmForKey(new Package(userId, pkgName));
            return;
        }
        mTrendCalculator.reset(
                getBalanceLocked(userId, pkgName), mActionAffordabilityNotes.get(userId, pkgName));
        ongoingEvents.forEach(mTrendCalculator);
        final long lowerTimeMs = mTrendCalculator.getTimeToCrossLowerThresholdMs();
        final long upperTimeMs = mTrendCalculator.getTimeToCrossUpperThresholdMs();
        final long timeToThresholdMs;
        if (lowerTimeMs == TrendCalculator.WILL_NOT_CROSS_THRESHOLD) {
            if (upperTimeMs == TrendCalculator.WILL_NOT_CROSS_THRESHOLD) {
                // Will never cross a threshold based on current events.
                mBalanceThresholdAlarmQueue.removeAlarmForKey(new Package(userId, pkgName));
                return;
            }
            timeToThresholdMs = upperTimeMs;
        } else {
            timeToThresholdMs = (upperTimeMs == TrendCalculator.WILL_NOT_CROSS_THRESHOLD)
                    ? lowerTimeMs : Math.min(lowerTimeMs, upperTimeMs);
        }
        mBalanceThresholdAlarmQueue.addAlarm(new Package(userId, pkgName),
                SystemClock.elapsedRealtime() + timeToThresholdMs);
    }

    @GuardedBy("mLock")
    void tearDownLocked() {
        mCurrentOngoingEvents.clear();
        mBalanceThresholdAlarmQueue.removeAllAlarms();
    }

    @VisibleForTesting
    static class OngoingEvent {
        public final long startTimeElapsed;
        public final int eventId;
        @Nullable
        public final String tag;
        @Nullable
        public final EconomicPolicy.Reward reward;
        public final long deltaPerSec;
        public int refCount;

        OngoingEvent(int eventId, @Nullable String tag,
                @Nullable EconomicPolicy.Reward reward, long startTimeElapsed, long deltaPerSec) {
            this.startTimeElapsed = startTimeElapsed;
            this.eventId = eventId;
            this.tag = tag;
            this.reward = reward;
            this.deltaPerSec = deltaPerSec;
            refCount = 1;
        }
    }

    private static final class Package {
        public final String packageName;
        public final int userId;

        Package(int userId, String packageName) {
            this.userId = userId;
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return appToString(userId, packageName);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (this == obj) {
                return true;
            }
            if (obj instanceof Package) {
                Package other = (Package) obj;
                return userId == other.userId && Objects.equals(packageName, other.packageName);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() + userId;
        }
    }

    /** Track when apps will cross the closest affordability threshold (in both directions). */
    private class BalanceThresholdAlarmQueue extends AlarmQueue<Package> {
        private BalanceThresholdAlarmQueue(Context context, Looper looper) {
            super(context, looper, ALARM_TAG_AFFORDABILITY_CHECK, "Affordability check", true,
                    15_000L);
        }

        @Override
        protected boolean isForUser(@NonNull Package key, int userId) {
            return key.userId == userId;
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<Package> expired) {
            for (int i = 0; i < expired.size(); ++i) {
                Package p = expired.valueAt(i);
                mHandler.obtainMessage(MSG_CHECK_BALANCE, p.userId, 0, p.packageName)
                        .sendToTarget();
            }
        }
    }

    @GuardedBy("mLock")
    public void registerAffordabilityChangeListenerLocked(int userId, @NonNull String pkgName,
            @NonNull EconomyManagerInternal.AffordabilityChangeListener listener,
            @NonNull EconomyManagerInternal.ActionBill bill) {
        ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                mActionAffordabilityNotes.get(userId, pkgName);
        if (actionAffordabilityNotes == null) {
            actionAffordabilityNotes = new ArraySet<>();
            mActionAffordabilityNotes.add(userId, pkgName, actionAffordabilityNotes);
        }
        final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();
        final ActionAffordabilityNote note =
                new ActionAffordabilityNote(bill, listener, economicPolicy);
        if (actionAffordabilityNotes.add(note)) {
            if (!mIrs.isEnabled()) {
                // When TARE isn't enabled, we always say something is affordable. We also don't
                // want to silently drop affordability change listeners in case TARE becomes enabled
                // because then clients will be in an ambiguous state.
                note.setNewAffordability(true);
                return;
            }
            note.recalculateModifiedPrice(economicPolicy, userId, pkgName);
            note.setNewAffordability(
                    getBalanceLocked(userId, pkgName) >= note.getCachedModifiedPrice());
            mIrs.postAffordabilityChanged(userId, pkgName, note);
            // Update ongoing alarm
            scheduleBalanceCheckLocked(userId, pkgName);
        }
    }

    @GuardedBy("mLock")
    public void unregisterAffordabilityChangeListenerLocked(int userId, @NonNull String pkgName,
            @NonNull EconomyManagerInternal.AffordabilityChangeListener listener,
            @NonNull EconomyManagerInternal.ActionBill bill) {
        final ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                mActionAffordabilityNotes.get(userId, pkgName);
        if (actionAffordabilityNotes != null) {
            final CompleteEconomicPolicy economicPolicy = mIrs.getCompleteEconomicPolicyLocked();
            final ActionAffordabilityNote note =
                    new ActionAffordabilityNote(bill, listener, economicPolicy);
            if (actionAffordabilityNotes.remove(note)) {
                // Update ongoing alarm
                scheduleBalanceCheckLocked(userId, pkgName);
            }
        }
    }

    static final class ActionAffordabilityNote {
        private final EconomyManagerInternal.ActionBill mActionBill;
        private final EconomyManagerInternal.AffordabilityChangeListener mListener;
        private long mModifiedPrice;
        private boolean mIsAffordable;

        @VisibleForTesting
        ActionAffordabilityNote(@NonNull EconomyManagerInternal.ActionBill bill,
                @NonNull EconomyManagerInternal.AffordabilityChangeListener listener,
                @NonNull EconomicPolicy economicPolicy) {
            mActionBill = bill;
            final List<EconomyManagerInternal.AnticipatedAction> anticipatedActions =
                    bill.getAnticipatedActions();
            for (int i = 0; i < anticipatedActions.size(); ++i) {
                final EconomyManagerInternal.AnticipatedAction aa = anticipatedActions.get(i);
                final EconomicPolicy.Action action = economicPolicy.getAction(aa.actionId);
                if (action == null) {
                    throw new IllegalArgumentException("Invalid action id: " + aa.actionId);
                }
            }
            mListener = listener;
        }

        @NonNull
        EconomyManagerInternal.ActionBill getActionBill() {
            return mActionBill;
        }

        @NonNull
        EconomyManagerInternal.AffordabilityChangeListener getListener() {
            return mListener;
        }

        private long getCachedModifiedPrice() {
            return mModifiedPrice;
        }

        @VisibleForTesting
        long recalculateModifiedPrice(@NonNull EconomicPolicy economicPolicy,
                int userId, @NonNull String pkgName) {
            long modifiedPrice = 0;
            final List<EconomyManagerInternal.AnticipatedAction> anticipatedActions =
                    mActionBill.getAnticipatedActions();
            for (int i = 0; i < anticipatedActions.size(); ++i) {
                final EconomyManagerInternal.AnticipatedAction aa = anticipatedActions.get(i);

                final long actionCost =
                        economicPolicy.getCostOfAction(aa.actionId, userId, pkgName);
                modifiedPrice += actionCost * aa.numInstantaneousCalls
                        + actionCost * (aa.ongoingDurationMs / 1000);
            }
            mModifiedPrice = modifiedPrice;
            return modifiedPrice;
        }

        boolean isCurrentlyAffordable() {
            return mIsAffordable;
        }

        private void setNewAffordability(boolean isAffordable) {
            mIsAffordable = isAffordable;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ActionAffordabilityNote)) return false;
            ActionAffordabilityNote other = (ActionAffordabilityNote) o;
            return mActionBill.equals(other.mActionBill)
                    && mListener.equals(other.mListener);
        }

        @Override
        public int hashCode() {
            int hash = 0;
            hash = 31 * hash + Objects.hash(mListener);
            hash = 31 * hash + mActionBill.hashCode();
            return hash;
        }
    }

    private final class AgentHandler extends Handler {
        AgentHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_BALANCE: {
                    final int userId = msg.arg1;
                    final String pkgName = (String) msg.obj;
                    synchronized (mLock) {
                        final ArraySet<ActionAffordabilityNote> actionAffordabilityNotes =
                                mActionAffordabilityNotes.get(userId, pkgName);
                        if (actionAffordabilityNotes != null
                                && actionAffordabilityNotes.size() > 0) {
                            final long newBalance = getBalanceLocked(userId, pkgName);

                            for (int i = 0; i < actionAffordabilityNotes.size(); ++i) {
                                final ActionAffordabilityNote note =
                                        actionAffordabilityNotes.valueAt(i);
                                final boolean isAffordable =
                                        newBalance >= note.getCachedModifiedPrice();
                                if (note.isCurrentlyAffordable() != isAffordable) {
                                    note.setNewAffordability(isAffordable);
                                    mIrs.postAffordabilityChanged(userId, pkgName, note);
                                }
                            }
                        }
                        scheduleBalanceCheckLocked(userId, pkgName);
                    }
                }
                break;
            }
        }
    }

    @GuardedBy("mLock")
    void dumpLocked(IndentingPrintWriter pw) {
        pw.println();
        mBalanceThresholdAlarmQueue.dump(pw);
    }
}
