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
 * limitations under the License
 */

package com.android.server.backup.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.annotation.Nullable;
import android.app.Application;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.SparseArray;

import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.Trampoline;
import com.android.server.backup.TransportManager;
import com.android.server.backup.internal.Operation;

import org.mockito.stubbing.Answer;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.atomic.AtomicReference;

/** Test utils for {@link BackupManagerService} and friends. */
public class BackupManagerServiceTestUtils {
    /**
     * If the class-under-test is going to execute methods as the system, it's a good idea to also
     * call {@link #setUpBinderCallerAndApplicationAsSystem(Application)} before this method.
     */
    public static BackupManagerService createInitializedBackupManagerService(
            Context context, File baseStateDir, File dataDir, TransportManager transportManager) {
        return createInitializedBackupManagerService(
                context, startBackupThread(null), baseStateDir, dataDir, transportManager);
    }

    public static BackupManagerService createInitializedBackupManagerService(
            Context context,
            HandlerThread backupThread,
            File baseStateDir,
            File dataDir,
            TransportManager transportManager) {
        BackupManagerService backupManagerService =
                new BackupManagerService(
                        context,
                        new Trampoline(context),
                        backupThread,
                        baseStateDir,
                        dataDir,
                        transportManager);
        ShadowLooper shadowBackupLooper = shadowOf(backupThread.getLooper());
        shadowBackupLooper.runToEndOfTasks();
        // Handler instances have their own clock, so advancing looper (with runToEndOfTasks())
        // above does NOT advance the handlers' clock, hence whenever a handler post messages with
        // specific time to the looper the time of those messages will be before the looper's time.
        // To fix this we advance SystemClock as well since that is from where the handlers read
        // time.
        ShadowSystemClock.setCurrentTimeMillis(shadowBackupLooper.getScheduler().getCurrentTime());
        return backupManagerService;
    }

    /**
     * Sets up basic mocks for {@link BackupManagerService} mock. If {@code backupManagerService} is
     * a spy, make sure you provide in the arguments the same objects that the original object uses.
     *
     * <p>If the class-under-test is going to execute methods as the system, it's a good idea to
     * also call {@link #setUpBinderCallerAndApplicationAsSystem(Application)}.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void setUpBackupManagerServiceBasics(
            BackupManagerService backupManagerService,
            Application application,
            TransportManager transportManager,
            PackageManager packageManager,
            Handler backupHandler,
            PowerManager.WakeLock wakeLock,
            BackupAgentTimeoutParameters agentTimeoutParameters) {
        SparseArray<Operation> operations = new SparseArray<>();

        when(backupManagerService.getContext()).thenReturn(application);
        when(backupManagerService.getTransportManager()).thenReturn(transportManager);
        when(backupManagerService.getPackageManager()).thenReturn(packageManager);
        when(backupManagerService.getBackupHandler()).thenReturn(backupHandler);
        when(backupManagerService.getCurrentOpLock()).thenReturn(new Object());
        when(backupManagerService.getQueueLock()).thenReturn(new Object());
        when(backupManagerService.getCurrentOperations()).thenReturn(operations);
        when(backupManagerService.getActivityManager()).thenReturn(mock(IActivityManager.class));
        when(backupManagerService.getWakelock()).thenReturn(wakeLock);
        when(backupManagerService.getAgentTimeoutParameters()).thenReturn(agentTimeoutParameters);

        AccessorMock backupEnabled = mockAccessor(false);
        doAnswer(backupEnabled.getter).when(backupManagerService).isBackupEnabled();
        doAnswer(backupEnabled.setter).when(backupManagerService).setBackupEnabled(anyBoolean());

        AccessorMock backupRunning = mockAccessor(false);
        doAnswer(backupEnabled.getter).when(backupManagerService).isBackupRunning();
        doAnswer(backupRunning.setter).when(backupManagerService).setBackupRunning(anyBoolean());

        doAnswer(
                        invocation -> {
                            operations.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(backupManagerService)
                .putOperation(anyInt(), any());
        doAnswer(
                        invocation -> {
                            int token = invocation.getArgument(0);
                            operations.remove(token);
                            return null;
                        })
                .when(backupManagerService)
                .removeOperation(anyInt());
    }

    public static void setUpBinderCallerAndApplicationAsSystem(Application application) {
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);
        ShadowBinder.setCallingPid(1211);
        ShadowApplication shadowApplication = shadowOf(application);
        shadowApplication.grantPermissions("android.permission.BACKUP");
        shadowApplication.grantPermissions("android.permission.CONFIRM_FULL_BACKUP");
    }

    /**
     * Returns one getter {@link Answer<T>} and one setter {@link Answer<T>} to be easily passed to
     * Mockito mocking facilities.
     *
     * @param defaultValue Value returned by the getter if there was no setter call until then.
     */
    public static <T> AccessorMock<T> mockAccessor(T defaultValue) {
        AtomicReference<T> holder = new AtomicReference<>(defaultValue);
        return new AccessorMock<>(
                invocation -> holder.get(),
                invocation -> {
                    holder.set(invocation.getArgument(0));
                    return null;
                });
    }

    public static PowerManager.WakeLock createBackupWakeLock(Application application) {
        PowerManager powerManager =
                (PowerManager) application.getSystemService(Context.POWER_SERVICE);
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");
    }

    /**
     * Creates a backup thread associated with a looper, starts it and returns its looper for
     * shadowing and creation of the backup handler.
     *
     * <p>Note that Robolectric simulates multi-thread in a single-thread to avoid flakiness, so
     * even though we started the thread, you should control its execution via the shadow of the
     * looper returned.
     *
     * @return The {@link Looper} for the backup thread.
     */
    public static Looper startBackupThreadAndGetLooper() {
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.start();
        return backupThread.getLooper();
    }

    /**
     * Similar to {@link #startBackupThreadAndGetLooper()} but with a custom exception handler and
     * returning the thread instead of the looper associated with it.
     *
     * @param exceptionHandler Uncaught exception handler for backup thread.
     * @return The backup thread.
     * @see #startBackupThreadAndGetLooper()
     */
    public static HandlerThread startBackupThread(
            @Nullable UncaughtExceptionHandler exceptionHandler) {
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.setUncaughtExceptionHandler(exceptionHandler);
        backupThread.start();
        return backupThread;
    }

    /**
     * Similar to {@link #startBackupThread(UncaughtExceptionHandler)} but logging uncaught
     * exceptions to logcat.
     *
     * @param tag Tag used for logging exceptions.
     * @return The backup thread.
     * @see #startBackupThread(UncaughtExceptionHandler)
     */
    public static HandlerThread startSilentBackupThread(String tag) {
        return startBackupThread(
                (thread, e) ->
                        ShadowLog.e(
                                tag, "Uncaught exception in test thread " + thread.getName(), e));
    }

    private BackupManagerServiceTestUtils() {}

    public static class AccessorMock<T> {
        public Answer<T> getter;
        public Answer<T> setter;

        private AccessorMock(Answer<T> getter, Answer<T> setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }
}
