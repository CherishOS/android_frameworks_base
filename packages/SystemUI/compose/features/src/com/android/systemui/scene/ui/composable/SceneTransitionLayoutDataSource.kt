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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.observableTransitionState
import com.android.systemui.scene.shared.model.SceneDataSource
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.TransitionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * An implementation of [SceneDataSource] that's backed by a [MutableSceneTransitionLayoutState].
 */
class SceneTransitionLayoutDataSource(
    private val state: MutableSceneTransitionLayoutState,

    /**
     * The [CoroutineScope] of the @Composable that's using this, it's critical that this is *not*
     * the application scope.
     */
    private val coroutineScope: CoroutineScope,
) : SceneDataSource {
    override val currentScene: StateFlow<SceneKey> =
        state
            .observableTransitionState()
            .flatMapLatest { observableTransitionState ->
                when (observableTransitionState) {
                    is ObservableTransitionState.Idle -> flowOf(observableTransitionState.scene)
                    is ObservableTransitionState.Transition ->
                        observableTransitionState.isUserInputOngoing.map { isUserInputOngoing ->
                            if (isUserInputOngoing) {
                                observableTransitionState.fromScene
                            } else {
                                observableTransitionState.toScene
                            }
                        }
                }
            }
            .map { it.asComposeUnaware() }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = state.transitionState.currentScene.asComposeUnaware(),
            )

    override fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey?,
    ) {
        state.setTargetScene(
            targetScene = toScene.asComposeAware(),
            transitionKey = transitionKey?.asComposeAware(),
            coroutineScope = coroutineScope,
        )
    }
}
