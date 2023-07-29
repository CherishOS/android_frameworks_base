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

package com.android.systemui.scene.ui.viewmodel

import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.RemoteUserInput
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Models UI state for the scene container. */
@SysUISingleton
class SceneContainerViewModel
@Inject
constructor(
    private val interactor: SceneInteractor,
) {
    /** A flow of motion events originating from outside of the scene framework. */
    val remoteUserInput: StateFlow<RemoteUserInput?> = interactor.remoteUserInput

    /**
     * Keys of all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    val allSceneKeys: List<SceneKey> = interactor.allSceneKeys()

    /** The current scene. */
    val currentScene: StateFlow<SceneModel> = interactor.currentScene

    /** Whether the container is visible. */
    val isVisible: StateFlow<Boolean> = interactor.isVisible

    /** Requests a transition to the scene with the given key. */
    fun setCurrentScene(scene: SceneModel) {
        interactor.setCurrentScene(scene)
    }

    /** Notifies of the progress of a scene transition. */
    fun setSceneTransitionProgress(progress: Float) {
        interactor.setSceneTransitionProgress(progress)
    }

    /** Handles a [MotionEvent] representing remote user input. */
    fun onRemoteUserInput(event: MotionEvent) {
        interactor.onRemoteUserInput(RemoteUserInput.translateMotionEvent(event))
    }
}
