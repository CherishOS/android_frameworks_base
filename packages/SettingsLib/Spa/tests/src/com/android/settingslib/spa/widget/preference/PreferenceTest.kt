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

package com.android.settingslib.spa.widget.preference

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.toState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            Preference(object : PreferenceModel {
                override val title = "Preference"
            })
        }

        composeTestRule.onNodeWithText("Preference").assertIsDisplayed()
    }

    @Test
    fun click_enabled_withEffect() {
        composeTestRule.setContent {
            var count by remember { mutableStateOf(0) }
            Preference(object : PreferenceModel {
                override val title = "Preference"
                override val summary = derivedStateOf { count.toString() }
                override val onClick: (() -> Unit) = { count++ }
            })
        }

        composeTestRule.onNodeWithText("Preference").performClick()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun click_disabled_noEffect() {
        composeTestRule.setContent {
            var count by remember { mutableStateOf(0) }
            Preference(object : PreferenceModel {
                override val title = "Preference"
                override val summary = derivedStateOf { count.toString() }
                override val enabled = false.toState()
                override val onClick: (() -> Unit) = { count++ }
            })
        }

        composeTestRule.onNodeWithText("Preference").performClick()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }
}
