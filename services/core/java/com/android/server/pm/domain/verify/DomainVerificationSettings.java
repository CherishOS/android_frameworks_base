/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.domain.verify.DomainVerificationState;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;
import com.android.server.pm.domain.verify.models.DomainVerificationStateMap;
import com.android.server.pm.domain.verify.models.DomainVerificationUserState;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class DomainVerificationSettings {

    /**
     * States read from disk that have yet to attach to a package, but are expected to, generally in
     * the context of scanning packages already on disk. This is expected to be empty once the boot
     * package scan completes.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<String, DomainVerificationPkgState> mPendingPkgStates = new ArrayMap<>();

    /**
     * States from restore that have yet to attach to a package. These are special in that their IDs
     * are dropped when the package is installed/otherwise becomes available, because the ID will
     * not match if the data is restored from a different device install.
     * <p>
     * If multiple restore calls come in and they overlap, the latest entry added for a package name
     * will be taken, dropping any previous versions.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final ArrayMap<String, DomainVerificationPkgState> mRestoredPkgStates =
            new ArrayMap<>();

    /**
     * Lock for all state reads/writes.
     */
    private final Object mLock = new Object();


    public void writeSettings(@NonNull TypedXmlSerializer xmlSerializer,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState)
            throws IOException {
        synchronized (mLock) {
            DomainVerificationPersistence.writeToXml(xmlSerializer, liveState,
                    mPendingPkgStates, mRestoredPkgStates);
        }
    }

    /**
     * Parses a previously stored set of states and merges them with {@param liveState}, directly
     * mutating the values. This is intended for reading settings written by {@link
     * #writeSettings(TypedXmlSerializer, DomainVerificationStateMap)} on the same device setup.
     */
    public void readSettings(@NonNull TypedXmlPullParser parser,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState)
            throws IOException, XmlPullParserException {
        DomainVerificationPersistence.ReadResult result =
                DomainVerificationPersistence.readFromXml(parser);
        ArrayMap<String, DomainVerificationPkgState> active = result.active;
        ArrayMap<String, DomainVerificationPkgState> restored = result.restored;

        synchronized (mLock) {
            int activeSize = active.size();
            for (int activeIndex = 0; activeIndex < activeSize; activeIndex++) {
                DomainVerificationPkgState pkgState = active.valueAt(activeIndex);
                String pkgName = pkgState.getPackageName();
                DomainVerificationPkgState existingState = liveState.get(pkgName);
                if (existingState != null) {
                    // This branch should never be possible. Settings should be read from disk
                    // before any states are attached. But just in case, handle it.
                    if (!existingState.getId().equals(pkgState.getId())) {
                        mergePkgState(existingState, pkgState);
                    }
                } else {
                    mPendingPkgStates.put(pkgName, pkgState);
                }
            }

            int restoredSize = restored.size();
            for (int restoredIndex = 0; restoredIndex < restoredSize; restoredIndex++) {
                DomainVerificationPkgState pkgState = restored.valueAt(restoredIndex);
                mRestoredPkgStates.put(pkgState.getPackageName(), pkgState);
            }
        }
    }

    /**
     * Parses a previously stored set of states and merges them with {@param liveState}, directly
     * mutating the values. This is intended for restoration across device setups.
     */
    public void restoreSettings(@NonNull TypedXmlPullParser parser,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> liveState)
            throws IOException, XmlPullParserException {
        // TODO(b/170746586): Restoration assumes user IDs match, which is probably not the case on
        //  a new device.

        DomainVerificationPersistence.ReadResult result =
                DomainVerificationPersistence.readFromXml(parser);

        // When restoring settings, both active and previously restored are merged, since they
        // should both go into the newly restored data. Active is added on top of restored just
        // in case a duplicate is found. Active should be preferred.
        ArrayMap<String, DomainVerificationPkgState> stateList = result.restored;
        stateList.putAll(result.active);

        synchronized (mLock) {
            for (int stateIndex = 0; stateIndex < stateList.size(); stateIndex++) {
                DomainVerificationPkgState newState = stateList.valueAt(stateIndex);
                String pkgName = newState.getPackageName();
                DomainVerificationPkgState existingState = liveState.get(pkgName);
                if (existingState == null) {
                    existingState = mPendingPkgStates.get(pkgName);
                }
                if (existingState == null) {
                    existingState = mRestoredPkgStates.get(pkgName);
                }

                if (existingState != null) {
                    mergePkgState(existingState, newState);
                } else {
                    // If there's no existing state, that means the new state has to be transformed
                    // in preparation for attaching to brand new package that may eventually be
                    // installed. This means coercing STATE_SUCCESS and STATE_RESTORED to
                    // STATE_RESTORED and dropping everything else, the same logic that
                    // mergePkgState runs, without the merge part.
                    ArrayMap<String, Integer> stateMap = newState.getStateMap();
                    int size = stateMap.size();
                    for (int index = 0; index < size; index++) {
                        Integer stateInteger = stateMap.valueAt(index);
                        if (stateInteger != null) {
                            int state = stateInteger;
                            if (state == DomainVerificationState.STATE_SUCCESS
                                    || state == DomainVerificationState.STATE_RESTORED) {
                                stateMap.setValueAt(index, state);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Merges a newly restored state with existing state. This should only be called for restore,
     * when the IDs aren't required to match.
     * <p>
     * If the existing state for a domain is
     * {@link DomainVerificationState#STATE_NO_RESPONSE}, then it will be overridden with
     * {@link DomainVerificationState#STATE_RESTORED} if the restored state is
     * {@link DomainVerificationState#STATE_SUCCESS} or
     * {@link DomainVerificationState#STATE_RESTORED}.
     * <p>
     * Otherwise the existing state is preserved, assuming any system rules, success state, or
     * specific error codes are fresher than the restored state. Essentially state is only restored
     * to grant additional verifications to an app.
     * <p>
     * For user selection state, presence in either state will be considered an enabled host. NOTE:
     * only {@link UserHandle#USER_SYSTEM} is merged. There is no restore path in place for
     * multiple users.
     * <p>
     * TODO(b/170746586): Figure out the restore path for multiple users
     * <p>
     * This will mutate {@param oldState} to contain the merged state.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static void mergePkgState(@NonNull DomainVerificationPkgState oldState,
            @NonNull DomainVerificationPkgState newState) {
        ArrayMap<String, Integer> oldStateMap = oldState.getStateMap();
        ArrayMap<String, Integer> newStateMap = newState.getStateMap();
        int size = newStateMap.size();
        for (int index = 0; index < size; index++) {
            String domain = newStateMap.keyAt(index);
            Integer newStateCode = newStateMap.valueAt(index);
            Integer oldStateCodeInteger = oldStateMap.get(domain);
            if (oldStateCodeInteger == null) {
                // Cannot add domains to an app
                continue;
            }

            int oldStateCode = oldStateCodeInteger;
            if (oldStateCode == DomainVerificationState.STATE_NO_RESPONSE) {
                if (newStateCode == DomainVerificationState.STATE_SUCCESS
                        || newStateCode == DomainVerificationState.STATE_RESTORED) {
                    oldStateMap.put(domain, DomainVerificationState.STATE_RESTORED);
                }
            }
        }

        SparseArray<DomainVerificationUserState> oldSelectionStates =
                oldState.getUserSelectionStates();

        SparseArray<DomainVerificationUserState> newSelectionStates =
                newState.getUserSelectionStates();

        DomainVerificationUserState newUserState = newSelectionStates.get(UserHandle.USER_SYSTEM);
        if (newUserState != null) {
            ArraySet<String> newEnabledHosts = newUserState.getEnabledHosts();
            DomainVerificationUserState oldUserState =
                    oldSelectionStates.get(UserHandle.USER_SYSTEM);

            boolean disallowLinkHandling = newUserState.isDisallowLinkHandling();
            if (oldUserState == null) {
                oldUserState = new DomainVerificationUserState(UserHandle.USER_SYSTEM,
                        newEnabledHosts, disallowLinkHandling);
                oldSelectionStates.put(UserHandle.USER_SYSTEM, oldUserState);
            } else {
                oldUserState.addHosts(newEnabledHosts)
                        .setDisallowLinkHandling(disallowLinkHandling);
            }
        }
    }

    public void removeUser(@UserIdInt int userId) {
        int pendingSize = mPendingPkgStates.size();
        for (int index = 0; index < pendingSize; index++) {
            mPendingPkgStates.valueAt(index).removeUser(userId);
        }

        // TODO(b/170746586): Restored assumes user IDs match, which is probably not the case
        //  on a new device
        int restoredSize = mRestoredPkgStates.size();
        for (int index = 0; index < restoredSize; index++) {
            mRestoredPkgStates.valueAt(index).removeUser(userId);
        }
    }

    @Nullable
    public DomainVerificationPkgState getPendingState(@NonNull String pkgName) {
        synchronized (mLock) {
            return mPendingPkgStates.get(pkgName);
        }
    }

    @Nullable
    public DomainVerificationPkgState getRestoredState(@NonNull String pkgName) {
        synchronized (mLock) {
            return mRestoredPkgStates.get(pkgName);
        }
    }
}
