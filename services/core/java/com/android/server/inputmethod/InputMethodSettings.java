/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManagerInternal;
import android.os.LocaleList;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Pair;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Utility class for putting and getting settings for InputMethod.
 *
 * <p>This is used in two ways:</p>
 * <ul>
 *     <li>Singleton instance in {@link InputMethodManagerService}, which is updated on
 *     user-switch to follow the current user.</li>
 *     <li>On-demand instances when we need settings for non-current users.</li>
 * </ul>
 */
final class InputMethodSettings {
    public static final boolean DEBUG = false;
    private static final String TAG = "InputMethodSettings";

    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;
    private static final String NOT_A_SUBTYPE_ID_STR = String.valueOf(NOT_A_SUBTYPE_ID);
    private static final char INPUT_METHOD_SEPARATOR = InputMethodUtils.INPUT_METHOD_SEPARATOR;
    private static final char INPUT_METHOD_SUBTYPE_SEPARATOR =
            InputMethodUtils.INPUT_METHOD_SUBTYPE_SEPARATOR;

    private final ArrayMap<String, InputMethodInfo> mMethodMap;
    @UserIdInt
    private final int mCurrentUserId;

    private static void buildEnabledInputMethodsSettingString(
            StringBuilder builder, Pair<String, ArrayList<String>> ime) {
        builder.append(ime.first);
        // Inputmethod and subtypes are saved in the settings as follows:
        // ime0;subtype0;subtype1:ime1;subtype0:ime2:ime3;subtype0;subtype1
        for (int i = 0; i < ime.second.size(); ++i) {
            final String subtypeId = ime.second.get(i);
            builder.append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(subtypeId);
        }
    }

    InputMethodSettings(ArrayMap<String, InputMethodInfo> methodMap, @UserIdInt int userId) {
        mMethodMap = methodMap;
        mCurrentUserId = userId;
        String ime = getSelectedInputMethod();
        String defaultDeviceIme = getSelectedDefaultDeviceInputMethod();
        if (defaultDeviceIme != null && !Objects.equals(ime, defaultDeviceIme)) {
            putSelectedInputMethod(defaultDeviceIme);
            putSelectedDefaultDeviceInputMethod(null);
        }
    }

    private void putString(@NonNull String key, @Nullable String str) {
        SecureSettingsWrapper.putString(key, str, mCurrentUserId);
    }

    @Nullable
    private String getString(@NonNull String key, @Nullable String defaultValue) {
        return SecureSettingsWrapper.getString(key, defaultValue, mCurrentUserId);
    }

    private void putInt(String key, int value) {
        SecureSettingsWrapper.putInt(key, value, mCurrentUserId);
    }

    private int getInt(String key, int defaultValue) {
        return SecureSettingsWrapper.getInt(key, defaultValue, mCurrentUserId);
    }

    ArrayList<InputMethodInfo> getEnabledInputMethodListLocked() {
        return getEnabledInputMethodListWithFilterLocked(null /* matchingCondition */);
    }

    @NonNull
    ArrayList<InputMethodInfo> getEnabledInputMethodListWithFilterLocked(
            @Nullable Predicate<InputMethodInfo> matchingCondition) {
        return createEnabledInputMethodListLocked(
                getEnabledInputMethodsAndSubtypeListLocked(), matchingCondition);
    }

    List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(
            InputMethodInfo imi, boolean allowsImplicitlyEnabledSubtypes) {
        List<InputMethodSubtype> enabledSubtypes =
                getEnabledInputMethodSubtypeListLocked(imi);
        if (allowsImplicitlyEnabledSubtypes && enabledSubtypes.isEmpty()) {
            enabledSubtypes = SubtypeUtils.getImplicitlyApplicableSubtypesLocked(
                    SystemLocaleWrapper.get(mCurrentUserId), imi);
        }
        return InputMethodSubtype.sort(imi, enabledSubtypes);
    }

