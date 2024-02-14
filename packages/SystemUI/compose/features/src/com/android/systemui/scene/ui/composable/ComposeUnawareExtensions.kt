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

package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.ObservableTransitionState as ComposeAwareObservableTransitionState
import com.android.compose.animation.scene.SceneKey as ComposeAwareSceneKey
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey

fun ComposeAwareSceneKey.asComposeUnaware(): SceneKey {
    return this.identity as SceneKey
}

fun ComposeAwareObservableTransitionState.asComposeUnaware(): ObservableTransitionState {
    return when (this) {
        is ComposeAwareObservableTransitionState.Idle ->
            ObservableTransitionState.Idle(scene.asComposeUnaware())
        is ComposeAwareObservableTransitionState.Transition ->
            ObservableTransitionState.Transition(
                fromScene = fromScene.asComposeUnaware(),
                toScene = toScene.asComposeUnaware(),
                progress = progress,
                isInitiatedByUserInput = isInitiatedByUserInput,
                isUserInputOngoing = isUserInputOngoing,
            )
    }
}
