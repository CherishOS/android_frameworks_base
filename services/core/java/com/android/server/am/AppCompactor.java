/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.internal.annotations.GuardedBy;

import android.app.ActivityManager;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;

import android.util.EventLog;
import android.util.StatsLog;

import static android.os.Process.THREAD_PRIORITY_FOREGROUND;

import com.android.server.ServiceThread;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public final class AppCompactor {
    /**
     * Processes to compact.
     */
    final ArrayList<ProcessRecord> mPendingCompactionProcesses = new ArrayList<ProcessRecord>();

    /*
     * This thread must be moved to the system background cpuset.
     * If that doesn't happen, it's probably going to draw a lot of power.
     * However, this has to happen after the first updateOomAdjLocked, because
     * that will wipe out the cpuset assignment for system_server threads.
     * Accordingly, this is in the AMS constructor.
     */
    final ServiceThread mCompactionThread;

    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_MSG = 1;
    final Handler mCompactionHandler;

    final ActivityManagerService mAm;
    final ActivityManagerConstants mConstants;

    public AppCompactor(ActivityManagerService am) {
        mAm = am;
        mConstants = am.mConstants;

        mCompactionThread = new ServiceThread("CompactionThread",
                THREAD_PRIORITY_FOREGROUND, true);
        mCompactionThread.start();
        mCompactionHandler = new MemCompactionHandler(this);
    }

    // Must be called while holding AMS lock.
    final void compactAppSome(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_SOME;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
            mCompactionHandler.obtainMessage(
                COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));
    }

    // Must be called while holding AMS lock.
    final void compactAppFull(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_FULL;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
            mCompactionHandler.obtainMessage(
                COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));

    }
    final class MemCompactionHandler extends Handler {
        AppCompactor mAc;

        private MemCompactionHandler(AppCompactor ac) {
            super(ac.mCompactionThread.getLooper());
            mAc = ac;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case COMPACT_PROCESS_MSG: {
                long start = SystemClock.uptimeMillis();
                ProcessRecord proc;
                int pid;
                String action;
                final String name;
                int pendingAction, lastCompactAction;
                long lastCompactTime;
                synchronized(mAc.mAm) {
                    proc = mAc.mPendingCompactionProcesses.remove(0);

                    // don't compact if the process has returned to perceptible
                    if (proc.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
                        return;
                    }

                    pid = proc.pid;
                    name = proc.processName;
                    pendingAction = proc.reqCompactAction;
                    lastCompactAction = proc.lastCompactAction;
                    lastCompactTime = proc.lastCompactTime;
                }
                if (pid == 0) {
                    // not a real process, either one being launched or one being killed
                    return;
                }

                // basic throttling
                if (pendingAction == COMPACT_PROCESS_SOME) {
                    // if we're compacting some, then compact if >10s after last full
                    // or >5s after last some
                    if ((lastCompactAction == COMPACT_PROCESS_SOME && (start - lastCompactTime < 5000)) ||
                        (lastCompactAction == COMPACT_PROCESS_FULL && (start - lastCompactTime < 10000))) {
                        return;
                    }
                } else {
                    // if we're compacting full, then compact if >10s after last full
                    // or >.5s after last some
                    if ((lastCompactAction == COMPACT_PROCESS_SOME && (start - lastCompactTime < 500)) ||
                        (lastCompactAction == COMPACT_PROCESS_FULL && (start - lastCompactTime < 10000))) {
                        return;
                    }
                }

                try {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Compact " +
                            ((pendingAction == COMPACT_PROCESS_SOME) ? "some" : "full") +
                            ": " + name);
                    long[] rssBefore = Process.getRss(pid);
                    FileOutputStream fos = new FileOutputStream("/proc/" + pid + "/reclaim");
                    if (pendingAction == COMPACT_PROCESS_SOME) {
                        action = "file";
                    } else {
                        action = "all";
                    }
                    fos.write(action.getBytes());
                    fos.close();
                    long[] rssAfter = Process.getRss(pid);
                    long end = SystemClock.uptimeMillis();
                    long time = end - start;
                    EventLog.writeEvent(EventLogTags.AM_COMPACT, pid, name, action,
                            rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                            rssAfter[0], rssAfter[1], rssAfter[2], rssAfter[3], time,
                            lastCompactAction, lastCompactTime, msg.arg1, msg.arg2);
                    StatsLog.write(StatsLog.APP_COMPACTED, pid, name, pendingAction,
                            rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                            rssAfter[0], rssAfter[1], rssAfter[2], rssAfter[3], time,
                            lastCompactAction, lastCompactTime, msg.arg1,
                            ActivityManager.processStateAmToProto(msg.arg2));
                    synchronized(mAc.mAm) {
                        proc.lastCompactTime = end;
                        proc.lastCompactAction = pendingAction;
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                } catch (Exception e) {
                    // nothing to do, presumably the process died
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                }
            }
            }
        }
    }


}
