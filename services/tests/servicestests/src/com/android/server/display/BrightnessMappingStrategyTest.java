/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.PowerManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Spline;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessMappingStrategyTest {

    private static final int[] LUX_LEVELS = {
        0,
        5,
        20,
        40,
        100,
        325,
        600,
        1250,
        2200,
        4000,
        5000
    };

    private static final float[] DISPLAY_LEVELS_NITS = {
        13.25f,
        54.0f,
        78.85f,
        105.02f,
        132.7f,
        170.12f,
        212.1f,
        265.2f,
        335.8f,
        415.2f,
        478.5f,
    };

    private static final int[] DISPLAY_LEVELS_BACKLIGHT = {
        9,
        30,
        45,
        62,
        78,
        96,
        119,
        146,
        178,
        221,
        255
    };

    private static final float[] DISPLAY_RANGE_NITS = { 2.685f, 478.5f };
    private static final int[] BACKLIGHT_RANGE = { 1, 255 };

    private static final float[] EMPTY_FLOAT_ARRAY = new float[0];
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    @Test
    public void testSimpleStrategyMappingAtControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(res);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel =
                    (float) DISPLAY_LEVELS_BACKLIGHT[i] / PowerManager.BRIGHTNESS_ON;
            assertEquals(expectedLevel,
                    simple.getBrightness(LUX_LEVELS[i]), 0.01f /*tolerance*/);
        }
    }

    @Test
    public void testSimpleStrategyMappingBetweenControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(res);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float backlight = simple.getBrightness(lux) * PowerManager.BRIGHTNESS_ON;
            assertTrue("Desired brightness should be between adjacent control points.",
                    backlight > DISPLAY_LEVELS_BACKLIGHT[i-1]
                        && backlight < DISPLAY_LEVELS_BACKLIGHT[i]);
        }
    }

    @Test
    public void testPhysicalStrategyMappingAtControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS,
                DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel = DISPLAY_LEVELS_NITS[i] / DISPLAY_RANGE_NITS[1];
            assertEquals(expectedLevel,
                    physical.getBrightness(LUX_LEVELS[i]), 0.01f /*tolerance*/);
        }
    }

    @Test
    public void testPhysicalStrategyMappingBetweenControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS,
                DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        Spline backlightToBrightness =
                Spline.createSpline(toFloatArray(BACKLIGHT_RANGE), DISPLAY_RANGE_NITS);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float backlight = physical.getBrightness(lux) * PowerManager.BRIGHTNESS_ON;
            final float nits = backlightToBrightness.interpolate(backlight);
            assertTrue("Desired brightness should be between adjacent control points.",
                    nits > DISPLAY_LEVELS_NITS[i-1] && nits < DISPLAY_LEVELS_NITS[i]);
        }
    }

    @Test
    public void testDefaultStrategyIsPhysical() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res);
        assertTrue(strategy instanceof BrightnessMappingStrategy.PhysicalMappingStrategy);
    }

    @Test
    public void testNonStrictlyIncreasingLuxLevelsFails() {
        final int[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length);
        final int idx = lux.length / 2;
        int tmp = lux[idx];
        lux[idx] = lux[idx+1];
        lux[idx+1] = tmp;
        Resources res = createResources(lux, DISPLAY_LEVELS_NITS,
                DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);

        // And make sure we get the same result even if it's monotone but not increasing.
        lux[idx] = lux[idx+1];
        res = createResources(lux, DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);
    }

    @Test
    public void testDifferentNumberOfControlPointValuesFails() {
        //Extra lux level
        final int[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length+1);
        // Make sure it's strictly increasing so that the only failure is the differing array
        // lengths
        lux[lux.length - 1] = lux[lux.length - 2] + 1;
        Resources res = createResources(lux, DISPLAY_LEVELS_NITS,
                DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);

        res = createResources(lux, DISPLAY_LEVELS_BACKLIGHT);
        strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);

        // Extra backlight level
        final int[] backlight = Arrays.copyOf(
                DISPLAY_LEVELS_BACKLIGHT, DISPLAY_LEVELS_BACKLIGHT.length+1);
        backlight[backlight.length - 1] = backlight[backlight.length - 2] + 1;
        res = createResources(LUX_LEVELS, backlight);
        strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);

        // Extra nits level
        final float[] nits = Arrays.copyOf(DISPLAY_RANGE_NITS, DISPLAY_LEVELS_NITS.length+1);
        nits[nits.length - 1] = nits[nits.length - 2] + 1;
        res = createResources(LUX_LEVELS, nits, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        strategy = BrightnessMappingStrategy.create(res);
        assertNull(strategy);
    }

    @Test
    public void testPhysicalStrategyRequiresNitsMapping() {
        Resources res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, EMPTY_FLOAT_ARRAY /*nitsRange*/, BACKLIGHT_RANGE);
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res);
        assertNull(physical);

        res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, EMPTY_INT_ARRAY /*backlightRange*/);
        physical = BrightnessMappingStrategy.create(res);
        assertNull(physical);

        res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, EMPTY_FLOAT_ARRAY /*nitsRange*/,
                EMPTY_INT_ARRAY /*backlightRange*/);
        physical = BrightnessMappingStrategy.create(res);
        assertNull(physical);
    }

    private static float[] toFloatArray(int[] vals) {
        float[] newVals = new float[vals.length];
        for (int i = 0; i < vals.length; i++) {
            newVals[i] = (float) vals[i];
        }
        return newVals;
    }

    private Resources createResources(int[] luxLevels, int[] brightnessLevelsBacklight) {
        return createResources(luxLevels, brightnessLevelsBacklight,
                EMPTY_FLOAT_ARRAY /*brightnessLevelsNits*/, EMPTY_FLOAT_ARRAY /*nitsRange*/,
                EMPTY_INT_ARRAY /*backlightRange*/);
    }

    private Resources createResources(int[] luxLevels, float[] brightnessLevelsNits,
            float[] nitsRange, int[] backlightRange) {
        return createResources(luxLevels, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                brightnessLevelsNits, nitsRange, backlightRange);
    }

    private Resources createResources(int[] luxLevels, int[] brightnessLevelsBacklight,
            float[] brightnessLevelsNits, float[] nitsRange, int[] backlightRange) {
        Resources mockResources = mock(Resources.class);
        // For historical reasons, the lux levels resource implicitly defines the first point as 0,
        // so we need to chop it off of the array the mock resource object returns.
        int[] luxLevelsResource = Arrays.copyOfRange(luxLevels, 1, luxLevels.length);
        when(mockResources.getIntArray(com.android.internal.R.array.config_autoBrightnessLevels))
                .thenReturn(luxLevelsResource);

        when(mockResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues))
                .thenReturn(brightnessLevelsBacklight);

        TypedArray mockBrightnessLevelNits = createFloatTypedArray(brightnessLevelsNits);
        when(mockResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(mockBrightnessLevelNits);

        TypedArray mockNitsRange = createFloatTypedArray(nitsRange);
        when(mockResources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits))
                .thenReturn(mockNitsRange);

        when(mockResources.getIntArray(
                com.android.internal.R.array.config_screenBrightnessBacklight))
                .thenReturn(backlightRange);

        when(mockResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum))
                .thenReturn(1);
        when(mockResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum))
                .thenReturn(255);
        return mockResources;
    }

    private TypedArray createFloatTypedArray(float[] vals) {
        TypedArray mockArray = mock(TypedArray.class);
        when(mockArray.length()).thenAnswer(invocation -> {
            return vals.length;
        });
        when(mockArray.getFloat(anyInt(), anyFloat())).thenAnswer(invocation -> {
            final float def = (float) invocation.getArguments()[1];
            if (vals == null) {
                return def;
            }
            int idx = (int) invocation.getArguments()[0];
            if (idx >= 0 && idx < vals.length) {
                return vals[idx];
            } else {
                return def;
            }
        });
        return mockArray;
    }

}
