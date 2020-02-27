/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.content.Context
import android.provider.DeviceConfig

import com.android.internal.annotations.VisibleForTesting
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NOTIFICATIONS_USE_PEOPLE_FILTERING
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_HEADS_UP
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT
import com.android.systemui.util.DeviceConfigProxy

import javax.inject.Inject

private var sUsePeopleFiltering: Boolean? = null

/**
 * Feature controller for the NOTIFICATIONS_USE_PEOPLE_FILTERING config.
 */
class NotificationSectionsFeatureManager @Inject constructor(
    val proxy: DeviceConfigProxy,
    val context: Context
) {

    fun isFilteringEnabled(): Boolean {
        return usePeopleFiltering(proxy)
    }

    fun getNotificationBuckets(): IntArray {
        return when {
            isFilteringEnabled() ->
                intArrayOf(BUCKET_HEADS_UP, BUCKET_PEOPLE, BUCKET_ALERTING, BUCKET_SILENT)
            NotificationUtils.useNewInterruptionModel(context) ->
                intArrayOf(BUCKET_ALERTING, BUCKET_SILENT)
            else ->
                intArrayOf(BUCKET_ALERTING)
        }
    }

    fun getNumberOfBuckets(): Int {
        return getNotificationBuckets().size
    }

    @VisibleForTesting
    fun clearCache() {
        sUsePeopleFiltering = null
    }
}

private fun usePeopleFiltering(proxy: DeviceConfigProxy): Boolean {
    if (sUsePeopleFiltering == null) {
        sUsePeopleFiltering = proxy.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, NOTIFICATIONS_USE_PEOPLE_FILTERING, true)
    }

    return sUsePeopleFiltering!!
}
