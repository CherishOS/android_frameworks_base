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

package com.android.systemui.shade.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.LockscreenSceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the shade scene. */
@SysUISingleton
class ShadeSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val lockscreenSceneInteractor: LockscreenSceneInteractor,
) {
    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: StateFlow<SceneKey> =
        lockscreenSceneInteractor.isDeviceLocked
            .map { isLocked -> upDestinationSceneKey(isLocked = isLocked) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    upDestinationSceneKey(
                        isLocked = lockscreenSceneInteractor.isDeviceLocked.value,
                    ),
            )

    /** Notifies that some content in the shade was clicked. */
    fun onContentClicked() {
        lockscreenSceneInteractor.dismissLockscreen()
    }

    private fun upDestinationSceneKey(
        isLocked: Boolean,
    ): SceneKey {
        return if (isLocked) SceneKey.Lockscreen else SceneKey.Gone
    }
}
