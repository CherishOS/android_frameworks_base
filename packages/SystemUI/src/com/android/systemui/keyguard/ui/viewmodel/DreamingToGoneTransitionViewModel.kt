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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class DreamingToGoneTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromDreamingTransitionInteractor.TO_GONE_DURATION,
                edge = Edge.create(from = DREAMING, to = Scenes.Gone),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = DREAMING, to = GONE),
            )

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> = transitionAnimation.immediatelyTransitionTo(0f)
}
