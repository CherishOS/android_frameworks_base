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

package com.android.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

/**
 * Tests for {@link VibratorService}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorServiceTest
 */
@Presubmit
public class VibratorServiceTest {

    private static final int UID = Process.ROOT_UID;
    private static final String PACKAGE_NAME = "package";
    private static final VibrationAttributes ALARM_ATTRS =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build();
    private static final VibrationAttributes HAPTIC_FEEDBACK_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_TOUCH).build();
    private static final VibrationAttributes NOTIFICATION_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_NOTIFICATION).build();
    private static final VibrationAttributes RINGTONE_ATTRS =
            new VibrationAttributes.Builder().setUsage(
                    VibrationAttributes.USAGE_RINGTONE).build();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PowerSaveState mPowerSaveStateMock;
    @Mock private Vibrator mVibratorMock;
    @Mock private VibratorService.NativeWrapper mNativeWrapperMock;
    @Mock private IVibratorStateListener mVibratorStateListenerMock;
    @Mock private IBinder mVibratorStateListenerBinderMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        MockContentResolver contentResolver = new MockContentResolver(mContextSpy);
        contentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());

        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mVibratorMock);
        when(mVibratorMock.getDefaultHapticFeedbackIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorMock.getDefaultRingVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibratorStateListenerMock.asBinder()).thenReturn(mVibratorStateListenerBinderMock);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        when(mPowerManagerInternalMock.getLowPowerState(PowerManager.ServiceType.VIBRATION))
                .thenReturn(mPowerSaveStateMock);

        addLocalServiceMock(PackageManagerInternal.class, mPackageManagerInternalMock);
        addLocalServiceMock(PowerManagerInternal.class, mPowerManagerInternalMock);
        FakeSettingsProvider.clearSettingsProvider();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        FakeSettingsProvider.clearSettingsProvider();
    }

    private VibratorService createService() {
        return new VibratorService(mContextSpy,
                new VibratorService.Injector() {
                    @Override
                    VibratorService.NativeWrapper getNativeWrapper() {
                        return mNativeWrapperMock;
                    }

                    @Override
                    Handler createHandler(Looper looper) {
                        return new Handler(mTestLooper.getLooper());
                    }

                    @Override
                    void addService(String name, IBinder service) {
                        // ignore
                    }
                });
    }

    @Test
    public void createService_initializesNativeService() {
        createService();
        verify(mNativeWrapperMock).vibratorInit();
        verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void hasVibrator_withVibratorHalPresent_returnsTrue() {
        when(mNativeWrapperMock.vibratorExists()).thenReturn(true);
        assertTrue(createService().hasVibrator());
    }

    @Test
    public void hasVibrator_withNoVibratorHalPresent_returnsFalse() {
        when(mNativeWrapperMock.vibratorExists()).thenReturn(false);
        assertFalse(createService().hasVibrator());
    }

    @Test
    public void hasAmplitudeControl_withAmplitudeControlSupport_returnsTrue() {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        assertTrue(createService().hasAmplitudeControl());
    }

    @Test
    public void hasAmplitudeControl_withNoAmplitudeControlSupport_returnsFalse() {
        assertFalse(createService().hasAmplitudeControl());
    }

    @Test
    public void areEffectsSupported_withNullResultFromNative_returnsSupportUnknown() {
        when(mNativeWrapperMock.vibratorGetSupportedEffects()).thenReturn(null);
        assertArrayEquals(new int[]{Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN},
                createService().areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK}));
    }

    @Test
    public void areEffectsSupported_withSomeEffectsSupported_returnsSupportYesAndNoForEffects() {
        int[] effects = new int[]{VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK};

        when(mNativeWrapperMock.vibratorGetSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        assertArrayEquals(
                new int[]{Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                        Vibrator.VIBRATION_EFFECT_SUPPORT_NO},
                createService().areEffectsSupported(effects));
    }

    @Test
    public void arePrimitivesSupported_withoutComposeCapability_returnsAlwaysFalse() {
        assertArrayEquals(new boolean[]{false, false},
                createService().arePrimitivesSupported(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_TICK
                }));
    }

    @Test
    public void arePrimitivesSupported_withNullResultFromNative_returnsAlwaysFalse() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mNativeWrapperMock.vibratorGetSupportedPrimitives()).thenReturn(null);

        assertArrayEquals(new boolean[]{false, false},
                createService().arePrimitivesSupported(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                }));
    }

    @Test
    public void arePrimitivesSupported_withSomeSupportedPrimitives_returnsBasedOnNativeResult() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mNativeWrapperMock.vibratorGetSupportedPrimitives())
                .thenReturn(new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK});

        assertArrayEquals(new boolean[]{true, false},
                createService().arePrimitivesSupported(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                }));
    }

    @Test
    public void setAlwaysOnEffect_withCapabilityAndValidEffect_enablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS));
        verify(mNativeWrapperMock).vibratorAlwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    public void setAlwaysOnEffect_withNonPrebakedEffect_ignoresEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.createOneShot(100, 255), ALARM_ATTRS));
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnDisable(anyLong());
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void setAlwaysOnEffect_withNullEffect_disablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);

        assertTrue(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1, null, ALARM_ATTRS));
        verify(mNativeWrapperMock).vibratorAlwaysOnDisable(eq(1L));
    }

    @Test
    public void setAlwaysOnEffect_withoutCapability_ignoresEffect() {
        assertFalse(createService().setAlwaysOnEffect(UID, PACKAGE_NAME, 1,
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK), ALARM_ATTRS));
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnDisable(anyLong());
        verify(mNativeWrapperMock, never()).vibratorAlwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void vibrate_withOneShotAndAmplitudeControl_turnsVibratorOnAndSetsAmplitude() {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorOn(eq(100L), any(VibratorService.Vibration.class));
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(128));
    }

    @Test
    public void vibrate_withOneShotAndNoAmplitudeControl_turnsVibratorOnAndIgnoresAmplitude() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, 128));
        assertTrue(service.isVibrating());

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorOn(eq(100L), any(VibratorService.Vibration.class));
        verify(mNativeWrapperMock, never()).vibratorSetAmplitude(anyInt());
    }

    @Test
    public void vibrate_withPrebaked_performsEffect() {
        when(mNativeWrapperMock.vibratorGetSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG),
                any(VibratorService.Vibration.class));
    }

    @Test
    public void vibrate_withComposed_performsEffect() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .compose();
        vibrate(service, effect);

        ArgumentCaptor<VibrationEffect.Composition.PrimitiveEffect[]> primitivesCaptor =
                ArgumentCaptor.forClass(VibrationEffect.Composition.PrimitiveEffect[].class);

        verify(mNativeWrapperMock).vibratorOff();
        verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                primitivesCaptor.capture(), any(VibratorService.Vibration.class));

        // Check all primitive effect fields are passed down to the HAL.
        assertEquals(1, primitivesCaptor.getValue().length);
        VibrationEffect.Composition.PrimitiveEffect primitive = primitivesCaptor.getValue()[0];
        assertEquals(VibrationEffect.Composition.PRIMITIVE_CLICK, primitive.id);
        assertEquals(0.5f, primitive.scale, /* delta= */ 1e-2);
        assertEquals(10, primitive.delay);
    }

    @Test
    public void vibrate_withWaveform_controlsVibratorAmplitudeDuringTotalVibrationTime()
            throws Exception {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{10, 10, 10}, new int[]{100, 200, 50}, -1);
        vibrate(service, effect);

        verify(mNativeWrapperMock).vibratorOff();

        // Wait for VibrateThread to turn vibrator ON with total timing and no callback.
        Thread.sleep(5);
        verify(mNativeWrapperMock).vibratorOn(eq(30L), isNull());

        // First amplitude set right away.
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(100));

        // Second amplitude set after first timing is finished.
        Thread.sleep(10);
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(200));

        // Third amplitude set after second timing is finished.
        Thread.sleep(10);
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(50));
    }

    @Test
    public void vibrate_withOneShotAndNativeCallbackTriggered_finishesVibration() {
        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).onComplete();
            return null;
        }).when(mNativeWrapperMock).vibratorOn(anyLong(), any(VibratorService.Vibration.class));
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOn(eq(100L),
                any(VibratorService.Vibration.class));
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void vibrate_withPrebakedAndNativeCallbackTriggered_finishesVibration() {
        when(mNativeWrapperMock.vibratorGetSupportedEffects())
                .thenReturn(new int[]{VibrationEffect.EFFECT_CLICK});
        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(2)).onComplete();
            return 10_000L; // 10s
        }).when(mNativeWrapperMock).vibratorPerformEffect(
                anyLong(), anyLong(), any(VibratorService.Vibration.class));
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
        inOrderVerifier.verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG),
                any(VibratorService.Vibration.class));
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void vibrate_withWaveformAndNativeCallback_callbackCannotBeTriggeredByNative()
            throws Exception {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{1, 3, 1, 2}, -1);
        vibrate(service, effect);

        // Wait for VibrateThread to finish: 1ms OFF, 3ms ON, 1ms OFF, 2ms ON.
        Thread.sleep(15);
        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOn(eq(3L), isNull());
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOn(eq(2L), isNull());
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void vibrate_withComposedAndNativeCallbackTriggered_finishesVibration() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).onComplete();
            return null;
        }).when(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(), any(VibratorService.Vibration.class));
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                .compose();
        vibrate(service, effect);

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
        inOrderVerifier.verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(VibrationEffect.Composition.PrimitiveEffect[].class),
                any(VibratorService.Vibration.class));
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void vibrate_whenBinderDies_cancelsVibration() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).binderDied();
            return null;
        }).when(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(), any(VibratorService.Vibration.class));
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10)
                .compose();
        vibrate(service, effect);

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
        inOrderVerifier.verify(mNativeWrapperMock).vibratorPerformComposedEffect(
                any(VibrationEffect.Composition.PrimitiveEffect[].class),
                any(VibratorService.Vibration.class));
        inOrderVerifier.verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void cancelVibrate_withDeviceVibrating_callsVibratorOff() {
        VibratorService service = createService();
        vibrate(service, VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
        assertTrue(service.isVibrating());
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock).vibratorOff();
    }

    @Test
    public void cancelVibrate_withDeviceNotVibrating_ignoresCall() {
        VibratorService service = createService();
        Mockito.clearInvocations(mNativeWrapperMock);

        service.cancelVibrate(service);
        assertFalse(service.isVibrating());
        verify(mNativeWrapperMock, never()).vibratorOff();
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        doAnswer(invocation -> {
            ((VibratorService.Vibration) invocation.getArgument(1)).onComplete();
            return null;
        }).when(mNativeWrapperMock).vibratorOn(anyLong(), any(VibratorService.Vibration.class));
        VibratorService service = createService();

        service.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);
        Mockito.clearInvocations(mVibratorStateListenerMock);

        vibrate(service, VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
        InOrder inOrderVerifier = inOrder(mVibratorStateListenerMock);
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
    }

    @Test
    public void unregisterVibratorStateListener_callbackNotTriggeredAfter() throws Exception {
        VibratorService service = createService();

        service.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);

        vibrate(service, VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE));
        verify(mVibratorStateListenerMock).onVibrating(true);

        service.unregisterVibratorStateListener(mVibratorStateListenerMock);
        Mockito.clearInvocations(mVibratorStateListenerMock);

        vibrate(service, VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
        verifyNoMoreInteractions(mVibratorStateListenerMock);
    }

    @Test
    public void scale_withPrebaked_userIntensitySettingAsEffectStrength() {
        // Alarm vibration is always VIBRATION_INTENSITY_HIGH.
        setVibrationIntensityUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setVibrationIntensityUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setVibrationIntensityUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        VibratorService service = createService();
        service.systemReady();

        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                ALARM_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                NOTIFICATION_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
                HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
                RINGTONE_ATTRS);

        verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_STRONG), any());
        verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_TICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_MEDIUM), any());
        verify(mNativeWrapperMock).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_DOUBLE_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_LIGHT), any());
        verify(mNativeWrapperMock, never()).vibratorPerformEffect(
                eq((long) VibrationEffect.EFFECT_HEAVY_CLICK), anyLong(), any());
    }

    @Test
    public void scale_withOneShotAndWaveform_usesScaleLevelOnAmplitude() throws Exception {
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_LOW);
        setVibrationIntensityUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setVibrationIntensityUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setVibrationIntensityUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        VibratorService service = createService();
        service.systemReady();

        vibrate(service, VibrationEffect.createOneShot(20, 100), ALARM_ATTRS);
        vibrate(service, VibrationEffect.createOneShot(20, 100), NOTIFICATION_ATTRS);
        vibrate(service, VibrationEffect.createOneShot(20, 255), RINGTONE_ATTRS);
        vibrate(service, VibrationEffect.createWaveform(new long[] { 10 }, new int[] { 100 }, -1),
                HAPTIC_FEEDBACK_ATTRS);

        // Waveform effect runs on a separate thread.
        Thread.sleep(5);

        // Alarm vibration is never scaled.
        verify(mNativeWrapperMock).vibratorSetAmplitude(eq(100));
        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        verify(mNativeWrapperMock).vibratorSetAmplitude(intThat(amplitude -> amplitude > 150));
        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        verify(mNativeWrapperMock).vibratorSetAmplitude(
                intThat(amplitude -> amplitude < 100 && amplitude > 50));
        // Ringtone vibration is off.
        verify(mNativeWrapperMock, never()).vibratorSetAmplitude(eq(255));
    }

    @Test
    public void scale_withComposed_usesScaleLevelOnPrimitiveScaleValues() {
        when(mVibratorMock.getDefaultNotificationVibrationIntensity())
                .thenReturn(Vibrator.VIBRATION_INTENSITY_LOW);
        setVibrationIntensityUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        setVibrationIntensityUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        setVibrationIntensityUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);

        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        VibratorService service = createService();
        service.systemReady();

        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose();
        ArgumentCaptor<VibrationEffect.Composition.PrimitiveEffect[]> primitivesCaptor =
                ArgumentCaptor.forClass(VibrationEffect.Composition.PrimitiveEffect[].class);

        vibrate(service, effect, ALARM_ATTRS);
        vibrate(service, effect, NOTIFICATION_ATTRS);
        vibrate(service, effect, HAPTIC_FEEDBACK_ATTRS);
        vibrate(service, effect, RINGTONE_ATTRS);

        // Ringtone vibration is off, so only the other 3 are propagated to native.
        verify(mNativeWrapperMock, times(3)).vibratorPerformComposedEffect(
                primitivesCaptor.capture(), any());

        List<VibrationEffect.Composition.PrimitiveEffect[]> values =
                primitivesCaptor.getAllValues();

        // Alarm vibration is never scaled.
        assertEquals(1f, values.get(0)[0].scale, /* delta= */ 1e-2);
        assertEquals(0.5f, values.get(0)[1].scale, /* delta= */ 1e-2);

        // Notification vibrations will be scaled with SCALE_VERY_HIGH.
        assertEquals(1f, values.get(1)[0].scale, /* delta= */ 1e-2);
        assertTrue(0.7 < values.get(1)[1].scale);

        // Haptic feedback vibrations will be scaled with SCALE_LOW.
        assertTrue(0.5 < values.get(2)[0].scale);
        assertTrue(0.5 > values.get(2)[1].scale);
    }

    private void vibrate(VibratorService service, VibrationEffect effect) {
        vibrate(service, effect, ALARM_ATTRS);
    }

    private void vibrate(VibratorService service, VibrationEffect effect,
            VibrationAttributes attributes) {
        service.vibrate(UID, PACKAGE_NAME, effect, attributes, "some reason", service);
    }

    private void mockVibratorCapabilities(int capabilities) {
        when(mNativeWrapperMock.vibratorGetCapabilities()).thenReturn((long) capabilities);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void setVibrationIntensityUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
    }
}
