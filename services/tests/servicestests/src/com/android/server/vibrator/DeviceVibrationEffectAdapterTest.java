/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.hardware.vibrator.IVibrator;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Tests for {@link DeviceVibrationEffectAdapter}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:DeviceVibrationEffectAdapterTest
 */
@Presubmit
public class DeviceVibrationEffectAdapterTest {
    private static final float TEST_MIN_FREQUENCY = 50;
    private static final float TEST_RESONANT_FREQUENCY = 150;
    private static final float TEST_FREQUENCY_RESOLUTION = 25;
    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f, /* 200Hz= */ 0.8f};

    private static final VibratorInfo.FrequencyMapping EMPTY_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(Float.NaN, Float.NaN, Float.NaN, Float.NaN, null);
    private static final VibratorInfo.FrequencyMapping TEST_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(TEST_MIN_FREQUENCY,
                    TEST_RESONANT_FREQUENCY, TEST_FREQUENCY_RESOLUTION,
                    /* suggestedSafeRangeHz= */ 50, TEST_AMPLITUDE_MAP);

    private DeviceVibrationEffectAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        VibrationSettings vibrationSettings = new VibrationSettings(
                InstrumentationRegistry.getContext(), new Handler(new TestLooper().getLooper()));
        mAdapter = new DeviceVibrationEffectAdapter(vibrationSettings);
    }

    @Test
    public void testPrebakedAndPrimitiveSegments_returnsOriginalSegment() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                new PrebakedSegment(
                        VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_STRONG),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.5f, 100)),
                /* repeatIndex= */ -1);

        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(EMPTY_FREQUENCY_MAPPING)));
        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(TEST_FREQUENCY_MAPPING)));
    }

    @Test
    public void testStepAndRampSegments_withoutPwleCapability_convertsRampsToSteps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequency= */ 1, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0.2f,
                        /* startFrequency= */ -4, /* endFrequency= */ 2, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequency= */ 0, /* endFrequency= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.65f, /* endAmplitude= */ 0.65f,
                        /* startFrequency= */ 0, /* endFrequency= */ 1, /* duration= */ 1000)),
                /* repeatIndex= */ 3);

        VibrationEffect.Composed adaptedEffect = (VibrationEffect.Composed) mAdapter.apply(effect,
                createVibratorInfo(EMPTY_FREQUENCY_MAPPING));
        assertTrue(adaptedEffect.getSegments().size() > effect.getSegments().size());
        assertTrue(adaptedEffect.getRepeatIndex() >= effect.getRepeatIndex());

        for (VibrationEffectSegment adaptedSegment : adaptedEffect.getSegments()) {
            assertTrue(adaptedSegment instanceof StepSegment);
        }
    }

    @Test
    public void testStepAndRampSegments_withPwleCapability_convertsStepsToRamps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequency= */ 1, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequency= */ -4, /* endFrequency= */ 2, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequency= */ 10, /* endFrequency= */ -5, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequency= */ 175, /* endFrequency= */ 175, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ 150, /* endFrequency= */ 150, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.1f, /* endAmplitude= */ 0.8f,
                        /* startFrequency= */ 50, /* endFrequency= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.1f,
                        /* startFrequency= */ 200, /* endFrequency= */ 50, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(TEST_FREQUENCY_MAPPING,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    @Test
    public void testStepAndRampSegments_withEmptyFreqMapping_returnsSameAmplitudesAndZeroFreq() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequency= */ 1, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequency= */ -1, /* endFrequency= */ 1, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ 10, /* endFrequency= */ -5, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(EMPTY_FREQUENCY_MAPPING,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    @Test
    public void testStepAndRampSegments_withValidFreqMapping_returnsClippedValues() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 1, /* frequency= */ -1, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequency= */ -4, /* endFrequency= */ 2, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequency= */ 10, /* endFrequency= */ -5, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ 150, /* endFrequency= */ 150,
                        /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.8f,
                        /* startFrequency= */ 125, /* endFrequency= */ 125,
                        /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.1f, /* endAmplitude= */ 0.8f,
                        /* startFrequency= */ 50, /* endFrequency= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.1f,
                        /* startFrequency= */ 200, /* endFrequency= */ 50, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(TEST_FREQUENCY_MAPPING,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    private static VibratorInfo createVibratorInfo(VibratorInfo.FrequencyMapping frequencyMapping,
            int... capabilities) {
        int cap = IntStream.of(capabilities).reduce((a, b) -> a | b).orElse(0);
        return new VibratorInfo.Builder(0)
                .setCapabilities(cap)
                .setFrequencyMapping(frequencyMapping)
                .build();
    }
}
