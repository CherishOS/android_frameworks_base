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
package com.android.server.pm;

import static android.app.AppOpsManager.OP_INTERACT_ACROSS_PROFILES;
import static android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY;
import static android.content.pm.CrossProfileApps.ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.app.IApplicationThread;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.ICrossProfileApps;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CrossProfileAppsServiceImpl extends ICrossProfileApps.Stub {
    private static final String TAG = "CrossProfileAppsService";

    private Context mContext;
    private Injector mInjector;
    private AppOpsService mAppOpsService;

    public CrossProfileAppsServiceImpl(Context context) {
        this(context, new InjectorImpl(context));
    }

    @VisibleForTesting
    CrossProfileAppsServiceImpl(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
    }

    @Override
    public List<UserHandle> getTargetUserProfiles(String callingPackage) {
        Objects.requireNonNull(callingPackage);

        verifyCallingPackage(callingPackage);

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.CROSS_PROFILE_APPS_GET_TARGET_USER_PROFILES)
                .setStrings(new String[] {callingPackage})
                .write();

        return getTargetUserProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
    }

    @Override
    public void startActivityAsUser(
            IApplicationThread caller,
            String callingPackage,
            ComponentName component,
            @UserIdInt int userId,
            boolean launchMainActivity) throws RemoteException {
        Objects.requireNonNull(callingPackage);
        Objects.requireNonNull(component);

        verifyCallingPackage(callingPackage);

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.CROSS_PROFILE_APPS_START_ACTIVITY_AS_USER)
                .setStrings(new String[] {callingPackage})
                .write();

        final int callerUserId = mInjector.getCallingUserId();
        final int callingUid = mInjector.getCallingUid();
        final int callingPid = mInjector.getCallingPid();

        List<UserHandle> allowedTargetUsers = getTargetUserProfilesUnchecked(
                callingPackage, callerUserId);
        if (!allowedTargetUsers.contains(UserHandle.of(userId))) {
            throw new SecurityException(callingPackage + " cannot access unrelated user " + userId);
        }

        // Verify that caller package is starting activity in its own package.
        if (!callingPackage.equals(component.getPackageName())) {
            throw new SecurityException(
                    callingPackage + " attempts to start an activity in other package - "
                            + component.getPackageName());
        }

        // Verify that target activity does handle the intent correctly.
        final Intent launchIntent = new Intent();
        if (launchMainActivity) {
            launchIntent.setAction(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // Only package name is set here, as opposed to component name, because intent action
            // and category are ignored if component name is present while we are resolving intent.
            launchIntent.setPackage(component.getPackageName());
        } else {
            // If the main activity is not being launched and the users are different, the caller
            // must have the required permission and the users must be in the same profile group
            // in order to launch any of its own activities.
            if (callerUserId != userId) {
                final int permissionFlag =  PermissionChecker.checkPermissionForPreflight(
                        mContext,
                        android.Manifest.permission.INTERACT_ACROSS_PROFILES,
                        callingPid,
                        callingUid,
                        callingPackage);
                if (permissionFlag != PermissionChecker.PERMISSION_GRANTED
                        || !isSameProfileGroup(callerUserId, userId)) {
                    throw new SecurityException("Attempt to launch activity without required "
                            + android.Manifest.permission.INTERACT_ACROSS_PROFILES + " permission"
                            + " or target user is not in the same profile group.");
                }
            }
            launchIntent.setComponent(component);
        }
        verifyActivityCanHandleIntentAndExported(launchIntent, component, callingUid, userId);

        launchIntent.setPackage(null);
        launchIntent.setComponent(component);
        mInjector.getActivityTaskManagerInternal().startActivityAsUser(
                caller, callingPackage, launchIntent,
                launchMainActivity
                        ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                        : null,
                userId);
    }

    @Override
    public boolean canRequestInteractAcrossProfiles(String callingPackage) {
        Objects.requireNonNull(callingPackage);
        verifyCallingPackage(callingPackage);
        return canRequestInteractAcrossProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
    }

    private boolean canRequestInteractAcrossProfilesUnchecked(
            String packageName, @UserIdInt int userId) {
        List<UserHandle> targetUserProfiles = getTargetUserProfilesUnchecked(packageName, userId);
        if (targetUserProfiles.isEmpty()) {
            return false;
        }
        if (!hasRequestedAppOpPermission(
                AppOpsManager.opToPermission(OP_INTERACT_ACROSS_PROFILES), packageName)) {
            return false;
        }
        return isCrossProfilePackageWhitelisted(packageName);
    }

    private boolean hasRequestedAppOpPermission(String permission, String packageName) {
        try {
            String[] packages =
                    mInjector.getIPackageManager().getAppOpPermissionPackages(permission);
            return ArrayUtils.contains(packages, packageName);
        } catch (RemoteException exc) {
            Slog.e(TAG, "PackageManager dead. Cannot get permission info");
            return false;
        }
    }

    @Override
    public boolean canInteractAcrossProfiles(String callingPackage) {
        Objects.requireNonNull(callingPackage);
        verifyCallingPackage(callingPackage);

        final List<UserHandle> targetUserProfiles = getTargetUserProfilesUnchecked(
                callingPackage, mInjector.getCallingUserId());
        if (targetUserProfiles.isEmpty()) {
            return false;
        }
        final int callingUid = mInjector.getCallingUid();
        final int callingPid = mInjector.getCallingPid();
        return isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingUid)
                || isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS, callingUid)
                || PermissionChecker.checkPermissionForPreflight(
                        mContext,
                        Manifest.permission.INTERACT_ACROSS_PROFILES,
                        callingPid,
                        callingUid,
                        callingPackage) == PermissionChecker.PERMISSION_GRANTED;
    }

    private boolean isCrossProfilePackageWhitelisted(String packageName) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            return mInjector.getDevicePolicyManagerInternal()
                    .getAllCrossProfilePackages().contains(packageName);
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    private List<UserHandle> getTargetUserProfilesUnchecked(
            String callingPackage, @UserIdInt int callingUserId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            final int[] enabledProfileIds =
                    mInjector.getUserManager().getEnabledProfileIds(callingUserId);

            List<UserHandle> targetProfiles = new ArrayList<>();
            for (final int userId : enabledProfileIds) {
                if (userId == callingUserId) {
                    continue;
                }
                if (!isPackageEnabled(callingPackage, userId)) {
                    continue;
                }
                targetProfiles.add(UserHandle.of(userId));
            }
            return targetProfiles;
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    private boolean isPackageEnabled(String packageName, @UserIdInt int userId) {
        final int callingUid = mInjector.getCallingUid();
        final long ident = mInjector.clearCallingIdentity();
        try {
            final PackageInfo info = mInjector.getPackageManagerInternal()
                    .getPackageInfo(
                            packageName,
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);
            return info != null && info.applicationInfo.enabled;
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    /**
     * Verify that the specified intent does resolved to the specified component and the resolved
     * activity is exported.
     */
    private void verifyActivityCanHandleIntentAndExported(
            Intent launchIntent, ComponentName component, int callingUid, @UserIdInt int userId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            final List<ResolveInfo> apps =
                    mInjector.getPackageManagerInternal().queryIntentActivities(
                            launchIntent,
                            launchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            callingUid,
                            userId);
            final int size = apps.size();
            for (int i = 0; i < size; ++i) {
                final ActivityInfo activityInfo = apps.get(i).activityInfo;
                if (TextUtils.equals(activityInfo.packageName, component.getPackageName())
                        && TextUtils.equals(activityInfo.name, component.getClassName())
                        && activityInfo.exported) {
                    return;
                }
            }
            throw new SecurityException("Attempt to launch activity without "
                    + " category Intent.CATEGORY_LAUNCHER or activity is not exported" + component);
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setInteractAcrossProfilesAppOp(String packageName, @Mode int newMode) {
        final int callingUid = mInjector.getCallingUid();
        if (!isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingUid)
                && !isPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS, callingUid)) {
            throw new SecurityException(
                    "INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL is required to set the"
                            + " app-op for interacting across profiles.");
        }
        if (!isPermissionGranted(Manifest.permission.MANAGE_APP_OPS_MODES, callingUid)) {
            throw new SecurityException(
                    "MANAGE_APP_OPS_MODES is required to set the app-op for interacting across"
                            + " profiles.");
        }
        final int callingUserId = mInjector.getCallingUserId();
        if (newMode == AppOpsManager.MODE_ALLOWED
                && !canRequestInteractAcrossProfilesUnchecked(packageName, callingUserId)) {
            // The user should not be prompted for apps that cannot request to interact across
            // profiles. However, we return early here if required to avoid race conditions.
            Slog.e(TAG, "Tried to turn on the appop for interacting across profiles for invalid"
                    + " app " + packageName);
            return;
        }
        final int[] profileIds =
                mInjector.getUserManager().getProfileIds(callingUserId, /* enabledOnly= */ false);
        for (int profileId : profileIds) {
            if (!isPackageInstalled(packageName, profileId)) {
                continue;
            }
            setInteractAcrossProfilesAppOpForUser(packageName, newMode, profileId);
        }
    }

    private boolean isPackageInstalled(String packageName, @UserIdInt int userId) {
        final int callingUid = mInjector.getCallingUid();
        final long identity = mInjector.clearCallingIdentity();
        try {
            final PackageInfo info =
                    mInjector.getPackageManagerInternal()
                            .getPackageInfo(
                                    packageName,
                                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                    callingUid,
                                    userId);
            return info != null;
        } finally {
            mInjector.restoreCallingIdentity(identity);
        }
    }

    private void setInteractAcrossProfilesAppOpForUser(
            String packageName, @Mode int newMode, @UserIdInt int userId) {
        try {
            setInteractAcrossProfilesAppOpForUserOrThrow(packageName, newMode, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Missing package " + packageName + " on user ID " + userId, e);
        }
    }

    private void setInteractAcrossProfilesAppOpForUserOrThrow(
            String packageName, @Mode int newMode, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        final int uid = mInjector.getPackageManager()
                .getPackageUidAsUser(packageName, /* flags= */ 0, userId);
        if (currentModeEquals(newMode, packageName, uid)) {
            Slog.w(TAG,"Attempt to set mode to existing value of " + newMode + " for "
                    + packageName + " on user ID " + userId);
            return;
        }
        mInjector.getAppOpsManager()
                .setMode(OP_INTERACT_ACROSS_PROFILES,
                        uid,
                        packageName,
                        newMode);
        sendCanInteractAcrossProfilesChangedBroadcast(packageName, uid, UserHandle.of(userId));
    }

    private boolean currentModeEquals(@Mode int otherMode, String packageName, int uid) {
        final String op =
                AppOpsManager.permissionToOp(Manifest.permission.INTERACT_ACROSS_PROFILES);
        return otherMode ==
                mInjector.getAppOpsManager().unsafeCheckOpNoThrow(op, uid, packageName);
    }

    private void sendCanInteractAcrossProfilesChangedBroadcast(
            String packageName, int uid, UserHandle userHandle) {
        final Intent intent = new Intent(ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED)
                .setPackage(packageName);
        if (!appDeclaresCrossProfileAttribute(uid)) {
            intent.addFlags(FLAG_RECEIVER_REGISTERED_ONLY);
        }
        mInjector.sendBroadcastAsUser(intent, userHandle);
    }

    private boolean appDeclaresCrossProfileAttribute(int uid) {
        return mInjector.getPackageManagerInternal().getPackage(uid).isCrossProfile();
    }

    private boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId) {
        final long ident = mInjector.clearCallingIdentity();
        try {
            return mInjector.getUserManager().isSameProfileGroup(callerUserId, userId);
        } finally {
            mInjector.restoreCallingIdentity(ident);
        }
    }

    /**
     * Verify that the given calling package is belong to the calling UID.
     */
    private void verifyCallingPackage(String callingPackage) {
        mInjector.getAppOpsManager().checkPackage(mInjector.getCallingUid(), callingPackage);
    }

    private boolean isPermissionGranted(String permission, int uid) {
        return PackageManager.PERMISSION_GRANTED == mInjector.checkComponentPermission(
                permission, uid, /* owningUid= */-1, /* exported= */ true);
    }

    private AppOpsService getAppOpsService() {
        if (mAppOpsService == null) {
            IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
            mAppOpsService = (AppOpsService) IAppOpsService.Stub.asInterface(b);
        }
        return mAppOpsService;
    }

    private static class InjectorImpl implements Injector {
        private Context mContext;

        public InjectorImpl(Context context) {
            mContext = context;
        }

        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        public int getCallingUserId() {
            return UserHandle.getCallingUserId();
        }

        public UserHandle getCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        public UserManager getUserManager() {
            return mContext.getSystemService(UserManager.class);
        }

        public PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
        }

        public PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        public AppOpsManager getAppOpsManager() {
            return mContext.getSystemService(AppOpsManager.class);
        }

        @Override
        public ActivityManagerInternal getActivityManagerInternal() {
            return LocalServices.getService(ActivityManagerInternal.class);
        }

        @Override
        public ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return LocalServices.getService(ActivityTaskManagerInternal.class);
        }

        @Override
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        @Override
        public DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
            return LocalServices.getService(DevicePolicyManagerInternal.class);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public int checkComponentPermission(
                String permission, int uid, int owningUid, boolean exported) {
            return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
        }
    }

    @VisibleForTesting
    public interface Injector {
        int getCallingUid();

        int getCallingPid();

        int getCallingUserId();

        UserHandle getCallingUserHandle();

        long clearCallingIdentity();

        void restoreCallingIdentity(long token);

        UserManager getUserManager();

        PackageManagerInternal getPackageManagerInternal();

        PackageManager getPackageManager();

        AppOpsManager getAppOpsManager();

        ActivityManagerInternal getActivityManagerInternal();

        ActivityTaskManagerInternal getActivityTaskManagerInternal();

        IPackageManager getIPackageManager();

        DevicePolicyManagerInternal getDevicePolicyManagerInternal();

        void sendBroadcastAsUser(Intent intent, UserHandle user);

        int checkComponentPermission(String permission, int uid, int owningUid, boolean exported);
    }
}