    List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(InputMethodInfo imi) {
        final List<Pair<String, ArrayList<String>>> imsList =
                getEnabledInputMethodsAndSubtypeListLocked();
        final List<InputMethodSubtype> enabledSubtypes = new ArrayList<>();
        if (imi != null) {
            for (int i = 0; i < imsList.size(); ++i) {
                final Pair<String, ArrayList<String>> imsPair = imsList.get(i);
                final InputMethodInfo info = mMethodMap.get(imsPair.first);
                if (info != null && info.getId().equals(imi.getId())) {
                    final int subtypeCount = info.getSubtypeCount();
                    for (int j = 0; j < subtypeCount; ++j) {
                        final InputMethodSubtype ims = info.getSubtypeAt(j);
                        for (int k = 0; k < imsPair.second.size(); ++k) {
                            final String s = imsPair.second.get(k);
                            if (String.valueOf(ims.hashCode()).equals(s)) {
                                enabledSubtypes.add(ims);
                            }
                        }
                    }
                    break;
                }
            }
        }
        return enabledSubtypes;
    }

    List<Pair<String, ArrayList<String>>> getEnabledInputMethodsAndSubtypeListLocked() {
        final String enabledInputMethodsStr = getEnabledInputMethodsStr();
        final TextUtils.SimpleStringSplitter inputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter subtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);
        final ArrayList<Pair<String, ArrayList<String>>> imsList = new ArrayList<>();
        if (TextUtils.isEmpty(enabledInputMethodsStr)) {
            return imsList;
        }
        inputMethodSplitter.setString(enabledInputMethodsStr);
        while (inputMethodSplitter.hasNext()) {
            String nextImsStr = inputMethodSplitter.next();
            subtypeSplitter.setString(nextImsStr);
            if (subtypeSplitter.hasNext()) {
                ArrayList<String> subtypeHashes = new ArrayList<>();
                // The first element is ime id.
                String imeId = subtypeSplitter.next();
                while (subtypeSplitter.hasNext()) {
                    subtypeHashes.add(subtypeSplitter.next());
                }
                imsList.add(new Pair<>(imeId, subtypeHashes));
            }
        }
        return imsList;
    }

    /**
     * Build and put a string of EnabledInputMethods with removing specified Id.
     *
     * @return the specified id was removed or not.
     */
    boolean buildAndPutEnabledInputMethodsStrRemovingIdLocked(
            StringBuilder builder, List<Pair<String, ArrayList<String>>> imsList, String id) {
        boolean isRemoved = false;
        boolean needsAppendSeparator = false;
        for (int i = 0; i < imsList.size(); ++i) {
            final Pair<String, ArrayList<String>> ims = imsList.get(i);
            final String curId = ims.first;
            if (curId.equals(id)) {
                // We are disabling this input method, and it is
                // currently enabled.  Skip it to remove from the
                // new list.
                isRemoved = true;
            } else {
                if (needsAppendSeparator) {
                    builder.append(INPUT_METHOD_SEPARATOR);
                } else {
                    needsAppendSeparator = true;
                }
                buildEnabledInputMethodsSettingString(builder, ims);
            }
        }
        if (isRemoved) {
            // Update the setting with the new list of input methods.
            putEnabledInputMethodsStr(builder.toString());
        }
        return isRemoved;
    }

    private ArrayList<InputMethodInfo> createEnabledInputMethodListLocked(
            List<Pair<String, ArrayList<String>>> imsList,
            Predicate<InputMethodInfo> matchingCondition) {
        final ArrayList<InputMethodInfo> res = new ArrayList<>();
        for (int i = 0; i < imsList.size(); ++i) {
            final Pair<String, ArrayList<String>> ims = imsList.get(i);
            final InputMethodInfo info = mMethodMap.get(ims.first);
            if (info != null && !info.isVrOnly()
                    && (matchingCondition == null || matchingCondition.test(info))) {
                res.add(info);
            }
        }
        return res;
    }

    void putEnabledInputMethodsStr(@Nullable String str) {
        if (DEBUG) {
            Slog.d(TAG, "putEnabledInputMethodStr: " + str);
        }
        if (TextUtils.isEmpty(str)) {
            // OK to coalesce to null, since getEnabledInputMethodsStr() can take care of the
            // empty data scenario.
            putString(Settings.Secure.ENABLED_INPUT_METHODS, null);
        } else {
            putString(Settings.Secure.ENABLED_INPUT_METHODS, str);
        }
    }

    @NonNull
    String getEnabledInputMethodsStr() {
        return getString(Settings.Secure.ENABLED_INPUT_METHODS, "");
    }

    private void saveSubtypeHistory(
            List<Pair<String, String>> savedImes, String newImeId, String newSubtypeId) {
        final StringBuilder builder = new StringBuilder();
        boolean isImeAdded = false;
        if (!TextUtils.isEmpty(newImeId) && !TextUtils.isEmpty(newSubtypeId)) {
            builder.append(newImeId).append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(
                    newSubtypeId);
            isImeAdded = true;
        }
        for (int i = 0; i < savedImes.size(); ++i) {
            final Pair<String, String> ime = savedImes.get(i);
            final String imeId = ime.first;
            String subtypeId = ime.second;
            if (TextUtils.isEmpty(subtypeId)) {
                subtypeId = NOT_A_SUBTYPE_ID_STR;
            }
            if (isImeAdded) {
                builder.append(INPUT_METHOD_SEPARATOR);
            } else {
                isImeAdded = true;
            }
            builder.append(imeId).append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(
                    subtypeId);
        }
        // Remove the last INPUT_METHOD_SEPARATOR
        putSubtypeHistoryStr(builder.toString());
    }

    private void addSubtypeToHistory(String imeId, String subtypeId) {
        final List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistoryLocked();
        for (int i = 0; i < subtypeHistory.size(); ++i) {
            final Pair<String, String> ime = subtypeHistory.get(i);
            if (ime.first.equals(imeId)) {
                if (DEBUG) {
                    Slog.v(TAG, "Subtype found in the history: " + imeId + ", "
                            + ime.second);
                }
                // We should break here
                subtypeHistory.remove(ime);
                break;
            }
        }
        if (DEBUG) {
            Slog.v(TAG, "Add subtype to the history: " + imeId + ", " + subtypeId);
        }
        saveSubtypeHistory(subtypeHistory, imeId, subtypeId);
    }

    private void putSubtypeHistoryStr(@NonNull String str) {
        if (DEBUG) {
            Slog.d(TAG, "putSubtypeHistoryStr: " + str);
        }
        if (TextUtils.isEmpty(str)) {
            // OK to coalesce to null, since getSubtypeHistoryStr() can take care of the empty
            // data scenario.
            putString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, null);
        } else {
            putString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, str);
        }
    }

    Pair<String, String> getLastInputMethodAndSubtypeLocked() {
        // Gets the first one from the history
        return getLastSubtypeForInputMethodLockedInternal(null);
    }

    @Nullable
    InputMethodSubtype getLastInputMethodSubtypeLocked() {
        final Pair<String, String> lastIme = getLastInputMethodAndSubtypeLocked();
        // TODO: Handle the case of the last IME with no subtypes
        if (lastIme == null || TextUtils.isEmpty(lastIme.first)
                || TextUtils.isEmpty(lastIme.second)) {
            return null;
        }
        final InputMethodInfo lastImi = mMethodMap.get(lastIme.first);
        if (lastImi == null) return null;
        try {
            final int lastSubtypeHash = Integer.parseInt(lastIme.second);
            final int lastSubtypeId = SubtypeUtils.getSubtypeIdFromHashCode(lastImi,
                    lastSubtypeHash);
            if (lastSubtypeId < 0 || lastSubtypeId >= lastImi.getSubtypeCount()) {
                return null;
            }
            return lastImi.getSubtypeAt(lastSubtypeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String getLastSubtypeForInputMethodLocked(String imeId) {
        Pair<String, String> ime = getLastSubtypeForInputMethodLockedInternal(imeId);
        if (ime != null) {
            return ime.second;
        } else {
            return null;
        }
    }

    private Pair<String, String> getLastSubtypeForInputMethodLockedInternal(String imeId) {
        final List<Pair<String, ArrayList<String>>> enabledImes =
                getEnabledInputMethodsAndSubtypeListLocked();
        final List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistoryLocked();
        for (int i = 0; i < subtypeHistory.size(); ++i) {
            final Pair<String, String> imeAndSubtype = subtypeHistory.get(i);
            final String imeInTheHistory = imeAndSubtype.first;
            // If imeId is empty, returns the first IME and subtype in the history
            if (TextUtils.isEmpty(imeId) || imeInTheHistory.equals(imeId)) {
                final String subtypeInTheHistory = imeAndSubtype.second;
                final String subtypeHashCode =
                        getEnabledSubtypeHashCodeForInputMethodAndSubtypeLocked(
                                enabledImes, imeInTheHistory, subtypeInTheHistory);
                if (!TextUtils.isEmpty(subtypeHashCode)) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "Enabled subtype found in the history: " + subtypeHashCode);
                    }
                    return new Pair<>(imeInTheHistory, subtypeHashCode);
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "No enabled IME found in the history");
        }
        return null;
    }

    private String getEnabledSubtypeHashCodeForInputMethodAndSubtypeLocked(List<Pair<String,
            ArrayList<String>>> enabledImes, String imeId, String subtypeHashCode) {
        final LocaleList localeList = SystemLocaleWrapper.get(mCurrentUserId);
        for (int i = 0; i < enabledImes.size(); ++i) {
            final Pair<String, ArrayList<String>> enabledIme = enabledImes.get(i);
            if (enabledIme.first.equals(imeId)) {
                final ArrayList<String> explicitlyEnabledSubtypes = enabledIme.second;
                final InputMethodInfo imi = mMethodMap.get(imeId);
                if (explicitlyEnabledSubtypes.isEmpty()) {
                    // If there are no explicitly enabled subtypes, applicable subtypes are
                    // enabled implicitly.
                    // If IME is enabled and no subtypes are enabled, applicable subtypes
                    // are enabled implicitly, so needs to treat them to be enabled.
                    if (imi != null && imi.getSubtypeCount() > 0) {
                        List<InputMethodSubtype> implicitlyEnabledSubtypes =
                                SubtypeUtils.getImplicitlyApplicableSubtypesLocked(localeList,
                                        imi);
                        final int numSubtypes = implicitlyEnabledSubtypes.size();
                        for (int j = 0; j < numSubtypes; ++j) {
                            final InputMethodSubtype st = implicitlyEnabledSubtypes.get(j);
                            if (String.valueOf(st.hashCode()).equals(subtypeHashCode)) {
                                return subtypeHashCode;
                            }
                        }
                    }
                } else {
                    for (int j = 0; j < explicitlyEnabledSubtypes.size(); ++j) {
                        final String s = explicitlyEnabledSubtypes.get(j);
                        if (s.equals(subtypeHashCode)) {
                            // If both imeId and subtypeId are enabled, return subtypeId.
                            try {
                                final int hashCode = Integer.parseInt(subtypeHashCode);
                                // Check whether the subtype id is valid or not
                                if (SubtypeUtils.isValidSubtypeId(imi, hashCode)) {
                                    return s;
                                } else {
                                    return NOT_A_SUBTYPE_ID_STR;
                                }
                            } catch (NumberFormatException e) {
                                return NOT_A_SUBTYPE_ID_STR;
                            }
                        }
                    }
                }
                // If imeId was enabled but subtypeId was disabled.
                return NOT_A_SUBTYPE_ID_STR;
            }
        }
        // If both imeId and subtypeId are disabled, return null
        return null;
    }

    private List<Pair<String, String>> loadInputMethodAndSubtypeHistoryLocked() {
        ArrayList<Pair<String, String>> imsList = new ArrayList<>();
        final String subtypeHistoryStr = getSubtypeHistoryStr();
        if (TextUtils.isEmpty(subtypeHistoryStr)) {
            return imsList;
        }
        final TextUtils.SimpleStringSplitter inputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter subtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);
        inputMethodSplitter.setString(subtypeHistoryStr);
        while (inputMethodSplitter.hasNext()) {
            String nextImsStr = inputMethodSplitter.next();
            subtypeSplitter.setString(nextImsStr);
            if (subtypeSplitter.hasNext()) {
                String subtypeId = NOT_A_SUBTYPE_ID_STR;
                // The first element is ime id.
                String imeId = subtypeSplitter.next();
                while (subtypeSplitter.hasNext()) {
                    subtypeId = subtypeSplitter.next();
                    break;
                }
                imsList.add(new Pair<>(imeId, subtypeId));
            }
        }
        return imsList;
    }

    @NonNull
    private String getSubtypeHistoryStr() {
        final String history = getString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, "");
        if (DEBUG) {
            Slog.d(TAG, "getSubtypeHistoryStr: " + history);
        }
        return history;
    }

    void putSelectedInputMethod(String imeId) {
        if (DEBUG) {
            Slog.d(TAG, "putSelectedInputMethodStr: " + imeId + ", "
                    + mCurrentUserId);
        }
        putString(Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
    }

    void putSelectedSubtype(int subtypeId) {
        if (DEBUG) {
            Slog.d(TAG, "putSelectedInputMethodSubtypeStr: " + subtypeId + ", "
                    + mCurrentUserId);
        }
        putInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, subtypeId);
    }

    @Nullable
    String getSelectedInputMethod() {
        final String imi = getString(Settings.Secure.DEFAULT_INPUT_METHOD, null);
        if (DEBUG) {
            Slog.d(TAG, "getSelectedInputMethodStr: " + imi);
        }
        return imi;
    }

    @Nullable
    String getSelectedDefaultDeviceInputMethod() {
        final String imi = getString(Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null);
        if (DEBUG) {
            Slog.d(TAG, "getSelectedDefaultDeviceInputMethodStr: " + imi + ", "
                    + mCurrentUserId);
        }
        return imi;
    }

    void putSelectedDefaultDeviceInputMethod(String imeId) {
        if (DEBUG) {
            Slog.d(TAG, "putSelectedDefaultDeviceInputMethodStr: " + imeId + ", "
                    + mCurrentUserId);
        }
        putString(Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, imeId);
    }

    void putDefaultVoiceInputMethod(String imeId) {
        if (DEBUG) {
            Slog.d(TAG,
                    "putDefaultVoiceInputMethodStr: " + imeId + ", " + mCurrentUserId);
        }
        putString(Settings.Secure.DEFAULT_VOICE_INPUT_METHOD, imeId);
    }

    @Nullable
    String getDefaultVoiceInputMethod() {
        final String imi = getString(Settings.Secure.DEFAULT_VOICE_INPUT_METHOD, null);
        if (DEBUG) {
            Slog.d(TAG, "getDefaultVoiceInputMethodStr: " + imi);
        }
        return imi;
    }

    boolean isSubtypeSelected() {
        return getSelectedInputMethodSubtypeHashCode() != NOT_A_SUBTYPE_ID;
    }

    private int getSelectedInputMethodSubtypeHashCode() {
        return getInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                NOT_A_SUBTYPE_ID);
    }

    @UserIdInt
    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    int getSelectedInputMethodSubtypeId(String selectedImiId) {
        final InputMethodInfo imi = mMethodMap.get(selectedImiId);
        if (imi == null) {
            return NOT_A_SUBTYPE_ID;
        }
        final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
        return SubtypeUtils.getSubtypeIdFromHashCode(imi, subtypeHashCode);
    }

    void saveCurrentInputMethodAndSubtypeToHistory(String curMethodId,
            InputMethodSubtype currentSubtype) {
        String subtypeId = NOT_A_SUBTYPE_ID_STR;
        if (currentSubtype != null) {
            subtypeId = String.valueOf(currentSubtype.hashCode());
        }
        if (InputMethodUtils.canAddToLastInputMethod(currentSubtype)) {
            addSubtypeToHistory(curMethodId, subtypeId);
        }
    }

    /**
     * A variant of {@link InputMethodManagerService#getCurrentInputMethodSubtypeLocked()} for
     * non-current users.
     *
     * <p>TODO: Address code duplication between this and
     * {@link InputMethodManagerService#getCurrentInputMethodSubtypeLocked()}.</p>
     *
     * @return {@link InputMethodSubtype} if exists. {@code null} otherwise.
     */
    @Nullable
    InputMethodSubtype getCurrentInputMethodSubtypeForNonCurrentUsers() {
        final String selectedMethodId = getSelectedInputMethod();
        if (selectedMethodId == null) {
            return null;
        }
        final InputMethodInfo imi = mMethodMap.get(selectedMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }

        final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
        if (subtypeHashCode != NOT_A_SUBTYPE_ID) {
            final int subtypeIndex = SubtypeUtils.getSubtypeIdFromHashCode(imi,
                    subtypeHashCode);
            if (subtypeIndex >= 0) {
                return imi.getSubtypeAt(subtypeIndex);
            }
        }

        // If there are no selected subtypes, the framework will try to find the most applicable
        // subtype from explicitly or implicitly enabled subtypes.
        final List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                getEnabledInputMethodSubtypeListLocked(imi, true);
        // If there is only one explicitly or implicitly enabled subtype, just returns it.
        if (explicitlyOrImplicitlyEnabledSubtypes.isEmpty()) {
            return null;
        }
        if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
            return explicitlyOrImplicitlyEnabledSubtypes.get(0);
        }
        final String locale = SystemLocaleWrapper.get(mCurrentUserId).get(0).toString();
        final InputMethodSubtype subtype = SubtypeUtils.findLastResortApplicableSubtypeLocked(
                explicitlyOrImplicitlyEnabledSubtypes, SubtypeUtils.SUBTYPE_MODE_KEYBOARD,
                locale, true);
        if (subtype != null) {
            return subtype;
        }
        return SubtypeUtils.findLastResortApplicableSubtypeLocked(
                explicitlyOrImplicitlyEnabledSubtypes, null, locale, true);
    }

    boolean setAdditionalInputMethodSubtypes(@NonNull String imeId,
            @NonNull ArrayList<InputMethodSubtype> subtypes,
            @NonNull ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap,
            @NonNull PackageManagerInternal packageManagerInternal, int callingUid) {
        final InputMethodInfo imi = mMethodMap.get(imeId);
        if (imi == null) {
            return false;
        }
        if (!InputMethodUtils.checkIfPackageBelongsToUid(packageManagerInternal, callingUid,
                imi.getPackageName())) {
            return false;
        }

        if (subtypes.isEmpty()) {
            additionalSubtypeMap.remove(imi.getId());
        } else {
            additionalSubtypeMap.put(imi.getId(), subtypes);
        }
        AdditionalSubtypeUtils.save(additionalSubtypeMap, mMethodMap, getCurrentUserId());
        return true;
    }

    boolean setEnabledInputMethodSubtypes(@NonNull String imeId,
            @NonNull int[] subtypeHashCodes) {
        final InputMethodInfo imi = mMethodMap.get(imeId);
        if (imi == null) {
            return false;
        }

        final IntArray validSubtypeHashCodes = new IntArray(subtypeHashCodes.length);
        for (int subtypeHashCode : subtypeHashCodes) {
            if (subtypeHashCode == NOT_A_SUBTYPE_ID) {
                continue;  // NOT_A_SUBTYPE_ID must not be saved
            }
            if (!SubtypeUtils.isValidSubtypeId(imi, subtypeHashCode)) {
                continue;  // this subtype does not exist in InputMethodInfo.
            }
            if (validSubtypeHashCodes.indexOf(subtypeHashCode) >= 0) {
                continue;  // The entry is already added.  No need to add anymore.
            }
            validSubtypeHashCodes.add(subtypeHashCode);
        }

        final String originalEnabledImesString = getEnabledInputMethodsStr();
        final String updatedEnabledImesString = updateEnabledImeString(
                originalEnabledImesString, imi.getId(), validSubtypeHashCodes);
        if (TextUtils.equals(originalEnabledImesString, updatedEnabledImesString)) {
            return false;
        }

        putEnabledInputMethodsStr(updatedEnabledImesString);
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static String updateEnabledImeString(@NonNull String enabledImesString,
            @NonNull String imeId, @NonNull IntArray enabledSubtypeHashCodes) {
        final TextUtils.SimpleStringSplitter imeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter imeSubtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);

        final StringBuilder sb = new StringBuilder();

        imeSplitter.setString(enabledImesString);
        boolean needsImeSeparator = false;
        while (imeSplitter.hasNext()) {
            final String nextImsStr = imeSplitter.next();
            imeSubtypeSplitter.setString(nextImsStr);
            if (imeSubtypeSplitter.hasNext()) {
                if (needsImeSeparator) {
                    sb.append(INPUT_METHOD_SEPARATOR);
                }
                if (TextUtils.equals(imeId, imeSubtypeSplitter.next())) {
                    sb.append(imeId);
                    for (int i = 0; i < enabledSubtypeHashCodes.size(); ++i) {
                        sb.append(INPUT_METHOD_SUBTYPE_SEPARATOR);
                        sb.append(enabledSubtypeHashCodes.get(i));
                    }
                } else {
                    sb.append(nextImsStr);
                }
                needsImeSeparator = true;
            }
        }
        return sb.toString();
    }

    void dumpLocked(final Printer pw, final String prefix) {
        pw.println(prefix + "mCurrentUserId=" + mCurrentUserId);
    }
}
