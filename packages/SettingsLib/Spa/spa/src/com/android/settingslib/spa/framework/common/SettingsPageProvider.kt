/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.common

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument

/**
 * An SettingsPageProvider represent a Settings page.
 */
interface SettingsPageProvider {

    /** The page name without arguments. */
    val name: String

    /** The page arguments, default is no arguments. */
    val arguments: List<NamedNavArgument>
        get() = emptyList()

    /** The [Composable] used to render this page. */
    @Composable
    fun Page(arguments: Bundle?)

    // fun buildEntry( arguments: Bundle?) : List<entry>
}
