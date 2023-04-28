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

package com.android.server.credentials.metrics.shared;

import android.annotation.NonNull;
import android.util.Slog;

import com.android.server.credentials.metrics.EntryEnum;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Some data is directly shared between the
 * {@link com.android.server.credentials.metrics.CandidatePhaseMetric} and the
 * {@link com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric}. This
 * aims to create an abstraction that holds that information, to avoid duplication.
 *
 * This class should be immutable and threadsafe once generated.
 */
public class ResponseCollective {
    /*
    Abstract Function (responseCounts, entryCounts) -> A 'ResponseCollective' containing information
    about a chosen or candidate providers available responses, be they entries or credentials.

    RepInvariant: mResponseCounts and mEntryCounts are always initialized

    Threadsafe and Immutability: Once generated, the maps remain unchangeable. The object is
    threadsafe and immutable, and safe from external changes. This is threadsafe because it is
    immutable after creation and only allows reads, not writes.
    */

    private static final String TAG = "ResponseCollective";

    // Stores the deduped credential response information, eg {"response":5} for this provider
    private final Map<String, Integer> mResponseCounts;
    // Stores the deduped entry information, eg {ENTRY_ENUM:5} for this provider
    private final Map<EntryEnum, Integer> mEntryCounts;

    public ResponseCollective(@NonNull Map<String, Integer> responseCounts,
            @NonNull Map<EntryEnum, Integer> entryCounts) {
        mResponseCounts = responseCounts == null ? new LinkedHashMap<>() :
                new LinkedHashMap<>(responseCounts);
        mEntryCounts = entryCounts == null ? new LinkedHashMap<>() :
                new LinkedHashMap<>(entryCounts);
    }

    /**
     * Returns the unique, deduped, response classtypes for logging associated with this provider.
     *
     * @return a string array for deduped classtypes
     */
    public String[] getUniqueResponseStrings() {
        if (mResponseCounts.isEmpty()) {
            Slog.w(TAG, "There are no unique string response types collected");
        }
        String[] result = new String[mResponseCounts.keySet().size()];
        mResponseCounts.keySet().toArray(result);
        return result;
    }

    /**
     * Returns the unique, deduped, response classtype counts for logging associated with this
     * provider.
     * @return a string array for deduped classtype counts
     */
    public int[] getUniqueResponseCounts() {
        if (mResponseCounts.isEmpty()) {
            Slog.w(TAG, "There are no unique string response type counts collected");
        }
        return mResponseCounts.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Returns the unique, deduped, entry types for logging associated with this provider.
     * @return an int array for deduped entries
     */
    public int[] getUniqueEntries() {
        if (mEntryCounts.isEmpty()) {
            Slog.w(TAG, "There are no unique entry response types collected");
        }
        return mEntryCounts.keySet().stream().mapToInt(Enum::ordinal).toArray();
    }

    /**
     * Returns the unique, deduped, entry classtype counts for logging associated with this
     * provider.
     * @return a string array for deduped classtype counts
     */
    public int[] getUniqueEntryCounts() {
        if (mEntryCounts.isEmpty()) {
            Slog.w(TAG, "There are no unique entry response type counts collected");
        }
        return mEntryCounts.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Given a specific {@link EntryEnum}, this provides us with the count of that entry within
     * this particular provider.
     * @param e the entry enum with which we want to know the count of
     * @return a count of this particular entry enum stored by this provider
     */
    public int getCountForEntry(EntryEnum e) {
        return mEntryCounts.get(e);
    }

    /**
     * Indicates the total number of existing entries for this provider.
     * @return a count of the total number of entries for this provider
     */
    public int getNumEntriesTotal() {
        return mEntryCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
