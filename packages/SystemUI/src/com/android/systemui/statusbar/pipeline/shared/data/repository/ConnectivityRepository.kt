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

package com.android.systemui.statusbar.pipeline.shared.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import androidx.annotation.ArrayRes
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.pipeline.shared.ConnectivityInputLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.CarrierMerged
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.Ethernet
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.Mobile
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.Wifi
import com.android.systemui.tuner.TunerService
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Provides data related to the connectivity state that needs to be shared across multiple different
 * types of connectivity (wifi, mobile, ethernet, etc.)
 */
interface ConnectivityRepository {
    /** Observable for the current set of connectivity icons that should be force-hidden. */
    val forceHiddenSlots: StateFlow<Set<ConnectivitySlot>>

    /** Observable for which connection(s) are currently default. */
    val defaultConnections: StateFlow<DefaultConnectionModel>
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ConnectivityRepositoryImpl
@Inject
constructor(
    connectivityManager: ConnectivityManager,
    private val connectivitySlots: ConnectivitySlots,
    context: Context,
    dumpManager: DumpManager,
    logger: ConnectivityInputLogger,
    @Application scope: CoroutineScope,
    tunerService: TunerService,
) : ConnectivityRepository, Dumpable {
    init {
        dumpManager.registerNormalDumpable("ConnectivityRepository", this)
    }

    // The default set of hidden icons to use if we don't get any from [TunerService].
    private val defaultHiddenIcons: Set<ConnectivitySlot> =
        context.resources
            .getStringArray(DEFAULT_HIDDEN_ICONS_RESOURCE)
            .asList()
            .toSlotSet(connectivitySlots)

    override val forceHiddenSlots: StateFlow<Set<ConnectivitySlot>> =
        conflatedCallbackFlow {
                val callback =
                    object : TunerService.Tunable {
                        override fun onTuningChanged(key: String, newHideList: String?) {
                            if (key != HIDDEN_ICONS_TUNABLE_KEY) {
                                return
                            }
                            logger.logTuningChanged(newHideList)

                            val outputList =
                                newHideList?.split(",")?.toSlotSet(connectivitySlots)
                                    ?: defaultHiddenIcons
                            trySend(outputList)
                        }
                    }
                tunerService.addTunable(callback, HIDDEN_ICONS_TUNABLE_KEY)

                awaitClose { tunerService.removeTunable(callback) }
            }
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = defaultHiddenIcons
            )

    @SuppressLint("MissingPermission")
    override val defaultConnections: StateFlow<DefaultConnectionModel> =
        conflatedCallbackFlow {
                val callback =
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onLost(network: Network) {
                            logger.logOnDefaultLost(network)
                            // The system no longer has a default network, so everything is
                            // non-default.
                            trySend(
                                DefaultConnectionModel(
                                    Wifi(isDefault = false),
                                    Mobile(isDefault = false),
                                    CarrierMerged(isDefault = false),
                                    Ethernet(isDefault = false),
                                    isValidated = false,
                                )
                            )
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            logger.logOnDefaultCapabilitiesChanged(network, networkCapabilities)

                            val isWifiDefault =
                                networkCapabilities.hasTransport(TRANSPORT_WIFI) ||
                                    networkCapabilities.getMainOrUnderlyingWifiInfo() != null
                            val isMobileDefault =
                                networkCapabilities.hasTransport(TRANSPORT_CELLULAR)
                            val isCarrierMergedDefault =
                                networkCapabilities
                                    .getMainOrUnderlyingWifiInfo()
                                    ?.isCarrierMerged == true
                            val isEthernetDefault =
                                networkCapabilities.hasTransport(TRANSPORT_ETHERNET)

                            val isValidated =
                                networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)

                            trySend(
                                DefaultConnectionModel(
                                    Wifi(isWifiDefault),
                                    Mobile(isMobileDefault),
                                    CarrierMerged(isCarrierMergedDefault),
                                    Ethernet(isEthernetDefault),
                                    isValidated,
                                )
                            )
                        }
                    }

                connectivityManager.registerDefaultNetworkCallback(callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .distinctUntilChanged()
            .onEach { logger.logDefaultConnectionsChanged(it) }
            .stateIn(scope, SharingStarted.Eagerly, DefaultConnectionModel())

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply { println("defaultHiddenIcons=$defaultHiddenIcons") }
    }

    companion object {
        @VisibleForTesting
        internal const val HIDDEN_ICONS_TUNABLE_KEY = StatusBarIconController.ICON_HIDE_LIST
        @VisibleForTesting
        @ArrayRes
        internal val DEFAULT_HIDDEN_ICONS_RESOURCE = R.array.config_statusBarIconsToExclude

        /** Converts a list of string slot names to a set of [ConnectivitySlot] instances. */
        private fun List<String>.toSlotSet(
            connectivitySlots: ConnectivitySlots
        ): Set<ConnectivitySlot> {
            return this.filter { it.isNotBlank() }
                .mapNotNull { connectivitySlots.getSlotFromName(it) }
                .toSet()
        }

        /**
         * Returns a [WifiInfo] object from the capabilities if it has one, or null if there is no
         * underlying wifi network.
         *
         * This will return a valid [WifiInfo] object if wifi is the main transport **or** wifi is
         * an underlying transport. This is important for carrier merged networks, where the main
         * transport info is *not* wifi, but the underlying transport info *is* wifi. We want to
         * always use [WifiInfo] if it's available, so we need to check the underlying transport
         * info.
         */
        fun NetworkCapabilities.getMainOrUnderlyingWifiInfo(): WifiInfo? {
            // Wifi info can either come from a WIFI Transport, or from a CELLULAR transport for
            // virtual networks like VCN.
            val canHaveWifiInfo =
                this.hasTransport(TRANSPORT_CELLULAR) || this.hasTransport(TRANSPORT_WIFI)
            if (!canHaveWifiInfo) {
                return null
            }

            return when (val currentTransportInfo = transportInfo) {
                // This VcnTransportInfo logic is copied from
                // [com.android.settingslib.Utils.tryGetWifiInfoForVcn]. It's copied instead of
                // re-used because it makes the logic here clearer, and because the method will be
                // removed once this pipeline is fully launched.
                is VcnTransportInfo -> currentTransportInfo.wifiInfo
                is WifiInfo -> currentTransportInfo
                else -> null
            }
        }
    }
}
