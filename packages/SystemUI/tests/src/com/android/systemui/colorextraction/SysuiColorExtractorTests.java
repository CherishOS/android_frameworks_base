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

package com.android.systemui.colorextraction;

import static org.junit.Assert.assertEquals;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.graphics.Color;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import com.android.systemui.SysuiTestCase;

import com.google.android.colorextraction.ColorExtractor;
import com.google.android.colorextraction.types.Tonal;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests color extraction generation.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SysuiColorExtractorTests extends SysuiTestCase {

    private static int[] sWhich = new int[]{
            WallpaperManager.FLAG_SYSTEM,
            WallpaperManager.FLAG_LOCK};
    private static int[] sTypes = new int[]{
            ColorExtractor.TYPE_NORMAL,
            ColorExtractor.TYPE_DARK,
            ColorExtractor.TYPE_EXTRA_DARK};

    @Test
    public void getColors_usesGreyIfWallpaperNotVisible() {
        ColorExtractor.GradientColors fallbackColors = new ColorExtractor.GradientColors();
        fallbackColors.setMainColor(ColorExtractor.FALLBACK_COLOR);
        fallbackColors.setSecondaryColor(ColorExtractor.FALLBACK_COLOR);

        SysuiColorExtractor extractor = new SysuiColorExtractor(getContext(), new Tonal(), false);
        simulateEvent(extractor);
        extractor.setWallpaperVisible(false);

        for (int which : sWhich) {
            for (int type : sTypes) {
                assertEquals("Not using fallback!", extractor.getColors(which, type),
                        fallbackColors);
            }
        }
    }

    @Test
    public void getColors_doesntUseFallbackIfVisible() {
        ColorExtractor.GradientColors colors = new ColorExtractor.GradientColors();
        colors.setMainColor(Color.RED);
        colors.setSecondaryColor(Color.RED);

        SysuiColorExtractor extractor = new SysuiColorExtractor(getContext(),
                (inWallpaperColors, outGradientColorsNormal, outGradientColorsDark,
                        outGradientColorsExtraDark) -> {
                    outGradientColorsNormal.set(colors);
                    outGradientColorsDark.set(colors);
                    outGradientColorsExtraDark.set(colors);
                    return true;
                }, false);
        simulateEvent(extractor);
        extractor.setWallpaperVisible(true);

        for (int which : sWhich) {
            for (int type : sTypes) {
                assertEquals("Not using extracted colors!",
                        extractor.getColors(which, type), colors);
            }
        }
    }

    private void simulateEvent(SysuiColorExtractor extractor) {
        // Let's fake a color event
        extractor.onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK), null, null, 0),
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }
}