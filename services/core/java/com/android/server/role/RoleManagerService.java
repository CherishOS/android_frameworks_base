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

package com.android.server.role;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.AppOpsManager;
import android.app.role.IOnRoleHoldersChangedListener;
import android.app.role.IRoleManager;
import android.app.role.RoleControllerManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ThrottledRunnable;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for role management.
 *
 * @see RoleManager
 */
public class RoleManagerService extends SystemService implements RoleUserState.Callback {
    private static final String LOG_TAG = RoleManagerService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final long GRANT_DEFAULT_ROLES_INTERVAL_MILLIS = 1000;

    @NonNull
    private final AppOpsManager mAppOpsManager;
    @NonNull
    private final PackageManagerInternal mPackageManagerInternal;
    @NonNull
    private final UserManagerInternal mUserManagerInternal;

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final LegacyRoleStateProvider mLegacyRoleStateProvider;

    /**
     * Maps user id to its state.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RoleUserState> mUserStates = new SparseArray<>();

    /**
     * Maps user id to its controller.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RoleControllerManager> mControllers = new SparseArray<>();

    /**
     * Maps user id to its list of listeners.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RemoteCallbackList<IOnRoleHoldersChangedListener>> mListeners =
            new SparseArray<>();

    @NonNull
    private final Handler mListenerHandler = FgThread.getHandler();

    /**
     * Maps user id to its throttled runnable for granting default roles.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<ThrottledRunnable> mGrantDefaultRolesThrottledRunnables =
            new SparseArray<>();

    public RoleManagerService(@NonNull Context context,
            @NonNull LegacyRoleStateProvider legacyRoleStateProvider) {
        super(context);

        mLegacyRoleStateProvider = legacyRoleStateProvider;

        RoleControllerManager.initializeRemoteServiceComponentName(context);

        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);

        LocalServices.addService(RoleManagerInternal.class, new Internal());

        registerUserRemovedReceiver();
    }

    private void registerUserRemovedReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull Context context, @NonNull Intent intent) {
                if (TextUtils.equals(intent.getAction(), Intent.ACTION_USER_REMOVED)) {
                    int userId = intent.<UserHandle>getParcelableExtra(Intent.EXTRA_USER)
                            .getIdentifier();
                    onRemoveUser(userId);
                }
            }
        }, intentFilter, null, null);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ROLE_SERVICE, new Stub());

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        getContext().registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int userId = UserHandle.getUserId(intent.getIntExtra(Intent.EXTRA_UID, -1));
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Packages changed - re-running initial grants for user "
                            + userId);
                }
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // Package is being upgraded - we're about to get ACTION_PACKAGE_ADDED
                    return;
                }
                maybeGrantDefaultRolesAsync(userId);
            }
        }, intentFilter, null, null);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        maybeGrantDefaultRolesSync(user.getUserHandle().getIdentifier());
    }

    @MainThread
    private void maybeGrantDefaultRolesSync(@UserIdInt int userId) {
        AndroidFuture<Void> future = maybeGrantDefaultRolesInternal(userId);
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(LOG_TAG, "Failed to grant default roles for user " + userId, e);
        }
    }

    private void maybeGrantDefaultRolesAsync(@UserIdInt int userId) {
        ThrottledRunnable runnable;
        synchronized (mLock) {
            runnable = mGrantDefaultRolesThrottledRunnables.get(userId);
            if (runnable == null) {
                runnable = new ThrottledRunnable(FgThread.getHandler(),
                        GRANT_DEFAULT_ROLES_INTERVAL_MILLIS,
                        () -> maybeGrantDefaultRolesInternal(userId));
                mGrantDefaultRolesThrottledRunnables.put(userId, runnable);
            }
        }
        runnable.run();
    }

    @AnyThread
    @NonNull
    private AndroidFuture<Void> maybeGrantDefaultRolesInternal(@UserIdInt int userId) {
        RoleUserState userState = getOrCreateUserState(userId);
        String oldPackagesHash = userState.getPackagesHash();
        String newPackagesHash = computePackageStateHash(userId);
        if (Objects.equals(oldPackagesHash, newPackagesHash)) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Already granted default roles for packages hash "
                        + newPackagesHash);
            }
            return AndroidFuture.completedFuture(null);
        }

        // Some package state has changed, so grant default roles again.
        Slog.i(LOG_TAG, "Granting default roles...");
        AndroidFuture<Void> future = new AndroidFuture<>();
        getOrCreateController(userId).grantDefaultRoles(FgThread.getExecutor(),
                successful -> {
                    if (successful) {
                        userState.setPackagesHash(newPackagesHash);
                        future.complete(null);
                    } else {
                        future.completeExceptionally(new RuntimeException());
                    }
                });
        return future;
    }

    @Nullable
    private String computePackageStateHash(@UserIdInt int userId) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

        mPackageManagerInternal.forEachInstalledPackage(pkg -> {
            try {
                dataOutputStream.writeUTF(pkg.getPackageName());
                dataOutputStream.writeLong(pkg.getLongVersionCode());
                dataOutputStream.writeInt(mPackageManagerInternal.getApplicationEnabledState(
                        pkg.getPackageName(), userId));

                ArraySet<String> enabledComponents =
                        mPackageManagerInternal.getEnabledComponents(pkg.getPackageName(), userId);
                int numComponents = CollectionUtils.size(enabledComponents);
                dataOutputStream.writeInt(numComponents);
                for (int i = 0; i < numComponents; i++) {
                    dataOutputStream.writeUTF(enabledComponents.valueAt(i));
                }

                ArraySet<String> disabledComponents =
                        mPackageManagerInternal.getDisabledComponents(pkg.getPackageName(), userId);
                numComponents = CollectionUtils.size(disabledComponents);
                for (int i = 0; i < numComponents; i++) {
                    dataOutputStream.writeUTF(disabledComponents.valueAt(i));
                }

                for (Signature signature : pkg.getSigningDetails().signatures) {
                    dataOutputStream.write(signature.toByteArray());
                }
            } catch (IOException e) {
                // Never happens for ByteArrayOutputStream and DataOutputStream.
                throw new AssertionError(e);
            }
        }, userId);

        return PackageUtils.computeSha256Digest(byteArrayOutputStream.toByteArray());
    }

    @NonNull
    private RoleUserState getOrCreateUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            RoleUserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new RoleUserState(userId, mLegacyRoleStateProvider, this);
                mUserStates.put(userId, userState);
            }
            return userState;
        }
    }

    @NonNull
    private RoleControllerManager getOrCreateController(@UserIdInt int userId) {
        synchronized (mLock) {
            RoleControllerManager controller = mControllers.get(userId);
            if (controller == null) {
                Context systemContext = getContext();
                Context context;
                try {
                    context = systemContext.createPackageContextAsUser(
                            systemContext.getPackageName(), 0, UserHandle.of(userId));
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
                controller = RoleControllerManager.createWithInitializedRemoteServiceComponentName(
                        FgThread.getHandler(), context);
                mControllers.put(userId, controller);
            }
            return controller;
        }
    }

    @Nullable
    private RemoteCallbackList<IOnRoleHoldersChangedListener> getListeners(@UserIdInt int userId) {
        synchronized (mLock) {
            return mListeners.get(userId);
        }
    }

    @NonNull
    private RemoteCallbackList<IOnRoleHoldersChangedListener> getOrCreateListeners(
            @UserIdInt int userId) {
        synchronized (mLock) {
            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = mListeners.get(userId);
            if (listeners == null) {
                listeners = new RemoteCallbackList<>();
                mListeners.put(userId, listeners);
            }
            return listeners;
        }
    }

    private void onRemoveUser(@UserIdInt int userId) {
        RemoteCallbackList<IOnRoleHoldersChangedListener> listeners;
        RoleUserState userState;
        synchronized (mLock) {
            mGrantDefaultRolesThrottledRunnables.remove(userId);
            listeners = mListeners.get(userId);
            mListeners.remove(userId);
            mControllers.remove(userId);
            userState = mUserStates.get(userId);
            mUserStates.remove(userId);
        }
        if (listeners != null) {
            listeners.kill();
        }
        if (userState != null) {
            userState.destroy();
        }
    }

    @Override
    public void onRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId) {
        mListenerHandler.sendMessage(PooledLambda.obtainMessage(
                RoleManagerService::notifyRoleHoldersChanged, this, roleName, userId));
    }

    @WorkerThread
    private void notifyRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId) {
        RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getListeners(userId);
        if (listeners != null) {
            notifyRoleHoldersChangedForListeners(listeners, roleName, userId);
        }

        RemoteCallbackList<IOnRoleHoldersChangedListener> allUsersListeners = getListeners(
                UserHandle.USER_ALL);
        if (allUsersListeners != null) {
            notifyRoleHoldersChangedForListeners(allUsersListeners, roleName, userId);
        }
    }

    @WorkerThread
    private void notifyRoleHoldersChangedForListeners(
            @NonNull RemoteCallbackList<IOnRoleHoldersChangedListener> listeners,
            @NonNull String roleName, @UserIdInt int userId) {
        int broadcastCount = listeners.beginBroadcast();
        try {
            for (int i = 0; i < broadcastCount; i++) {
                IOnRoleHoldersChangedListener listener = listeners.getBroadcastItem(i);
                try {
                    listener.onRoleHoldersChanged(roleName, userId);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling OnRoleHoldersChangedListener", e);
                }
            }
        } finally {
            listeners.finishBroadcast();
        }
    }

    private class Stub extends IRoleManager.Stub {

        @Override
        public boolean isRoleAvailable(@NonNull String roleName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            int userId = UserHandle.getUserId(getCallingUid());
            return getOrCreateUserState(userId).isRoleAvailable(roleName);
        }

        @Override
        public boolean isRoleHeld(@NonNull String roleName, @NonNull String packageName) {
            int callingUid = getCallingUid();
            mAppOpsManager.checkPackage(callingUid, packageName);

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getUserId(callingUid);
            ArraySet<String> roleHolders = getOrCreateUserState(userId).getRoleHolders(roleName);
            if (roleHolders == null) {
                return false;
            }
            return roleHolders.contains(packageName);
        }

        @NonNull
        @Override
        public List<String> getRoleHoldersAsUser(@NonNull String roleName, @UserIdInt int userId) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return Collections.emptyList();
            }
            enforceCrossUserPermission(userId, false, "getRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "getRoleHoldersAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            ArraySet<String> roleHolders = getOrCreateUserState(userId).getRoleHolders(roleName);
            if (roleHolders == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(roleHolders);
        }

        @Override
        public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            enforceCrossUserPermission(userId, false, "addRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "addRoleHolderAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onAddRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            enforceCrossUserPermission(userId, false, "removeRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "removeRoleHolderAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onRemoveRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void clearRoleHoldersAsUser(@NonNull String roleName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            enforceCrossUserPermission(userId, false, "clearRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "clearRoleHoldersAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onClearRoleHolders(roleName, flags, callback);
        }

        @Override
        public void addOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            enforceCrossUserPermission(userId, true, "addOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "addOnRoleHoldersChangedListenerAsUser");

            Objects.requireNonNull(listener, "listener cannot be null");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getOrCreateListeners(
                    userId);
            listeners.register(listener);
        }

        @Override
        public void removeOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            enforceCrossUserPermission(userId, true, "removeOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "removeOnRoleHoldersChangedListenerAsUser");

            Objects.requireNonNull(listener, "listener cannot be null");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getListeners(userId);
            if (listener == null) {
                return;
            }
            listeners.unregister(listener);
        }

        @Override
        public void setRoleNamesFromController(@NonNull List<String> roleNames) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "setRoleNamesFromController");

            Objects.requireNonNull(roleNames, "roleNames cannot be null");

            int userId = UserHandle.getUserId(Binder.getCallingUid());
            getOrCreateUserState(userId).setRoleNames(roleNames);
        }

        @Override
        public boolean addRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "addRoleHolderFromController");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getUserId(Binder.getCallingUid());
            return getOrCreateUserState(userId).addRoleHolder(roleName, packageName);
        }

        @Override
        public boolean removeRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "removeRoleHolderFromController");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getUserId(Binder.getCallingUid());
            return getOrCreateUserState(userId).removeRoleHolder(roleName, packageName);
        }

        @Override
        public List<String> getHeldRolesFromController(@NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "getRolesHeldFromController");

            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getUserId(Binder.getCallingUid());
            return getOrCreateUserState(userId).getHeldRoles(packageName);
        }

        private void enforceCrossUserPermission(@UserIdInt int userId, boolean allowAll,
                @NonNull String message) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (userId == callingUserId) {
                return;
            }
            Preconditions.checkArgument(userId >= UserHandle.USER_SYSTEM
                    || (allowAll && userId == UserHandle.USER_ALL), "Invalid user " + userId);
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
            if (callingUid == Process.SHELL_UID && userId >= UserHandle.USER_SYSTEM) {
                if (mUserManagerInternal.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES,
                        userId)) {
                    throw new SecurityException("Shell does not have permission to access user "
                            + userId);
                }
            }
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new RoleManagerShellCommand(this).exec(this, in.getFileDescriptor(),
                    out.getFileDescriptor(), err.getFileDescriptor(), args);
        }

        @Nullable
        @Override
        public String getBrowserRoleHolder(@UserIdInt int userId) {
            final int callingUid = Binder.getCallingUid();
            if (UserHandle.getUserId(callingUid) != userId) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
            }
            if (mPackageManagerInternal.getInstantAppPackageName(callingUid) != null) {
                return null;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                return CollectionUtils.firstOrNull(getRoleHoldersAsUser(RoleManager.ROLE_BROWSER,
                        userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean setBrowserRoleHolder(@Nullable String packageName, @UserIdInt int userId) {
            final Context context = getContext();
            context.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            if (UserHandle.getUserId(Binder.getCallingUid()) != userId) {
                context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
            }

            if (!mUserManagerInternal.exists(userId)) {
                return false;
            }

            final AndroidFuture<Void> future = new AndroidFuture<>();
            final RemoteCallback callback = new RemoteCallback(result -> {
                boolean successful = result != null;
                if (successful) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(new RuntimeException());
                }
            });
            final long identity = Binder.clearCallingIdentity();
            try {
                if (packageName != null) {
                    addRoleHolderAsUser(RoleManager.ROLE_BROWSER, packageName, 0, userId, callback);
                } else {
                    clearRoleHoldersAsUser(RoleManager.ROLE_BROWSER, 0, userId, callback);
                }
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    Slog.e(LOG_TAG, "Exception while setting default browser: " + packageName, e);
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            return true;
        }

        @Override
        public String getSmsRoleHolder(int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return CollectionUtils.firstOrNull(getRoleHoldersAsUser(RoleManager.ROLE_SMS,
                        userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!checkDumpPermission("role", fout)) {
                return;
            }

            boolean dumpAsProto = args != null && ArrayUtils.contains(args, "--proto");
            DualDumpOutputStream dumpOutputStream;
            if (dumpAsProto) {
                dumpOutputStream = new DualDumpOutputStream(new ProtoOutputStream(
                        new FileOutputStream(fd)));
            } else {
                fout.println("ROLE MANAGER STATE (dumpsys role):");
                dumpOutputStream = new DualDumpOutputStream(new IndentingPrintWriter(fout, "  "));
            }

            synchronized (mLock) {
                final int userStatesSize = mUserStates.size();
                for (int i = 0; i < userStatesSize; i++) {
                    final RoleUserState userState = mUserStates.valueAt(i);

                    userState.dump(dumpOutputStream, "user_states",
                            RoleManagerServiceDumpProto.USER_STATES);
                }
            }

            dumpOutputStream.flush();
        }

        private boolean checkDumpPermission(@NonNull String serviceName,
                @NonNull PrintWriter writer) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                writer.println("Permission Denial: can't dump " + serviceName + " from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " due to missing " + android.Manifest.permission.DUMP + " permission");
                return false;
            } else {
                return true;
            }
        }
    }

    private class Internal extends RoleManagerInternal {
        @NonNull
        @Override
        public ArrayMap<String, ArraySet<String>> getRolesAndHolders(@UserIdInt int userId) {
            return getOrCreateUserState(userId).getRolesAndHolders();
        }
    }
}
