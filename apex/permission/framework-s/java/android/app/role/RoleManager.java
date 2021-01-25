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

package android.app.role;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides information about and manages roles.
 * <p>
 * A role is a unique name within the system associated with certain privileges. The list of
 * available roles might change with a system app update, so apps should not make assumption about
 * the availability of roles. Instead, they should always query if the role is available using
 * {@link #isRoleAvailable(String)} before trying to do anything with it. Some predefined role names
 * are available as constants in this class, and a list of possibly available roles can be found in
 * the <a href="{@docRoot}reference/androidx/core/role/package-summary.html">AndroidX Role
 * library</a>.
 * <p>
 * There can be multiple applications qualifying for a role, but only a subset of them can become
 * role holders. To qualify for a role, an application must meet certain requirements, including
 * defining certain components in its manifest. These requirements can be found in the AndroidX
 * Libraries. Then the application will need user consent to become a role holder, which can be
 * requested using {@link android.app.Activity#startActivityForResult(Intent, int)} with the
 * {@code Intent} obtained from {@link #createRequestRoleIntent(String)}.
 * <p>
 * Upon becoming a role holder, the application may be granted certain privileges that are role
 * specific. When the application loses its role, these privileges will also be revoked.
 */
@SystemService(Context.ROLE_SERVICE)
public final class RoleManager {

    private static final String LOG_TAG = RoleManager.class.getSimpleName();

    /**
     * The name of the assistant app role.
     *
     * @see android.service.voice.VoiceInteractionService
     */
    public static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";

    /**
     * The name of the browser role.
     *
     * @see Intent#CATEGORY_APP_BROWSER
     */
    public static final String ROLE_BROWSER = "android.app.role.BROWSER";

    /**
     * The name of the dialer role.
     *
     * @see Intent#ACTION_DIAL
     * @see android.telecom.InCallService
     */
    public static final String ROLE_DIALER = "android.app.role.DIALER";

    /**
     * The name of the SMS role.
     *
     * @see Intent#CATEGORY_APP_MESSAGING
     */
    public static final String ROLE_SMS = "android.app.role.SMS";

    /**
     * The name of the emergency role
     */
    public static final String ROLE_EMERGENCY = "android.app.role.EMERGENCY";

    /**
     * The name of the home role.
     *
     * @see Intent#CATEGORY_HOME
     */
    public static final String ROLE_HOME = "android.app.role.HOME";

    /**
     * The name of the call redirection role.
     * <p>
     * A call redirection app provides a means to re-write the phone number for an outgoing call to
     * place the call through a call redirection service.
     *
     * @see android.telecom.CallRedirectionService
     */
    public static final String ROLE_CALL_REDIRECTION = "android.app.role.CALL_REDIRECTION";

    /**
     * The name of the call screening and caller id role.
     *
     * @see android.telecom.CallScreeningService
     */
    public static final String ROLE_CALL_SCREENING = "android.app.role.CALL_SCREENING";

    /**
     * @hide
     */
    @IntDef(flag = true, value = { MANAGE_HOLDERS_FLAG_DONT_KILL_APP })
    public @interface ManageHoldersFlags {}

    /**
     * Flag parameter for {@link #addRoleHolderAsUser}, {@link #removeRoleHolderAsUser} and
     * {@link #clearRoleHoldersAsUser} to indicate that apps should not be killed when changing
     * their role holder status.
     *
     * @hide
     */
    @SystemApi
    public static final int MANAGE_HOLDERS_FLAG_DONT_KILL_APP = 1;

    /**
     * The action used to request user approval of a role for an application.
     *
     * @hide
     */
    public static final String ACTION_REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE";

    /**
     * The permission required to manage records of role holders in {@link RoleManager} directly.
     *
     * @hide
     */
    public static final String PERMISSION_MANAGE_ROLES_FROM_CONTROLLER =
            "com.android.permissioncontroller.permission.MANAGE_ROLES_FROM_CONTROLLER";

    @NonNull
    private final Context mContext;

    @NonNull
    private final IRoleManager mService;

    @GuardedBy("mListenersLock")
    @NonNull
    private final SparseArray<ArrayMap<OnRoleHoldersChangedListener,
            OnRoleHoldersChangedListenerDelegate>> mListeners = new SparseArray<>();
    @NonNull
    private final Object mListenersLock = new Object();

    @GuardedBy("mRoleControllerManagerLock")
    @Nullable
    private RoleControllerManager mRoleControllerManager;
    private final Object mRoleControllerManagerLock = new Object();

    /**
     * Create a new instance of this class.
     *
     * @param context the {@link Context}
     * @param service the {@link IRoleManager} service
     *
     * @hide
     */
    public RoleManager(@NonNull Context context, @NonNull IRoleManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns an {@code Intent} suitable for passing to
     * {@link android.app.Activity#startActivityForResult(Intent, int)} which prompts the user to
     * grant a role to this application.
     * <p>
     * If the role is granted, the {@code resultCode} will be
     * {@link android.app.Activity#RESULT_OK}, otherwise it will be
     * {@link android.app.Activity#RESULT_CANCELED}.
     *
     * @param roleName the name of requested role
     *
     * @return the {@code Intent} to prompt user to grant the role
     */
    @NonNull
    public Intent createRequestRoleIntent(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Intent intent = new Intent(ACTION_REQUEST_ROLE);
        intent.setPackage(mContext.getPackageManager().getPermissionControllerPackageName());
        intent.putExtra(Intent.EXTRA_ROLE_NAME, roleName);
        return intent;
    }

    /**
     * Check whether a role is available in the system.
     *
     * @param roleName the name of role to checking for
     *
     * @return whether the role is available in the system
     */
    public boolean isRoleAvailable(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        try {
            return mService.isRoleAvailable(roleName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether the calling application is holding a particular role.
     *
     * @param roleName the name of the role to check for
     *
     * @return whether the calling application is holding the role
     */
    public boolean isRoleHeld(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        try {
            return mService.isRoleHeld(roleName, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get package names of the applications holding the role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS}.
     *
     * @param roleName the name of the role to get the role holder for
     *
     * @return a list of package names of the role holders, or an empty list if none.
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public List<String> getRoleHolders(@NonNull String roleName) {
        return getRoleHoldersAsUser(roleName, Process.myUserHandle());
    }

    /**
     * Get package names of the applications holding the role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to get the role holder for
     * @param user the user to get the role holder for
     *
     * @return a list of package names of the role holders, or an empty list if none.
     *
     * @see #addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     * @see #removeRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     * @see #clearRoleHoldersAsUser(String, int, UserHandle, Executor, Consumer)
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public List<String> getRoleHoldersAsUser(@NonNull String roleName, @NonNull UserHandle user) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Objects.requireNonNull(user, "user cannot be null");
        try {
            return mService.getRoleHoldersAsUser(roleName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a specific application to the holders of a role. If the role is exclusive, the previous
     * holder will be replaced.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     * @param flags optional behavior flags
     * @param user the user to add the role holder for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #removeRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     * @see #clearRoleHoldersAsUser(String, int, UserHandle, Executor, Consumer)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
            @ManageHoldersFlags int flags, @NonNull UserHandle user,
            @CallbackExecutor @NonNull Executor executor, @NonNull Consumer<Boolean> callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        Objects.requireNonNull(user, "user cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            mService.addRoleHolderAsUser(roleName, packageName, flags, user.getIdentifier(),
                    createRemoteCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a specific application from the holders of a role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     * @param flags optional behavior flags
     * @param user the user to remove the role holder for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     * @see #clearRoleHoldersAsUser(String, int, UserHandle, Executor, Consumer)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
            @ManageHoldersFlags int flags, @NonNull UserHandle user,
            @CallbackExecutor @NonNull Executor executor, @NonNull Consumer<Boolean> callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        Objects.requireNonNull(user, "user cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            mService.removeRoleHolderAsUser(roleName, packageName, flags, user.getIdentifier(),
                    createRemoteCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove all holders of a role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to remove role holders for
     * @param flags optional behavior flags
     * @param user the user to remove role holders for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     * @see #removeRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void clearRoleHoldersAsUser(@NonNull String roleName, @ManageHoldersFlags int flags,
            @NonNull UserHandle user, @CallbackExecutor @NonNull Executor executor,
            @NonNull Consumer<Boolean> callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Objects.requireNonNull(user, "user cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            mService.clearRoleHoldersAsUser(roleName, flags, user.getIdentifier(),
                    createRemoteCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    private static RemoteCallback createRemoteCallback(@NonNull Executor executor,
            @NonNull Consumer<Boolean> callback) {
        return new RemoteCallback(result -> executor.execute(() -> {
            boolean successful = result != null;
            final long token = Binder.clearCallingIdentity();
            try {
                callback.accept(successful);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }));
    }

    /**
     * Add a listener to observe role holder changes
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.OBSERVE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param executor the {@code Executor} to call the listener on.
     * @param listener the listener to be added
     * @param user the user to add the listener for
     *
     * @see #removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener, UserHandle)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS)
    @SystemApi
    public void addOnRoleHoldersChangedListenerAsUser(@CallbackExecutor @NonNull Executor executor,
            @NonNull OnRoleHoldersChangedListener listener, @NonNull UserHandle user) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");
        Objects.requireNonNull(user, "user cannot be null");
        int userId = user.getIdentifier();
        synchronized (mListenersLock) {
            ArrayMap<OnRoleHoldersChangedListener, OnRoleHoldersChangedListenerDelegate> listeners =
                    mListeners.get(userId);
            if (listeners == null) {
                listeners = new ArrayMap<>();
                mListeners.put(userId, listeners);
            } else {
                if (listeners.containsKey(listener)) {
                    return;
                }
            }
            OnRoleHoldersChangedListenerDelegate listenerDelegate =
                    new OnRoleHoldersChangedListenerDelegate(executor, listener);
            try {
                mService.addOnRoleHoldersChangedListenerAsUser(listenerDelegate, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            listeners.put(listener, listenerDelegate);
        }
    }

    /**
     * Remove a listener observing role holder changes
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.OBSERVE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param listener the listener to be removed
     * @param user the user to remove the listener for
     *
     * @see #addOnRoleHoldersChangedListenerAsUser(Executor, OnRoleHoldersChangedListener,
     *                                             UserHandle)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS)
    @SystemApi
    public void removeOnRoleHoldersChangedListenerAsUser(
            @NonNull OnRoleHoldersChangedListener listener, @NonNull UserHandle user) {
        Objects.requireNonNull(listener, "listener cannot be null");
        Objects.requireNonNull(user, "user cannot be null");
        int userId = user.getIdentifier();
        synchronized (mListenersLock) {
            ArrayMap<OnRoleHoldersChangedListener, OnRoleHoldersChangedListenerDelegate> listeners =
                    mListeners.get(userId);
            if (listeners == null) {
                return;
            }
            OnRoleHoldersChangedListenerDelegate listenerDelegate = listeners.get(listener);
            if (listenerDelegate == null) {
                return;
            }
            try {
                mService.removeOnRoleHoldersChangedListenerAsUser(listenerDelegate,
                        user.getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                mListeners.remove(userId);
            }
        }
    }

    /**
     * Set the names of all the available roles. Should only be called from
     * {@link android.app.role.RoleControllerService}.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@link #PERMISSION_MANAGE_ROLES_FROM_CONTROLLER}.
     *
     * @param roleNames the names of all the available roles
     *
     * @deprecated This is only usable by the role controller service, which is an internal
     *             implementation detail inside role.
     *
     * @hide
     */
    @Deprecated
    @RequiresPermission(PERMISSION_MANAGE_ROLES_FROM_CONTROLLER)
    @SystemApi
    public void setRoleNamesFromController(@NonNull List<String> roleNames) {
        Objects.requireNonNull(roleNames, "roleNames cannot be null");
        try {
            mService.setRoleNamesFromController(roleNames);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a specific application to the holders of a role, only modifying records inside
     * {@link RoleManager}. Should only be called from
     * {@link android.app.role.RoleControllerService}.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@link #PERMISSION_MANAGE_ROLES_FROM_CONTROLLER}.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     *
     * @return whether the operation was successful, and will also be {@code true} if a matching
     *         role holder is already found.
     *
     * @see #getRoleHolders(String)
     * @see #removeRoleHolderFromController(String, String)
     *
     * @deprecated This is only usable by the role controller service, which is an internal
     *             implementation detail inside role.
     *
     * @hide
     */
    @Deprecated
    @RequiresPermission(PERMISSION_MANAGE_ROLES_FROM_CONTROLLER)
    @SystemApi
    public boolean addRoleHolderFromController(@NonNull String roleName,
            @NonNull String packageName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        try {
            return mService.addRoleHolderFromController(roleName, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a specific application from the holders of a role, only modifying records inside
     * {@link RoleManager}. Should only be called from
     * {@link android.app.role.RoleControllerService}.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@link #PERMISSION_MANAGE_ROLES_FROM_CONTROLLER}.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     *
     * @return whether the operation was successful, and will also be {@code true} if no matching
     *         role holder was found to remove.
     *
     * @see #getRoleHolders(String)
     * @see #addRoleHolderFromController(String, String)
     *
     * @deprecated This is only usable by the role controller service, which is an internal
     *             implementation detail inside role.
     *
     * @hide
     */
    @Deprecated
    @RequiresPermission(PERMISSION_MANAGE_ROLES_FROM_CONTROLLER)
    @SystemApi
    public boolean removeRoleHolderFromController(@NonNull String roleName,
            @NonNull String packageName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        try {
            return mService.removeRoleHolderFromController(roleName, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of all roles that the given package is currently holding
     *
     * @param packageName the package name
     * @return the list of role names
     *
     * @deprecated This is only usable by the role controller service, which is an internal
     *             implementation detail inside role.
     *
     * @hide
     */
    @Deprecated
    @NonNull
    @RequiresPermission(PERMISSION_MANAGE_ROLES_FROM_CONTROLLER)
    @SystemApi
    public List<String> getHeldRolesFromController(@NonNull String packageName) {
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        try {
            return mService.getHeldRolesFromController(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the role holder of {@link #ROLE_BROWSER} without requiring
     * {@link Manifest.permission#OBSERVE_ROLE_HOLDERS}, as in
     * {@link android.content.pm.PackageManager#getDefaultBrowserPackageNameAsUser(int)}
     *
     * @param userId the user ID
     * @return the package name of the default browser, or {@code null} if none
     *
     * @hide
     */
    @Nullable
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public String getBrowserRoleHolder(@UserIdInt int userId) {
        try {
            return mService.getBrowserRoleHolder(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the role holder of {@link #ROLE_BROWSER} requiring
     * {@link Manifest.permission.SET_PREFERRED_APPLICATIONS} instead of
     * {@link Manifest.permission#MANAGE_ROLE_HOLDERS}, as in
     * {@link android.content.pm.PackageManager#setDefaultBrowserPackageNameAsUser(String, int)}
     *
     * @param packageName the package name of the default browser, or {@code null} if none
     * @param userId the user ID
     * @return whether the default browser was set successfully
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(Manifest.permission.SET_PREFERRED_APPLICATIONS)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public boolean setBrowserRoleHolder(@Nullable String packageName, @UserIdInt int userId) {
        try {
            return mService.setBrowserRoleHolder(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allows getting the role holder for {@link #ROLE_SMS} without requiring
     * {@link Manifest.permission#OBSERVE_ROLE_HOLDERS}, as in
     * {@link android.provider.Telephony.Sms#getDefaultSmsPackage(Context)}.
     *
     * @param userId the user ID to get the default SMS package for
     * @return the package name of the default SMS app, or {@code null} if none
     *
     * @hide
     */
    @Nullable
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public String getSmsRoleHolder(@UserIdInt int userId) {
        try {
            return mService.getSmsRoleHolder(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether a role should be visible to user.
     *
     * @param roleName name of the role to check for
     * @param executor the executor to execute callback on
     * @param callback the callback to receive whether the role should be visible to user
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void isRoleVisible(@NonNull String roleName,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        getRoleControllerManager().isRoleVisible(roleName, executor, callback);
    }

    /**
     * Check whether an application is visible for a role.
     *
     * While an application can be qualified for a role, it can still stay hidden from user (thus
     * not visible). If an application is visible for a role, we may show things related to the role
     * for it, e.g. showing an entry pointing to the role settings in its application info page.
     *
     * @param roleName the name of the role to check for
     * @param packageName the package name of the application to check for
     * @param executor the executor to execute callback on
     * @param callback the callback to receive whether the application is visible for the role
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void isApplicationVisibleForRole(@NonNull String roleName, @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        getRoleControllerManager().isApplicationVisibleForRole(roleName, packageName, executor,
                callback);
    }

    @NonNull
    private RoleControllerManager getRoleControllerManager() {
        synchronized (mRoleControllerManagerLock) {
            if (mRoleControllerManager == null) {
                mRoleControllerManager = new RoleControllerManager(mContext);
            }
            return mRoleControllerManager;
        }
    }

    private static class OnRoleHoldersChangedListenerDelegate
            extends IOnRoleHoldersChangedListener.Stub {

        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final OnRoleHoldersChangedListener mListener;

        OnRoleHoldersChangedListenerDelegate(@NonNull Executor executor,
                @NonNull OnRoleHoldersChangedListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mListener.onRoleHoldersChanged(roleName, UserHandle.of(userId)));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
