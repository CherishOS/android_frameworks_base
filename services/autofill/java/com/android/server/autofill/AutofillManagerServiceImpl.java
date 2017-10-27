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
 * limitations under the License.
 */

package com.android.server.autofill;

import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.view.autofill.AutofillManager.ACTION_START_SESSION;
import static android.view.autofill.AutofillManager.NO_SESSION;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.service.autofill.AutofillService;
import android.service.autofill.AutofillServiceInfo;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillEventHistory.Event;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.HandlerCaller;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Random;

/**
 * Bridge between the {@code system_server}'s {@link AutofillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutofillManagerServiceImpl {

    private static final String TAG = "AutofillManagerServiceImpl";
    private static final int MAX_SESSION_ID_CREATE_TRIES = 2048;

    /** Minimum interval to prune abandoned sessions */
    private static final int MAX_ABANDONED_SESSION_MILLIS = 30000;

    static final int MSG_SERVICE_SAVE = 1;

    private final int mUserId;
    private final Context mContext;
    private final Object mLock;
    private final AutoFillUI mUi;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private RemoteCallbackList<IAutoFillManagerClient> mClients;
    private AutofillServiceInfo mInfo;

    private static final Random sRandom = new Random();

    private final LocalLog mRequestsHistory;
    private final LocalLog mUiLatencyHistory;

    /**
     * Apps disabled by the service; key is package name, value is when they will be enabled again.
     */
    private ArrayMap<String, Long> mDisabledApps;

    /**
     * Activities disabled by the service; key is component name, value is when they will be enabled
     * again.
     */
    private ArrayMap<ComponentName, Long> mDisabledActivities;

    /**
     * Whether service was disabled for user due to {@link UserManager} restrictions.
     */
    private boolean mDisabled;

    /**
     * Caches whether the setup completed for the current user.
     */
    @GuardedBy("mLock")
    private boolean mSetupComplete;

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_SERVICE_SAVE:
                handleSessionSave(msg.arg1);
                break;
            default:
                Slog.w(TAG, "invalid msg on handler: " + msg);
        }
    };

    private final HandlerCaller mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(),
            mHandlerCallback, true);

    /**
     * Cache of pending {@link Session}s, keyed by sessionId.
     *
     * <p>They're kept until the {@link AutofillService} finished handling a request, an error
     * occurs, or the session is abandoned.
     */
    @GuardedBy("mLock")
    private final SparseArray<Session> mSessions = new SparseArray<>();

    /** The last selection */
    @GuardedBy("mLock")
    private FillEventHistory mEventHistory;

    /** When was {@link PruneTask} last executed? */
    private long mLastPrune = 0;

    AutofillManagerServiceImpl(Context context, Object lock, LocalLog requestsHistory,
            LocalLog uiLatencyHistory, int userId, AutoFillUI ui, boolean disabled) {
        mContext = context;
        mLock = lock;
        mRequestsHistory = requestsHistory;
        mUiLatencyHistory = uiLatencyHistory;
        mUserId = userId;
        mUi = ui;
        updateLocked(disabled);
    }

    @Nullable
    CharSequence getServiceName() {
        final String packageName = getServicePackageName();
        if (packageName == null) {
            return null;
        }

        try {
            final PackageManager pm = mContext.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info);
        } catch (Exception e) {
            Slog.e(TAG, "Could not get label for " + packageName + ": " + e);
            return packageName;
        }
    }

    @Nullable
    String getServicePackageName() {
        final ComponentName serviceComponent = getServiceComponentName();
        if (serviceComponent != null) {
            return serviceComponent.getPackageName();
        }
        return null;
    }

    ComponentName getServiceComponentName() {
        synchronized (mLock) {
            if (mInfo == null) {
                return null;
            }
            return mInfo.getServiceInfo().getComponentName();
        }
    }

    private boolean isSetupCompletedLocked() {
        final String setupComplete = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, mUserId);
        return "1".equals(setupComplete);
    }

    private String getComponentNameFromSettings() {
        return Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.AUTOFILL_SERVICE, mUserId);
    }

    void updateLocked(boolean disabled) {
        final boolean wasEnabled = isEnabled();
        if (sVerbose) {
            Slog.v(TAG, "updateLocked(u=" + mUserId + "): wasEnabled=" + wasEnabled
                    + ", mSetupComplete= " + mSetupComplete
                    + ", disabled=" + disabled + ", mDisabled=" + mDisabled);
        }
        mSetupComplete = isSetupCompletedLocked();
        mDisabled = disabled;
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        final String componentName = getComponentNameFromSettings();
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                        0, mUserId);
                if (serviceInfo == null) {
                    Slog.e(TAG, "Bad AutofillService name: " + componentName);
                }
            } catch (RuntimeException | RemoteException e) {
                Slog.e(TAG, "Error getting service info for '" + componentName + "': " + e);
                serviceInfo = null;
            }
        }
        try {
            if (serviceInfo != null) {
                mInfo = new AutofillServiceInfo(mContext.getPackageManager(),
                        serviceComponent, mUserId);
                if (sDebug) Slog.d(TAG, "Set component for user " + mUserId + " as " + mInfo);
            } else {
                mInfo = null;
                if (sDebug) {
                    Slog.d(TAG, "Reset component for user " + mUserId + " (" + componentName + ")");
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Bad AutofillServiceInfo for '" + componentName + "': " + e);
            mInfo = null;
        }
        final boolean isEnabled = isEnabled();
        if (wasEnabled != isEnabled) {
            if (!isEnabled) {
                final int sessionCount = mSessions.size();
                for (int i = sessionCount - 1; i >= 0; i--) {
                    final Session session = mSessions.valueAt(i);
                    session.removeSelfLocked();
                }
            }
            sendStateToClients(false);
        }
    }

    boolean addClientLocked(IAutoFillManagerClient client) {
        if (mClients == null) {
            mClients = new RemoteCallbackList<>();
        }
        mClients.register(client);
        return isEnabled();
    }

    void setAuthenticationResultLocked(Bundle data, int sessionId, int authenticationId, int uid) {
        if (!isEnabled()) {
            return;
        }
        final Session session = mSessions.get(sessionId);
        if (session != null && uid == session.uid) {
            session.setAuthenticationResultLocked(data, authenticationId);
        }
    }

    void setHasCallback(int sessionId, int uid, boolean hasIt) {
        if (!isEnabled()) {
            return;
        }
        final Session session = mSessions.get(sessionId);
        if (session != null && uid == session.uid) {
            synchronized (mLock) {
                session.setHasCallbackLocked(hasIt);
            }
        }
    }

    int startSessionLocked(@NonNull IBinder activityToken, int uid,
            @NonNull IBinder appCallbackToken, @NonNull AutofillId autofillId,
            @NonNull Rect virtualBounds, @Nullable AutofillValue value, boolean hasCallback,
            int flags, @NonNull ComponentName componentName) {
        if (!isEnabled()) {
            return 0;
        }

        final String shortComponentName = componentName.toShortString();

        if (isAutofillDisabledLocked(componentName)) {
            if (sDebug) {
                Slog.d(TAG, "startSession(" + shortComponentName
                        + "): ignored because disabled by service");
            }

            final IAutoFillManagerClient client = IAutoFillManagerClient.Stub
                    .asInterface(appCallbackToken);
            try {
                client.setSessionFinished(AutofillManager.STATE_DISABLED_BY_SERVICE);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not notify " + shortComponentName + " that it's disabled: " + e);
            }

            return NO_SESSION;
        }

        if (sVerbose) Slog.v(TAG, "startSession(): token=" + activityToken + ", flags=" + flags);

        // Occasionally clean up abandoned sessions
        pruneAbandonedSessionsLocked();

        final Session newSession = createSessionByTokenLocked(activityToken, uid, appCallbackToken,
                hasCallback, componentName);
        if (newSession == null) {
            return NO_SESSION;
        }

        final String historyItem =
                "id=" + newSession.id + " uid=" + uid + " a=" + shortComponentName
                + " s=" + mInfo.getServiceInfo().packageName
                + " u=" + mUserId + " i=" + autofillId + " b=" + virtualBounds
                + " hc=" + hasCallback + " f=" + flags;
        mRequestsHistory.log(historyItem);

        newSession.updateLocked(autofillId, virtualBounds, value, ACTION_START_SESSION, flags);

        return newSession.id;
    }

    /**
     * Remove abandoned sessions if needed.
     */
    private void pruneAbandonedSessionsLocked() {
        long now = System.currentTimeMillis();
        if (mLastPrune < now - MAX_ABANDONED_SESSION_MILLIS) {
            mLastPrune = now;

            if (mSessions.size() > 0) {
                (new PruneTask()).execute();
            }
        }
    }

    void finishSessionLocked(int sessionId, int uid) {
        if (!isEnabled()) {
            return;
        }

        final Session session = mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            if (sVerbose) {
                Slog.v(TAG, "finishSessionLocked(): no session for " + sessionId + "(" + uid + ")");
            }
            return;
        }

        session.logContextCommittedLocked();

        final boolean finished = session.showSaveLocked();
        if (sVerbose) Slog.v(TAG, "finishSessionLocked(): session finished on save? " + finished);

        if (finished) {
            session.removeSelfLocked();
        }
    }

    void cancelSessionLocked(int sessionId, int uid) {
        if (!isEnabled()) {
            return;
        }

        final Session session = mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            Slog.w(TAG, "cancelSessionLocked(): no session for " + sessionId + "(" + uid + ")");
            return;
        }
        session.removeSelfLocked();
    }

    void disableOwnedAutofillServicesLocked(int uid) {
        Slog.i(TAG, "disableOwnedServices(" + uid + "): " + mInfo);
        if (mInfo == null) return;

        final ServiceInfo serviceInfo = mInfo.getServiceInfo();
        if (serviceInfo.applicationInfo.uid != uid) {
            Slog.w(TAG, "disableOwnedServices(): ignored when called by UID " + uid
                    + " instead of " + serviceInfo.applicationInfo.uid
                    + " for service " + mInfo);
            return;
        }


        final long identity = Binder.clearCallingIdentity();
        try {
            final String autoFillService = getComponentNameFromSettings();
            final ComponentName componentName = serviceInfo.getComponentName();
            if (componentName.equals(ComponentName.unflattenFromString(autoFillService))) {
                mMetricsLogger.action(MetricsEvent.AUTOFILL_SERVICE_DISABLED_SELF,
                        componentName.getPackageName());
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.AUTOFILL_SERVICE, null, mUserId);
                destroySessionsLocked();
            } else {
                Slog.w(TAG, "disableOwnedServices(): ignored because current service ("
                        + serviceInfo + ") does not match Settings (" + autoFillService + ")");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Session createSessionByTokenLocked(@NonNull IBinder activityToken, int uid,
            @NonNull IBinder appCallbackToken, boolean hasCallback,
            @NonNull ComponentName componentName) {
        // use random ids so that one app cannot know that another app creates sessions
        int sessionId;
        int tries = 0;
        do {
            tries++;
            if (tries > MAX_SESSION_ID_CREATE_TRIES) {
                Slog.w(TAG, "Cannot create session in " + MAX_SESSION_ID_CREATE_TRIES + " tries");
                return null;
            }

            sessionId = sRandom.nextInt();
        } while (sessionId == NO_SESSION || mSessions.indexOfKey(sessionId) >= 0);

        final Session newSession = new Session(this, mUi, mContext, mHandlerCaller, mUserId, mLock,
                sessionId, uid, activityToken, appCallbackToken, hasCallback,
                mUiLatencyHistory, mInfo.getServiceInfo().getComponentName(), componentName);
        mSessions.put(newSession.id, newSession);

        return newSession;
    }

    /**
     * Restores a session after an activity was temporarily destroyed.
     *
     * @param sessionId The id of the session to restore
     * @param uid UID of the process that tries to restore the session
     * @param activityToken The new instance of the activity
     * @param appCallback The callbacks to the activity
     */
    boolean restoreSession(int sessionId, int uid, @NonNull IBinder activityToken,
            @NonNull IBinder appCallback) {
        final Session session = mSessions.get(sessionId);

        if (session == null || uid != session.uid) {
            return false;
        } else {
            session.switchActivity(activityToken, appCallback);
            return true;
        }
    }

    /**
     * Updates a session and returns whether it should be restarted.
     */
    boolean updateSessionLocked(int sessionId, int uid, AutofillId autofillId, Rect virtualBounds,
            AutofillValue value, int action, int flags) {
        final Session session = mSessions.get(sessionId);
        if (session == null || session.uid != uid) {
            if ((flags & FLAG_MANUAL_REQUEST) != 0) {
                if (sDebug) {
                    Slog.d(TAG, "restarting session " + sessionId + " due to manual request on "
                            + autofillId);
                }
                return true;
            }
            if (sVerbose) {
                Slog.v(TAG, "updateSessionLocked(): session gone for " + sessionId
                        + "(" + uid + ")");
            }
            return false;
        }

        session.updateLocked(autofillId, virtualBounds, value, action, flags);
        return false;
    }

    void removeSessionLocked(int sessionId) {
        mSessions.remove(sessionId);
    }

    private void handleSessionSave(int sessionId) {
        synchronized (mLock) {
            final Session session = mSessions.get(sessionId);
            if (session == null) {
                Slog.w(TAG, "handleSessionSave(): already gone: " + sessionId);

                return;
            }
            session.callSaveLocked();
        }
    }

    void onPendingSaveUi(int operation, @NonNull IBinder token) {
        if (sVerbose) Slog.v(TAG, "onPendingSaveUi(" + operation + "): " + token);
        synchronized (mLock) {
            final int sessionCount = mSessions.size();
            for (int i = sessionCount - 1; i >= 0; i--) {
                final Session session = mSessions.valueAt(i);
                if (session.isSaveUiPendingForTokenLocked(token)) {
                    session.onPendingSaveUi(operation, token);
                    return;
                }
            }
        }
        if (sDebug) {
            Slog.d(TAG, "No pending Save UI for token " + token + " and operation "
                    + DebugUtils.flagsToString(AutofillManager.class, "PENDING_UI_OPERATION_",
                            operation));
        }
    }

    void destroyLocked() {
        if (sVerbose) Slog.v(TAG, "destroyLocked()");

        final int numSessions = mSessions.size();
        final ArraySet<RemoteFillService> remoteFillServices = new ArraySet<>(numSessions);
        for (int i = 0; i < numSessions; i++) {
            final RemoteFillService remoteFillService = mSessions.valueAt(i).destroyLocked();
            if (remoteFillService != null) {
                remoteFillServices.add(remoteFillService);
            }
        }
        mSessions.clear();
        for (int i = 0; i < remoteFillServices.size(); i++) {
            remoteFillServices.valueAt(i).destroy();
        }

        sendStateToClients(true);
    }

    @NonNull
    CharSequence getServiceLabel() {
        return mInfo.getServiceInfo().loadLabel(mContext.getPackageManager());
    }

    @NonNull
    Drawable getServiceIcon() {
        return mInfo.getServiceInfo().loadIcon(mContext.getPackageManager());
    }

    /**
     * Initializes the last fill selection after an autofill service returned a new
     * {@link FillResponse}.
     */
    void setLastResponse(int serviceUid, int sessionId, @NonNull FillResponse response) {
        synchronized (mLock) {
            mEventHistory = new FillEventHistory(serviceUid, sessionId, response.getClientState());
        }
    }

    /**
     * Resets the last fill selection.
     */
    void resetLastResponse() {
        synchronized (mLock) {
            mEventHistory = null;
        }
    }

    private boolean isValidEventLocked(String method, int sessionId) {
        if (mEventHistory == null) {
            Slog.w(TAG, method + ": not logging event because history is null");
            return false;
        }
        if (sessionId != mEventHistory.getSessionId()) {
            if (sDebug) {
                Slog.d(TAG, method + ": not logging event for session " + sessionId
                        + " because tracked session is " + mEventHistory.getSessionId());
            }
            return false;
        }
        return true;
    }

    /**
     * Updates the last fill selection when an authentication was selected.
     */
    void setAuthenticationSelected(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("setAuthenticationSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_AUTHENTICATION_SELECTED, null, clientState, null, null,
                                null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill selection when an dataset authentication was selected.
     */
    void logDatasetAuthenticationSelected(@Nullable String selectedDataset, int sessionId,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetAuthenticationSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_DATASET_AUTHENTICATION_SELECTED, selectedDataset,
                                clientState, null, null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill selection when an save Ui is shown.
     */
    void logSaveShown(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logSaveShown()", sessionId)) {
                mEventHistory.addEvent(new Event(Event.TYPE_SAVE_SHOWN, null, clientState, null,
                        null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill response when a dataset was selected.
     */
    void logDatasetSelected(@Nullable String selectedDataset, int sessionId,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_DATASET_SELECTED, selectedDataset, clientState, null,
                                null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill response when an autofill context is committed.
     */
    void logContextCommitted(int sessionId, @Nullable Bundle clientState,
            @Nullable ArrayList<String> selectedDatasets,
            @Nullable ArraySet<String> ignoredDatasets,
            @Nullable ArrayList<AutofillId> changedFieldIds,
            @Nullable ArrayList<String> changedDatasetIds,
            @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
            @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetNotSelected()", sessionId)) {
                mEventHistory.addEvent(new Event(Event.TYPE_CONTEXT_COMMITTED, null,
                        clientState, selectedDatasets, ignoredDatasets,
                        changedFieldIds, changedDatasetIds,
                        manuallyFilledFieldIds, manuallyFilledDatasetIds));
            }
        }
    }

    /**
     * Gets the fill event history.
     *
     * @param callingUid The calling uid
     *
     * @return The history or {@code null} if there is none.
     */
    FillEventHistory getFillEventHistory(int callingUid) {
        synchronized (mLock) {
            if (mEventHistory != null && mEventHistory.getServiceUid() == callingUid) {
                return mEventHistory;
            }
        }

        return null;
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("User: "); pw.println(mUserId);
        pw.print(prefix); pw.print("Component: "); pw.println(mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null);
        pw.print(prefix); pw.print("Component from settings: ");
            pw.println(getComponentNameFromSettings());
        pw.print(prefix); pw.print("Default component: ");
            pw.println(mContext.getString(R.string.config_defaultAutofillService));
        pw.print(prefix); pw.print("Disabled: "); pw.println(mDisabled);
        pw.print(prefix); pw.print("Setup complete: "); pw.println(mSetupComplete);
        pw.print(prefix); pw.print("Last prune: "); pw.println(mLastPrune);

        pw.print(prefix); pw.print("Disabled apps: ");

        if (mDisabledApps == null) {
            pw.println("N/A");
        } else {
            final int size = mDisabledApps.size();
            pw.println(size);
            final StringBuilder builder = new StringBuilder();
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < size; i++) {
                final String packageName = mDisabledApps.keyAt(i);
                final long expiration = mDisabledApps.valueAt(i);
                 builder.append(prefix).append(prefix)
                     .append(i).append(". ").append(packageName).append(": ");
                 TimeUtils.formatDuration((expiration - now), builder);
                 builder.append('\n');
             }
             pw.println(builder);
        }

        pw.print(prefix); pw.print("Disabled activities: ");

        if (mDisabledActivities == null) {
            pw.println("N/A");
        } else {
            final int size = mDisabledActivities.size();
            pw.println(size);
            final StringBuilder builder = new StringBuilder();
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < size; i++) {
                final ComponentName component = mDisabledActivities.keyAt(i);
                final long expiration = mDisabledActivities.valueAt(i);
                 builder.append(prefix).append(prefix)
                     .append(i).append(". ").append(component).append(": ");
                 TimeUtils.formatDuration((expiration - now), builder);
                 builder.append('\n');
             }
             pw.println(builder);
        }

        final int size = mSessions.size();
        if (size == 0) {
            pw.print(prefix); pw.println("No sessions");
        } else {
            pw.print(prefix); pw.print(size); pw.println(" sessions:");
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print("#"); pw.println(i + 1);
                mSessions.valueAt(i).dumpLocked(prefix2, pw);
            }
        }

        if (mEventHistory == null || mEventHistory.getEvents() == null
                || mEventHistory.getEvents().size() == 0) {
            pw.print(prefix); pw.println("No event on last fill response");
        } else {
            pw.print(prefix); pw.println("Events of last fill response:");
            pw.print(prefix);

            int numEvents = mEventHistory.getEvents().size();
            for (int i = 0; i < numEvents; i++) {
                final Event event = mEventHistory.getEvents().get(i);
                pw.println("  " + i + ": eventType=" + event.getType() + " datasetId="
                        + event.getDatasetId());
            }
        }
    }

    void destroySessionsLocked() {
        if (mSessions.size() == 0) {
            mUi.destroyAll(null, null, false);
            return;
        }
        while (mSessions.size() > 0) {
            mSessions.valueAt(0).forceRemoveSelfLocked();
        }
    }

    // TODO(b/64940307): remove this method if SaveUI is refactored to be attached on activities
    void destroyFinishedSessionsLocked() {
        final int sessionCount = mSessions.size();
        for (int i = sessionCount - 1; i >= 0; i--) {
            final Session session = mSessions.valueAt(i);
            if (session.isSavingLocked()) {
                if (sDebug) Slog.d(TAG, "destroyFinishedSessionsLocked(): " + session.id);
                session.forceRemoveSelfLocked();
            }
        }
    }

    void listSessionsLocked(ArrayList<String> output) {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            output.add((mInfo != null ? mInfo.getServiceInfo().getComponentName()
                    : null) + ":" + mSessions.keyAt(i));
        }
    }

    private void sendStateToClients(boolean resetClient) {
        final RemoteCallbackList<IAutoFillManagerClient> clients;
        final int userClientCount;
        synchronized (mLock) {
            if (mClients == null) {
                return;
            }
            clients = mClients;
            userClientCount = clients.beginBroadcast();
        }
        try {
            for (int i = 0; i < userClientCount; i++) {
                final IAutoFillManagerClient client = clients.getBroadcastItem(i);
                try {
                    final boolean resetSession;
                    synchronized (mLock) {
                        resetSession = resetClient || isClientSessionDestroyedLocked(client);
                    }
                    client.setState(isEnabled(), resetSession, resetClient);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            clients.finishBroadcast();
        }
    }

    private boolean isClientSessionDestroyedLocked(IAutoFillManagerClient client) {
        final int sessionCount = mSessions.size();
        for (int i = 0; i < sessionCount; i++) {
            final Session session = mSessions.valueAt(i);
            if (session.getClient().equals(client)) {
                return session.isDestroyed();
            }
        }
        return true;
    }

    boolean isEnabled() {
        return mSetupComplete && mInfo != null && !mDisabled;
    }

    /**
     * Called by {@link Session} when service asked to disable autofill for an app.
     */
    void disableAutofillForApp(@NonNull String packageName, long duration) {
        synchronized (mLock) {
            if (mDisabledApps == null) {
                mDisabledApps = new ArrayMap<>(1);
            }
            long expiration = SystemClock.elapsedRealtime() + duration;
            // Protect it against overflow
            if (expiration < 0) {
                expiration = Long.MAX_VALUE;
            }
            mDisabledApps.put(packageName, expiration);
            int intDuration = duration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) duration;
            mMetricsLogger.write(Helper.newLogMaker(MetricsEvent.AUTOFILL_SERVICE_DISABLED_APP,
                    packageName, getServicePackageName())
                    .setCounterValue(intDuration));
        }
    }

    /**
     * Called by {@link Session} when service asked to disable autofill an app.
     */
    void disableAutofillForActivity(@NonNull ComponentName componentName, long duration) {
        synchronized (mLock) {
            if (mDisabledActivities == null) {
                mDisabledActivities = new ArrayMap<>(1);
            }
            long expiration = SystemClock.elapsedRealtime() + duration;
            // Protect it against overflow
            if (expiration < 0) {
                expiration = Long.MAX_VALUE;
            }
            mDisabledActivities.put(componentName, expiration);
            int intDuration = duration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) duration;
            mMetricsLogger.write(Helper.newLogMaker(MetricsEvent.AUTOFILL_SERVICE_DISABLED_ACTIVITY,
                    componentName.getPackageName(), getServicePackageName())
                    .addTaggedData(MetricsEvent.FIELD_CLASS_NAME, componentName.getClassName())
                    .setCounterValue(intDuration));
        }
    }

    /**
     * Checks if autofill is disabled by service to the given activity.
     */
    private boolean isAutofillDisabledLocked(@NonNull ComponentName componentName) {
        // Check activities first.
        long elapsedTime = 0;
        if (mDisabledActivities != null) {
            elapsedTime = SystemClock.elapsedRealtime();
            final Long expiration = mDisabledActivities.get(componentName);
            if (expiration != null) {
                if (expiration >= elapsedTime) return true;
                // Restriction expired - clean it up.
                if (sVerbose) {
                    Slog.v(TAG, "Removing " + componentName.toShortString() + " from disabled list");
                }
                mDisabledActivities.remove(componentName);
            }
        }

        // Then check apps.
        final String packageName = componentName.getPackageName();
        if (mDisabledApps == null) return false;

        final Long expiration = mDisabledApps.get(packageName);
        if (expiration == null) return false;

        if (elapsedTime == 0) {
            elapsedTime = SystemClock.elapsedRealtime();
        }

        if (expiration >= elapsedTime) return true;

        // Restriction expired - clean it up.
        if (sVerbose)  Slog.v(TAG, "Removing " + packageName + " from disabled list");
        mDisabledApps.remove(packageName);
        return false;
    }

    @Override
    public String toString() {
        return "AutofillManagerServiceImpl: [userId=" + mUserId
                + ", component=" + (mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null) + "]";
    }

    /** Task used to prune abandoned session */
    private class PruneTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... ignored) {
            int numSessionsToRemove;

            SparseArray<IBinder> sessionsToRemove;

            synchronized (mLock) {
                numSessionsToRemove = mSessions.size();
                sessionsToRemove = new SparseArray<>(numSessionsToRemove);

                for (int i = 0; i < numSessionsToRemove; i++) {
                    Session session = mSessions.valueAt(i);

                    sessionsToRemove.put(session.id, session.getActivityTokenLocked());
                }
            }

            IActivityManager am = ActivityManager.getService();

            // Only remove sessions which's activities are not known to the activity manager anymore
            for (int i = 0; i < numSessionsToRemove; i++) {
                try {
                    // The activity manager cannot resolve activities that have been removed
                    if (am.getActivityClassForToken(sessionsToRemove.valueAt(i)) != null) {
                        sessionsToRemove.removeAt(i);
                        i--;
                        numSessionsToRemove--;
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Cannot figure out if activity is finished", e);
                }
            }

            synchronized (mLock) {
                for (int i = 0; i < numSessionsToRemove; i++) {
                    Session sessionToRemove = mSessions.get(sessionsToRemove.keyAt(i));

                    if (sessionToRemove != null && sessionsToRemove.valueAt(i)
                            == sessionToRemove.getActivityTokenLocked()) {
                        if (sessionToRemove.isSavingLocked()) {
                            if (sVerbose) {
                                Slog.v(TAG, "Session " + sessionToRemove.id + " is saving");
                            }
                        } else {
                            if (sDebug) {
                                Slog.i(TAG, "Prune session " + sessionToRemove.id + " ("
                                    + sessionToRemove.getActivityTokenLocked() + ")");
                            }
                            sessionToRemove.removeSelfLocked();
                        }
                    }
                }
            }

            return null;
        }
    }
}
