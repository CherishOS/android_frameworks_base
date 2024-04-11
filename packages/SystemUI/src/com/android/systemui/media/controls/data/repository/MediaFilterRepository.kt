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

package com.android.systemui.media.controls.data.repository

import com.android.internal.logging.InstanceId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A repository that holds the state of filtered media data on the device. */
@SysUISingleton
class MediaFilterRepository @Inject constructor() {

    /** Instance id of media control that recommendations card reactivated. */
    private val _reactivatedId: MutableStateFlow<InstanceId?> = MutableStateFlow(null)
    val reactivatedId: StateFlow<InstanceId?> = _reactivatedId.asStateFlow()

    private val _smartspaceMediaData: MutableStateFlow<SmartspaceMediaData> =
        MutableStateFlow(SmartspaceMediaData())
    val smartspaceMediaData: StateFlow<SmartspaceMediaData> = _smartspaceMediaData.asStateFlow()

    private val _selectedUserEntries: MutableStateFlow<Map<InstanceId, MediaData>> =
        MutableStateFlow(LinkedHashMap())
    val selectedUserEntries: StateFlow<Map<InstanceId, MediaData>> =
        _selectedUserEntries.asStateFlow()

    private val _allUserEntries: MutableStateFlow<Map<String, MediaData>> =
        MutableStateFlow(LinkedHashMap())
    val allUserEntries: StateFlow<Map<String, MediaData>> = _allUserEntries.asStateFlow()

    private val _mediaDataLoadedStates: MutableStateFlow<List<MediaDataLoadingModel>> =
        MutableStateFlow(mutableListOf())
    val mediaDataLoadedStates: StateFlow<List<MediaDataLoadingModel>> =
        _mediaDataLoadedStates.asStateFlow()

    private val _recommendationsLoadingState: MutableStateFlow<SmartspaceMediaLoadingModel> =
        MutableStateFlow(SmartspaceMediaLoadingModel.Unknown)
    val recommendationsLoadingState: StateFlow<SmartspaceMediaLoadingModel> =
        _recommendationsLoadingState.asStateFlow()

    fun addMediaEntry(key: String, data: MediaData) {
        val entries = LinkedHashMap<String, MediaData>(_allUserEntries.value)
        entries[key] = data
        _allUserEntries.value = entries
    }

    /**
     * Removes the media entry corresponding to the given [key].
     *
     * @return media data if an entry is actually removed, `null` otherwise.
     */
    fun removeMediaEntry(key: String): MediaData? {
        val entries = LinkedHashMap<String, MediaData>(_allUserEntries.value)
        val mediaData = entries.remove(key)
        _allUserEntries.value = entries
        return mediaData
    }

    fun addSelectedUserMediaEntry(data: MediaData) {
        val entries = LinkedHashMap<InstanceId, MediaData>(_selectedUserEntries.value)
        entries[data.instanceId] = data
        _selectedUserEntries.value = entries
    }

    /**
     * Removes selected user media entry given the corresponding key.
     *
     * @return media data if an entry is actually removed, `null` otherwise.
     */
    fun removeSelectedUserMediaEntry(key: InstanceId): MediaData? {
        val entries = LinkedHashMap<InstanceId, MediaData>(_selectedUserEntries.value)
        val mediaData = entries.remove(key)
        _selectedUserEntries.value = entries
        return mediaData
    }

    /**
     * Removes selected user media entry given a key and media data.
     *
     * @return true if media data is removed, false otherwise.
     */
    fun removeSelectedUserMediaEntry(key: InstanceId, data: MediaData): Boolean {
        val entries = LinkedHashMap<InstanceId, MediaData>(_selectedUserEntries.value)
        val succeed = entries.remove(key, data)
        if (!succeed) {
            return false
        }
        _selectedUserEntries.value = entries
        return true
    }

    fun clearSelectedUserMedia() {
        _selectedUserEntries.value = LinkedHashMap()
    }

    /** Updates recommendation data with a new smartspace media data. */
    fun setRecommendation(smartspaceMediaData: SmartspaceMediaData) {
        _smartspaceMediaData.value = smartspaceMediaData
    }

    /** Updates media control key that recommendations card reactivated. */
    fun setReactivatedId(instanceId: InstanceId?) {
        _reactivatedId.value = instanceId
    }

    fun addMediaDataLoadingState(mediaDataLoadingModel: MediaDataLoadingModel) {
        // Filter out previous loading state that has same [InstanceId].
        val loadedStates =
            _mediaDataLoadedStates.value.filter { loadedModel ->
                loadedModel !is MediaDataLoadingModel.Loaded ||
                    !loadedModel.equalInstanceIds(mediaDataLoadingModel)
            }

        _mediaDataLoadedStates.value =
            loadedStates +
                if (mediaDataLoadingModel is MediaDataLoadingModel.Loaded) {
                    listOf(mediaDataLoadingModel)
                } else {
                    emptyList()
                }
    }

    fun setRecommedationsLoadingState(smartspaceMediaLoadingModel: SmartspaceMediaLoadingModel) {
        _recommendationsLoadingState.value = smartspaceMediaLoadingModel
    }
}
