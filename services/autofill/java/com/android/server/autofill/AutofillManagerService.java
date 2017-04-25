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

import static android.Manifest.permission.MANAGE_AUTO_FILL;
import static android.content.Context.AUTOFILL_MANAGER_SERVICE;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;
import static com.android.server.autofill.Helper.bundleToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.service.autofill.FillEventHistory;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entry point service for autofill management.
 *
 * <p>This service provides the {@link IAutoFillManager} implementation and keeps a list of
 * {@link AutofillManagerServiceImpl} per user; the real work is done by
 * {@link AutofillManagerServiceImpl} itself.
 */
public final class AutofillManagerService extends SystemService {

    private static final String TAG = "AutofillManagerService";

    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";

    private final Context mContext;
    private final AutoFillUI mUi;

    private final Object mLock = new Object();

    /**
     * Cache of {@link AutofillManagerServiceImpl} per user id.
     * <p>
     * It has to be mapped by user id because the same current user could have simultaneous sessions
     * associated to different user profiles (for example, in a multi-window environment or when
     * device has work profiles).
     */
    @GuardedBy("mLock")
    private SparseArray<AutofillManagerServiceImpl> mServicesCache = new SparseArray<>();

    /**
     * Users disabled due to {@link UserManager} restrictions.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mDisabledUsers = new SparseBooleanArray();

    private final LocalLog mRequestsHistory = new LocalLog(20);

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                final String reason = intent.getStringExtra("reason");
                if (VERBOSE) {
                    Slog.v(TAG, "close system dialogs: " + reason);
                }
                mUi.hideAll();
            }
        }
    };

    public AutofillManagerService(Context context) {
        super(context);
        mContext = context;
        mUi = new AutoFillUI(mContext);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, FgThread.getHandler());

        // Hookup with UserManager to disable service when necessary.
        final UserManager um = context.getSystemService(UserManager.class);
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final List<UserInfo> users = um.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            final boolean disabled = umi.getUserRestriction(userId, UserManager.DISALLOW_AUTOFILL);
            if (disabled) {
                mDisabledUsers.put(userId, disabled);
            }
        }
        umi.addUserRestrictionsListener((userId, newRestrictions, prevRestrictions) -> {
            final boolean disabledNow =
                    newRestrictions.getBoolean(UserManager.DISALLOW_AUTOFILL, false);
            synchronized (mLock) {
                final boolean disabledBefore = mDisabledUsers.get(userId);
                if (disabledBefore == disabledNow) {
                    // Nothing changed, do nothing.
                    if (DEBUG) {
                        Slog.d(TAG, "Restriction not changed for user " + userId + ": "
                                + bundleToString(newRestrictions));
                        return;
                    }
                }
                mDisabledUsers.put(userId, disabledNow);
                updateCachedServiceLocked(userId, disabledNow);
            }
        });
    }

    @Override
    public void onStart() {
        publishBinderService(AUTOFILL_MANAGER_SERVICE, new AutoFillManagerServiceStub());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            new SettingsObserver(BackgroundThread.getHandler());
        }
    }

    @Override
    public void onUnlockUser(int userId) {
        synchronized (mLock) {
            updateCachedServiceLocked(userId);
        }
    }

    @Override
    public void onCleanupUser(int userId) {
        synchronized (mLock) {
            removeCachedServiceLocked(userId);
        }
    }

    /**
     * Gets the service instance for an user.
     *
     * @return service instance.
     */
    @NonNull
    AutofillManagerServiceImpl getServiceForUserLocked(int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        AutofillManagerServiceImpl service = mServicesCache.get(resolvedUserId);
        if (service == null) {
            service = new AutofillManagerServiceImpl(mContext, mLock, mRequestsHistory,
                    resolvedUserId, mUi, mDisabledUsers.get(resolvedUserId));
            mServicesCache.put(userId, service);
        }
        return service;
    }

    /**
     * Peeks the service instance for a user.
     *
     * @return service instance or {@code null} if not already present
     */
    @Nullable
    AutofillManagerServiceImpl peekServiceForUserLocked(int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        return mServicesCache.get(resolvedUserId);
    }

