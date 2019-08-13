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

package com.android.server.pm;

import android.Manifest;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.permission.IPermissionManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.server.FgThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The entity responsible for filtering visibility between apps based on declarations in their
 * manifests.
 */
class AppsFilter {

    private static final String TAG = PackageManagerService.TAG;

    // Forces filtering logic to run for debug purposes.
    // STOPSHIP (b/136675067): should be false after development is complete
    private static final boolean DEBUG_RUN_WHEN_DISABLED = true;

    // Logs all filtering instead of enforcing
    private static final boolean DEBUG_ALLOW_ALL = false;

    @SuppressWarnings("ConstantExpression")
    private static final boolean DEBUG_LOGGING = false | DEBUG_RUN_WHEN_DISABLED | DEBUG_ALLOW_ALL;

    /**
     * This contains a list of packages that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    private final SparseArray<HashMap<String, ArrayList<String>>> mImplicitlyQueryable =
            new SparseArray<>();

    /**
     * A mapping from the set of packages that query other packages via package name to the
     * list of packages that they can see.
     */
    private final HashMap<String, List<String>> mQueriesViaPackage = new HashMap<>();

    /**
     * A mapping from the set of packages that query others via intent to the list
     * of packages that the intents resolve to.
     */
    private final HashMap<String, List<String>> mQueriesViaIntent = new HashMap<>();

    /**
     * A set of packages that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final HashSet<String> mForceQueryable;
    /**
     * A set of packages that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final Set<String> mForceQueryableByDevice;

    /** True if all system apps should be made queryable by default. */
    private final boolean mSystemAppsQueryable;

    private final IPermissionManager mPermissionManager;

    private final AppOpsManager mAppOpsManager;
    private final ConfigProvider mConfigProvider;

    AppsFilter(ConfigProvider configProvider, IPermissionManager permissionManager,
            AppOpsManager appOpsManager, String[] forceQueryableWhitelist,
            boolean systemAppsQueryable) {
        mConfigProvider = configProvider;
        mAppOpsManager = appOpsManager;
        final HashSet<String> forceQueryableByDeviceSet = new HashSet<>();
        Collections.addAll(forceQueryableByDeviceSet, forceQueryableWhitelist);
        this.mForceQueryableByDevice = Collections.unmodifiableSet(forceQueryableByDeviceSet);
        this.mForceQueryable = new HashSet<>();
        mPermissionManager = permissionManager;
        mSystemAppsQueryable = systemAppsQueryable;
    }

