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
package com.android.internal.colorextraction.types;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.WallpaperColors;
import android.graphics.Color;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Range;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.graphics.ColorUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Tests tonal palette generation.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TonalTest {

    @Test
    public void colorRange_containsColor() {
        Tonal.ColorRange colorRange = new Tonal.ColorRange(new Range<>(0f, 50f),
                new Range<>(0f, 1f), new Range<>(0f, 1f));
        float[] hsl = new float[] {25, 0, 0};
        assertTrue("Range " + colorRange + " doesn't contain " + Arrays.toString(hsl),
                colorRange.containsColor(hsl[0], hsl[1], hsl[2]));
    }

    @Test
    public void colorRange_doesntContainColor() {
        Tonal.ColorRange colorRange = new Tonal.ColorRange(new Range<>(0f, 50f),
                new Range<>(0f, 0.5f), new Range<>(0f, 0.5f));
        float[] hsl = new float[] {100, 0, 0};
        assertFalse("Range " + colorRange + " shouldn't contain " + Arrays.toString(hsl),
                colorRange.containsColor(hsl[0], hsl[1], hsl[2]));
        hsl = new float[] {0, 0.6f, 0};
        assertFalse("Range " + colorRange + " shouldn't contain " + Arrays.toString(hsl),
                colorRange.containsColor(hsl[0], hsl[1], hsl[2]));
        hsl = new float[] {0, 0, 0.6f};
        assertFalse("Range " + colorRange + " shouldn't contain " + Arrays.toString(hsl),
                colorRange.containsColor(hsl[0], hsl[1], hsl[2]));
    }

    @Test
    public void colorRange_excludeBlacklistedColor() {
        // Creating a WallpaperColors object that contains *only* blacklisted colors
        float[] hsl = Tonal.BLACKLISTED_COLORS[0].getCenter();
        WallpaperColors colors = new WallpaperColors(Color.valueOf(ColorUtils.HSLToColor(hsl)),
                null, null, 0);

        // Make sure that palette generation will fail
        Tonal tonal = new Tonal();
        boolean success = tonal.extractInto(colors, new GradientColors(), new GradientColors(),
                new GradientColors());
        assertFalse("Cannot generate a tonal palette from blacklisted colors ", success);
    }
}
