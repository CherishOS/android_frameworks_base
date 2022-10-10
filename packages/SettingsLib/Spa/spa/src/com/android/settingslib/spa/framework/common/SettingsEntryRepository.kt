/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.common

import android.util.Log
import java.util.LinkedList

private const val TAG = "EntryRepository"
private const val MAX_ENTRY_SIZE = 5000

data class SettingsPageWithEntry(
    val page: SettingsPage,
    val entries: List<SettingsEntry>,
)

/**
 * The repository to maintain all Settings entries
 */
class SettingsEntryRepository(sppRepository: SettingsPageProviderRepository) {
    // Map of entry unique Id to entry
    private val entryMap: Map<String, SettingsEntry>

    // Map of Settings page to its contained entries.
    private val pageWithEntryMap: Map<String, SettingsPageWithEntry>

    init {
        Log.d(TAG, "Initialize")
        entryMap = mutableMapOf()
        pageWithEntryMap = mutableMapOf()

        val entryQueue = LinkedList<SettingsEntry>()
        for (page in sppRepository.getAllRootPages()) {
            val rootEntry = SettingsEntryBuilder.createRoot(owner = page).build()
            if (!entryMap.containsKey(rootEntry.id)) {
                entryQueue.push(rootEntry)
                entryMap.put(rootEntry.id, rootEntry)
            }
        }

        while (entryQueue.isNotEmpty() && entryMap.size < MAX_ENTRY_SIZE) {
            val entry = entryQueue.pop()
            val page = entry.toPage
            if (page == null || pageWithEntryMap.containsKey(page.id)) continue
            val spp = sppRepository.getProviderOrNull(page.name) ?: continue
            val newEntries = spp.buildEntry(page.arguments)
            pageWithEntryMap[page.id] = SettingsPageWithEntry(page, newEntries)
            for (newEntry in newEntries) {
                if (!entryMap.containsKey(newEntry.id)) {
                    entryQueue.push(newEntry)
                    entryMap.put(newEntry.id, newEntry)
                }
            }
        }

        Log.d(
            TAG,
            "Initialize Completed: ${entryMap.size} entries in ${pageWithEntryMap.size} pages"
        )
    }

    fun getAllPageWithEntry(): Collection<SettingsPageWithEntry> {
        return pageWithEntryMap.values
    }

    fun getPageWithEntry(pageId: String): SettingsPageWithEntry? {
        return pageWithEntryMap[pageId]
    }

    fun getAllEntries(): Collection<SettingsEntry> {
        return entryMap.values
    }

    fun getEntry(entryId: String): SettingsEntry? {
        return entryMap[entryId]
    }

    fun getEntryPath(entryId: String): String {
        return "TODO(path_of_$entryId)"
    }
}
