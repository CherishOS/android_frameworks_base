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

package com.android.settingslib.spa.framework.util

import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.common.LocalEntryDataProvider
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory

@Composable
fun LogEntryEvent(): (event: LogEvent) -> Unit {
    val entryId = LocalEntryDataProvider.current.entryId ?: return {}
    return {
        SpaEnvironmentFactory.instance.logger.event(entryId, it, category = LogCategory.VIEW)
    }
}

@Composable
fun WrapOnClickWithLog(onClick: (() -> Unit)?): (() -> Unit)? {
    if (onClick == null) return null
    val logEvent = LogEntryEvent()
    return {
        logEvent(LogEvent.ENTRY_CLICK)
        onClick()
    }
}

@Composable
fun WrapOnSwitchWithLog(onSwitch: ((checked: Boolean) -> Unit)?): ((checked: Boolean) -> Unit)? {
    if (onSwitch == null) return null
    val logEvent = LogEntryEvent()
    return {
        val event = if (it) LogEvent.ENTRY_SWITCH_ON else LogEvent.ENTRY_SWITCH_OFF
        logEvent(event)
        onSwitch(it)
    }
}
