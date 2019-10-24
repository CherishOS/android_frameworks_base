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

package com.android.internal.compat;

import android.content.pm.ApplicationInfo;

parcelable CompatibilityChangeConfig;

/**
 * Platform private API for talking with the PlatformCompat service.
 *
 * <p> Should be used for gating and logging from non-app processes.
 * For app processes please use android.compat.Compatibility API.
 *
 * {@hide}
 */
interface IPlatformCompat
{

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, ApplicationInfo)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId The ID of the compatibility change taking effect.
     * @param appInfo  Representing the affected app.
     */
    void reportChange(long changeId, in ApplicationInfo appInfo);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, String)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId    The ID of the compatibility change taking effect.
     * @param userId      The ID of the user that the operation is done for.
     * @param packageName The package name of the app in question.
     */
     void reportChangeByPackageName(long changeId, in String packageName, int userId);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, int)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId The ID of the compatibility change taking effect.
     * @param uid      The UID of the app in question.
     */
    void reportChangeByUid(long changeId, int uid);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, ApplicationInfo)} would, so
     * there is no need to call that method directly.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param appInfo  Representing the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabled(long changeId, in ApplicationInfo appInfo);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>Same as {@link #isChangeEnabled(long, ApplicationInfo)}, except it receives a package name
     * and userId instead of an {@link ApplicationInfo}
     * object, and finds an app info object based on the package name. Returns {@code true} if
     * there is no installed package by that name.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method
     * returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, String)} would, so there is
     * no need to call that method directly.
     *
     * @param changeId    The ID of the compatibility change in question.
     * @param packageName The package name of the app in question.
     * @param userId      The ID of the user that the operation is done for.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabledByPackageName(long changeId, in String packageName, int userId);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>Same as {@link #isChangeEnabled(long, ApplicationInfo)}, except it receives a uid
     * instead of an {@link ApplicationInfo} object, and finds an app info object based on the
     * uid (or objects if there's more than one package associated with the UID).
     * Returns {@code true} if there are no installed packages for the required UID, or if the
     * change is enabled for ALL of the installed packages associated with the provided UID. Please
     * use a more specific API if you want a different behaviour for multi-package UIDs.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method
     * returns {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, int)} would, so there is
     * no need to call that method directly.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param uid      The UID of the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabledByUid(long changeId, int uid);

    /**
     * Add overrides to compatibility changes.
     *
     * @param overrides Parcelable containing the compat change overrides to be applied.
     * @param packageName The package name of the app whose changes will be overridden.
     *
     */
    void setOverrides(in CompatibilityChangeConfig overrides, in String packageName);

    /**
     * Revert overrides to compatibility changes.
     *
     * @param packageName The package name of the app whose overrides will be cleared.
     *
     */
    void clearOverrides(in String packageName);
}
