/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.overlay.OverlayPaths;
import android.os.PersistableBundle;
import android.service.pm.PackageProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.pkg.AndroidPackageApi;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateImpl;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SuspendParams;
import com.android.server.utils.SnapshotCache;

import libcore.util.EmptyArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Settings data for a particular package we know about.
 * @hide
 */
@DataClass(genGetters = true, genConstructor = false, genSetters = false, genBuilder = false)
@DataClass.Suppress({"getSnapshot", })
public class PackageSetting extends SettingBase implements PackageStateInternal {

    /**
     * Temporary holding space for the shared user ID. While parsing package settings, the
     * shared users tag may come after the packages. In this case, we must delay linking the
     * shared user setting with the package setting. The shared user ID lets us link the
     * two objects.
     */
    private int sharedUserId;

    @Nullable
    private Map<String, Set<String>> mimeGroups;

    @Deprecated
    @Nullable
    private Set<String> mOldCodePaths;

    @Nullable
    private String[] usesSdkLibraries;

    @Nullable
    private long[] usesSdkLibrariesVersionsMajor;

    @Nullable
    private String[] usesStaticLibraries;

    @Nullable
    private long[] usesStaticLibrariesVersions;

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @Nullable
    @Deprecated
    private String legacyNativeLibraryPath;

    @NonNull
    private String mName;

    @Nullable
    private String mRealName;

    private int mAppId;

    /**
     * It is expected that all code that uses a {@link PackageSetting} understands this inner field
     * may be null. Note that this relationship only works one way. It should not be possible to
     * have an entry inside {@link PackageManagerService#mPackages} without a corresponding
     * {@link PackageSetting} inside {@link Settings#mPackages}.
     *
     * @see PackageState#getAndroidPackage()
     */
    @Nullable
    private AndroidPackage pkg;

    /**
     * WARNING. The object reference is important. We perform integer equality and NOT
     * object equality to check whether shared user settings are the same.
     */
    @Nullable
    private SharedUserSetting sharedUser;

    /** @see AndroidPackage#getPath() */
    @NonNull
    private File mPath;
    @NonNull
    private String mPathString;

    private float mLoadingProgress;

    @Nullable
    private String mPrimaryCpuAbi;

    @Nullable
    private String mSecondaryCpuAbi;

    @Nullable
    private String mCpuAbiOverride;

    private long mLastModifiedTime;
    private long firstInstallTime;
    private long lastUpdateTime;
    private long versionCode;

    @NonNull
    private PackageSignatures signatures;

    private boolean installPermissionsFixed;

    @NonNull
    private PackageKeySetData keySetData = new PackageKeySetData();

    // TODO: Access is not locked.
    @NonNull
    private final SparseArray<PackageUserStateImpl> mUserStates = new SparseArray<>();

    @NonNull
    private InstallSource installSource;

    /** @see PackageState#getVolumeUuid()  */
    @Nullable
    private String volumeUuid;

    /** @see PackageState#getCategoryOverride() */
    private int categoryOverride = ApplicationInfo.CATEGORY_UNDEFINED;

    /** @see PackageState#isUpdateAvailable() */
    private boolean updateAvailable;

    private boolean forceQueryableOverride;

    @NonNull
    private PackageStateUnserialized pkgState = new PackageStateUnserialized();

    @NonNull
    private UUID mDomainSetId;

    /**
     * Snapshot support.
     */
    @NonNull
    private final SnapshotCache<PackageSetting> mSnapshot;

