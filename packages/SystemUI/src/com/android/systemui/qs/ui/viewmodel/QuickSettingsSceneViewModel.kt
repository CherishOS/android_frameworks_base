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

package com.android.systemui.qs.ui.viewmodel

import com.android.systemui.keyguard.domain.interactor.LockscreenSceneInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Models UI state and handles user input for the quick settings scene. */
class QuickSettingsSceneViewModel
@AssistedInject
constructor(
    lockscreenSceneInteractorFactory: LockscreenSceneInteractor.Factory,
    @Assisted containerName: String,
) {
    private val lockscreenSceneInteractor: LockscreenSceneInteractor =
        lockscreenSceneInteractorFactory.create(containerName)

    /** Notifies that some content in quick settings was clicked. */
    fun onContentClicked() {
        lockscreenSceneInteractor.dismissLockscreen()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): QuickSettingsSceneViewModel
    }
}
