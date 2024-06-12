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

package com.android.systemui.touchpad.tutorial.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class TouchpadTutorialViewModel : ViewModel() {

    private val _screen = MutableStateFlow(Screen.TUTORIAL_SELECTION)
    val screen: StateFlow<Screen> = _screen

    fun goTo(screen: Screen) {
        _screen.value = screen
    }

    class Factory @Inject constructor() : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TouchpadTutorialViewModel() as T
        }
    }
}

enum class Screen {
    TUTORIAL_SELECTION,
    BACK_GESTURE,
    HOME_GESTURE,
}