    // Called by Shell command.
    void requestSaveForUser(int userId) {
        Slog.i(TAG, "requestSaveForUser(): " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        final IBinder activityToken = getTopActivityForUser();
        if (activityToken != null) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service == null) {
                    Log.w(TAG, "handleSaveForUser(): no cached service for userId " + userId);
                    return;
                }

                service.requestSaveForUserLocked(activityToken);
            }
        }
    }

    // Called by Shell command.
    void destroySessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "destroySessions() for userId " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.destroySessionsLocked();
                }
            } else {
                final int size = mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    mServicesCache.valueAt(i).destroySessionsLocked();
                }
            }
        }

        try {
            receiver.send(0, new Bundle());
        } catch (RemoteException e) {
            // Just ignore it...
        }
    }

    // Called by Shell command.
    void listSessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "listSessions() for userId " + userId);
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        final Bundle resultData = new Bundle();
        final ArrayList<String> sessions = new ArrayList<>();

        synchronized (mLock) {
            if (userId != UserHandle.USER_ALL) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.listSessionsLocked(sessions);
                }
            } else {
                final int size = mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    mServicesCache.valueAt(i).listSessionsLocked(sessions);
                }
            }
        }

        resultData.putStringArrayList(RECEIVER_BUNDLE_EXTRA_SESSIONS, sessions);
        try {
            receiver.send(0, resultData);
        } catch (RemoteException e) {
            // Just ignore it...
        }
    }

    // Called by Shell command.
    void reset() {
        Slog.i(TAG, "reset()");
        mContext.enforceCallingPermission(MANAGE_AUTO_FILL, TAG);
        synchronized (mLock) {
            final int size = mServicesCache.size();
            for (int i = 0; i < size; i++) {
                mServicesCache.valueAt(i).destroyLocked();
            }
            mServicesCache.clear();
        }
    }

    /**
     * Removes a cached service for a given user.
     */
    private void removeCachedServiceLocked(int userId) {
        final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            service.destroyLocked();
        }
    }

    /**
     * Updates a cached service for a given user.
     */
    private void updateCachedServiceLocked(int userId) {
        updateCachedServiceLocked(userId, mDisabledUsers.get(userId));
    }

    /**
     * Updates a cached service for a given user.
     */
    private void updateCachedServiceLocked(int userId, boolean disabled) {
        AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.updateLocked(disabled);
        }
    }

    private IBinder getTopActivityForUser() {
        final List<IBinder> topActivities = LocalServices
                .getService(ActivityManagerInternal.class).getTopVisibleActivities();
        if (VERBOSE) {
            Slog.v(TAG, "Top activities (" + topActivities.size() + "): " + topActivities);
        }
        if (topActivities.isEmpty()) {
            Slog.w(TAG, "Could not get top activity");
            return null;
        }
        return topActivities.get(0);
    }

    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        @Override
        public boolean addClient(IAutoFillManagerClient client, int userId) {
            synchronized (mLock) {
                return getServiceForUserLocked(userId).addClientLocked(client);
            }
        }

        @Override
        public void setAuthenticationResult(Bundle data, int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.setAuthenticationResultLocked(data, sessionId, getCallingUid());
            }
        }

        @Override
        public void setHasCallback(int sessionId, int userId, boolean hasIt) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                service.setHasCallback(sessionId, getCallingUid(), hasIt);
            }
        }

        @Override
        public int startSession(IBinder activityToken, IBinder windowToken, IBinder appCallback,
                AutofillId autofillId, Rect bounds, AutofillValue value, int userId,
                boolean hasCallback, int flags, String packageName) {

            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");
            autofillId = Preconditions.checkNotNull(autofillId, "autoFillId");
            packageName = Preconditions.checkNotNull(packageName, "packageName");

            Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()), "userId");

            try {
                mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(packageName + " is not a valid package", e);
            }

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
                return service.startSessionLocked(activityToken, getCallingUid(), windowToken,
                        appCallback, autofillId, bounds, value, hasCallback, flags, packageName);
            }
        }

        @Override
        public FillEventHistory getFillEventHistory() throws RemoteException {
            UserHandle user = getCallingUserHandle();
            int uid = getCallingUid();

            synchronized (mLock) {
                AutofillManagerServiceImpl service = peekServiceForUserLocked(user.getIdentifier());
                if (service != null) {
                    return service.getFillEventHistory(uid);
                }
            }

            return null;
        }

        @Override
        public boolean restoreSession(int sessionId, IBinder activityToken, IBinder appCallback)
                throws RemoteException {
            activityToken = Preconditions.checkNotNull(activityToken, "activityToken");
            appCallback = Preconditions.checkNotNull(appCallback, "appCallback");

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = mServicesCache.get(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    return service.restoreSession(sessionId, getCallingUid(), activityToken,
                            appCallback);
                }
            }

            return false;
        }

        @Override
        public void setWindow(int sessionId, IBinder windowToken) throws RemoteException {
            windowToken = Preconditions.checkNotNull(windowToken, "windowToken");

            synchronized (mLock) {
                final AutofillManagerServiceImpl service = mServicesCache.get(
                        UserHandle.getCallingUserId());
                if (service != null) {
                    service.setWindow(sessionId, getCallingUid(), windowToken);
                }
            }
        }

        @Override
        public void updateSession(int sessionId, AutofillId id, Rect bounds,
                AutofillValue value, int flags, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.updateSessionLocked(sessionId, getCallingUid(), id, bounds, value,
                            flags);
                }
            }
        }

        @Override
        public void finishSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.finishSessionLocked(sessionId, getCallingUid());
                }
            }
        }

        @Override
        public void cancelSession(int sessionId, int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.cancelSessionLocked(sessionId, getCallingUid());
                }
            }
        }

        @Override
        public void disableOwnedAutofillServices(int userId) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    service.disableOwnedAutofillServicesLocked(Binder.getCallingUid());
                }
            }
        }

        @Override
        public boolean isServiceSupported(int userId) {
            synchronized (mLock) {
                return !mDisabledUsers.get(userId);
            }
        }

        @Override
        public boolean isServiceEnabled(int userId, String packageName) {
            synchronized (mLock) {
                final AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service == null) return false;
                return Objects.equals(packageName, service.getPackageName());
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            synchronized (mLock) {
                pw.print("Disabled users: "); pw.println(mDisabledUsers);
                final int size = mServicesCache.size();
                pw.print("Cached services: ");
                if (size == 0) {
                    pw.println("none");
                } else {
                    pw.println(size);
                    for (int i = 0; i < size; i++) {
                        pw.print("\nService at index "); pw.println(i);
                        final AutofillManagerServiceImpl impl = mServicesCache.valueAt(i);
                        impl.dumpLocked("  ", pw);
                    }
                }
                mUi.dump(pw);
            }
            pw.println("Requests history:");
            mRequestsHistory.reverseDump(fd, pw, args);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new AutofillManagerServiceShellCommand(AutofillManagerService.this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.AUTOFILL_SERVICE), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                updateCachedServiceLocked(userId);
            }
        }
    }
}
