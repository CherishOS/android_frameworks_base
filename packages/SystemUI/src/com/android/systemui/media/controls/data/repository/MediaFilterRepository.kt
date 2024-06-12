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

import android.content.Context
import com.android.internal.logging.InstanceId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.data.model.MediaSortKeyModel
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.time.SystemClock
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.Locale
import java.util.TreeMap
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A repository that holds the state of filtered media data on the device. */
@SysUISingleton
class MediaFilterRepository
@Inject
constructor(
    @Application applicationContext: Context,
    private val systemClock: SystemClock,
    private val configurationController: ConfigurationController,
) {

    val onAnyMediaConfigurationChange: Flow<Unit> = conflatedCallbackFlow {
        val callback =
            object : ConfigurationController.ConfigurationListener {
                override fun onDensityOrFontScaleChanged() {
                    trySend(Unit)
                }

                override fun onThemeChanged() {
                    trySend(Unit)
                }

                override fun onUiModeChanged() {
                    trySend(Unit)
                }

                override fun onLocaleListChanged() {
                    if (locale != applicationContext.resources.configuration.locales.get(0)) {
                        locale = applicationContext.resources.configuration.locales.get(0)
                        trySend(Unit)
                    }
                }
            }
        configurationController.addCallback(callback)
        trySend(Unit)
        awaitClose { configurationController.removeCallback(callback) }
    }

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

    private val comparator =
        compareByDescending<MediaSortKeyModel> {
                it.isPlaying == true && it.playbackLocation == MediaData.PLAYBACK_LOCAL
            }
            .thenByDescending {
                it.isPlaying == true && it.playbackLocation == MediaData.PLAYBACK_CAST_LOCAL
            }
            .thenByDescending { it.active }
            .thenByDescending { it.isPrioritizedRec }
            .thenByDescending { !it.isResume }
            .thenByDescending { it.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE }
            .thenByDescending { it.lastActive }
            .thenByDescending { it.updateTime }
            .thenByDescending { it.notificationKey }

    private val _currentMedia: MutableStateFlow<List<MediaCommonModel>> =
        MutableStateFlow(mutableListOf())
    val currentMedia = _currentMedia.asStateFlow()

    private var sortedMedia = TreeMap<MediaSortKeyModel, MediaCommonModel>(comparator)
    private var mediaFromRecPackageName: String? = null
    private var locale: Locale = applicationContext.resources.configuration.locales.get(0)

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
        val sortedMap = TreeMap<MediaSortKeyModel, MediaCommonModel>(comparator)
        sortedMap.putAll(
            sortedMedia.filter { (_, commonModel) ->
                commonModel !is MediaCommonModel.MediaControl ||
                    commonModel.mediaLoadedModel.instanceId != mediaDataLoadingModel.instanceId
            }
        )

        _selectedUserEntries.value[mediaDataLoadingModel.instanceId]?.let {
            val sortKey =
                MediaSortKeyModel(
                    isPrioritizedRec = false,
                    it.isPlaying,
                    it.playbackLocation,
                    it.active,
                    it.resumption,
                    it.lastActive,
                    it.notificationKey,
                    systemClock.currentTimeMillis(),
                    it.instanceId,
                )

            if (mediaDataLoadingModel is MediaDataLoadingModel.Loaded) {
                val newCommonModel =
                    MediaCommonModel.MediaControl(
                        mediaDataLoadingModel,
                        canBeRemoved(it),
                        isMediaFromRec(it)
                    )
                sortedMap[sortKey] = newCommonModel

                // On Addition or tapping on recommendations, we should show the new order of media.
                if (mediaFromRecPackageName == it.packageName) {
                    if (it.isPlaying == true) {
                        mediaFromRecPackageName = null
                        _currentMedia.value = sortedMap.values.toList()
                    }
                } else if (sortedMap.size > _currentMedia.value.size && it.active) {
                    _currentMedia.value = sortedMap.values.toList()
                } else {
                    // When loading an update for an existing media control.
                    val currentList =
                        mutableListOf<MediaCommonModel>().apply { addAll(_currentMedia.value) }
                    currentList.forEachIndexed { index, mediaCommonModel ->
                        if (
                            mediaCommonModel is MediaCommonModel.MediaControl &&
                                mediaCommonModel.mediaLoadedModel.instanceId ==
                                    mediaDataLoadingModel.instanceId &&
                                mediaCommonModel != newCommonModel
                        ) {
                            // Update media model if changed.
                            currentList[index] = newCommonModel
                        }
                    }
                    _currentMedia.value = currentList
                }
            }
        }

        sortedMedia = sortedMap

        // On removal we want to keep the order being shown to user.
        if (mediaDataLoadingModel is MediaDataLoadingModel.Removed) {
            _currentMedia.value =
                _currentMedia.value.filter { commonModel ->
                    commonModel !is MediaCommonModel.MediaControl ||
                        mediaDataLoadingModel.instanceId != commonModel.mediaLoadedModel.instanceId
                }
        }
    }

    fun setRecommendationsLoadingState(smartspaceMediaLoadingModel: SmartspaceMediaLoadingModel) {
        val isPrioritized =
            when (smartspaceMediaLoadingModel) {
                is SmartspaceMediaLoadingModel.Loaded -> smartspaceMediaLoadingModel.isPrioritized
                else -> false
            }
        val sortedMap = TreeMap<MediaSortKeyModel, MediaCommonModel>(comparator)
        sortedMap.putAll(
            sortedMedia.filter { (_, commonModel) ->
                commonModel !is MediaCommonModel.MediaRecommendations
            }
        )

        val sortKey =
            MediaSortKeyModel(
                isPrioritizedRec = isPrioritized,
                isPlaying = false,
                active = _smartspaceMediaData.value.isActive,
            )
        when (smartspaceMediaLoadingModel) {
            is SmartspaceMediaLoadingModel.Loaded ->
                sortedMap[sortKey] =
                    MediaCommonModel.MediaRecommendations(smartspaceMediaLoadingModel)
            is SmartspaceMediaLoadingModel.Removed ->
                _currentMedia.value =
                    _currentMedia.value.filter { commonModel ->
                        commonModel !is MediaCommonModel.MediaRecommendations
                    }
        }

        if (sortedMap.size > sortedMedia.size) {
            _currentMedia.value = sortedMap.values.toList()
        }
        sortedMedia = sortedMap
    }

    fun setOrderedMedia() {
        _currentMedia.value = sortedMedia.values.toList()
    }

    fun setMediaFromRecPackageName(packageName: String) {
        mediaFromRecPackageName = packageName
    }

    fun hasActiveMedia(): Boolean {
        return _selectedUserEntries.value.any { it.value.active }
    }

    fun hasAnyMedia(): Boolean {
        return _selectedUserEntries.value.entries.isNotEmpty()
    }

    fun isRecommendationActive(): Boolean {
        return _smartspaceMediaData.value.isActive
    }

    private fun canBeRemoved(data: MediaData): Boolean {
        return data.isPlaying?.let { !it } ?: data.isClearable && !data.active
    }

    private fun isMediaFromRec(data: MediaData): Boolean {
        return data.isPlaying == true && mediaFromRecPackageName == data.packageName
    }
}