    public static AppsFilter create(Context context) {
        // tracks whether the feature is enabled where -1 is unknown, 0 is false and 1 is true;
        final AtomicInteger featureEnabled = new AtomicInteger(-1);

        final boolean forceSystemAppsQueryable =
                context.getResources().getBoolean(R.bool.config_forceSystemPackagesQueryable);
        final String[] forcedQueryablePackageNames;
        if (forceSystemAppsQueryable) {
            // all system apps already queryable, no need to read and parse individual exceptions
            forcedQueryablePackageNames = new String[]{};
        } else {
            forcedQueryablePackageNames =
                    context.getResources().getStringArray(R.array.config_forceQueryablePackages);
            for (int i = 0; i < forcedQueryablePackageNames.length; i++) {
                forcedQueryablePackageNames[i] = forcedQueryablePackageNames[i].intern();
            }
        }
        IPermissionManager permissionmgr =
                (IPermissionManager) ServiceManager.getService("permissionmgr");
        return new AppsFilter(() -> {
            if (featureEnabled.get() < 0) {
                featureEnabled.set(DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                        "package_query_filtering_enabled", false) ? 1 : 0);
                DeviceConfig.addOnPropertiesChangedListener(
                        DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                        FgThread.getExecutor(),
                        pr -> featureEnabled.set(
                                pr.getBoolean("package_query_filtering_enabled", false) ? 1 : 0));
            }
            return featureEnabled.get() == 1;
        }, permissionmgr,
                context.getSystemService(AppOpsManager.class), forcedQueryablePackageNames,
                forceSystemAppsQueryable);
    }

    /** Returns true if the querying package may query for the potential target package */
    private static boolean canQuery(PackageParser.Package querying,
            PackageParser.Package potentialTarget) {
        if (querying.mQueriesIntents == null) {
            return false;
        }
        for (Intent intent : querying.mQueriesIntents) {
            for (PackageParser.Activity activity : potentialTarget.activities) {
                if (activity.intents != null) {
                    for (PackageParser.ActivityIntentInfo filter : activity.intents) {
                        if (matches(intent, filter)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Returns true if the given intent matches the given filter. */
    private static boolean matches(Intent intent, PackageParser.ActivityIntentInfo filter) {
        return filter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "AppsFilter") > 0;
    }

    /**
     * Marks that a package initiated an interaction with another package, granting visibility of
     * the prior from the former.
     *
     * @param initiatingPackage the package initiating the interaction
     * @param targetPackage     the package being interacted with and thus gaining visibility of the
     *                          initiating package.
     * @param userId            the user in which this interaction was taking place
     */
    private void markAppInteraction(
            PackageSetting initiatingPackage, PackageSetting targetPackage, int userId) {
        HashMap<String, ArrayList<String>> currentUser = mImplicitlyQueryable.get(userId);
        if (currentUser == null) {
            currentUser = new HashMap<>();
            mImplicitlyQueryable.put(userId, currentUser);
        }
        if (!currentUser.containsKey(targetPackage.pkg.packageName)) {
            currentUser.put(targetPackage.pkg.packageName, new ArrayList<>());
        }
        currentUser.get(targetPackage.pkg.packageName).add(initiatingPackage.pkg.packageName);
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkg   the new package being added
     * @param existing all other packages currently on the device.
     */
    public void addPackage(PackageParser.Package newPkg,
            Map<String, PackageParser.Package> existing) {
        // let's re-evaluate the ability of already added packages to see this new package
        if (newPkg.mForceQueryable
                || (mSystemAppsQueryable && (newPkg.isSystem() || newPkg.isUpdatedSystemApp()))) {
            mForceQueryable.add(newPkg.packageName);
        } else {
            for (String packageName : mQueriesViaIntent.keySet()) {
                if (packageName == newPkg.packageName) {
                    continue;
                }
                final PackageParser.Package existingPackage = existing.get(packageName);
                if (canQuery(existingPackage, newPkg)) {
                    mQueriesViaIntent.get(packageName).add(newPkg.packageName);
                }
            }
        }
        // if the new package declares them, let's evaluate its ability to see existing packages
        mQueriesViaIntent.put(newPkg.packageName, new ArrayList<>());
        for (PackageParser.Package existingPackage : existing.values()) {
            if (existingPackage.packageName == newPkg.packageName) {
                continue;
            }
            if (existingPackage.mForceQueryable
                    || (mSystemAppsQueryable
                    && (newPkg.isSystem() || newPkg.isUpdatedSystemApp()))) {
                continue;
            }
            if (canQuery(newPkg, existingPackage)) {
                mQueriesViaIntent.get(newPkg.packageName).add(existingPackage.packageName);
            }
        }
        final ArrayList<String> queriesPackages = new ArrayList<>(
                newPkg.mQueriesPackages == null ? 0 : newPkg.mQueriesPackages.size());
        if (newPkg.mQueriesPackages != null) {
            queriesPackages.addAll(newPkg.mQueriesPackages);
        }
        mQueriesViaPackage.put(newPkg.packageName, queriesPackages);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param packageName the name of the package being removed.
     */
    public void removePackage(String packageName) {
        mForceQueryable.remove(packageName);

        for (int i = 0; i < mImplicitlyQueryable.size(); i++) {
            mImplicitlyQueryable.valueAt(i).remove(packageName);
            for (ArrayList<String> initiators : mImplicitlyQueryable.valueAt(i).values()) {
                initiators.remove(packageName);
            }
        }

        mQueriesViaIntent.remove(packageName);
        for (List<String> declarators : mQueriesViaIntent.values()) {
            declarators.remove(packageName);
        }

        mQueriesViaPackage.remove(packageName);
    }

    /**
     * Returns true if the calling package should not be able to see the target package, false if no
     * filtering should be done.
     *
     * @param callingUid       the uid of the caller attempting to access a package
     * @param callingSetting   the setting attempting to access a package or null if it could not be
     *                         found
     * @param targetPkgSetting the package being accessed
     * @param userId           the user in which this access is being attempted
     */
    public boolean shouldFilterApplication(int callingUid, @Nullable SettingBase callingSetting,
            PackageSetting targetPkgSetting, int userId) {
        if (callingUid < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final boolean featureEnabled = mConfigProvider.isEnabled();
        if (!featureEnabled && !DEBUG_RUN_WHEN_DISABLED) {
            return false;
        }
        if (callingSetting == null) {
            Slog.wtf(TAG, "No setting found for non system uid " + callingUid);
            return true;
        }
        PackageSetting callingPkgSetting = null;
        if (callingSetting instanceof PackageSetting) {
            callingPkgSetting = (PackageSetting) callingSetting;
            if (!shouldFilterApplicationInternal(callingPkgSetting, targetPkgSetting,
                    userId)) {
                // TODO: actually base this on a start / launch (not just a query)
                markAppInteraction(callingPkgSetting, targetPkgSetting, userId);
                return false;
            }
        } else if (callingSetting instanceof SharedUserSetting) {
            final ArraySet<PackageSetting> packageSettings =
                    ((SharedUserSetting) callingSetting).packages;
            if (packageSettings != null && packageSettings.size() > 0) {
                for (int i = 0, max = packageSettings.size(); i < max; i++) {
                    final PackageSetting packageSetting = packageSettings.valueAt(i);
                    if (!shouldFilterApplicationInternal(packageSetting, targetPkgSetting,
                            userId)) {
                        // TODO: actually base this on a start / launch (not just a query)
                        markAppInteraction(packageSetting, targetPkgSetting, userId);
                        return false;
                    }
                    if (callingPkgSetting == null && packageSetting.pkg != null) {
                        callingPkgSetting = packageSetting;
                    }
                }
                if (callingPkgSetting == null) {
                    Slog.wtf(TAG, callingSetting + " does not have any non-null packages!");
                    return true;
                }
            } else {
                Slog.wtf(TAG, callingSetting + " has no packages!");
                return true;
            }
        }

        if (!featureEnabled) {
            return false;
        }
        final int mode = mAppOpsManager
                .checkOpNoThrow(AppOpsManager.OP_QUERY_ALL_PACKAGES, callingUid,
                        callingPkgSetting.pkg.packageName);
        switch (mode) {
            case AppOpsManager.MODE_DEFAULT:
                if (DEBUG_LOGGING) {
                    Slog.d(TAG, "filtered interaction: " + callingPkgSetting.name + " -> "
                            + targetPkgSetting.name + (DEBUG_ALLOW_ALL ? " ALLOWED" : ""));
                }
                return !DEBUG_ALLOW_ALL;
            case AppOpsManager.MODE_ALLOWED:
                // explicitly allowed to see all packages, don't filter
                return false;
            case AppOpsManager.MODE_ERRORED:
                // deny / error: let's log so developer can audit usages
                Slog.i(TAG, callingPkgSetting.pkg.packageName
                        + " blocked from accessing " + targetPkgSetting.pkg.packageName);
            case AppOpsManager.MODE_IGNORED:
                // fall through
            default:
                return true;
        }
    }

    private boolean shouldFilterApplicationInternal(
            PackageSetting callingPkgSetting, PackageSetting targetPkgSetting, int userId) {
        final String callingName = callingPkgSetting.pkg.packageName;
        final PackageParser.Package targetPkg = targetPkgSetting.pkg;

        // This package isn't technically installed and won't be written to settings, so we can
        // treat it as filtered until it's available again.
        if (targetPkg == null) {
            return true;
        }
        final String targetName = targetPkg.packageName;
        if (callingPkgSetting.pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.R) {
            return false;
        }
        if (isImplicitlyQueryableSystemApp(targetPkgSetting)) {
            return false;
        }
        if (targetPkg.mForceQueryable) {
            return false;
        }
        if (mForceQueryable.contains(targetName)) {
            return false;
        }
        if (mQueriesViaPackage.containsKey(callingName)
                && mQueriesViaPackage.get(callingName).contains(
                targetName)) {
            // the calling package has explicitly declared the target package; allow
            return false;
        } else if (mQueriesViaIntent.containsKey(callingName)
                && mQueriesViaIntent.get(callingName).contains(targetName)) {
            return false;
        }
        if (mImplicitlyQueryable.get(userId) != null
                && mImplicitlyQueryable.get(userId).containsKey(callingName)
                && mImplicitlyQueryable.get(userId).get(callingName).contains(targetName)) {
            return false;
        }
        try {
            if (mPermissionManager.checkPermission(
                    Manifest.permission.QUERY_ALL_PACKAGES, callingName, userId)
                    == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } catch (RemoteException e) {
            return true;
        }
        return true;
    }

    private boolean isImplicitlyQueryableSystemApp(PackageSetting targetPkgSetting) {
        return targetPkgSetting.isSystem() && (mSystemAppsQueryable
                || mForceQueryableByDevice.contains(targetPkgSetting.pkg.packageName));
    }

    public interface ConfigProvider {
        boolean isEnabled();
    }

}
