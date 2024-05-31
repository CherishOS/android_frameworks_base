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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaOutputComponentModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject

/** User actions interactor for Media Output Volume Panel component. */
@VolumePanelScope
class MediaOutputActionsInteractor
@Inject
constructor(private val mediaOutputDialogManager: MediaOutputDialogManager) {

    fun onBarClick(model: MediaOutputComponentModel?, expandable: Expandable?) {
        if (model is MediaOutputComponentModel.MediaSession) {
            mediaOutputDialogManager.createAndShowWithController(
                model.session.packageName,
                false,
                expandable?.dialogController()
            )
        } else {
            mediaOutputDialogManager.createAndShowForSystemRouting(expandable?.dialogController())
        }
    }

    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj =
                DialogCuj(
                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                    MediaOutputDialogManager.INTERACTION_JANK_TAG
                )
        )
    }
}