    private SnapshotCache<PackageSetting> makeCache() {
        return new SnapshotCache<PackageSetting>(this, this) {
            @Override
            public PackageSetting createSnapshot() {
                return new PackageSetting(mSource, true);
            }};
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public PackageSetting(String name, String realName, @NonNull File path,
            String legacyNativeLibraryPath, String primaryCpuAbi,
            String secondaryCpuAbi, String cpuAbiOverride,
            long longVersionCode, int pkgFlags, int pkgPrivateFlags,
            int sharedUserId,
            String[] usesSdkLibraries, long[] usesSdkLibrariesVersionsMajor,
            String[] usesStaticLibraries, long[] usesStaticLibrariesVersions,
            Map<String, Set<String>> mimeGroups,
            @NonNull UUID domainSetId) {
        super(pkgFlags, pkgPrivateFlags);
        this.mName = name;
        this.mRealName = realName;
        this.usesSdkLibraries = usesSdkLibraries;
        this.usesSdkLibrariesVersionsMajor = usesSdkLibrariesVersionsMajor;
        this.usesStaticLibraries = usesStaticLibraries;
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        this.mPath = path;
        this.mPathString = path.toString();
        this.legacyNativeLibraryPath = legacyNativeLibraryPath;
        this.mPrimaryCpuAbi = primaryCpuAbi;
        this.mSecondaryCpuAbi = secondaryCpuAbi;
        this.mCpuAbiOverride = cpuAbiOverride;
        this.versionCode = longVersionCode;
        this.signatures = new PackageSignatures();
        this.installSource = InstallSource.EMPTY;
        this.sharedUserId = sharedUserId;
        mDomainSetId = domainSetId;
        copyMimeGroups(mimeGroups);
        mSnapshot = makeCache();
    }

    /**
     * New instance of PackageSetting replicating the original settings.
     * Note that it keeps the same PackageParser.Package instance.
     */
    PackageSetting(PackageSetting orig) {
        this(orig, false);
    }

    /**
     * New instance of PackageSetting with one-level-deep cloning.
     * <p>
     * IMPORTANT: With a shallow copy, we do NOT create new contained objects.
     * This means, for example, changes to the user state of the original PackageSetting
     * will also change the user state in its copy.
     */
    PackageSetting(PackageSetting base, String realPkgName) {
        this(base, false);
        this.mRealName = realPkgName;
    }

    PackageSetting(@NonNull PackageSetting original, boolean sealedSnapshot)  {
        super(original);
        copyPackageSetting(original);
        if (sealedSnapshot) {
            sharedUser = sharedUser == null ? null : sharedUser.snapshot();
            mSnapshot = new SnapshotCache.Sealed();
        } else {
            mSnapshot = makeCache();
        }
    }

    /**
     * Return the package snapshot.
     */
    public PackageSetting snapshot() {
        return mSnapshot.snapshot();
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, List<UserInfo> users,
            LegacyPermissionDataProvider dataProvider) {
        final long packageToken = proto.start(fieldId);
        proto.write(PackageProto.NAME, (mRealName != null ? mRealName : mName));
        proto.write(PackageProto.UID, mAppId);
        proto.write(PackageProto.VERSION_CODE, versionCode);
        proto.write(PackageProto.INSTALL_TIME_MS, firstInstallTime);
        proto.write(PackageProto.UPDATE_TIME_MS, lastUpdateTime);
        proto.write(PackageProto.INSTALLER_NAME, installSource.installerPackageName);

        if (pkg != null) {
            proto.write(PackageProto.VERSION_STRING, pkg.getVersionName());

            long splitToken = proto.start(PackageProto.SPLITS);
            proto.write(PackageProto.SplitProto.NAME, "base");
            proto.write(PackageProto.SplitProto.REVISION_CODE, pkg.getBaseRevisionCode());
            proto.end(splitToken);

            if (pkg.getSplitNames() != null) {
                for (int i = 0; i < pkg.getSplitNames().length; i++) {
                    splitToken = proto.start(PackageProto.SPLITS);
                    proto.write(PackageProto.SplitProto.NAME, pkg.getSplitNames()[i]);
                    proto.write(PackageProto.SplitProto.REVISION_CODE,
                            pkg.getSplitRevisionCodes()[i]);
                    proto.end(splitToken);
                }
            }

            long sourceToken = proto.start(PackageProto.INSTALL_SOURCE);
            proto.write(PackageProto.InstallSourceProto.INITIATING_PACKAGE_NAME,
                    installSource.initiatingPackageName);
            proto.write(PackageProto.InstallSourceProto.ORIGINATING_PACKAGE_NAME,
                    installSource.originatingPackageName);
            proto.end(sourceToken);
        }
        proto.write(PackageProto.StatesProto.IS_LOADING, isLoading());
        writeUsersInfoToProto(proto, PackageProto.USERS);
        writePackageUserPermissionsProto(proto, PackageProto.USER_PERMISSIONS, users, dataProvider);
        proto.end(packageToken);
    }

    public boolean isSharedUser() {
        return sharedUser != null;
    }

    public PackageSetting setAppId(int appId) {
        this.mAppId = appId;
        onChanged();
        return this;
    }

    public PackageSetting setCpuAbiOverride(String cpuAbiOverrideString) {
        this.mCpuAbiOverride = cpuAbiOverrideString;
        onChanged();
        return this;
    }

    public PackageSetting setFirstInstallTime(long firstInstallTime) {
        this.firstInstallTime = firstInstallTime;
        onChanged();
        return this;
    }

    public PackageSetting setForceQueryableOverride(boolean forceQueryableOverride) {
        this.forceQueryableOverride = forceQueryableOverride;
        onChanged();
        return this;
    }

    public PackageSetting setInstallerPackageName(String packageName) {
        installSource = installSource.setInstallerPackage(packageName);
        onChanged();
        return this;
    }

    public PackageSetting setInstallSource(InstallSource installSource) {
        this.installSource = Objects.requireNonNull(installSource);
        onChanged();
        return this;
    }

    PackageSetting removeInstallerPackage(String packageName) {
        installSource = installSource.removeInstallerPackage(packageName);
        onChanged();
        return this;
    }

    public PackageSetting setIsOrphaned(boolean isOrphaned) {
        installSource = installSource.setIsOrphaned(isOrphaned);
        onChanged();
        return this;
    }

    public PackageSetting setKeySetData(PackageKeySetData keySetData) {
        this.keySetData = keySetData;
        onChanged();
        return this;
    }

    public PackageSetting setLastModifiedTime(long timeStamp) {
        this.mLastModifiedTime = timeStamp;
        onChanged();
        return this;
    }

    public PackageSetting setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
        onChanged();
        return this;
    }

    public PackageSetting setLongVersionCode(long versionCode) {
        this.versionCode = versionCode;
        onChanged();
        return this;
    }

    public boolean setMimeGroup(String mimeGroup, List<String> mimeTypes) {
        Set<String> oldMimeTypes = mimeGroups == null ? null : mimeGroups.get(mimeGroup);
        if (oldMimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + mName);
        }

