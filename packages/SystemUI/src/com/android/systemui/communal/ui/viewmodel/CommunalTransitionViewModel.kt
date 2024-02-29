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

package com.android.systemui.communal.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge

/** View model for transitions related to the communal hub. */
interface CommunalTransitionViewModel {
    val isUmoOnCommunal: Flow<Boolean>
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTransitionViewModelImpl
@Inject
constructor(
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    dreamToGlanceableHubTransitionViewModel: DreamingToGlanceableHubTransitionViewModel,
    glanceableHubToDreamTransitionViewModel: GlanceableHubToDreamingTransitionViewModel,
) : CommunalTransitionViewModel {
    /**
     * Whether UMO location should be on communal. This flow is responsive to transitions so that a
     * new value is emitted at the right step of a transition to/from communal hub that the location
     * of UMO should be updated.
     */
    override val isUmoOnCommunal: Flow<Boolean> =
        merge(
                lockscreenToGlanceableHubTransitionViewModel.showUmo,
                glanceableHubToLockscreenTransitionViewModel.showUmo,
                dreamToGlanceableHubTransitionViewModel.showUmo,
                glanceableHubToDreamTransitionViewModel.showUmo,
            )
            .distinctUntilChanged()
}
