/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.permission.PermissionManagerInternal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Internal interfaces services.
 *
 * TODO: Should be merged into PermissionManagerInternal, but currently uses internal classes.
 */
public abstract class PermissionManagerServiceInternal extends PermissionManagerInternal {
    /**
     * Callbacks invoked when interesting actions have been taken on a permission.
     * <p>
     * NOTE: The current arguments are merely to support the existing use cases. This
     * needs to be properly thought out with appropriate arguments for each of the
     * callback methods.
     */
    public static class PermissionCallback {
        public void onGidsChanged(@AppIdInt int appId, @UserIdInt int userId) {
        }
        public void onPermissionChanged() {
        }
        public void onPermissionGranted(int uid, @UserIdInt int userId) {
        }
        public void onInstallPermissionGranted() {
        }
        public void onPermissionRevoked(int uid, @UserIdInt int userId) {
        }
        public void onInstallPermissionRevoked() {
        }
        public void onPermissionUpdated(@UserIdInt int[] updatedUserIds, boolean sync) {
        }
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
        }
        public void onPermissionRemoved() {
        }
        public void onInstallPermissionUpdated() {
        }
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
        }
    }

    public abstract void systemReady();

    public abstract boolean isPermissionsReviewRequired(@NonNull PackageParser.Package pkg,
            @UserIdInt int userId);

    public abstract void grantRuntimePermission(
            @NonNull String permName, @NonNull String packageName, boolean overridePolicy,
            int callingUid, int userId, @Nullable PermissionCallback callback);
    public abstract void grantRuntimePermissionsGrantedToDisabledPackage(
            @NonNull PackageParser.Package pkg, int callingUid,
            @Nullable PermissionCallback callback);
    public abstract void grantRequestedRuntimePermissions(
            @NonNull PackageParser.Package pkg, @NonNull int[] userIds,
            @NonNull String[] grantedPermissions, int callingUid,
            @Nullable PermissionCallback callback);
    public abstract @Nullable List<String> getWhitelistedRestrictedPermissions(
            @NonNull PackageParser.Package pkg,
            @PackageManager.PermissionWhitelistFlags int whitelistFlags, int userId);
    public abstract void setWhitelistedRestrictedPermissions(
            @NonNull PackageParser.Package pkg, @NonNull int[] userIds,
            @NonNull List<String> permissions, int callingUid,
            @PackageManager.PermissionWhitelistFlags int whitelistFlags,
            @Nullable PermissionCallback callback);
    public abstract void revokeRuntimePermission(@NonNull String permName,
            @NonNull String packageName, boolean overridePolicy, int userId,
            @Nullable PermissionCallback callback);

    /**
     * Update permissions when a package changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param packageName The package that is updated
     * @param pkg The package that is updated, or {@code null} if package is deleted
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updatePermissions(@NonNull String packageName,
            @Nullable PackageParser.Package pkg,
            @NonNull Collection<PackageParser.Package> allPackages,
            @NonNull PermissionCallback callback);

    /**
     * Update all permissions for all apps.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param volumeUuid The volume of the packages to be updated, {@code null} for all volumes
     * @param allPackages All currently known packages
     * @param callback Callback to call after permission changes
     */
    public abstract void updateAllPermissions(@Nullable String volumeUuid, boolean sdkUpdate,
            @NonNull Collection<PackageParser.Package> allPackages,
            @NonNull PermissionCallback callback);

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     * @param allPackageNames All packages
     * @param permissionCallback Callback for permission changed
     */
    public abstract void revokeRuntimePermissionsIfGroupChanged(
            @NonNull PackageParser.Package newPackage,
            @NonNull PackageParser.Package oldPackage,
            @NonNull ArrayList<String> allPackageNames,
            @NonNull PermissionCallback permissionCallback);

    /**
     * Add all permissions in the given package.
     * <p>
     * NOTE: argument {@code groupTEMP} is temporary until mPermissionGroups is moved to
     * the permission settings.
     */
    public abstract void addAllPermissions(@NonNull PackageParser.Package pkg, boolean chatty);
    public abstract void addAllPermissionGroups(@NonNull PackageParser.Package pkg, boolean chatty);
    public abstract void removeAllPermissions(@NonNull PackageParser.Package pkg, boolean chatty);

    /** Retrieve the packages that have requested the given app op permission */
    public abstract @Nullable String[] getAppOpPermissionPackages(
            @NonNull String permName, int callingUid);

    public abstract int getPermissionFlags(@NonNull String permName,
            @NonNull String packageName, int callingUid, int userId);

    /**
     * Updates the flags associated with a permission by replacing the flags in
     * the specified mask with the provided flag values.
     */
    public abstract void updatePermissionFlags(@NonNull String permName,
            @NonNull String packageName, int flagMask, int flagValues, int callingUid, int userId,
            boolean overridePolicy, @Nullable PermissionCallback callback);

    public abstract int checkPermission(@NonNull String permName, @NonNull String packageName,
            int callingUid, int userId);
    public abstract int checkUidPermission(@NonNull String permName,
            @Nullable PackageParser.Package pkg, int uid, int callingUid);

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userid} is not for the caller.
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell, @NonNull String message);
    /**
     * @see #enforceCrossUserPermission(int, int, boolean, boolean, String)
     * @param requirePermissionWhenSameUser When {@code true}, still require the cross user
     * permission to be held even if the callingUid and userId reference the same user.
     */
    public abstract void enforceCrossUserPermission(int callingUid, int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, @NonNull String message);
    public abstract void enforceGrantRevokeRuntimePermissionPermissions(@NonNull String message);

    public abstract @NonNull PermissionSettings getPermissionSettings();
    public abstract @NonNull DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy();

    /** HACK HACK methods to allow for partial migration of data to the PermissionManager class */
    public abstract @Nullable BasePermission getPermissionTEMP(@NonNull String permName);

    /** Get all permission that have a certain protection level */
    public abstract @NonNull ArrayList<PermissionInfo> getAllPermissionWithProtectionLevel(
            @PermissionInfo.Protection int protectionLevel);
}
