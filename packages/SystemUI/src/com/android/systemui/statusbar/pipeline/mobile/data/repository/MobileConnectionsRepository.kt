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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repo for monitoring the complete active subscription info list, to be consumed and filtered based
 * on various policy
 */
interface MobileConnectionsRepository {
    /** Observable list of current mobile subscriptions */
    val subscriptionsFlow: Flow<List<SubscriptionInfo>>

    /** Observable for the subscriptionId of the current mobile data connection */
    val activeMobileDataSubscriptionId: StateFlow<Int>

    /** Observable for [MobileMappings.Config] tracking the defaults */
    val defaultDataSubRatConfig: StateFlow<Config>

    /** Tracks [SubscriptionManager.getDefaultDataSubscriptionId] */
    val defaultDataSubId: StateFlow<Int>

    /** The current connectivity status for the default mobile network connection */
    val defaultMobileNetworkConnectivity: StateFlow<MobileConnectivityModel>

    /** Get or create a repository for the line of service for the given subscription ID */
    fun getRepoForSubId(subId: Int): MobileConnectionRepository

    /** Observe changes to the [Settings.Global.MOBILE_DATA] setting */
    val globalMobileDataSettingChangedEvent: Flow<Unit>
}
