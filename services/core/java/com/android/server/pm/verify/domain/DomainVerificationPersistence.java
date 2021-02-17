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

package com.android.server.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.verify.domain.DomainVerificationState;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.server.pm.SettingsXml;
import com.android.server.pm.verify.domain.models.DomainVerificationPkgState;
import com.android.server.pm.verify.domain.models.DomainVerificationStateMap;
import com.android.server.pm.verify.domain.models.DomainVerificationUserState;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

public class DomainVerificationPersistence {

    private static final String TAG = "DomainVerificationPersistence";

    public static final String TAG_DOMAIN_VERIFICATIONS = "domain-verifications";
    public static final String TAG_ACTIVE = "active";
    public static final String TAG_RESTORED = "restored";

    public static final String TAG_PACKAGE_STATE = "package-state";
    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_ID = "id";
    private static final String ATTR_HAS_AUTO_VERIFY_DOMAINS = "hasAutoVerifyDomains";
    private static final String TAG_USER_STATES = "user-states";

    public static final String TAG_USER_STATE = "user-state";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ALLOW_LINK_HANDLING = "allowLinkHandling";
    public static final String TAG_ENABLED_HOSTS = "enabled-hosts";
    public static final String TAG_HOST = "host";

    private static final String TAG_STATE = "state";
    public static final String TAG_DOMAIN = "domain";
    public static final String ATTR_NAME = "name";
    public static final String ATTR_STATE = "state";

    public static void writeToXml(@NonNull TypedXmlSerializer xmlSerializer,
            @NonNull DomainVerificationStateMap<DomainVerificationPkgState> attached,
            @NonNull ArrayMap<String, DomainVerificationPkgState> pending,
            @NonNull ArrayMap<String, DomainVerificationPkgState> restored) throws IOException {
        try (SettingsXml.Serializer serializer = SettingsXml.serializer(xmlSerializer)) {
            try (SettingsXml.WriteSection ignored = serializer.startSection(
                    TAG_DOMAIN_VERIFICATIONS)) {
                // Both attached and pending states are written to the active set, since both
                // should be restored when the device reboots or runs a backup. They're merged into
                // the same list because at read time the distinction isn't relevant. The pending
                // list should generally be empty at this point anyways.
                ArraySet<DomainVerificationPkgState> active = new ArraySet<>();

                int attachedSize = attached.size();
                for (int attachedIndex = 0; attachedIndex < attachedSize; attachedIndex++) {
                    active.add(attached.valueAt(attachedIndex));
                }

                int pendingSize = pending.size();
                for (int pendingIndex = 0; pendingIndex < pendingSize; pendingIndex++) {
                    active.add(pending.valueAt(pendingIndex));
                }

                try (SettingsXml.WriteSection activeSection = serializer.startSection(TAG_ACTIVE)) {
                    writePackageStates(activeSection, active);
                }

                try (SettingsXml.WriteSection restoredSection = serializer.startSection(
                        TAG_RESTORED)) {
                    writePackageStates(restoredSection, restored.values());
                }
            }
        }
    }

    private static void writePackageStates(@NonNull SettingsXml.WriteSection section,
            @NonNull Collection<DomainVerificationPkgState> states) throws IOException {
        if (states.isEmpty()) {
            return;
        }

        for (DomainVerificationPkgState state : states) {
            writePkgStateToXml(section, state);
        }
    }

    @NonNull
    public static ReadResult readFromXml(@NonNull TypedXmlPullParser parentParser)
            throws IOException, XmlPullParserException {
        ArrayMap<String, DomainVerificationPkgState> active = new ArrayMap<>();
        ArrayMap<String, DomainVerificationPkgState> restored = new ArrayMap<>();

        SettingsXml.ChildSection child = SettingsXml.parser(parentParser).children();
        while (child.moveToNext()) {
            switch (child.getName()) {
                case TAG_ACTIVE:
                    readPackageStates(child, active);
                    break;
                case TAG_RESTORED:
                    readPackageStates(child, restored);
                    break;
            }
        }

        return new ReadResult(active, restored);
    }

    private static void readPackageStates(@NonNull SettingsXml.ReadSection section,
            @NonNull ArrayMap<String, DomainVerificationPkgState> map) {
        SettingsXml.ChildSection child = section.children();
        while (child.moveToNext(TAG_PACKAGE_STATE)) {
            DomainVerificationPkgState pkgState = createPkgStateFromXml(child);
            if (pkgState != null) {
                // State is unique by package name
                map.put(pkgState.getPackageName(), pkgState);
            }
        }
    }

    /**
     * Reads a package state from XML. Assumes the starting {@link #TAG_PACKAGE_STATE} has already
     * been entered.
     */
    @Nullable
    public static DomainVerificationPkgState createPkgStateFromXml(
            @NonNull SettingsXml.ReadSection section) {
        String packageName = section.getString(ATTR_PACKAGE_NAME);
        String idString = section.getString(ATTR_ID);
        boolean hasAutoVerifyDomains = section.getBoolean(ATTR_HAS_AUTO_VERIFY_DOMAINS);
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(idString)) {
            return null;
        }
        UUID id = UUID.fromString(idString);

        final ArrayMap<String, Integer> stateMap = new ArrayMap<>();
        final SparseArray<DomainVerificationUserState> userStates = new SparseArray<>();

        SettingsXml.ChildSection child = section.children();
        while (child.moveToNext()) {
            switch (child.getName()) {
                case TAG_STATE:
                    readDomainStates(child, stateMap);
                    break;
                case TAG_USER_STATES:
                    readUserStates(child, userStates);
                    break;
            }
        }

