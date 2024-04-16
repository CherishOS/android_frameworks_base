/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.ui

import android.content.res.Resources
import android.text.TextUtils
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.screenrecord.domain.model.ScreenRecordTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [ScreenRecordTileModel] to [QSTileState]. */
class ScreenRecordTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<ScreenRecordTileModel> {
    override fun map(config: QSTileConfig, data: ScreenRecordTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_screen_record_label)
            supportedActions = setOf(QSTileState.UserAction.CLICK)

            when (data) {
                is ScreenRecordTileModel.Recording -> {
                    activationState = QSTileState.ActivationState.ACTIVE
                    val loadedIcon =
                        Icon.Loaded(
                            resources.getDrawable(R.drawable.qs_screen_record_icon_on, theme),
                            contentDescription = null
                        )
                    icon = { loadedIcon }
                    sideViewIcon = QSTileState.SideViewIcon.None
                    secondaryLabel = resources.getString(R.string.quick_settings_screen_record_stop)
                }
                is ScreenRecordTileModel.Starting -> {
                    activationState = QSTileState.ActivationState.ACTIVE
                    val loadedIcon =
                        Icon.Loaded(
                            resources.getDrawable(R.drawable.qs_screen_record_icon_on, theme),
                            contentDescription = null
                        )
                    icon = { loadedIcon }
                    val countDown = Math.floorDiv(data.millisUntilStarted + 500, 1000)
                    sideViewIcon = QSTileState.SideViewIcon.None
                    secondaryLabel = String.format("%d...", countDown)
                }
                is ScreenRecordTileModel.DoingNothing -> {
                    activationState = QSTileState.ActivationState.INACTIVE
                    val loadedIcon =
                        Icon.Loaded(
                            resources.getDrawable(R.drawable.qs_screen_record_icon_off, theme),
                            contentDescription = null
                        )
                    icon = { loadedIcon }
                    sideViewIcon = QSTileState.SideViewIcon.Chevron // tapping will open dialog
                    secondaryLabel =
                        resources.getString(R.string.quick_settings_screen_record_start)
                }
            }
            contentDescription =
                if (TextUtils.isEmpty(secondaryLabel)) label
                else TextUtils.concat(label, ", ", secondaryLabel)
        }
}
