/*
 * Copyright 2017 The Android Open Source Project
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
package android.app;

import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.PendingTransactionActions;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.IBinder;

import com.android.internal.content.ReferrerIntent;

import java.io.PrintWriter;
import java.util.List;

/**
 * Defines operations that a {@link android.app.servertransaction.ClientTransaction} or its items
 * can perform on client.
 * @hide
 */
public abstract class ClientTransactionHandler {

    // Schedule phase related logic and handlers.

    /** Prepare and schedule transaction for execution. */
    void scheduleTransaction(ClientTransaction transaction) {
        transaction.preExecute(this);
        sendMessage(ActivityThread.H.EXECUTE_TRANSACTION, transaction);
    }

    abstract void sendMessage(int what, Object obj);


    // Prepare phase related logic and handlers. Methods that inform about about pending changes or
    // do other internal bookkeeping.

    /** Set pending config in case it will be updated by other transaction item. */
    public abstract void updatePendingConfiguration(Configuration config);

    /** Set current process state. */
    public abstract void updateProcessState(int processState, boolean fromIpc);


    // Execute phase related logic and handlers. Methods here execute actual lifecycle transactions
    // and deliver callbacks.

    /** Destroy the activity. */
    public abstract void handleDestroyActivity(IBinder token, boolean finishing, int configChanges,
            boolean getNonConfigInstance);

    /** Pause the activity. */
    public abstract void handlePauseActivity(IBinder token, boolean finished, boolean userLeaving,
            int configChanges, boolean dontReport, PendingTransactionActions pendingActions);

    /** Resume the activity. */
    public abstract void handleResumeActivity(IBinder token, boolean clearHide, boolean isForward,
            String reason);

    /** Stop the activity. */
    public abstract void handleStopActivity(IBinder token, boolean show, int configChanges,
            PendingTransactionActions pendingActions);

    /** Report that activity was stopped to server. */
    public abstract void reportStop(PendingTransactionActions pendingActions);

    /** Restart the activity after it was stopped. */
    public abstract void performRestartActivity(IBinder token, boolean start);

    /** Deliver activity (override) configuration change. */
    public abstract void handleActivityConfigurationChanged(IBinder activityToken,
            Configuration overrideConfig, int displayId);

    /** Deliver result from another activity. */
    public abstract void handleSendResult(IBinder token, List<ResultInfo> results);

    /** Deliver multi-window mode change notification. */
    public abstract void handleMultiWindowModeChanged(IBinder token, boolean isInMultiWindowMode,
            Configuration overrideConfig);

    /** Deliver new intent. */
    public abstract void handleNewIntent(IBinder token, List<ReferrerIntent> intents,
            boolean andPause);

    /** Deliver picture-in-picture mode change notification. */
    public abstract void handlePictureInPictureModeChanged(IBinder token, boolean isInPipMode,
            Configuration overrideConfig);

    /** Update window visibility. */
    public abstract void handleWindowVisibility(IBinder token, boolean show);

    /** Perform activity launch. */
    public abstract Activity handleLaunchActivity(ActivityThread.ActivityClientRecord r,
            PendingTransactionActions pendingActions);

    /** Perform activity start. */
    public abstract void handleStartActivity(ActivityThread.ActivityClientRecord r,
            PendingTransactionActions pendingActions);

    /** Get package info. */
    public abstract LoadedApk getLoadedApkNoCheck(ApplicationInfo ai,
            CompatibilityInfo compatInfo);

    /** Deliver app configuration change notification. */
    public abstract void handleConfigurationChanged(Configuration config);

    /**
     * Get {@link android.app.ActivityThread.ActivityClientRecord} instance that corresponds to the
     * provided token.
     */
    public abstract ActivityThread.ActivityClientRecord getActivityClient(IBinder token);

    /**
     * Debugging output.
     * @param pw {@link PrintWriter} to write logs to.
     * @param prefix Prefix to prepend to output.
     */
    public abstract void dump(PrintWriter pw, String prefix);
}
