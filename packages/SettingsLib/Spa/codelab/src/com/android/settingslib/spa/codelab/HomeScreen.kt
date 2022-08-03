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

package com.android.settingslib.spa.codelab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.onClickNavigateTo
import com.android.settingslib.spa.theme.SettingsDimension
import com.android.settingslib.spa.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

@Composable
fun HomeScreen() {
    Column {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(SettingsDimension.itemPadding),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
        )

        Preference(object : PreferenceModel {
            override val title = "Preference"
            override val onClick: (() -> Unit) = onClickNavigateTo(Destinations.Preference)
        })
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SettingsTheme {
        HomeScreen()
    }
}
