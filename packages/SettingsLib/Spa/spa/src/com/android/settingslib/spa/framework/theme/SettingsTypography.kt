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

package com.android.settingslib.spa.framework.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private class SettingsTypography {
    private val brand = FontFamily.Default
    private val plain = FontFamily.Default

    val typography = Typography(
        displayLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.2).sp
        ),
        displayMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.0.sp
        ),
        displaySmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.0.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.02.em,
        ),
        titleMedium = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.02.em,
        ),
        titleSmall = TextStyle(
            fontFamily = brand,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.02.em,
        ),
        bodyLarge = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.em,
        ),
        bodyMedium = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.em,
        ),
        bodySmall = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.em,
        ),
        labelLarge = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.01.em,
        ),
        labelMedium = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.01.em,
        ),
        labelSmall = TextStyle(
            fontFamily = plain,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.01.em,
        ),
    )
}

@Composable
internal fun rememberSettingsTypography(): Typography {
    return remember { SettingsTypography().typography }
}
