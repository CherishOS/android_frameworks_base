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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.view

import android.os.Bundle
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel.CastToOtherDeviceChipViewModel.Companion.CAST_TO_OTHER_DEVICE_ICON
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.phone.SystemUIDialog

/** A dialog that lets the user stop an ongoing cast-screen-to-other-device event. */
class EndCastToOtherDeviceDialogDelegate(
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val stopAction: () -> Unit,
    private val state: ProjectionChipModel.Projecting,
) : SystemUIDialog.Delegate {
    override fun createDialog(): SystemUIDialog {
        return endMediaProjectionDialogHelper.createDialog(this)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        with(dialog) {
            setIcon(CAST_TO_OTHER_DEVICE_ICON)
            setTitle(R.string.cast_to_other_device_stop_dialog_title)
            setMessage(
                endMediaProjectionDialogHelper.getDialogMessage(
                    state.projectionState,
                    genericMessageResId = R.string.cast_to_other_device_stop_dialog_message,
                    specificAppMessageResId =
                        R.string.cast_to_other_device_stop_dialog_message_specific_app,
                )
            )
            // No custom on-click, because the dialog will automatically be dismissed when the
            // button is clicked anyway.
            setNegativeButton(R.string.close_dialog_button, /* onClick= */ null)
            setPositiveButton(R.string.cast_to_other_device_stop_dialog_button) { _, _ ->
                stopAction.invoke()
            }
        }
    }
}
