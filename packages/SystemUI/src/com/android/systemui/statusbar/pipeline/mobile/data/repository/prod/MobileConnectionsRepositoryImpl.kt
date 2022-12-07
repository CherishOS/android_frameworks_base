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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.provider.Settings.Global.MOBILE_DATA
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.android.internal.telephony.PhoneConstants
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MobileConnectionsRepositoryImpl
@Inject
constructor(
    private val connectivityManager: ConnectivityManager,
    private val subscriptionManager: SubscriptionManager,
    private val telephonyManager: TelephonyManager,
    private val logger: ConnectivityPipelineLogger,
    broadcastDispatcher: BroadcastDispatcher,
    private val globalSettings: GlobalSettings,
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val mobileConnectionRepositoryFactory: MobileConnectionRepositoryImpl.Factory
) : MobileConnectionsRepository {
    private val subIdRepositoryCache: MutableMap<Int, MobileConnectionRepository> = mutableMapOf()

    /**
     * State flow that emits the set of mobile data subscriptions, each represented by its own
     * [SubscriptionInfo]. We probably only need the [SubscriptionInfo.getSubscriptionId] of each
     * info object, but for now we keep track of the infos themselves.
     */
    override val subscriptionsFlow: StateFlow<List<SubscriptionInfo>> =
        conflatedCallbackFlow {
                val callback =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(Unit)
                        }
                    }

                subscriptionManager.addOnSubscriptionsChangedListener(
                    bgDispatcher.asExecutor(),
                    callback,
                )

                awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(callback) }
            }
            .mapLatest { fetchSubscriptionsList() }
            .onEach { infos -> dropUnusedReposFromCache(infos) }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), listOf())

    /** StateFlow that keeps track of the current active mobile data subscription */
    override val activeMobileDataSubscriptionId: StateFlow<Int> =
        conflatedCallbackFlow {
                val callback =
                    object : TelephonyCallback(), ActiveDataSubscriptionIdListener {
                        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                            trySend(subId)
                        }
                    }

                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), INVALID_SUBSCRIPTION_ID)

    private val defaultDataSubIdChangeEvent: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    override val defaultDataSubId: StateFlow<Int> =
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            ) { intent, _ ->
                intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, INVALID_SUBSCRIPTION_ID)
            }
            .distinctUntilChanged()
            .onEach { defaultDataSubIdChangeEvent.tryEmit(Unit) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                SubscriptionManager.getDefaultDataSubscriptionId()
            )

    private val carrierConfigChangedEvent =
        broadcastDispatcher.broadcastFlow(
            IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)
        )

    /**
     * [Config] is an object that tracks relevant configuration flags for a given subscription ID.
     * In the case of [MobileMappings], it's hard-coded to check the default data subscription's
     * config, so this will apply to every icon that we care about.
     *
     * Relevant bits in the config are things like
     * [CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL]
     *
     * This flow will produce whenever the default data subscription or the carrier config changes.
     */
    override val defaultDataSubRatConfig: StateFlow<Config> =
        merge(defaultDataSubIdChangeEvent, carrierConfigChangedEvent)
            .mapLatest { Config.readConfig(context) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = Config.readConfig(context)
            )

    override fun getRepoForSubId(subId: Int): MobileConnectionRepository {
        if (!isValidSubId(subId)) {
            throw IllegalArgumentException(
                "subscriptionId $subId is not in the list of valid subscriptions"
            )
        }

        return subIdRepositoryCache[subId]
            ?: createRepositoryForSubId(subId).also { subIdRepositoryCache[subId] = it }
    }

    /**
     * In single-SIM devices, the [MOBILE_DATA] setting is phone-wide. For multi-SIM, the individual
     * connection repositories also observe the URI for [MOBILE_DATA] + subId.
     */
    override val globalMobileDataSettingChangedEvent: Flow<Unit> = conflatedCallbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }

        globalSettings.registerContentObserver(
            globalSettings.getUriFor(MOBILE_DATA),
            true,
            observer
        )

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    @SuppressLint("MissingPermission")
    override val defaultMobileNetworkConnectivity: StateFlow<MobileConnectivityModel> =
        conflatedCallbackFlow {
                val callback =
                    object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onLost(network: Network) {
                            // Send a disconnected model when lost. Maybe should create a sealed
                            // type or null here?
                            trySend(MobileConnectivityModel())
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities
                        ) {
                            trySend(
                                MobileConnectivityModel(
                                    isConnected = caps.hasTransport(TRANSPORT_CELLULAR),
                                    isValidated = caps.hasCapability(NET_CAPABILITY_VALIDATED),
                                )
                            )
                        }
                    }

                connectivityManager.registerDefaultNetworkCallback(callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), MobileConnectivityModel())

    private fun isValidSubId(subId: Int): Boolean {
        subscriptionsFlow.value.forEach {
            if (it.subscriptionId == subId) {
                return true
            }
        }

        return false
    }

    @VisibleForTesting fun getSubIdRepoCache() = subIdRepositoryCache

    private fun createRepositoryForSubId(subId: Int): MobileConnectionRepository {
        return mobileConnectionRepositoryFactory.build(
            subId,
            defaultDataSubId,
            globalMobileDataSettingChangedEvent,
        )
    }

    private fun dropUnusedReposFromCache(newInfos: List<SubscriptionInfo>) {
        // Remove any connection repository from the cache that isn't in the new set of IDs. They
        // will get garbage collected once their subscribers go away
        val currentValidSubscriptionIds = newInfos.map { it.subscriptionId }

        subIdRepositoryCache.keys.forEach {
            if (!currentValidSubscriptionIds.contains(it)) {
                subIdRepositoryCache.remove(it)
            }
        }
    }

    private suspend fun fetchSubscriptionsList(): List<SubscriptionInfo> =
        withContext(bgDispatcher) { subscriptionManager.completeActiveSubscriptionInfoList }
}
