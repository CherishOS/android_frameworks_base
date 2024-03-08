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

package com.android.systemui.volume.panel.component.spatialaudio.ui.composable

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.toColor
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import com.android.systemui.volume.panel.component.selector.ui.composable.VolumePanelRadioButtonBar
import com.android.systemui.volume.panel.component.spatial.ui.viewmodel.SpatialAudioViewModel
import javax.inject.Inject

class SpatialAudioPopup
@Inject
constructor(
    private val viewModel: SpatialAudioViewModel,
    private val volumePanelPopup: VolumePanelPopup,
) {

    /** Shows a popup with the [expandable] animation. */
    fun show(expandable: Expandable) {
        volumePanelPopup.show(expandable, { Title() }, { Content(it) })
    }

    @Composable
    private fun Title() {
        Text(
            text = stringResource(R.string.volume_panel_spatial_audio_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }

    @Composable
    private fun Content(dialog: SystemUIDialog) {
        val isAvailable by viewModel.isAvailable.collectAsState()

        if (!isAvailable) {
            SideEffect { dialog.dismiss() }
            return
        }

        val enabledModelStates by viewModel.spatialAudioButtonByEnabled.collectAsState()
        if (enabledModelStates.isEmpty()) {
            return
        }
        VolumePanelRadioButtonBar {
            for (buttonViewModel in enabledModelStates) {
                item(
                    isSelected = buttonViewModel.button.isChecked,
                    onItemSelected = { viewModel.setEnabled(buttonViewModel.model) },
                    icon = {
                        Icon(
                            icon = buttonViewModel.button.icon,
                            tint = buttonViewModel.iconColor.toColor(),
                        )
                    },
                    label = {
                        Text(
                            text = buttonViewModel.button.label.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = buttonViewModel.labelColor.toColor(),
                        )
                    }
                )
            }
        }
    }
}
