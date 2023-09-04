/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.ParcelableException;
import android.os.RemoteException;

/**
 * {@code ArchiveManager} is used to archive apps. During the archival process, the apps APKs and
 * cache are removed from the device while the user data is kept. Through the
 * {@code requestUnarchive()} call, apps can be restored again through their responsible app store.
 *
 * <p> Archived apps are returned as displayable apps through the {@link LauncherApps} APIs and
 * will be displayed to users with UI treatment to highlight that said apps are archived. If
 * a user taps on an archived app, the app will be unarchived and the restoration process is
 * communicated.
 *
 * @hide
 */
// TODO(b/278560219) Improve public documentation.
@SystemApi
public class PackageArchiver {

    /**
     * Extra field for the package name of a package that is requested to be unarchived. Sent as
     * part of the {@link android.content.Intent#ACTION_UNARCHIVE_PACKAGE} intent.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_UNARCHIVE_PACKAGE_NAME =
            "android.content.pm.extra.UNARCHIVE_PACKAGE_NAME";

    /**
     * If true, the requestor of the unarchival has specified that the app should be unarchived
     * for {@link android.os.UserHandle#ALL}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_UNARCHIVE_ALL_USERS =
            "android.content.pm.extra.UNARCHIVE_ALL_USERS";

    private final Context mContext;
    private final IPackageArchiverService mService;

    /**
     * @hide
     */
    public PackageArchiver(Context context, IPackageArchiverService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Requests to archive a package which is currently installed.
     *
     * @param statusReceiver Callback used to notify when the operation is completed.
     * @throws NameNotFoundException If {@code packageName} isn't found or not available to the
     *                               caller or isn't archived.
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    @SystemApi
    public void requestArchive(@NonNull String packageName, @NonNull IntentSender statusReceiver)
            throws NameNotFoundException {
        try {
            mService.requestArchive(packageName, mContext.getPackageName(), statusReceiver,
                    mContext.getUser());
        } catch (ParcelableException e) {
            e.maybeRethrow(NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests to unarchive a currently archived package.
     *
     * <p> Sends a request to unarchive an app to the responsible installer. The installer is
     * determined by {@link InstallSourceInfo#getUpdateOwnerPackageName()}, or
     * {@link InstallSourceInfo#getInstallingPackageName()} if the former value is null.
     *
     * <p> The installation will happen asynchronously and can be observed through
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED}.
     *
     * @throws NameNotFoundException If {@code packageName} isn't found or not visible to the
     *                               caller or if the package has no installer on the device
     *                               anymore to unarchive it.
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.REQUEST_INSTALL_PACKAGES})
    @SystemApi
    public void requestUnarchive(@NonNull String packageName)
            throws NameNotFoundException {
        try {
            mService.requestUnarchive(packageName, mContext.getPackageName(), mContext.getUser());
        } catch (ParcelableException e) {
            e.maybeRethrow(NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
