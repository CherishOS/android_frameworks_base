/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Helper class for reporting boot timing metrics.
 * @hide
 */
public class BootTimingsTraceLog {
    // Debug boot time for every step if it's non-user build.
    private static final boolean DEBUG_BOOT_TIME = !Build.IS_USER;
    private final Deque<Pair<String, Long>> mStartTimes
            = DEBUG_BOOT_TIME ? new ArrayDeque<>() : null;
    private final String mTag;
    private long mTraceTag;

    public BootTimingsTraceLog(String tag, long traceTag) {
        mTag = tag;
        mTraceTag = traceTag;
    }

    public void traceBegin(String name) {
        Trace.traceBegin(mTraceTag, name);
        if (DEBUG_BOOT_TIME) {
            mStartTimes.push(Pair.create(name, SystemClock.elapsedRealtime()));
        }
    }

    public void traceEnd() {
        Trace.traceEnd(mTraceTag);
        if (!DEBUG_BOOT_TIME) {
            return;
        }
        if (mStartTimes.peek() == null) {
            Slog.w(mTag, "traceEnd called more times than traceBegin");
            return;
        }
        Pair<String, Long> event = mStartTimes.pop();
        // Log the duration so it can be parsed by external tools for performance reporting
        Slog.d(mTag, event.first + " took to complete: "
                + (SystemClock.elapsedRealtime() - event.second) + "ms");
    }
}
