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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Holds business logic for the Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class DeviceItemInteractor
@Inject
constructor(
    private val bluetoothTileDialogRepository: BluetoothTileDialogRepository,
    private val audioManager: AudioManager,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter(),
    private val localBluetoothManager: LocalBluetoothManager?,
    private val uiEventLogger: UiEventLogger,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val mutableDeviceItemUpdate: MutableSharedFlow<List<DeviceItem>> =
        MutableSharedFlow(extraBufferCapacity = 1)
    internal val deviceItemUpdate
        get() = mutableDeviceItemUpdate.asSharedFlow()

    internal val deviceItemUpdateRequest: SharedFlow<Unit> =
        conflatedCallbackFlow {
                val listener =
                    object : BluetoothCallback {
                        override fun onActiveDeviceChanged(
                            activeDevice: CachedBluetoothDevice?,
                            bluetoothProfile: Int
                        ) {
                            super.onActiveDeviceChanged(activeDevice, bluetoothProfile)
                            trySendWithFailureLogging(Unit, TAG, "onActiveDeviceChanged")
                        }

                        override fun onConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice?,
                            state: Int
                        ) {
                            super.onConnectionStateChanged(cachedDevice, state)
                            trySendWithFailureLogging(Unit, TAG, "onConnectionStateChanged")
                        }

                        override fun onDeviceAdded(cachedDevice: CachedBluetoothDevice) {
                            super.onDeviceAdded(cachedDevice)
                            trySendWithFailureLogging(Unit, TAG, "onDeviceAdded")
                        }

                        override fun onProfileConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice,
                            state: Int,
                            bluetoothProfile: Int
                        ) {
                            super.onProfileConnectionStateChanged(
                                cachedDevice,
                                state,
                                bluetoothProfile
                            )
                            trySendWithFailureLogging(Unit, TAG, "onProfileConnectionStateChanged")
                        }
                    }
                localBluetoothManager?.eventManager?.registerCallback(listener)
                awaitClose { localBluetoothManager?.eventManager?.unregisterCallback(listener) }
            }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0))

    private var deviceItemFactoryList: List<DeviceItemFactory> =
        listOf(
            AvailableMediaDeviceItemFactory(),
            ConnectedDeviceItemFactory(),
            SavedDeviceItemFactory()
        )

    private var displayPriority: List<DeviceItemType> =
        listOf(
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
            DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
            DeviceItemType.SAVED_BLUETOOTH_DEVICE,
        )

    internal suspend fun updateDeviceItems(context: Context) {
        withContext(backgroundDispatcher) {
            mutableDeviceItemUpdate.tryEmit(
                bluetoothTileDialogRepository.cachedDevices
                    .mapNotNull { cachedDevice ->
                        deviceItemFactoryList
                            .firstOrNull { it.isFilterMatched(cachedDevice, audioManager) }
                            ?.create(context, cachedDevice)
                    }
                    .sort(displayPriority, bluetoothAdapter?.mostRecentlyConnectedDevices)
            )
        }
    }

    private fun List<DeviceItem>.sort(
        displayPriority: List<DeviceItemType>,
        mostRecentlyConnectedDevices: List<BluetoothDevice>?
    ): List<DeviceItem> {
        return this.sortedWith(
            compareBy<DeviceItem> { displayPriority.indexOf(it.type) }
                .thenBy {
                    mostRecentlyConnectedDevices?.indexOf(it.cachedBluetoothDevice.device) ?: 0
                }
        )
    }

    internal fun updateDeviceItemOnClick(deviceItem: DeviceItem) {
        when (deviceItem.type) {
            DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> {
                if (!BluetoothUtils.isActiveMediaDevice(deviceItem.cachedBluetoothDevice)) {
                    deviceItem.cachedBluetoothDevice.setActive()
                    uiEventLogger.log(BluetoothTileDialogUiEvent.CONNECTED_DEVICE_SET_ACTIVE)
                }
            }
            DeviceItemType.CONNECTED_BLUETOOTH_DEVICE -> {}
            DeviceItemType.SAVED_BLUETOOTH_DEVICE -> {
                deviceItem.cachedBluetoothDevice.connect()
                uiEventLogger.log(BluetoothTileDialogUiEvent.SAVED_DEVICE_CONNECT)
            }
        }
    }

    internal fun setDeviceItemFactoryListForTesting(list: List<DeviceItemFactory>) {
        deviceItemFactoryList = list
    }

    internal fun setDisplayPriorityForTesting(list: List<DeviceItemType>) {
        displayPriority = list
    }

    companion object {
        private const val TAG = "DeviceItemInteractor"
    }
}
