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

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.slice.SettingsSliceDataRepository

private const val TAG = "SpaEnvironment"

object SpaEnvironmentFactory {
    private var spaEnvironment: SpaEnvironment? = null

    fun reset(env: SpaEnvironment) {
        spaEnvironment = env
        Log.d(TAG, "reset")
    }

    @Composable
    fun resetForPreview() {
        val context = LocalContext.current
        spaEnvironment = object : SpaEnvironment(context) {
            override val pageProviderRepository = lazy {
                SettingsPageProviderRepository(
                    allPageProviders = emptyList(),
                    rootPages = emptyList()
                )
            }
        }
        Log.d(TAG, "resetForPreview")
    }

    fun isReady(): Boolean {
        return spaEnvironment != null
    }

    val instance: SpaEnvironment
        get() {
            if (spaEnvironment == null)
                throw UnsupportedOperationException("Spa environment is not set")
            return spaEnvironment!!
        }
}

abstract class SpaEnvironment(context: Context) {
    abstract val pageProviderRepository: Lazy<SettingsPageProviderRepository>

    val entryRepository = lazy { SettingsEntryRepository(pageProviderRepository.value) }

    val sliceDataRepository = lazy { SettingsSliceDataRepository(entryRepository.value) }

    // In Robolectric test, applicationContext is not available. Use context as fallback.
    val appContext: Context = context.applicationContext ?: context

    open val browseActivityClass: Class<out Activity>? = null
    open val sliceBroadcastReceiverClass: Class<out BroadcastReceiver>? = null
    open val searchProviderAuthorities: String? = null
    open val sliceProviderAuthorities: String? = null
    open val logger: SpaLogger = object : SpaLogger {}

    // TODO: add other environment setup here.
}
