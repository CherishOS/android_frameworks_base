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

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import android.Manifest;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.ComponentParseUtils;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedComponent;
import android.content.pm.parsing.ComponentParseUtils.ParsedIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.net.Uri;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseSetArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.FgThread;
import com.android.server.om.OverlayReferenceMapper;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * The entity responsible for filtering visibility between apps based on declarations in their
 * manifests.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class AppsFilter {

    private static final String TAG = "AppsFilter";

    // Logs all filtering instead of enforcing
    private static final boolean DEBUG_ALLOW_ALL = false;

    @SuppressWarnings("ConstantExpression")
    private static final boolean DEBUG_LOGGING = false | DEBUG_ALLOW_ALL;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    private final SparseSetArray<Integer> mImplicitlyQueryable = new SparseSetArray<>();

    /**
     * A mapping from the set of App IDs that query other App IDs via package name to the
     * list of packages that they can see.
     */
    private final SparseSetArray<Integer> mQueriesViaPackage = new SparseSetArray<>();

    /**
     * A mapping from the set of App IDs that query others via intent to the list
     * of packages that the intents resolve to.
     */
    private final SparseSetArray<Integer> mQueriesViaIntent = new SparseSetArray<>();

    /**
     * A set of App IDs that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final ArraySet<Integer> mForceQueryable = new ArraySet<>();

    /**
     * The set of package names provided by the device that should be force queryable regardless of
     * their manifest contents.
     */
    private final String[] mForceQueryableByDevicePackageNames;

    /** True if all system apps should be made queryable by default. */
    private final boolean mSystemAppsQueryable;

    private final FeatureConfig mFeatureConfig;

    private final OverlayReferenceMapper mOverlayReferenceMapper;

    AppsFilter(FeatureConfig featureConfig, String[] forceQueryableWhitelist,
            boolean systemAppsQueryable,
            @Nullable OverlayReferenceMapper.Provider overlayProvider) {
        mFeatureConfig = featureConfig;
        mForceQueryableByDevicePackageNames = forceQueryableWhitelist;
        mSystemAppsQueryable = systemAppsQueryable;
        mOverlayReferenceMapper = new OverlayReferenceMapper(true /*deferRebuild*/,
                overlayProvider);
    }

    public interface FeatureConfig {
        /** Called when the system is ready and components can be queried. */
        void onSystemReady();

        /** @return true if we should filter apps at all. */
        boolean isGloballyEnabled();

        /** @return true if the feature is enabled for the given package. */
        boolean packageIsEnabled(AndroidPackage pkg);
    }

    private static class FeatureConfigImpl implements FeatureConfig {
        private static final String FILTERING_ENABLED_NAME = "package_query_filtering_enabled";
        private final PackageManagerService.Injector mInjector;
        private volatile boolean mFeatureEnabled = false;

        private FeatureConfigImpl(PackageManagerService.Injector injector) {
            mInjector = injector;
        }

        @Override
        public void onSystemReady() {
            mFeatureEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FILTERING_ENABLED_NAME, false);
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FgThread.getExecutor(),
                    properties -> {
                        if (properties.getKeyset().contains(FILTERING_ENABLED_NAME)) {
                            synchronized (FeatureConfigImpl.this) {
                                mFeatureEnabled = properties.getBoolean(FILTERING_ENABLED_NAME,
                                        false);
                            }
                        }
                    });
        }

        @Override
        public boolean isGloballyEnabled() {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "isGloballyEnabled");
            try {
                return mFeatureEnabled;
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }

        @Override
        public boolean packageIsEnabled(AndroidPackage pkg) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "packageIsEnabled");
            try {
                // TODO(b/135203078): Do not use toAppInfo
                return mInjector.getCompatibility().isChangeEnabled(
                        PackageManager.FILTER_APPLICATION_QUERY, pkg.toAppInfoWithoutState());
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
    }

    public static AppsFilter create(PackageManagerService.Injector injector) {
        final boolean forceSystemAppsQueryable =
                injector.getContext().getResources()
                        .getBoolean(R.bool.config_forceSystemPackagesQueryable);
        final FeatureConfig featureConfig = new FeatureConfigImpl(injector);
        final String[] forcedQueryablePackageNames;
        if (forceSystemAppsQueryable) {
            // all system apps already queryable, no need to read and parse individual exceptions
            forcedQueryablePackageNames = new String[]{};
        } else {
            forcedQueryablePackageNames =
                    injector.getContext().getResources()
                            .getStringArray(R.array.config_forceQueryablePackages);
            for (int i = 0; i < forcedQueryablePackageNames.length; i++) {
                forcedQueryablePackageNames[i] = forcedQueryablePackageNames[i].intern();
            }
        }
        return new AppsFilter(featureConfig, forcedQueryablePackageNames,
                forceSystemAppsQueryable, null);
    }

    /** Returns true if the querying package may query for the potential target package */
    private static boolean canQueryViaIntent(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        if (querying.getQueriesIntents() == null) {
            return false;
        }
        for (Intent intent : querying.getQueriesIntents()) {
            if (matches(intent, potentialTarget)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canQueryViaPackage(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        return querying.getQueriesPackages() != null
                && querying.getQueriesPackages().contains(potentialTarget.getPackageName());
    }

    private static boolean canQueryAsInstaller(PackageSetting querying,
            AndroidPackage potentialTarget) {
        final InstallSource installSource = querying.installSource;
        if (potentialTarget.getPackageName().equals(installSource.installerPackageName)) {
            return true;
        }
        if (!installSource.isInitiatingPackageUninstalled
                && potentialTarget.getPackageName().equals(installSource.initiatingPackageName)) {
            return true;
        }
        return false;
    }

    private static boolean matches(Intent intent, AndroidPackage potentialTarget) {
        for (int p = ArrayUtils.size(potentialTarget.getProviders()) - 1; p >= 0; p--) {
            ParsedProvider provider = potentialTarget.getProviders().get(p);
            if (!provider.isExported()) {
                continue;
            }
            final Uri data = intent.getData();
            if (!"content".equalsIgnoreCase(intent.getScheme()) || data == null
                    || provider.getAuthority() == null) {
                continue;
            }
            StringTokenizer authorities = new StringTokenizer(provider.getAuthority(), ";", false);
            while (authorities.hasMoreElements()) {
                if (Objects.equals(authorities.nextElement(), data.getAuthority())) {
                    return true;
                }
            }
        }
        for (int s = ArrayUtils.size(potentialTarget.getServices()) - 1; s >= 0; s--) {
            ParsedService service = potentialTarget.getServices().get(s);
            if (!service.exported) {
                continue;
            }
            if (matchesAnyFilter(intent, service)) {
                return true;
            }
        }
        for (int a = ArrayUtils.size(potentialTarget.getActivities()) - 1; a >= 0; a--) {
            ParsedActivity activity = potentialTarget.getActivities().get(a);
            if (!activity.exported) {
                continue;
            }
            if (matchesAnyFilter(intent, activity)) {
                return true;
            }
        }
        for (int r = ArrayUtils.size(potentialTarget.getReceivers()) - 1; r >= 0; r--) {
            ParsedActivity receiver = potentialTarget.getReceivers().get(r);
            if (!receiver.exported) {
                continue;
            }
            if (matchesAnyFilter(intent, receiver)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFilter(
            Intent intent, ParsedComponent<? extends ParsedIntentInfo> component) {
        List<? extends ParsedIntentInfo> intents = component.intents;
        for (int i = ArrayUtils.size(intents) - 1; i >= 0; i--) {
            IntentFilter intentFilter = intents.get(i);
            if (intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                    intent.getData(), intent.getCategories(), "AppsFilter") > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Grants access based on an interaction between a calling and target package, granting
     * visibility of the caller from the target.
     *
     * @param callingUid the uid initiating the interaction
     * @param targetUid  the uid being interacted with and thus gaining visibility of the
     *                   initiating uid.
     */
    public void grantImplicitAccess(int callingUid, int targetUid) {
        if (mImplicitlyQueryable.add(targetUid, callingUid) && DEBUG_LOGGING) {
            Slog.wtf(TAG, "implicit access granted: " + callingUid + " -> " + targetUid);
        }
    }

    public void onSystemReady() {
        mFeatureConfig.onSystemReady();
        mOverlayReferenceMapper.rebuildIfDeferred();
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkgSetting    the new setting being added
     * @param existingSettings all other settings currently on the device.
     */
    public void addPackage(PackageSetting newPkgSetting,
            ArrayMap<String, PackageSetting> existingSettings) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "filter.addPackage");
        try {
            final AndroidPackage newPkg = newPkgSetting.pkg;
            if (newPkg == null) {
                // nothing to add
                return;
            }

            final boolean newIsForceQueryable =
                    mForceQueryable.contains(newPkgSetting.appId)
                            /* shared user that is already force queryable */
                            || newPkg.isForceQueryable()
                            || (newPkgSetting.isSystem() && (mSystemAppsQueryable
                            || ArrayUtils.contains(mForceQueryableByDevicePackageNames,
                            newPkg.getPackageName())));
            if (newIsForceQueryable) {
                mForceQueryable.add(newPkgSetting.appId);
            }

            for (int i = existingSettings.size() - 1; i >= 0; i--) {
                final PackageSetting existingSetting = existingSettings.valueAt(i);
                if (existingSetting.appId == newPkgSetting.appId || existingSetting.pkg == null) {
                    continue;
                }
                final AndroidPackage existingPkg = existingSetting.pkg;
                // let's evaluate the ability of already added packages to see this new package
                if (!newIsForceQueryable) {
                    if (canQueryViaIntent(existingPkg, newPkg)) {
                        mQueriesViaIntent.add(existingSetting.appId, newPkgSetting.appId);
                    }
                    if (canQueryViaPackage(existingPkg, newPkg)
                            || canQueryAsInstaller(existingSetting, newPkg)) {
                        mQueriesViaPackage.add(existingSetting.appId, newPkgSetting.appId);
                    }
                }
                // now we'll evaluate our new package's ability to see existing packages
                if (!mForceQueryable.contains(existingSetting.appId)) {
                    if (canQueryViaIntent(newPkg, existingPkg)) {
                        mQueriesViaIntent.add(newPkgSetting.appId, existingSetting.appId);
                    }
                    if (canQueryViaPackage(newPkg, existingPkg)
                            || canQueryAsInstaller(newPkgSetting, existingPkg)) {
                        mQueriesViaPackage.add(newPkgSetting.appId, existingSetting.appId);
                    }
                }
            }

            int existingSize = existingSettings.size();
            ArrayMap<String, AndroidPackage> existingPkgs = new ArrayMap<>(existingSize);
            for (int index = 0; index < existingSize; index++) {
                PackageSetting pkgSetting = existingSettings.valueAt(index);
                if (pkgSetting.pkg != null) {
                    existingPkgs.put(pkgSetting.name, pkgSetting.pkg);
                }
            }
            mOverlayReferenceMapper.addPkg(newPkgSetting.pkg, existingPkgs);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting  the setting of the package being removed.
     * @param allUsers array of all current users on device.
     */
    public void removePackage(PackageSetting setting, int[] allUsers,
            ArrayMap<String, PackageSetting> existingSettings) {
        mForceQueryable.remove(setting.appId);

        for (int u = 0; u < allUsers.length; u++) {
            final int userId = allUsers[u];
            final int removingUid = UserHandle.getUid(userId, setting.appId);
            mImplicitlyQueryable.remove(removingUid);
            for (int i = mImplicitlyQueryable.size() - 1; i >= 0; i--) {
                mImplicitlyQueryable.remove(mImplicitlyQueryable.keyAt(i), removingUid);
            }
        }

        mQueriesViaIntent.remove(setting.appId);
        for (int i = mQueriesViaIntent.size() - 1; i >= 0; i--) {
            mQueriesViaIntent.remove(mQueriesViaIntent.keyAt(i), setting.appId);
        }
        mQueriesViaPackage.remove(setting.appId);
        for (int i = mQueriesViaPackage.size() - 1; i >= 0; i--) {
            mQueriesViaPackage.remove(mQueriesViaPackage.keyAt(i), setting.appId);
        }

        // re-add other shared user members to re-establish visibility between them and other
        // packages
        if (setting.sharedUser != null) {
            for (int i = setting.sharedUser.packages.size() - 1; i >= 0; i--) {
                if (setting.sharedUser.packages.valueAt(i) == setting) {
                    continue;
                }
                addPackage(setting.sharedUser.packages.valueAt(i), existingSettings);
            }
        }

        mOverlayReferenceMapper.removePkg(setting.name);
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
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplication");
        try {
            if (!shouldFilterApplicationInternal(callingUid, callingSetting, targetPkgSetting,
                    userId)) {
                return false;
            }
            if (DEBUG_LOGGING) {
                log(callingSetting, targetPkgSetting,
                        DEBUG_ALLOW_ALL ? "ALLOWED" : "BLOCKED", new RuntimeException());
            }
            return !DEBUG_ALLOW_ALL;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private boolean shouldFilterApplicationInternal(int callingUid, SettingBase callingSetting,
            PackageSetting targetPkgSetting, int userId) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplicationInternal");
        try {
            final boolean featureEnabled = mFeatureConfig.isGloballyEnabled();
            if (!featureEnabled) {
                if (DEBUG_LOGGING) {
                    Slog.d(TAG, "filtering disabled; skipped");
                }
                return false;
            }
            if (callingUid < Process.FIRST_APPLICATION_UID) {
                if (DEBUG_LOGGING) {
                    Slog.d(TAG, "filtering skipped; " + callingUid + " is system");
                }
                return false;
            }
            if (callingSetting == null) {
                Slog.wtf(TAG, "No setting found for non system uid " + callingUid);
                return true;
            }
            final PackageSetting callingPkgSetting;
            final ArraySet<PackageSetting> callingSharedPkgSettings;
            Trace.beginSection("callingSetting instanceof");
            if (callingSetting instanceof PackageSetting) {
                callingPkgSetting = (PackageSetting) callingSetting;
                callingSharedPkgSettings = null;
            } else {
                callingPkgSetting = null;
                callingSharedPkgSettings = ((SharedUserSetting) callingSetting).packages;
            }
            Trace.endSection();

            if (callingPkgSetting != null) {
                if (!mFeatureConfig.packageIsEnabled(callingPkgSetting.pkg)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "DISABLED");
                    }
                    return false;
                }
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    if (!mFeatureConfig.packageIsEnabled(callingSharedPkgSettings.valueAt(i).pkg)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "DISABLED");
                        }
                        return false;
                    }
                }
            }

            // This package isn't technically installed and won't be written to settings, so we can
            // treat it as filtered until it's available again.
            final AndroidPackage targetPkg = targetPkgSetting.pkg;
            if (targetPkg == null) {
                if (DEBUG_LOGGING) {
                    Slog.wtf(TAG, "shouldFilterApplication: " + "targetPkg is null");
                }
                return true;
            }
            final String targetName = targetPkg.getPackageName();
            Trace.beginSection("getAppId");
            final int callingAppId;
            if (callingPkgSetting != null) {
                callingAppId = callingPkgSetting.appId;
            } else {
                callingAppId = callingSharedPkgSettings.valueAt(0).appId; // all should be the same
            }
            final int targetAppId = targetPkgSetting.appId;
            Trace.endSection();
            if (callingAppId == targetAppId) {
                if (DEBUG_LOGGING) {
                    log(callingSetting, targetPkgSetting, "same app id");
                }
                return false;
            }

            try {
                Trace.beginSection("hasPermission");
                if (callingSetting.getPermissionsState().hasPermission(
                        Manifest.permission.QUERY_ALL_PACKAGES, UserHandle.getUserId(callingUid))) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "has query-all permission");
                    }
                    return false;
                }
            } finally {
                Trace.endSection();
            }
            try {
                Trace.beginSection("mForceQueryable");
                if (mForceQueryable.contains(targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "force queryable");
                    }
                    return false;
                }
            } finally {
                Trace.endSection();
            }
            try {
                Trace.beginSection("mQueriesViaPackage");
                if (mQueriesViaPackage.contains(callingAppId, targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "queries package");
                    }
                    return false;
                }
            } finally {
                Trace.endSection();
            }
            try {
                Trace.beginSection("mQueriesViaIntent");
                if (mQueriesViaIntent.contains(callingAppId, targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "queries intent");
                    }
                    return false;
                }
            } finally {
                Trace.endSection();
            }

            try {
                Trace.beginSection("mImplicitlyQueryable");
                final int targetUid = UserHandle.getUid(userId, targetAppId);
                if (mImplicitlyQueryable.contains(callingUid, targetUid)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "implicitly queryable for user");
                    }
                    return false;
                }
            } finally {
                Trace.endSection();
            }
            if (callingPkgSetting != null) {
                if (callingPkgInstruments(callingPkgSetting, targetPkgSetting, targetName)) {
                    return false;
                }
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    if (callingPkgInstruments(callingSharedPkgSettings.valueAt(i),
                            targetPkgSetting, targetName)) {
                        return false;
                    }
                }
            }

            if (callingSharedPkgSettings != null) {
                int size = callingSharedPkgSettings.size();
                for (int index = 0; index < size; index++) {
                    PackageSetting pkgSetting = callingSharedPkgSettings.valueAt(index);
                    if (mOverlayReferenceMapper.isValidActor(targetName, pkgSetting.name)) {
                        if (DEBUG_LOGGING) {
                            log(callingPkgSetting, targetPkgSetting,
                                    "matches shared user of package that acts on target of "
                                            + "overlay");
                        }
                        return false;
                    }
                }
            } else {
                if (mOverlayReferenceMapper.isValidActor(targetName, callingPkgSetting.name)) {
                    if (DEBUG_LOGGING) {
                        log(callingPkgSetting, targetPkgSetting, "acts on target of overlay");
                    }
                    return false;
                }
            }

            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private static boolean callingPkgInstruments(PackageSetting callingPkgSetting,
            PackageSetting targetPkgSetting,
            String targetName) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "callingPkgInstruments");
            final List<ComponentParseUtils.ParsedInstrumentation> inst =
                    callingPkgSetting.pkg.getInstrumentations();
            for (int i = ArrayUtils.size(inst) - 1; i >= 0; i--) {
                if (Objects.equals(inst.get(i).getTargetPackage(), targetName)) {
                    if (DEBUG_LOGGING) {
                        log(callingPkgSetting, targetPkgSetting, "instrumentation");
                    }
                    return true;
                }
            }
            return false;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private static void log(SettingBase callingPkgSetting, PackageSetting targetPkgSetting,
            String description) {
        log(callingPkgSetting, targetPkgSetting, description, null);
    }

    private static void log(SettingBase callingPkgSetting, PackageSetting targetPkgSetting,
            String description, Throwable throwable) {
        Slog.wtf(TAG,
                "interaction: " + callingPkgSetting
                        + " -> " + targetPkgSetting.name + " "
                        + description, throwable);
    }

    public void dumpQueries(
            PrintWriter pw, PackageManagerService pms, @Nullable Integer filteringAppId,
            DumpState dumpState,
            int[] users) {
        final SparseArray<String> cache = new SparseArray<>();
        ToString<Integer> expandPackages = input -> {
            String cachedValue = cache.get(input);
            if (cachedValue == null) {
                final String[] packagesForUid = pms.getPackagesForUid(input);
                if (packagesForUid == null) {
                    cachedValue = "[unknown app id " + input + "]";
                } else {
                    cachedValue = packagesForUid.length == 1 ? packagesForUid[0]
                            : "[" + TextUtils.join(",", packagesForUid) + "]";
                }
                cache.put(input, cachedValue);
            }
            return cachedValue;
        };
        pw.println();
        pw.println("Queries:");
        dumpState.onTitlePrinted();
        if (!mFeatureConfig.isGloballyEnabled()) {
            pw.println("  DISABLED");
            if (!DEBUG_LOGGING) {
                return;
            }
        }
        pw.println("  system apps queryable: " + mSystemAppsQueryable);
        dumpPackageSet(pw, filteringAppId, mForceQueryable, "forceQueryable", "  ", expandPackages);
        pw.println("  queries via package name:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaPackage, "    ", expandPackages);
        pw.println("  queries via intent:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaIntent, "    ", expandPackages);
        pw.println("  queryable via interaction:");
        for (int user : users) {
            pw.append("    User ").append(Integer.toString(user)).println(":");
            dumpQueriesMap(pw,
                    filteringAppId == null ? null : UserHandle.getUid(user, filteringAppId),
                    mImplicitlyQueryable, "      ", expandPackages);
        }
    }

    private static void dumpQueriesMap(PrintWriter pw, @Nullable Integer filteringId,
            SparseSetArray<Integer> queriesMap, String spacing,
            @Nullable ToString<Integer> toString) {
        for (int i = 0; i < queriesMap.size(); i++) {
            Integer callingId = queriesMap.keyAt(i);
            if (Objects.equals(callingId, filteringId)) {
                // don't filter target package names if the calling is filteringId
                dumpPackageSet(
                        pw, null /*filteringId*/, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            } else {
                dumpPackageSet(
                        pw, filteringId, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            }
        }
    }

    private interface ToString<T> {
        String toString(T input);
    }

    private static <T> void dumpPackageSet(PrintWriter pw, @Nullable T filteringId,
            Set<T> targetPkgSet, String subTitle, String spacing,
            @Nullable ToString<T> toString) {
        if (targetPkgSet != null && targetPkgSet.size() > 0
                && (filteringId == null || targetPkgSet.contains(filteringId))) {
            pw.append(spacing).append(subTitle).println(":");
            for (T item : targetPkgSet) {
                if (filteringId == null || Objects.equals(filteringId, item)) {
                    pw.append(spacing).append("  ")
                            .println(toString == null ? item : toString.toString(item));
                }
            }
        }
    }
}
