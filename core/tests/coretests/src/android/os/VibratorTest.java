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

package android.os;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.vibrator.IVibrator;
import android.media.AudioAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for {@link Vibrator}.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:VibratorTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class VibratorTest {

    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    private Context mContextSpy;
    private Vibrator mVibratorSpy;

    @Before
    public void setUp() {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        mVibratorSpy = spy(new SystemVibrator(mContextSpy));
    }

    @Test
    public void getId_returnsDefaultId() {
        assertEquals(-1, mVibratorSpy.getId());
    }

    @Test
    public void areEffectsSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.areEffectsSupported(new int[0]).length);
        assertEquals(1,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK}).length);
        assertEquals(2,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK,
                        VibrationEffect.EFFECT_TICK}).length);
    }

    @Test
    public void areEffectsSupported_noVibrator_returnsAlwaysNo() {
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[0]);
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void areEffectsSupported_unsupportedInOneVibrator_returnsNo() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unsupportedVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(new int[0])
                .build();
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void areEffectsSupported_unknownInOneVibrator_returnsUnknown() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unknownSupportVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{supportedVibrator, unknownSupportVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void arePrimitivesSupported_supportedInAllVibrators_returnsYes() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void arePrimitivesSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.arePrimitivesSupported(new int[0]).length);
        assertEquals(1, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void arePrimitivesSupported_noVibrator_returnsAlwaysFalse() {
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[0]);
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void arePrimitivesSupported_unsupportedInOneVibrator_returnsFalse() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void arePrimitivesSupported_supportedInAllVibrators_returnsTrue() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 5)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 15)
                .build();
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.getPrimitiveDurations(new int[0]).length);
        assertEquals(1, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void getPrimitivesDurations_noVibrator_returnsAlwaysZero() {
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[0]);
        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_unsupportedInOneVibrator_returnsZero() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_supportedInAllVibrators_returnsMaxDuration() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .build();
        SystemVibrator.AllVibratorsInfo info = new SystemVibrator.AllVibratorsInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertEquals(20, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void vibrate_withVibrationAttributes_usesGivenAttributes() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationAttributes attributes = new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_TOUCH).build();

        mVibratorSpy.vibrate(effect, attributes);

        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), eq(attributes));
    }

    @Test
    public void vibrate_withAudioAttributes_createsVibrationAttributesWithSameUsage() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION).build();

        mVibratorSpy.vibrate(effect, audioAttributes);

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_COMMUNICATION_REQUEST,
                vibrationAttributes.getUsage());
        // Keeps original AudioAttributes usage to be used by the VibratorService.
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withoutAudioAttributes_passesOnDefaultAttributes() {
        mVibratorSpy.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), any(), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(new VibrationAttributes.Builder().build(), vibrationAttributes);
    }
}
