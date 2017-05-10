/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.google.android.colorextraction.types;

import android.app.WallpaperColors;

import com.google.android.colorextraction.ColorExtractor;

/**
 * Interface to allow various color extraction implementations.
 */
public interface ExtractionType {

    /**
     * Executes color extraction by reading WallpaperColors and setting
     * main and secondary colors on GradientColors.
     *
     * @param inWallpaperColors where to read from
     * @param outGradientColors object that should receive the colors
     * @return true if successful
     */
    boolean extractInto(WallpaperColors inWallpaperColors,
            ColorExtractor.GradientColors outGradientColors);
}
