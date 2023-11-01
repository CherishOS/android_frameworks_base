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

package com.android.systemui.communal.ui.viewmodel

import android.appwidget.AppWidgetHost
import android.content.Context
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.communal.ui.model.CommunalContentUiModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@SysUISingleton
class CommunalViewModel
@Inject
constructor(
    @Application private val context: Context,
    private val appWidgetHost: AppWidgetHost,
    communalInteractor: CommunalInteractor,
    tutorialInteractor: CommunalTutorialInteractor,
) {
    /** Whether communal hub should show tutorial content. */
    val showTutorialContent: Flow<Boolean> = tutorialInteractor.isTutorialAvailable

    /** List of widgets to be displayed in the communal hub. */
    val widgetContent: Flow<List<CommunalContentUiModel>> =
        communalInteractor.widgetContent.map { widgets ->
            widgets.map Widget@{ widget ->
                // TODO(b/306406256): As adding and removing widgets functionalities are
                // supported, cache the host views so they're not recreated each time.
                val hostView =
                    appWidgetHost.createView(context, widget.appWidgetId, widget.providerInfo)
                return@Widget CommunalContentUiModel(
                    // TODO(b/308148193): a more scalable solution for unique ids.
                    id = "widget_${widget.appWidgetId}",
                    view = hostView,
                )
            }
        }
}