        ArraySet<String> newMimeTypes = new ArraySet<>(mimeTypes);
        boolean hasChanges = !newMimeTypes.equals(oldMimeTypes);
        mimeGroups.put(mimeGroup, newMimeTypes);
        if (hasChanges) {
            onChanged();
        }
        return hasChanges;
    }

    public PackageSetting setPkg(AndroidPackage pkg) {
        this.pkg = pkg;
        onChanged();
        return this;
    }

    /**
     * Notify {@link #onChanged()}  if the parameter {@code usesLibraryFiles} is different from
     * {@link #getUsesLibraryFiles()}.
     * @param usesLibraryFiles the new uses library files
     * @return {@code this}
     */
    public PackageSetting setPkgStateLibraryFiles(@NonNull Collection<String> usesLibraryFiles) {
        final Collection<String> oldUsesLibraryFiles = getUsesLibraryFiles();
        if (oldUsesLibraryFiles.size() != usesLibraryFiles.size()
                || !oldUsesLibraryFiles.containsAll(usesLibraryFiles)) {
            pkgState.setUsesLibraryFiles(new ArrayList<>(usesLibraryFiles));
            onChanged();
        }
        return this;
    }

    public PackageSetting setPrimaryCpuAbi(String primaryCpuAbiString) {
        this.mPrimaryCpuAbi = primaryCpuAbiString;
        onChanged();
        return this;
    }

    public PackageSetting setSecondaryCpuAbi(String secondaryCpuAbiString) {
        this.mSecondaryCpuAbi = secondaryCpuAbiString;
        onChanged();
        return this;
    }

    public PackageSetting setSignatures(PackageSignatures signatures) {
        this.signatures = signatures;
        onChanged();
        return this;
    }

    public PackageSetting setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
        onChanged();
        return this;
    }

    @Override
    public boolean isExternalStorage() {
        return (getFlags() & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    public PackageSetting setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
        onChanged();
        return this;
    }

    public int getSharedUserIdInt() {
        if (sharedUser != null) {
            return sharedUser.userId;
        }
        return sharedUserId;
    }

    @Override
    public String toString() {
        return "PackageSetting{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + "/" + mAppId + "}";
    }

    protected void copyMimeGroups(@Nullable Map<String, Set<String>> newMimeGroups) {
        if (newMimeGroups == null) {
            mimeGroups = null;
            return;
        }

        mimeGroups = new ArrayMap<>(newMimeGroups.size());
        for (String mimeGroup : newMimeGroups.keySet()) {
            Set<String> mimeTypes = newMimeGroups.get(mimeGroup);

            if (mimeTypes != null) {
                mimeGroups.put(mimeGroup, new ArraySet<>(mimeTypes));
            } else {
                mimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
    }

    /** Updates all fields in the current setting from another. */
    public void updateFrom(PackageSetting other) {
        copyPackageSetting(other);

        Set<String> mimeGroupNames = other.mimeGroups != null ? other.mimeGroups.keySet() : null;
        updateMimeGroups(mimeGroupNames);

        onChanged();
    }

    /**
     * Updates declared MIME groups, removing no longer declared groups
     * and keeping previous state of MIME groups
     */
    PackageSetting updateMimeGroups(@Nullable Set<String> newMimeGroupNames) {
        if (newMimeGroupNames == null) {
            mimeGroups = null;
            return this;
        }

        if (mimeGroups == null) {
            // set mimeGroups to empty map to avoid repeated null-checks in the next loop
            mimeGroups = Collections.emptyMap();
        }

        ArrayMap<String, Set<String>> updatedMimeGroups =
                new ArrayMap<>(newMimeGroupNames.size());

        for (String mimeGroup : newMimeGroupNames) {
            if (mimeGroups.containsKey(mimeGroup)) {
                updatedMimeGroups.put(mimeGroup, mimeGroups.get(mimeGroup));
            } else {
                updatedMimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
        onChanged();
        mimeGroups = updatedMimeGroups;
        return this;
    }

    @Deprecated
    @Override
    public LegacyPermissionState getLegacyPermissionState() {
        return (sharedUser != null)
                ? sharedUser.getLegacyPermissionState()
                : super.getLegacyPermissionState();
    }

    public PackageSetting setInstallPermissionsFixed(boolean installPermissionsFixed) {
        this.installPermissionsFixed = installPermissionsFixed;
        return this;
    }

    public boolean isPrivileged() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    public boolean isOem() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    public boolean isVendor() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    public boolean isProduct() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    @Override
    public boolean isRequiredForSystemUser() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER) != 0;
    }

    public boolean isSystemExt() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    public boolean isOdm() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    public boolean isSystem() {
        return (getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public SigningDetails getSigningDetails() {
        return signatures.mSigningDetails;
    }

    public PackageSetting setSigningDetails(SigningDetails signingDetails) {
        // TODO: Immutability
        signatures.mSigningDetails = signingDetails;
        onChanged();
        return this;
    }

    public void copyPackageSetting(PackageSetting other) {
        super.copySettingBase(other);
        sharedUserId = other.sharedUserId;
        mLoadingProgress = other.mLoadingProgress;
        legacyNativeLibraryPath = other.legacyNativeLibraryPath;
        mName = other.mName;
        mRealName = other.mRealName;
        mAppId = other.mAppId;
        pkg = other.pkg;
        sharedUser = other.sharedUser;
        mPath = other.mPath;
        mPathString = other.mPathString;
        mPrimaryCpuAbi = other.mPrimaryCpuAbi;
        mSecondaryCpuAbi = other.mSecondaryCpuAbi;
        mCpuAbiOverride = other.mCpuAbiOverride;
        mLastModifiedTime = other.mLastModifiedTime;
        firstInstallTime = other.firstInstallTime;
        lastUpdateTime = other.lastUpdateTime;
        versionCode = other.versionCode;
        signatures = other.signatures;
        installPermissionsFixed = other.installPermissionsFixed;
        keySetData = new PackageKeySetData(other.keySetData);
        installSource = other.installSource;
        volumeUuid = other.volumeUuid;
        categoryOverride = other.categoryOverride;
        updateAvailable = other.updateAvailable;
        forceQueryableOverride = other.forceQueryableOverride;
        mDomainSetId = other.mDomainSetId;

        usesSdkLibraries = other.usesSdkLibraries != null
                ? Arrays.copyOf(other.usesSdkLibraries,
                other.usesSdkLibraries.length) : null;
        usesSdkLibrariesVersionsMajor = other.usesSdkLibrariesVersionsMajor != null
                ? Arrays.copyOf(other.usesSdkLibrariesVersionsMajor,
                other.usesSdkLibrariesVersionsMajor.length) : null;

        usesStaticLibraries = other.usesStaticLibraries != null
                ? Arrays.copyOf(other.usesStaticLibraries,
                other.usesStaticLibraries.length) : null;
        usesStaticLibrariesVersions = other.usesStaticLibrariesVersions != null
                ? Arrays.copyOf(other.usesStaticLibrariesVersions,
                other.usesStaticLibrariesVersions.length) : null;

        mUserStates.clear();
        for (int i = 0; i < other.mUserStates.size(); i++) {
            mUserStates.put(other.mUserStates.keyAt(i), other.mUserStates.valueAt(i));
        }

        if (mOldCodePaths != null) {
            if (other.mOldCodePaths != null) {
                mOldCodePaths.clear();
                mOldCodePaths.addAll(other.mOldCodePaths);
            } else {
                mOldCodePaths = null;
            }
        }

        copyMimeGroups(other.mimeGroups);
        pkgState.updateFrom(other.pkgState);
        onChanged();
    }

    @VisibleForTesting
    PackageUserStateImpl modifyUserState(int userId) {
        PackageUserStateImpl state = mUserStates.get(userId);
        if (state == null) {
            state = new PackageUserStateImpl();
            mUserStates.put(userId, state);
            onChanged();
        }
        return state;
    }

    @NonNull
    public PackageUserStateInternal readUserState(int userId) {
        PackageUserStateInternal state = mUserStates.get(userId);
        if (state == null) {
            return PackageUserStateInternal.DEFAULT;
        }
        return state;
    }

    void setEnabled(int state, int userId, String callingPackage) {
        modifyUserState(userId)
                .setEnabledState(state)
                .setLastDisableAppCaller(callingPackage);
        onChanged();
    }

    int getEnabled(int userId) {
        return readUserState(userId).getEnabledState();
    }

    String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).getLastDisableAppCaller();
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).setInstalled(inst);
        onChanged();
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).isInstalled();
    }

    int getInstallReason(int userId) {
        return readUserState(userId).getInstallReason();
    }

    void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).setInstallReason(installReason);
        onChanged();
    }

    int getUninstallReason(int userId) {
        return readUserState(userId).getUninstallReason();
    }

    void setUninstallReason(@PackageManager.UninstallReason int uninstallReason, int userId) {
        modifyUserState(userId).setUninstallReason(uninstallReason);
        onChanged();
    }

    boolean setOverlayPaths(OverlayPaths overlayPaths, int userId) {
        boolean changed = modifyUserState(userId).setOverlayPaths(overlayPaths);
        if (changed) {
            onChanged();
        }
        return changed;
    }

    @NonNull
    OverlayPaths getOverlayPaths(int userId) {
        return readUserState(userId).getOverlayPaths();
    }

    boolean setOverlayPathsForLibrary(String libName, OverlayPaths overlayPaths, int userId) {
        boolean changed = modifyUserState(userId)
                .setSharedLibraryOverlayPaths(libName, overlayPaths);
        onChanged();
        return changed;
    }

    @NonNull
    Map<String, OverlayPaths> getOverlayPathsForLibrary(int userId) {
        return readUserState(userId).getSharedLibraryOverlayPaths();
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).isInstalled()) {
                return true;
            }
        }
        return false;
    }

    int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    long getCeDataInode(int userId) {
        return readUserState(userId).getCeDataInode();
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).setCeDataInode(ceDataInode);
        onChanged();
    }

    boolean getStopped(int userId) {
        return readUserState(userId).isStopped();
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).setStopped(stop);
        onChanged();
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).isNotLaunched();
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).setNotLaunched(stop);
        onChanged();
    }

    boolean getHidden(int userId) {
        return readUserState(userId).isHidden();
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).setHidden(hidden);
        onChanged();
    }

    int getDistractionFlags(int userId) {
        return readUserState(userId).getDistractionFlags();
    }

    void setDistractionFlags(int distractionFlags, int userId) {
        modifyUserState(userId).setDistractionFlags(distractionFlags);
        onChanged();
    }

    boolean getSuspended(int userId) {
        return readUserState(userId).isSuspended();
    }

    boolean addOrUpdateSuspension(String suspendingPackage, SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras, int userId) {
        final PackageUserStateImpl existingUserState = modifyUserState(userId);
        final SuspendParams newSuspendParams = SuspendParams.getInstanceOrNull(dialogInfo,
                appExtras, launcherExtras);
        if (existingUserState.getSuspendParams() == null) {
            existingUserState.setSuspendParams(new ArrayMap<>());
        }
        final SuspendParams oldSuspendParams =
                existingUserState.getSuspendParams().put(suspendingPackage, newSuspendParams);
        existingUserState.setSuspended(true);
        onChanged();
        return !Objects.equals(oldSuspendParams, newSuspendParams);
    }

    boolean removeSuspension(String suspendingPackage, int userId) {
        boolean wasModified = false;
        final PackageUserStateImpl existingUserState = modifyUserState(userId);
        if (existingUserState.getSuspendParams() != null) {
            if (existingUserState.getSuspendParams().remove(suspendingPackage) != null) {
                wasModified = true;
            }
            if (existingUserState.getSuspendParams().size() == 0) {
                existingUserState.setSuspendParams(null);
            }
        }
        existingUserState.setSuspended((existingUserState.getSuspendParams() != null));
        onChanged();
        return wasModified;
    }

    void removeSuspension(Predicate<String> suspendingPackagePredicate, int userId) {
        final PackageUserStateImpl existingUserState = modifyUserState(userId);
        if (existingUserState.getSuspendParams() != null) {
            for (int i = existingUserState.getSuspendParams().size() - 1; i >= 0; i--) {
                final String suspendingPackage = existingUserState.getSuspendParams().keyAt(i);
                if (suspendingPackagePredicate.test(suspendingPackage)) {
                    existingUserState.getSuspendParams().removeAt(i);
                }
            }
            if (existingUserState.getSuspendParams().size() == 0) {
                existingUserState.setSuspendParams(null);
            }
        }
        existingUserState.setSuspended((existingUserState.getSuspendParams() != null));
        onChanged();
    }

    public boolean getInstantApp(int userId) {
        return readUserState(userId).isInstantApp();
    }

    void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).setInstantApp(instantApp);
        onChanged();
    }

    boolean getVirtualPreload(int userId) {
        return readUserState(userId).isVirtualPreload();
    }

    void setVirtualPreload(boolean virtualPreload, int userId) {
        modifyUserState(userId).setVirtualPreload(virtualPreload);
        onChanged();
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, int distractionFlags, boolean suspended,
            ArrayMap<String, SuspendParams> suspendParams, boolean instantApp,
            boolean virtualPreload, String lastDisableAppCaller,
            ArraySet<String> enabledComponents, ArraySet<String> disabledComponents,
            int installReason, int uninstallReason,
            String harmfulAppWarning, String splashScreenTheme) {
        modifyUserState(userId)
                .setSuspendParams(suspendParams)
                .setCeDataInode(ceDataInode)
                .setEnabledState(enabled)
                .setInstalled(installed)
                .setStopped(stopped)
                .setNotLaunched(notLaunched)
                .setHidden(hidden)
                .setDistractionFlags(distractionFlags)
                .setSuspended(suspended)
                .setLastDisableAppCaller(lastDisableAppCaller)
                .setEnabledComponents(enabledComponents)
                .setDisabledComponents(disabledComponents)
                .setInstallReason(installReason)
                .setUninstallReason(uninstallReason)
                .setInstantApp(instantApp)
                .setVirtualPreload(virtualPreload)
                .setHarmfulAppWarning(harmfulAppWarning)
                .setSplashScreenTheme(splashScreenTheme);
        onChanged();
    }

    void setUserState(int userId, PackageUserStateInternal otherState) {
        setUserState(userId, otherState.getCeDataInode(), otherState.getEnabledState(),
                otherState.isInstalled(),
                otherState.isStopped(), otherState.isNotLaunched(), otherState.isHidden(),
                otherState.getDistractionFlags(), otherState.isSuspended(),
                otherState.getSuspendParams(),
                otherState.isInstantApp(),
                otherState.isVirtualPreload(), otherState.getLastDisableAppCaller(),
                new ArraySet<>(otherState.getEnabledComponentsNoCopy()),
                new ArraySet<>(otherState.getDisabledComponentsNoCopy()),
                otherState.getInstallReason(), otherState.getUninstallReason(),
                otherState.getHarmfulAppWarning(), otherState.getSplashScreenTheme());
    }

    ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).getEnabledComponentsNoCopy();
    }

    ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).getDisabledComponentsNoCopy();
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).setEnabledComponents(components);
        onChanged();
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).setDisabledComponents(components);
        onChanged();
    }

    void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).setEnabledComponents(components != null
                ? new ArraySet<String>(components) : null);
        onChanged();
    }

    void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).setDisabledComponents(components != null
                ? new ArraySet<String>(components) : null);
        onChanged();
    }

    PackageUserStateImpl modifyUserStateComponents(int userId, boolean disabled,
            boolean enabled) {
        PackageUserStateImpl state = modifyUserState(userId);
        boolean changed = false;
        if (disabled && state.getDisabledComponentsNoCopy() == null) {
            state.setDisabledComponents(new ArraySet<String>(1));
            changed = true;
        }
        if (enabled && state.getEnabledComponentsNoCopy() == null) {
            state.setEnabledComponents(new ArraySet<String>(1));
            changed = true;
        }
        if (changed) {
            onChanged();
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false)
                .getDisabledComponentsNoCopy().add(componentClassName);
        onChanged();
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true)
                .getEnabledComponentsNoCopy().add(componentClassName);
        onChanged();
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.getDisabledComponentsNoCopy() != null
                ? state.getDisabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getEnabledComponentsNoCopy().add(componentClassName);
        if (changed) {
            onChanged();
        }
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.getEnabledComponentsNoCopy() != null
                ? state.getEnabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getDisabledComponentsNoCopy().add(componentClassName);
        if (changed) {
            onChanged();
        }
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.getDisabledComponentsNoCopy() != null
                ? state.getDisabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getEnabledComponentsNoCopy() != null
                ? state.getEnabledComponentsNoCopy().remove(componentClassName) : false;
        if (changed) {
            onChanged();
        }
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserStateInternal state = readUserState(userId);
        if (state.getEnabledComponentsNoCopy() != null
                && state.getEnabledComponentsNoCopy().contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.getDisabledComponentsNoCopy() != null
                && state.getDisabledComponentsNoCopy().contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    void removeUser(int userId) {
        mUserStates.delete(userId);
        onChanged();
    }

    public int[] getNotInstalledUserIds() {
        int count = 0;
        int userStateCount = mUserStates.size();
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserStates.valueAt(i).isInstalled()) {
                count++;
            }
        }
        if (count == 0) {
            return EmptyArray.INT;
        }

        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserStates.valueAt(i).isInstalled()) {
                excludedUserIds[idx++] = mUserStates.keyAt(i);
            }
        }
        return excludedUserIds;
    }

    /**
     * TODO (b/170263003) refactor to dump to permissiongr proto
     * Dumps the permissions that are granted to users for this package.
     */
    void writePackageUserPermissionsProto(ProtoOutputStream proto, long fieldId,
            List<UserInfo> users, LegacyPermissionDataProvider dataProvider) {
        Collection<LegacyPermissionState.PermissionState> runtimePermissionStates;
        for (UserInfo user : users) {
            final long permissionsToken = proto.start(PackageProto.USER_PERMISSIONS);
            proto.write(PackageProto.UserPermissionsProto.ID, user.id);

            runtimePermissionStates = dataProvider.getLegacyPermissionState(mAppId)
                    .getPermissionStates(user.id);
            for (LegacyPermissionState.PermissionState permission : runtimePermissionStates) {
                if (permission.isGranted()) {
                    proto.write(PackageProto.UserPermissionsProto.GRANTED_PERMISSIONS,
                            permission.getName());
                }
            }
            proto.end(permissionsToken);
        }
    }

    protected void writeUsersInfoToProto(ProtoOutputStream proto, long fieldId) {
        int count = mUserStates.size();
        for (int i = 0; i < count; i++) {
            final long userToken = proto.start(fieldId);
            final int userId = mUserStates.keyAt(i);
            final PackageUserStateInternal state = mUserStates.valueAt(i);
            proto.write(PackageProto.UserInfoProto.ID, userId);
            final int installType;
            if (state.isInstantApp()) {
                installType = PackageProto.UserInfoProto.INSTANT_APP_INSTALL;
            } else if (state.isInstalled()) {
                installType = PackageProto.UserInfoProto.FULL_APP_INSTALL;
            } else {
                installType = PackageProto.UserInfoProto.NOT_INSTALLED_FOR_USER;
            }
            proto.write(PackageProto.UserInfoProto.INSTALL_TYPE, installType);
            proto.write(PackageProto.UserInfoProto.IS_HIDDEN, state.isHidden());
            proto.write(PackageProto.UserInfoProto.DISTRACTION_FLAGS, state.getDistractionFlags());
            proto.write(PackageProto.UserInfoProto.IS_SUSPENDED, state.isSuspended());
            if (state.isSuspended()) {
                for (int j = 0; j < state.getSuspendParams().size(); j++) {
                    proto.write(PackageProto.UserInfoProto.SUSPENDING_PACKAGE,
                            state.getSuspendParams().keyAt(j));
                }
            }
            proto.write(PackageProto.UserInfoProto.IS_STOPPED, state.isStopped());
            proto.write(PackageProto.UserInfoProto.IS_LAUNCHED, !state.isNotLaunched());
            proto.write(PackageProto.UserInfoProto.ENABLED_STATE, state.getEnabledState());
            proto.write(
                    PackageProto.UserInfoProto.LAST_DISABLED_APP_CALLER,
                    state.getLastDisableAppCaller());
            proto.end(userToken);
        }
    }

    void setHarmfulAppWarning(int userId, String harmfulAppWarning) {
        modifyUserState(userId).setHarmfulAppWarning(harmfulAppWarning);
        onChanged();
    }

    String getHarmfulAppWarning(int userId) {
        PackageUserState userState = readUserState(userId);
        return userState.getHarmfulAppWarning();
    }

    /**
     * @see #mPath
     */
    PackageSetting setPath(@NonNull File path) {
        this.mPath = path;
        this.mPathString = path.toString();
        onChanged();
        return this;
    }

    /**
     * @see PackageUserStateImpl#overrideLabelAndIcon(ComponentName, String, Integer)
     *
     * @param userId the specific user to change the label/icon for
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean overrideNonLocalizedLabelAndIcon(@NonNull ComponentName component,
            @Nullable String label, @Nullable Integer icon, @UserIdInt int userId) {
        boolean changed = modifyUserState(userId).overrideLabelAndIcon(component, label, icon);
        onChanged();
        return changed;
    }

    /**
     * @see PackageUserStateImpl#resetOverrideComponentLabelIcon()
     *
     * @param userId the specific user to reset
     */
    public void resetOverrideComponentLabelIcon(@UserIdInt int userId) {
        modifyUserState(userId).resetOverrideComponentLabelIcon();
        onChanged();
    }

    /**
     * @param userId    the specified user to modify the theme for
     * @param themeName the theme name to persist
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     */
    public void setSplashScreenTheme(@UserIdInt int userId, @Nullable String themeName) {
        modifyUserState(userId).setSplashScreenTheme(themeName);
        onChanged();
    }

    /**
     * @param userId the specified user to get the theme setting from
     * @return the theme name previously persisted for the user or null
     * if no splashscreen theme is persisted.
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     */
    @Nullable
    public String getSplashScreenTheme(@UserIdInt int userId) {
        return readUserState(userId).getSplashScreenTheme();
    }

    /**
     * @return True if package is still being loaded, false if the package is fully loaded.
     */
    public boolean isLoading() {
        return Math.abs(1.0f - mLoadingProgress) >= 0.00000001f;
    }

    public PackageSetting setLoadingProgress(float progress) {
        mLoadingProgress = progress;
        onChanged();
        return this;
    }

    @NonNull
    @Override
    public long getVersionCode() {
        return versionCode;
    }

    /**
     * @see PackageState#getMimeGroups()
     */
    @Nullable
    @Override
    public Map<String, Set<String>> getMimeGroups() {
        return CollectionUtils.isEmpty(mimeGroups) ? Collections.emptyMap()
                : Collections.unmodifiableMap(mimeGroups);
    }

    @NonNull
    @Override
    public String getPackageName() {
        return mName;
    }

    @Nullable
    @Override
    public AndroidPackageApi getAndroidPackage() {
        return getPkg();
    }

    @Nullable
    @Override
    public Integer getSharedUserId() {
        return sharedUser == null ? null : sharedUser.userId;
    }

    @NonNull
    public SigningInfo getSigningInfo() {
        return new SigningInfo(signatures.mSigningDetails);
    }

    @NonNull
    @Override
    public String[] getUsesSdkLibraries() {
        return usesSdkLibraries == null ? EmptyArray.STRING : usesSdkLibraries;
    }

    @NonNull
    @Override
    public long[] getUsesSdkLibrariesVersionsMajor() {
        return usesSdkLibrariesVersionsMajor == null ? EmptyArray.LONG
                : usesSdkLibrariesVersionsMajor;
    }

    @NonNull
    @Override
    public String[] getUsesStaticLibraries() {
        return usesStaticLibraries == null ? EmptyArray.STRING : usesStaticLibraries;
    }

    @NonNull
    @Override
    public long[] getUsesStaticLibrariesVersions() {
        return usesStaticLibrariesVersions == null ? EmptyArray.LONG : usesStaticLibrariesVersions;
    }

    @NonNull
    @Override
    public List<SharedLibraryInfo> getUsesLibraryInfos() {
        return pkgState.getUsesLibraryInfos();
    }

    @NonNull
    @Override
    public List<String> getUsesLibraryFiles() {
        return pkgState.getUsesLibraryFiles();
    }

    @Override
    public boolean isHiddenUntilInstalled() {
        return pkgState.isHiddenUntilInstalled();
    }

    @NonNull
    @Override
    public long[] getLastPackageUsageTime() {
        return pkgState.getLastPackageUsageTimeInMills();
    }

    @Override
    public boolean isUpdatedSystemApp() {
        return pkgState.isUpdatedSystemApp();
    }

    public PackageSetting setDomainSetId(@NonNull UUID domainSetId) {
        mDomainSetId = domainSetId;
        onChanged();
        return this;
    }

    public PackageSetting setSharedUser(SharedUserSetting sharedUser) {
        this.sharedUser = sharedUser;
        onChanged();
        return this;
    }

    public PackageSetting setCategoryOverride(int categoryHint) {
        this.categoryOverride = categoryHint;
        onChanged();
        return this;
    }

    public PackageSetting setLegacyNativeLibraryPath(
            String legacyNativeLibraryPathString) {
        this.legacyNativeLibraryPath = legacyNativeLibraryPathString;
        onChanged();
        return this;
    }

    public PackageSetting setMimeGroups(@NonNull Map<String, Set<String>> mimeGroups) {
        this.mimeGroups = mimeGroups;
        onChanged();
        return this;
    }

    public PackageSetting setOldCodePaths(Set<String> oldCodePaths) {
        mOldCodePaths = oldCodePaths;
        onChanged();
        return this;
    }

    public PackageSetting setUsesSdkLibraries(String[] usesSdkLibraries) {
        this.usesSdkLibraries = usesSdkLibraries;
        onChanged();
        return this;
    }

    public PackageSetting setUsesSdkLibrariesVersionsMajor(long[] usesSdkLibrariesVersions) {
        this.usesSdkLibrariesVersionsMajor = usesSdkLibrariesVersions;
        onChanged();
        return this;
    }

    public PackageSetting setUsesStaticLibraries(String[] usesStaticLibraries) {
        this.usesStaticLibraries = usesStaticLibraries;
        onChanged();
        return this;
    }

    public PackageSetting setUsesStaticLibrariesVersions(long[] usesStaticLibrariesVersions) {
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        onChanged();
        return this;
    }

    @NonNull
    @Override
    public PackageStateUnserialized getTransientState() {
        return pkgState;
    }

    @NonNull
    public SparseArray<? extends PackageUserStateInternal> getUserStates() {
        return mUserStates;
    }

    public PackageSetting addMimeTypes(String mimeGroup, Set<String> mimeTypes) {
        if (mimeGroups == null) {
            mimeGroups = new ArrayMap<>();
        }

        Set<String> existingMimeTypes = mimeGroups.get(mimeGroup);
        if (existingMimeTypes == null) {
            existingMimeTypes = new ArraySet<>();
            mimeGroups.put(mimeGroup, existingMimeTypes);
        }
        existingMimeTypes.addAll(mimeTypes);
        return this;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public @Deprecated @Nullable Set<String> getOldCodePaths() {
        return mOldCodePaths;
    }

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @DataClass.Generated.Member
    public @Nullable @Deprecated String getLegacyNativeLibraryPath() {
        return legacyNativeLibraryPath;
    }

    @DataClass.Generated.Member
    public @NonNull String getName() {
        return mName;
    }

    @DataClass.Generated.Member
    public @Nullable String getRealName() {
        return mRealName;
    }

    @DataClass.Generated.Member
    public int getAppId() {
        return mAppId;
    }

    /**
     * It is expected that all code that uses a {@link PackageSetting} understands this inner field
     * may be null. Note that this relationship only works one way. It should not be possible to
     * have an entry inside {@link PackageManagerService#mPackages} without a corresponding
     * {@link PackageSetting} inside {@link Settings#mPackages}.
     *
     * @see PackageState#getAndroidPackage()
     */
    @DataClass.Generated.Member
    public @Nullable AndroidPackage getPkg() {
        return pkg;
    }

    /**
     * WARNING. The object reference is important. We perform integer equality and NOT
     * object equality to check whether shared user settings are the same.
     */
    @DataClass.Generated.Member
    public @Nullable SharedUserSetting getSharedUser() {
        return sharedUser;
    }

    /**
     * @see AndroidPackage#getPath()
     */
    @DataClass.Generated.Member
    public @NonNull File getPath() {
        return mPath;
    }

    @DataClass.Generated.Member
    public @NonNull String getPathString() {
        return mPathString;
    }

    @DataClass.Generated.Member
    public float getLoadingProgress() {
        return mLoadingProgress;
    }

    @DataClass.Generated.Member
    public @Nullable String getPrimaryCpuAbi() {
        return mPrimaryCpuAbi;
    }

    @DataClass.Generated.Member
    public @Nullable String getSecondaryCpuAbi() {
        return mSecondaryCpuAbi;
    }

    @DataClass.Generated.Member
    public @Nullable String getCpuAbiOverride() {
        return mCpuAbiOverride;
    }

    @DataClass.Generated.Member
    public long getLastModifiedTime() {
        return mLastModifiedTime;
    }

    @DataClass.Generated.Member
    public long getFirstInstallTime() {
        return firstInstallTime;
    }

    @DataClass.Generated.Member
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @DataClass.Generated.Member
    public @NonNull PackageSignatures getSignatures() {
        return signatures;
    }

    @DataClass.Generated.Member
    public boolean isInstallPermissionsFixed() {
        return installPermissionsFixed;
    }

    @DataClass.Generated.Member
    public @NonNull PackageKeySetData getKeySetData() {
        return keySetData;
    }

    @DataClass.Generated.Member
    public @NonNull InstallSource getInstallSource() {
        return installSource;
    }

    /**
     * @see PackageState#getVolumeUuid()
     */
    @DataClass.Generated.Member
    public @Nullable String getVolumeUuid() {
        return volumeUuid;
    }

    /**
     * @see PackageState#getCategoryOverride()
     */
    @DataClass.Generated.Member
    public int getCategoryOverride() {
        return categoryOverride;
    }

    /**
     * @see PackageState#isUpdateAvailable()
     */
    @DataClass.Generated.Member
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    @DataClass.Generated.Member
    public boolean isForceQueryableOverride() {
        return forceQueryableOverride;
    }

    @DataClass.Generated.Member
    public @NonNull PackageStateUnserialized getPkgState() {
        return pkgState;
    }

    @DataClass.Generated.Member
    public @NonNull UUID getDomainSetId() {
        return mDomainSetId;
    }

    @DataClass.Generated(
            time = 1635870549646L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java",
            inputSignatures = "private  int sharedUserId\nprivate @android.annotation.Nullable java.util.Map<java.lang.String,java.util.Set<java.lang.String>> mimeGroups\nprivate @java.lang.Deprecated @android.annotation.Nullable java.util.Set<java.lang.String> mOldCodePaths\nprivate @android.annotation.Nullable java.lang.String[] usesStaticLibraries\nprivate @android.annotation.Nullable long[] usesStaticLibrariesVersions\nprivate @android.annotation.Nullable @java.lang.Deprecated java.lang.String legacyNativeLibraryPath\nprivate @android.annotation.NonNull java.lang.String mName\nprivate @android.annotation.Nullable java.lang.String mRealName\nprivate  int mAppId\nprivate @android.annotation.Nullable com.android.server.pm.parsing.pkg.AndroidPackage pkg\nprivate @android.annotation.Nullable com.android.server.pm.SharedUserSetting sharedUser\nprivate @android.annotation.NonNull java.io.File mPath\nprivate @android.annotation.NonNull java.lang.String mPathString\nprivate  float mLoadingProgress\nprivate @android.annotation.Nullable java.lang.String mPrimaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mSecondaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mCpuAbiOverride\nprivate  long mLastModifiedTime\nprivate  long firstInstallTime\nprivate  long lastUpdateTime\nprivate  long versionCode\nprivate @android.annotation.NonNull com.android.server.pm.PackageSignatures signatures\nprivate  boolean installPermissionsFixed\nprivate @android.annotation.NonNull com.android.server.pm.PackageKeySetData keySetData\nprivate final @android.annotation.NonNull android.util.SparseArray<com.android.server.pm.pkg.PackageUserStateImpl> mUserStates\nprivate @android.annotation.NonNull com.android.server.pm.InstallSource installSource\nprivate @android.annotation.Nullable java.lang.String volumeUuid\nprivate  int categoryOverride\nprivate  boolean updateAvailable\nprivate  boolean forceQueryableOverride\nprivate @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized pkgState\nprivate @android.annotation.NonNull java.util.UUID mDomainSetId\nprivate final @android.annotation.NonNull com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> mSnapshot\nprivate  com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> makeCache()\npublic  com.android.server.pm.PackageSetting snapshot()\npublic  void dumpDebug(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\npublic  java.util.List<java.lang.String> getMimeGroup(java.lang.String)\nprivate  java.util.Set<java.lang.String> getMimeGroupInternal(java.lang.String)\npublic  boolean isSharedUser()\npublic  com.android.server.pm.PackageSetting setAppId(int)\npublic  com.android.server.pm.PackageSetting setCpuAbiOverride(java.lang.String)\npublic  com.android.server.pm.PackageSetting setFirstInstallTime(long)\npublic  com.android.server.pm.PackageSetting setForceQueryableOverride(boolean)\npublic  com.android.server.pm.PackageSetting setInstallerPackageName(java.lang.String)\npublic  com.android.server.pm.PackageSetting setInstallSource(com.android.server.pm.InstallSource)\n  com.android.server.pm.PackageSetting removeInstallerPackage(java.lang.String)\npublic  com.android.server.pm.PackageSetting setIsOrphaned(boolean)\npublic  com.android.server.pm.PackageSetting setKeySetData(com.android.server.pm.PackageKeySetData)\npublic  com.android.server.pm.PackageSetting setLastModifiedTime(long)\npublic  com.android.server.pm.PackageSetting setLastUpdateTime(long)\npublic  com.android.server.pm.PackageSetting setLongVersionCode(long)\npublic  boolean setMimeGroup(java.lang.String,java.util.List<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setPkg(com.android.server.pm.parsing.pkg.AndroidPackage)\npublic  com.android.server.pm.PackageSetting setPrimaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSecondaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSignatures(com.android.server.pm.PackageSignatures)\npublic  com.android.server.pm.PackageSetting setVolumeUuid(java.lang.String)\npublic @java.lang.Override boolean isExternalStorage()\npublic  com.android.server.pm.PackageSetting setUpdateAvailable(boolean)\npublic  int getSharedUserIdInt()\npublic @java.lang.Override java.lang.String toString()\nprotected  void copyMimeGroups(java.util.Map<java.lang.String,java.util.Set<java.lang.String>>)\npublic  void updateFrom(com.android.server.pm.PackageSetting)\n  com.android.server.pm.PackageSetting updateMimeGroups(java.util.Set<java.lang.String>)\npublic @java.lang.Deprecated @java.lang.Override com.android.server.pm.permission.LegacyPermissionState getLegacyPermissionState()\npublic  com.android.server.pm.PackageSetting setInstallPermissionsFixed(boolean)\npublic  boolean isPrivileged()\npublic  boolean isOem()\npublic  boolean isVendor()\npublic  boolean isProduct()\npublic @java.lang.Override boolean isRequiredForSystemUser()\npublic  boolean isSystemExt()\npublic  boolean isOdm()\npublic  boolean isSystem()\npublic  android.content.pm.SigningDetails getSigningDetails()\npublic  com.android.server.pm.PackageSetting setSigningDetails(android.content.pm.SigningDetails)\npublic  void copyPackageSetting(com.android.server.pm.PackageSetting)\n @com.android.internal.annotations.VisibleForTesting com.android.server.pm.pkg.PackageUserStateImpl modifyUserState(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateInternal readUserState(int)\n  void setEnabled(int,int,java.lang.String)\n  int getEnabled(int)\n  java.lang.String getLastDisabledAppCaller(int)\n  void setInstalled(boolean,int)\n  boolean getInstalled(int)\n  int getInstallReason(int)\n  void setInstallReason(int,int)\n  int getUninstallReason(int)\n  void setUninstallReason(int,int)\n  boolean setOverlayPaths(android.content.pm.overlay.OverlayPaths,int)\n @android.annotation.NonNull android.content.pm.overlay.OverlayPaths getOverlayPaths(int)\n  boolean setOverlayPathsForLibrary(java.lang.String,android.content.pm.overlay.OverlayPaths,int)\n @android.annotation.NonNull java.util.Map<java.lang.String,android.content.pm.overlay.OverlayPaths> getOverlayPathsForLibrary(int)\n  boolean isAnyInstalled(int[])\n  int[] queryInstalledUsers(int[],boolean)\n  long getCeDataInode(int)\n  void setCeDataInode(long,int)\n  boolean getStopped(int)\n  void setStopped(boolean,int)\n  boolean getNotLaunched(int)\n  void setNotLaunched(boolean,int)\n  boolean getHidden(int)\n  void setHidden(boolean,int)\n  int getDistractionFlags(int)\n  void setDistractionFlags(int,int)\n  boolean getSuspended(int)\n  boolean addOrUpdateSuspension(java.lang.String,android.content.pm.SuspendDialogInfo,android.os.PersistableBundle,android.os.PersistableBundle,int)\n  boolean removeSuspension(java.lang.String,int)\n  void removeSuspension(java.util.function.Predicate<java.lang.String>,int)\npublic  boolean getInstantApp(int)\n  void setInstantApp(boolean,int)\n  boolean getVirtualPreload(int)\n  void setVirtualPreload(boolean,int)\n  void setUserState(int,long,int,boolean,boolean,boolean,boolean,int,boolean,android.util.ArrayMap<java.lang.String,com.android.server.pm.pkg.SuspendParams>,boolean,boolean,java.lang.String,android.util.ArraySet<java.lang.String>,android.util.ArraySet<java.lang.String>,int,int,java.lang.String,java.lang.String)\n  void setUserState(int,com.android.server.pm.pkg.PackageUserStateInternal)\n  android.util.ArraySet<java.lang.String> getEnabledComponents(int)\n  android.util.ArraySet<java.lang.String> getDisabledComponents(int)\n  void setEnabledComponents(android.util.ArraySet<java.lang.String>,int)\n  void setDisabledComponents(android.util.ArraySet<java.lang.String>,int)\n  void setEnabledComponentsCopy(android.util.ArraySet<java.lang.String>,int)\n  void setDisabledComponentsCopy(android.util.ArraySet<java.lang.String>,int)\n  com.android.server.pm.pkg.PackageUserStateImpl modifyUserStateComponents(int,boolean,boolean)\n  void addDisabledComponent(java.lang.String,int)\n  void addEnabledComponent(java.lang.String,int)\n  boolean enableComponentLPw(java.lang.String,int)\n  boolean disableComponentLPw(java.lang.String,int)\n  boolean restoreComponentLPw(java.lang.String,int)\n  int getCurrentEnabledStateLPr(java.lang.String,int)\n  void removeUser(int)\npublic  int[] getNotInstalledUserIds()\n  void writePackageUserPermissionsProto(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\nprotected  void writeUsersInfoToProto(android.util.proto.ProtoOutputStream,long)\n  void setHarmfulAppWarning(int,java.lang.String)\n  java.lang.String getHarmfulAppWarning(int)\n  com.android.server.pm.PackageSetting setPath(java.io.File)\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideNonLocalizedLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer,int)\npublic  void resetOverrideComponentLabelIcon(int)\npublic  void setSplashScreenTheme(int,java.lang.String)\npublic @android.annotation.Nullable java.lang.String getSplashScreenTheme(int)\npublic  boolean isLoading()\npublic  com.android.server.pm.PackageSetting setLoadingProgress(float)\npublic @android.annotation.NonNull @java.lang.Override long getVersionCode()\npublic @android.annotation.Nullable @java.lang.Override java.util.Map<java.lang.String,java.util.Set<java.lang.String>> getMimeGroups()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String getPackageName()\npublic @android.annotation.Nullable @java.lang.Override com.android.server.pm.pkg.AndroidPackageApi getAndroidPackage()\npublic @android.annotation.Nullable @java.lang.Override java.lang.Integer getSharedUserId()\npublic @android.annotation.NonNull android.content.pm.SigningInfo getSigningInfo()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String[] getUsesStaticLibraries()\npublic @android.annotation.NonNull @java.lang.Override long[] getUsesStaticLibrariesVersions()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<android.content.pm.SharedLibraryInfo> getUsesLibraryInfos()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<java.lang.String> getUsesLibraryFiles()\npublic @java.lang.Override boolean isHiddenUntilInstalled()\npublic @android.annotation.NonNull @java.lang.Override long[] getLastPackageUsageTime()\npublic @java.lang.Override boolean isUpdatedSystemApp()\npublic  com.android.server.pm.PackageSetting setDomainSetId(java.util.UUID)\npublic  com.android.server.pm.PackageSetting setSharedUser(com.android.server.pm.SharedUserSetting)\npublic  com.android.server.pm.PackageSetting setCategoryOverride(int)\npublic  com.android.server.pm.PackageSetting setLegacyNativeLibraryPath(java.lang.String)\npublic  com.android.server.pm.PackageSetting setMimeGroups(java.util.Map<java.lang.String,java.util.Set<java.lang.String>>)\npublic  com.android.server.pm.PackageSetting setOldCodePaths(java.util.Set<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setUsesStaticLibraries(java.lang.String[])\npublic  com.android.server.pm.PackageSetting setUsesStaticLibrariesVersions(long[])\npublic @android.annotation.NonNull @java.lang.Override com.android.server.pm.pkg.PackageStateUnserialized getTransientState()\npublic @android.annotation.NonNull android.util.SparseArray<? extends PackageUserStateInternal> getUserStates()\nclass PackageSetting extends com.android.server.pm.SettingBase implements [com.android.server.pm.pkg.PackageStateInternal]\n@com.android.internal.util.DataClass(genGetters=true, genConstructor=false, genSetters=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
