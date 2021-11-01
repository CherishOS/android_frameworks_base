/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.REASON_BOOT_AFTER_OTA;
import static com.android.server.pm.PackageManagerService.REASON_CMDLINE;
import static com.android.server.pm.PackageManagerService.REASON_FIRST_BOOT;
import static com.android.server.pm.PackageManagerService.STUB_SUFFIX;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getDefaultCompilerFilter;
import static com.android.server.pm.PackageManagerServiceUtils.REMOVE_IF_NULL_PKG;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.dex.ArtManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.apphibernation.AppHibernationService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class DexOptHelper {
    private static final long SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 60 * 60 * 1000;

    private final PackageManagerService mPm;

    public boolean isDexOptDialogShown() {
        return mDexOptDialogShown;
    }

    @GuardedBy("mPm.mLock")
    private boolean mDexOptDialogShown;

    DexOptHelper(PackageManagerService pm) {
        mPm = pm;
    }

    /*
     * Return the prebuilt profile path given a package base code path.
     */
    private static String getPrebuildProfilePath(AndroidPackage pkg) {
        return pkg.getBaseApkPath() + ".prof";
    }

    /**
     * Performs dexopt on the set of packages in {@code packages} and returns an int array
     * containing statistics about the invocation. The array consists of three elements,
     * which are (in order) {@code numberOfPackagesOptimized}, {@code numberOfPackagesSkipped}
     * and {@code numberOfPackagesFailed}.
     */
    public int[] performDexOptUpgrade(List<AndroidPackage> pkgs, boolean showDialog,
            final int compilationReason, boolean bootComplete) {

        int numberOfPackagesVisited = 0;
        int numberOfPackagesOptimized = 0;
        int numberOfPackagesSkipped = 0;
        int numberOfPackagesFailed = 0;
        final int numberOfPackagesToDexopt = pkgs.size();

        for (AndroidPackage pkg : pkgs) {
            numberOfPackagesVisited++;

            boolean useProfileForDexopt = false;

            if ((mPm.isFirstBoot() || mPm.isDeviceUpgrading()) && pkg.isSystem()) {
                // Copy over initial preopt profiles since we won't get any JIT samples for methods
                // that are already compiled.
                File profileFile = new File(getPrebuildProfilePath(pkg));
                // Copy profile if it exists.
                if (profileFile.exists()) {
                    try {
                        // We could also do this lazily before calling dexopt in
                        // PackageDexOptimizer to prevent this happening on first boot. The issue
                        // is that we don't have a good way to say "do this only once".
                        if (!mPm.mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                pkg.getUid(), pkg.getPackageName(),
                                ArtManager.getProfileName(null))) {
                            Log.e(TAG, "Installer failed to copy system profile!");
                        } else {
                            // Disabled as this causes speed-profile compilation during first boot
                            // even if things are already compiled.
                            // useProfileForDexopt = true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to copy profile " + profileFile.getAbsolutePath() + " ",
                                e);
                    }
                } else {
                    PackageSetting disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(
                            pkg.getPackageName());
                    // Handle compressed APKs in this path. Only do this for stubs with profiles to
                    // minimize the number off apps being speed-profile compiled during first boot.
                    // The other paths will not change the filter.
                    if (disabledPs != null && disabledPs.getPkg().isStub()) {
                        // The package is the stub one, remove the stub suffix to get the normal
                        // package and APK names.
                        String systemProfilePath = getPrebuildProfilePath(disabledPs.getPkg())
                                .replace(STUB_SUFFIX, "");
                        profileFile = new File(systemProfilePath);
                        // If we have a profile for a compressed APK, copy it to the reference
                        // location.
                        // Note that copying the profile here will cause it to override the
                        // reference profile every OTA even though the existing reference profile
                        // may have more data. We can't copy during decompression since the
                        // directories are not set up at that point.
                        if (profileFile.exists()) {
                            try {
                                // We could also do this lazily before calling dexopt in
                                // PackageDexOptimizer to prevent this happening on first boot. The
                                // issue is that we don't have a good way to say "do this only
                                // once".
                                if (!mPm.mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                        pkg.getUid(), pkg.getPackageName(),
                                        ArtManager.getProfileName(null))) {
                                    Log.e(TAG, "Failed to copy system profile for stub package!");
                                } else {
                                    useProfileForDexopt = true;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to copy profile "
                                        + profileFile.getAbsolutePath() + " ", e);
                            }
                        }
                    }
                }
            }

            if (!PackageDexOptimizer.canOptimizePackage(pkg)) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Skipping update of non-optimizable app " + pkg.getPackageName());
                }
                numberOfPackagesSkipped++;
                continue;
            }

            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Updating app " + numberOfPackagesVisited + " of "
                        + numberOfPackagesToDexopt + ": " + pkg.getPackageName());
            }

            if (showDialog) {
                try {
                    ActivityManager.getService().showBootMessage(
                            mPm.mContext.getResources().getString(R.string.android_upgrading_apk,
                                    numberOfPackagesVisited, numberOfPackagesToDexopt), true);
                } catch (RemoteException e) {
                }
                synchronized (mPm.mLock) {
                    mDexOptDialogShown = true;
                }
            }

            int pkgCompilationReason = compilationReason;
            if (useProfileForDexopt) {
                // Use background dexopt mode to try and use the profile. Note that this does not
                // guarantee usage of the profile.
                pkgCompilationReason = PackageManagerService.REASON_BACKGROUND_DEXOPT;
            }

            if (SystemProperties.getBoolean(mPm.PRECOMPILE_LAYOUTS, false)) {
                mPm.mArtManagerService.compileLayouts(pkg);
            }

            // checkProfiles is false to avoid merging profiles during boot which
            // might interfere with background compilation (b/28612421).
            // Unfortunately this will also means that "pm.dexopt.boot=speed-profile" will
            // behave differently than "pm.dexopt.bg-dexopt=speed-profile" but that's a
            // trade-off worth doing to save boot time work.
            int dexoptFlags = bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0;
            if (compilationReason == REASON_FIRST_BOOT) {
                // TODO: This doesn't cover the upgrade case, we should check for this too.
                dexoptFlags |= DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE;
            }
            int primaryDexOptStatus = performDexOptTraced(new DexoptOptions(
                    pkg.getPackageName(),
                    pkgCompilationReason,
                    dexoptFlags));

            switch (primaryDexOptStatus) {
                case PackageDexOptimizer.DEX_OPT_PERFORMED:
                    numberOfPackagesOptimized++;
                    break;
                case PackageDexOptimizer.DEX_OPT_SKIPPED:
                    numberOfPackagesSkipped++;
                    break;
                case PackageDexOptimizer.DEX_OPT_CANCELLED:
                    // ignore this case
                    break;
                case PackageDexOptimizer.DEX_OPT_FAILED:
                    numberOfPackagesFailed++;
                    break;
                default:
                    Log.e(TAG, "Unexpected dexopt return code " + primaryDexOptStatus);
                    break;
            }
        }

        return new int[]{numberOfPackagesOptimized, numberOfPackagesSkipped,
                numberOfPackagesFailed};
    }

    public void performPackageDexOptUpgradeIfNeeded() {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can request package update");

        // We need to re-extract after an OTA.
        boolean causeUpgrade = mPm.isDeviceUpgrading();

        // First boot or factory reset.
        // Note: we also handle devices that are upgrading to N right now as if it is their
        //       first boot, as they do not have profile data.
        boolean causeFirstBoot = mPm.isFirstBoot() || mPm.isPreNUpgrade();

        if (!causeUpgrade && !causeFirstBoot) {
            return;
        }

        List<PackageSetting> pkgSettings;
        synchronized (mPm.mLock) {
            pkgSettings = getPackagesForDexopt(mPm.mSettings.getPackagesLocked().values(), mPm);
        }

        List<AndroidPackage> pkgs = new ArrayList<>(pkgSettings.size());
        for (int index = 0; index < pkgSettings.size(); index++) {
            pkgs.add(pkgSettings.get(index).getPkg());
        }

        final long startTime = System.nanoTime();
        final int[] stats = performDexOptUpgrade(pkgs, mPm.isPreNUpgrade() /* showDialog */,
                causeFirstBoot ? REASON_FIRST_BOOT : REASON_BOOT_AFTER_OTA,
                false /* bootComplete */);

        final int elapsedTimeSeconds =
                (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);

        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_dexopted", stats[0]);
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_skipped", stats[1]);
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_num_failed", stats[2]);
        MetricsLogger.histogram(
                mPm.mContext, "opt_dialog_num_total", getOptimizablePackages().size());
        MetricsLogger.histogram(mPm.mContext, "opt_dialog_time_s", elapsedTimeSeconds);
    }

    public ArraySet<String> getOptimizablePackages() {
        ArraySet<String> pkgs = new ArraySet<>();
        synchronized (mPm.mLock) {
            for (AndroidPackage p : mPm.mPackages.values()) {
                if (PackageDexOptimizer.canOptimizePackage(p)) {
                    pkgs.add(p.getPackageName());
                }
            }
        }
        if (AppHibernationService.isAppHibernationEnabled()) {
            AppHibernationManagerInternal appHibernationManager =
                    mPm.mInjector.getLocalService(AppHibernationManagerInternal.class);
            pkgs.removeIf(pkgName -> appHibernationManager.isHibernatingGlobally(pkgName));
        }
        return pkgs;
    }

    /*package*/ boolean performDexOpt(DexoptOptions options) {
        if (mPm.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        } else if (mPm.isInstantApp(options.getPackageName(), UserHandle.getCallingUserId())) {
            return false;
        }

        if (options.isDexoptOnlySecondaryDex()) {
            return mPm.getDexManager().dexoptSecondaryDex(options);
        } else {
            int dexoptStatus = performDexOptWithStatus(options);
            return dexoptStatus != PackageDexOptimizer.DEX_OPT_FAILED;
        }
    }

    /**
     * Perform dexopt on the given package and return one of following result:
     * {@link PackageDexOptimizer#DEX_OPT_SKIPPED}
     * {@link PackageDexOptimizer#DEX_OPT_PERFORMED}
     * {@link PackageDexOptimizer#DEX_OPT_CANCELLED}
     * {@link PackageDexOptimizer#DEX_OPT_FAILED}
     */
    @PackageDexOptimizer.DexOptResult
    /* package */ int performDexOptWithStatus(DexoptOptions options) {
        return performDexOptTraced(options);
    }

    private int performDexOptTraced(DexoptOptions options) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");
        try {
            return performDexOptInternal(options);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    // Run dexopt on a given package. Returns true if dexopt did not fail, i.e.
    // if the package can now be considered up to date for the given filter.
    private int performDexOptInternal(DexoptOptions options) {
        AndroidPackage p;
        PackageSetting pkgSetting;
        synchronized (mPm.mLock) {
            p = mPm.mPackages.get(options.getPackageName());
            pkgSetting = mPm.mSettings.getPackageLPr(options.getPackageName());
            if (p == null || pkgSetting == null) {
                // Package could not be found. Report failure.
                return PackageDexOptimizer.DEX_OPT_FAILED;
            }
            mPm.getPackageUsage().maybeWriteAsync(mPm.mSettings.getPackagesLocked());
            mPm.mCompilerStats.maybeWriteAsync();
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mPm.mInstallLock) {
                return performDexOptInternalWithDependenciesLI(p, pkgSetting, options);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private int performDexOptInternalWithDependenciesLI(AndroidPackage p,
            @NonNull PackageSetting pkgSetting, DexoptOptions options) {
        // System server gets a special path.
        if (PLATFORM_PACKAGE_NAME.equals(p.getPackageName())) {
            return mPm.getDexManager().dexoptSystemServer(options);
        }

        // Select the dex optimizer based on the force parameter.
        // Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
        //       allocate an object here.
        PackageDexOptimizer pdo = options.isForce()
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPm.mPackageDexOptimizer)
                : mPm.mPackageDexOptimizer;

        // Dexopt all dependencies first. Note: we ignore the return value and march on
        // on errors.
        // Note that we are going to call performDexOpt on those libraries as many times as
        // they are referenced in packages. When we do a batch of performDexOpt (for example
        // at boot, or background job), the passed 'targetCompilerFilter' stays the same,
        // and the first package that uses the library will dexopt it. The
        // others will see that the compiled code for the library is up to date.
        Collection<SharedLibraryInfo> deps = SharedLibraryHelper.findSharedLibraries(pkgSetting);
        final String[] instructionSets = getAppDexInstructionSets(
                AndroidPackageUtils.getPrimaryCpuAbi(p, pkgSetting),
                AndroidPackageUtils.getSecondaryCpuAbi(p, pkgSetting));
        if (!deps.isEmpty()) {
            DexoptOptions libraryOptions = new DexoptOptions(options.getPackageName(),
                    options.getCompilationReason(), options.getCompilerFilter(),
                    options.getSplitName(),
                    options.getFlags() | DexoptOptions.DEXOPT_AS_SHARED_LIBRARY);
            for (SharedLibraryInfo info : deps) {
                AndroidPackage depPackage = null;
                PackageSetting depPackageSetting = null;
                synchronized (mPm.mLock) {
                    depPackage = mPm.mPackages.get(info.getPackageName());
                    depPackageSetting = mPm.mSettings.getPackageLPr(info.getPackageName());
                }
                if (depPackage != null && depPackageSetting != null) {
                    // TODO: Analyze and investigate if we (should) profile libraries.
                    pdo.performDexOpt(depPackage, depPackageSetting, instructionSets,
                            mPm.getOrCreateCompilerPackageStats(depPackage),
                            mPm.getDexManager().getPackageUseInfoOrDefault(
                                    depPackage.getPackageName()), libraryOptions);
                } else {
                    // TODO(ngeoffray): Support dexopting system shared libraries.
                }
            }
        }

        return pdo.performDexOpt(p, pkgSetting, instructionSets,
                mPm.getOrCreateCompilerPackageStats(p),
                mPm.getDexManager().getPackageUseInfoOrDefault(p.getPackageName()), options);
    }

    public void forceDexOpt(String packageName) {
        PackageManagerServiceUtils.enforceSystemOrRoot("forceDexOpt");

        AndroidPackage pkg;
        PackageSetting pkgSetting;
        synchronized (mPm.mLock) {
            pkg = mPm.mPackages.get(packageName);
            pkgSetting = mPm.mSettings.getPackageLPr(packageName);
            if (pkg == null || pkgSetting == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }

        synchronized (mPm.mInstallLock) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");

            // Whoever is calling forceDexOpt wants a compiled package.
            // Don't use profiles since that may cause compilation to be skipped.
            final int res = performDexOptInternalWithDependenciesLI(pkg, pkgSetting,
                    new DexoptOptions(packageName,
                            getDefaultCompilerFilter(),
                            DexoptOptions.DEXOPT_FORCE | DexoptOptions.DEXOPT_BOOT_COMPLETE));

            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            if (res != PackageDexOptimizer.DEX_OPT_PERFORMED) {
                throw new IllegalStateException("Failed to dexopt: " + res);
            }
        }
    }

    public boolean performDexOptMode(String packageName,
            boolean checkProfiles, String targetCompilerFilter, boolean force,
            boolean bootComplete, String splitName) {
        PackageManagerServiceUtils.enforceSystemOrRootOrShell("performDexOptMode");

        int flags = (checkProfiles ? DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES : 0)
                | (force ? DexoptOptions.DEXOPT_FORCE : 0)
                | (bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0);
        return performDexOpt(new DexoptOptions(packageName, REASON_CMDLINE,
                targetCompilerFilter, splitName, flags));
    }

    public boolean performDexOptSecondary(String packageName, String compilerFilter,
            boolean force) {
        int flags = DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX
                | DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES
                | DexoptOptions.DEXOPT_BOOT_COMPLETE
                | (force ? DexoptOptions.DEXOPT_FORCE : 0);
        return performDexOpt(new DexoptOptions(packageName, compilerFilter, flags));
    }

    // Sort apps by importance for dexopt ordering. Important apps are given
    // more priority in case the device runs out of space.
    public static List<PackageSetting> getPackagesForDexopt(
            Collection<PackageSetting> packages,
            PackageManagerService packageManagerService) {
        return getPackagesForDexopt(packages, packageManagerService, DEBUG_DEXOPT);
    }

    public static List<PackageSetting> getPackagesForDexopt(
            Collection<PackageSetting> pkgSettings,
            PackageManagerService packageManagerService,
            boolean debug) {
        List<PackageSetting> result = new LinkedList<>();
        ArrayList<PackageSetting> remainingPkgSettings = new ArrayList<>(pkgSettings);

        // First, remove all settings without available packages
        remainingPkgSettings.removeIf(REMOVE_IF_NULL_PKG);

        ArrayList<PackageSetting> sortTemp = new ArrayList<>(remainingPkgSettings.size());

        // Give priority to core apps.
        applyPackageFilter(pkgSetting -> pkgSetting.getPkg().isCoreApp(), result,
                remainingPkgSettings, sortTemp, packageManagerService);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        applyPackageFilter(pkgSetting -> pkgNames.contains(pkgSetting.getPackageName()), result,
                remainingPkgSettings, sortTemp, packageManagerService);

        // Give priority to apps used by other apps.
        DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter(pkgSetting ->
                        dexManager.getPackageUseInfoOrDefault(pkgSetting.getPackageName())
                                .isAnyCodePathUsedByOtherApps(),
                result, remainingPkgSettings, sortTemp, packageManagerService);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        Predicate<PackageSetting> remainingPredicate;
        if (!remainingPkgSettings.isEmpty()
                && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (debug) {
                Log.i(TAG, "Looking at historical package use");
            }
            // Get the package that was used last.
            PackageSetting lastUsed = Collections.max(remainingPkgSettings,
                    (pkgSetting1, pkgSetting2) -> Long.compare(
                            pkgSetting1.getPkgState().getLatestForegroundPackageUseTimeInMills(),
                            pkgSetting2.getPkgState().getLatestForegroundPackageUseTimeInMills()));
            if (debug) {
                Log.i(TAG, "Taking package " + lastUsed.getPackageName()
                        + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime = lastUsed.getPkgState()
                    .getLatestForegroundPackageUseTimeInMills();
            // Be defensive if for some reason package usage has bogus data.
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - SEVEN_DAYS_IN_MILLISECONDS;
                remainingPredicate = pkgSetting -> pkgSetting.getPkgState()
                        .getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
            } else {
                // No meaningful historical info. Take all.
                remainingPredicate = pkgSetting -> true;
            }
            sortPackagesByUsageDate(remainingPkgSettings, packageManagerService);
        } else {
            // No historical info. Take all.
            remainingPredicate = pkgSetting -> true;
        }
        applyPackageFilter(remainingPredicate, result, remainingPkgSettings, sortTemp,
                packageManagerService);

        if (debug) {
            Log.i(TAG, "Packages to be dexopted: " + packagesToString(result));
            Log.i(TAG, "Packages skipped from dexopt: " + packagesToString(remainingPkgSettings));
        }

        return result;
    }

    // Apply the given {@code filter} to all packages in {@code packages}. If tested positive, the
    // package will be removed from {@code packages} and added to {@code result} with its
    // dependencies. If usage data is available, the positive packages will be sorted by usage
    // data (with {@code sortTemp} as temporary storage).
    private static void applyPackageFilter(
            Predicate<PackageSetting> filter,
            Collection<PackageSetting> result,
            Collection<PackageSetting> packages,
            @NonNull List<PackageSetting> sortTemp,
            PackageManagerService packageManagerService) {
        for (PackageSetting pkgSetting : packages) {
            if (filter.test(pkgSetting)) {
                sortTemp.add(pkgSetting);
            }
        }

        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);

        for (PackageSetting pkgSetting : sortTemp) {
            result.add(pkgSetting);

            List<PackageSetting> deps =
                    packageManagerService.findSharedNonSystemLibraries(pkgSetting);
            if (!deps.isEmpty()) {
                deps.removeAll(result);
                result.addAll(deps);
                packages.removeAll(deps);
            }
        }

        sortTemp.clear();
    }

    // Sort a list of apps by their last usage, most recently used apps first. The order of
    // packages without usage data is undefined (but they will be sorted after the packages
    // that do have usage data).
    private static void sortPackagesByUsageDate(List<PackageSetting> pkgSettings,
            PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }

        Collections.sort(pkgSettings, (pkgSetting1, pkgSetting2) ->
                Long.compare(
                        pkgSetting2.getPkgState().getLatestForegroundPackageUseTimeInMills(),
                        pkgSetting1.getPkgState().getLatestForegroundPackageUseTimeInMills())
        );
    }

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, null, 0, userId)
                    .getList();
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    public static String packagesToString(List<PackageSetting> pkgSettings) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < pkgSettings.size(); index++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkgSettings.get(index).getPackageName());
        }
        return sb.toString();
    }

     /**
     * Requests that files preopted on a secondary system partition be copied to the data partition
     * if possible.  Note that the actual copying of the files is accomplished by init for security
     * reasons. This simply requests that the copy takes place and awaits confirmation of its
     * completion. See platform/system/extras/cppreopt/ for the implementation of the actual copy.
     */
    public static void requestCopyPreoptedFiles() {
        final int WAIT_TIME_MS = 100;
        final String CP_PREOPT_PROPERTY = "sys.cppreopt";
        if (SystemProperties.getInt("ro.cp_system_other_odex", 0) == 1) {
            SystemProperties.set(CP_PREOPT_PROPERTY, "requested");
            // We will wait for up to 100 seconds.
            final long timeStart = SystemClock.uptimeMillis();
            final long timeEnd = timeStart + 100 * 1000;
            long timeNow = timeStart;
            while (!SystemProperties.get(CP_PREOPT_PROPERTY).equals("finished")) {
                try {
                    Thread.sleep(WAIT_TIME_MS);
                } catch (InterruptedException e) {
                    // Do nothing
                }
                timeNow = SystemClock.uptimeMillis();
                if (timeNow > timeEnd) {
                    SystemProperties.set(CP_PREOPT_PROPERTY, "timed-out");
                    Slog.wtf(TAG, "cppreopt did not finish!");
                    break;
                }
            }

            Slog.i(TAG, "cppreopts took " + (timeNow - timeStart) + " ms");
        }
    }

    /*package*/ void controlDexOptBlocking(boolean block) {
        mPm.mPackageDexOptimizer.controlDexOptBlocking(block);
    }
}
