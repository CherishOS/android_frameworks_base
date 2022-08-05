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

package com.android.systemui.statusbar.pipeline

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.repository.NetworkCapabilityInfo
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A processor that transforms raw connectivity information that we get from callbacks and turns it
 * into a list of displayable connectivity information.
 *
 * This will be used for the new status bar pipeline to calculate the list of icons that should be
 * displayed in the RHS of the status bar.
 *
 * Anyone can listen to [processedInfoFlow] to get updates to the processed data.
 */
@SysUISingleton
class ConnectivityInfoProcessor @Inject constructor(
        connectivityInfoCollector: ConnectivityInfoCollector,
        context: Context,
        @Application private val scope: CoroutineScope,
        statusBarPipelineFlags: StatusBarPipelineFlags,
) : CoreStartable(context) {
    // Note: This flow will not start running until a client calls `collect` on it, which means that
    // [connectivityInfoCollector]'s flow will also not start anything until that `collect` call
    // happens.
    val processedInfoFlow: Flow<ProcessedConnectivityInfo> =
            if (!statusBarPipelineFlags.isNewPipelineEnabled())
                emptyFlow()
            else connectivityInfoCollector.rawConnectivityInfoFlow
                    .map { it.process() }
                    .stateIn(
                            scope,
                            started = Lazily,
                            initialValue = ProcessedConnectivityInfo()
                    )

    override fun start() {
    }

    private fun RawConnectivityInfo.process(): ProcessedConnectivityInfo {
        // TODO(b/238425913): Actually process the raw info into meaningful data.
        return ProcessedConnectivityInfo(this.networkCapabilityInfo)
    }
}

/**
 * An object containing connectivity info that has been processed into data that can be directly
 * used by the status bar (and potentially other SysUI areas) to display icons.
 */
data class ProcessedConnectivityInfo(
        val networkCapabilityInfo: Map<Int, NetworkCapabilityInfo> = emptyMap(),
)
