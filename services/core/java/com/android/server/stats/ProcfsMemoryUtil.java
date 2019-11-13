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
 * limitations under the License.
 */
package com.android.server.stats;

import static android.os.Process.PROC_OUT_STRING;

import android.annotation.Nullable;
import android.os.Process;

import java.util.function.BiConsumer;

final class ProcfsMemoryUtil {
    private static final int[] CMDLINE_OUT = new int[] { PROC_OUT_STRING };
    private static final String[] STATUS_KEYS = new String[] {
            "Uid:",
            "VmHWM:",
            "VmRSS:",
            "RssAnon:",
            "VmSwap:"
    };

    private ProcfsMemoryUtil() {}

    /**
     * Reads memory stats of a process from procfs. Returns values of the VmHWM, VmRss, AnonRSS,
     * VmSwap fields in /proc/pid/status in kilobytes or null if not available.
     */
    @Nullable
    static MemorySnapshot readMemorySnapshotFromProcfs(int pid) {
        long[] output = new long[STATUS_KEYS.length];
        output[0] = -1;
        Process.readProcLines("/proc/" + pid + "/status", STATUS_KEYS, output);
        if (output[0] == -1 || (output[3] == 0 && output[4] == 0)) {
            // Could not open file or anon rss / swap are 0 indicating the process is in a zombie
            // state.
            return null;
        }
        final MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.uid = (int) output[0];
        snapshot.rssHighWaterMarkInKilobytes = (int) output[1];
        snapshot.rssInKilobytes = (int) output[2];
        snapshot.anonRssInKilobytes = (int) output[3];
        snapshot.swapInKilobytes = (int) output[4];
        return snapshot;
    }

    /**
     * Reads cmdline of a process from procfs.
     *
     * Returns content of /proc/pid/cmdline (e.g. /system/bin/statsd) or an empty string
     * if the file is not available.
     */
    static String readCmdlineFromProcfs(int pid) {
        String[] cmdline = new String[1];
        if (!Process.readProcFile("/proc/" + pid + "/cmdline", CMDLINE_OUT, cmdline, null, null)) {
            return "";
        }
        return cmdline[0];
    }

    static void forEachPid(BiConsumer<Integer, String> func) {
        int[] pids = new int[1024];
        pids = Process.getPids("/proc", pids);
        for (int pid : pids) {
            if (pid < 0) {
                return;
            }
            String cmdline = readCmdlineFromProcfs(pid);
            if (cmdline.isEmpty()) {
                continue;
            }
            func.accept(pid, cmdline);
        }
    }

    static final class MemorySnapshot {
        public int uid;
        public int rssHighWaterMarkInKilobytes;
        public int rssInKilobytes;
        public int anonRssInKilobytes;
        public int swapInKilobytes;
    }
}
