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

package com.android.systemui.communal.shared

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.ComponentName
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import java.util.Optional
import javax.inject.Inject

/**
 * Widget host that interacts with AppWidget service and host to manage and provide info for widgets
 * shown in the glanceable hub.
 */
class CommunalWidgetHost
@Inject
constructor(
    private val appWidgetManager: Optional<AppWidgetManager>,
    private val appWidgetHost: AppWidgetHost,
    @CommunalLog logBuffer: LogBuffer,
) {
    companion object {
        private const val TAG = "CommunalWidgetHost"
    }
    private val logger = Logger(logBuffer, TAG)

    /**
     * Allocate an app widget id and binds the widget.
     *
     * @return widgetId if binding is successful; otherwise return null
     */
    fun allocateIdAndBindWidget(provider: ComponentName): Int? {
        val id = appWidgetHost.allocateAppWidgetId()
        if (bindWidget(id, provider)) {
            logger.d("Successfully bound the widget $provider")
            return id
        }
        appWidgetHost.deleteAppWidgetId(id)
        logger.d("Failed to bind the widget $provider")
        return null
    }

    private fun bindWidget(widgetId: Int, provider: ComponentName): Boolean {
        if (appWidgetManager.isPresent) {
            return appWidgetManager.get().bindAppWidgetIdIfAllowed(widgetId, provider)
        }
        return false
    }

    /**
     * Returns whether a particular widget requires configuration when it is first added.
     *
     * Must be called after the widget id has been bound.
     */
    fun requiresConfiguration(widgetId: Int): Boolean {
        if (appWidgetManager.isPresent) {
            val widgetInfo = appWidgetManager.get().getAppWidgetInfo(widgetId)
            val featureFlags: Int = widgetInfo.widgetFeatures
            // A widget's configuration is optional only if it's configuration is marked as optional
            // AND it can be reconfigured later.
            val configurationOptional =
                (featureFlags and WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
                    featureFlags and WIDGET_FEATURE_RECONFIGURABLE != 0)
            return widgetInfo.configure != null && !configurationOptional
        }
        return false
    }
}
