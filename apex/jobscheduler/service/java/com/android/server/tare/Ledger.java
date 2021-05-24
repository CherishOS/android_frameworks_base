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

import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.server.tare.TareUtils.narcToArc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Ledger to track the last recorded balance and recent activities of an app.
 */
class Ledger {
    static class Transaction {
        public final long startTimeMs;
        public final long endTimeMs;
        @NonNull
        public final String reason;
        @Nullable
        public final String tag;
        public final long delta;

        Transaction(long startTimeMs, long endTimeMs,
                @NonNull String reason, @Nullable String tag, long delta) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.reason = reason;
            this.tag = tag;
            this.delta = delta;
        }
    }

    /** Last saved balance. This doesn't take currently ongoing events into account. */
    private long mCurrentBalance = 0;
    private final List<Transaction> mTransactions = new ArrayList<>();
    private final ArrayMap<String, Long> mCumulativeDeltaPerReason = new ArrayMap<>();
    private long mEarliestSumTime;

    Ledger() {
    }

    long getCurrentBalance() {
        return mCurrentBalance;
    }

    void recordTransaction(@NonNull Transaction transaction) {
        mTransactions.add(transaction);
        mCurrentBalance += transaction.delta;

        Long sum = mCumulativeDeltaPerReason.get(transaction.reason);
        if (sum == null) {
            sum = 0L;
        }
        sum += transaction.delta;
        mCumulativeDeltaPerReason.put(transaction.reason, sum);
        mEarliestSumTime = Math.min(mEarliestSumTime, transaction.startTimeMs);
    }

    long get24HourSum(@NonNull String reason, final long now) {
        final long windowStartTime = now - 24 * HOUR_IN_MILLIS;
        if (mEarliestSumTime < windowStartTime) {
            // Need to redo sums
            mCumulativeDeltaPerReason.clear();
            for (int i = mTransactions.size() - 1; i >= 0; --i) {
                final Transaction transaction = mTransactions.get(i);
                if (transaction.endTimeMs <= windowStartTime) {
                    break;
                }
                final Long sumObj = mCumulativeDeltaPerReason.get(transaction.reason);
                long sum = sumObj == null ? 0 : sumObj;
                if (transaction.startTimeMs >= windowStartTime) {
                    sum += transaction.delta;
                } else {
                    // Pro-rate durationed deltas. Intentionally floor the result.
                    sum += (long) (1.0 * (transaction.endTimeMs - windowStartTime)
                            * transaction.delta)
                            / (transaction.endTimeMs - transaction.startTimeMs);
                }
                mCumulativeDeltaPerReason.put(transaction.reason, sum);
            }
            mEarliestSumTime = windowStartTime;
        }
        Long sum = mCumulativeDeltaPerReason.get(reason);
        if (sum == null) {
            return 0;
        }
        return sum;
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Ledger{");
        pw.increaseIndent();

        pw.print("cur balance", narcToArc(getCurrentBalance())).println();

        pw.decreaseIndent();
        pw.println("}");
    }
}
