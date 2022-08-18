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

package com.android.settingslib.spaprivileged.template.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.Footer

@Composable
fun AppInfoPage(
    title: String,
    packageName: String,
    userId: Int,
    footerText: String,
    content: @Composable () -> Unit,
) {
    // TODO: Replace with SettingsScaffold
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = title,
            modifier = Modifier.padding(SettingsDimension.itemPadding),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
        )

        AppInfo(packageName, userId)

        content()

        Footer(footerText)
    }
}
