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

package com.android.systemui.keyboard.shortcut.ui.viewmodel

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperInteractor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ShortcutHelperViewModel
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val interactor: ShortcutHelperInteractor
) {

    val shouldShow =
        interactor.state
            .map { it is ShortcutHelperState.Active }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    fun onViewClosed() {
        interactor.onViewClosed()
    }

    fun onViewOpened() {
        interactor.onViewOpened()
    }
}