        return new DomainVerificationPkgState(packageName, id, hasAutoVerifyDomains, stateMap,
                userStates);
    }

    private static void readUserStates(@NonNull SettingsXml.ReadSection section,
            @NonNull SparseArray<DomainVerificationUserState> userStates) {
        SettingsXml.ChildSection child = section.children();
        while (child.moveToNext(TAG_USER_STATE)) {
            DomainVerificationUserState userState = createUserStateFromXml(child);
            if (userState != null) {
                userStates.put(userState.getUserId(), userState);
            }
        }
    }

    private static void readDomainStates(@NonNull SettingsXml.ReadSection stateSection,
            @NonNull ArrayMap<String, Integer> stateMap) {
        SettingsXml.ChildSection child = stateSection.children();
        while (child.moveToNext(TAG_DOMAIN)) {
            String name = child.getString(ATTR_NAME);
            int state = child.getInt(ATTR_STATE, DomainVerificationState.STATE_NO_RESPONSE);
            stateMap.put(name, state);
        }
    }

    public static void writePkgStateToXml(@NonNull SettingsXml.WriteSection parentSection,
            @NonNull DomainVerificationPkgState pkgState) throws IOException {
        try (SettingsXml.WriteSection ignored =
                     parentSection.startSection(TAG_PACKAGE_STATE)
                             .attribute(ATTR_PACKAGE_NAME, pkgState.getPackageName())
                             .attribute(ATTR_ID, pkgState.getId().toString())
                             .attribute(ATTR_HAS_AUTO_VERIFY_DOMAINS,
                                     pkgState.isHasAutoVerifyDomains())) {
            writeStateMap(parentSection, pkgState.getStateMap());
            writeUserStates(parentSection, pkgState.getUserSelectionStates());
        }
    }

    private static void writeUserStates(@NonNull SettingsXml.WriteSection parentSection,
            @NonNull SparseArray<DomainVerificationUserState> states) throws IOException {
        int size = states.size();
        if (size == 0) {
            return;
        }

        try (SettingsXml.WriteSection section = parentSection.startSection(TAG_USER_STATES)) {
            for (int index = 0; index < size; index++) {
                writeUserStateToXml(section, states.valueAt(index));
            }
        }
    }

    private static void writeStateMap(@NonNull SettingsXml.WriteSection parentSection,
            @NonNull ArrayMap<String, Integer> stateMap) throws IOException {
        if (stateMap.isEmpty()) {
            return;
        }

        try (SettingsXml.WriteSection stateSection = parentSection.startSection(TAG_STATE)) {
            int size = stateMap.size();
            for (int index = 0; index < size; index++) {
                stateSection.startSection(TAG_DOMAIN)
                        .attribute(ATTR_NAME, stateMap.keyAt(index))
                        .attribute(ATTR_STATE, stateMap.valueAt(index))
                        .finish();
            }
        }
    }

    /**
     * Reads a user state from XML. Assumes the starting {@link #TAG_USER_STATE} has already been
     * entered.
     */
    @Nullable
    public static DomainVerificationUserState createUserStateFromXml(
            @NonNull SettingsXml.ReadSection section) {
        int userId = section.getInt(ATTR_USER_ID);
        if (userId == -1) {
            return null;
        }

        boolean allowLinkHandling = section.getBoolean(ATTR_ALLOW_LINK_HANDLING, true);
        ArraySet<String> enabledHosts = new ArraySet<>();

        SettingsXml.ChildSection child = section.children();
        while (child.moveToNext(TAG_ENABLED_HOSTS)) {
            readEnabledHosts(child, enabledHosts);
        }

        return new DomainVerificationUserState(userId, enabledHosts, allowLinkHandling);
    }

    private static void readEnabledHosts(@NonNull SettingsXml.ReadSection section,
            @NonNull ArraySet<String> enabledHosts) {
        SettingsXml.ChildSection child = section.children();
        while (child.moveToNext(TAG_HOST)) {
            String hostName = child.getString(ATTR_NAME);
            if (!TextUtils.isEmpty(hostName)) {
                enabledHosts.add(hostName);
            }
        }
    }

    public static void writeUserStateToXml(@NonNull SettingsXml.WriteSection parentSection,
            @NonNull DomainVerificationUserState userState) throws IOException {
        try (SettingsXml.WriteSection section =
                     parentSection.startSection(TAG_USER_STATE)
                             .attribute(ATTR_USER_ID, userState.getUserId())
                             .attribute(ATTR_ALLOW_LINK_HANDLING,
                                     userState.isLinkHandlingAllowed())) {
            ArraySet<String> enabledHosts = userState.getEnabledHosts();
            if (!enabledHosts.isEmpty()) {
                try (SettingsXml.WriteSection enabledHostsSection =
                             section.startSection(TAG_ENABLED_HOSTS)) {
                    int size = enabledHosts.size();
                    for (int index = 0; index < size; index++) {
                        enabledHostsSection.startSection(TAG_HOST)
                                .attribute(ATTR_NAME, enabledHosts.valueAt(index))
                                .finish();
                    }
                }
            }
        }
    }

    public static class ReadResult {

        @NonNull
        public final ArrayMap<String, DomainVerificationPkgState> active;

        @NonNull
        public final ArrayMap<String, DomainVerificationPkgState> restored;

        public ReadResult(@NonNull ArrayMap<String, DomainVerificationPkgState> active,
                @NonNull ArrayMap<String, DomainVerificationPkgState> restored) {
            this.active = active;
            this.restored = restored;
        }
    }
}
