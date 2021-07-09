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

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import com.android.server.vibrator.VibratorController.OnVibrationCompleteListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides {@link VibratorController} with controlled vibrator hardware capabilities and
 * interactions.
 */
final class FakeVibratorControllerProvider {
    private static final int EFFECT_DURATION = 20;

    private final Map<Long, PrebakedSegment> mEnabledAlwaysOnEffects = new HashMap<>();
    private final List<VibrationEffectSegment> mEffectSegments = new ArrayList<>();
    private final List<Integer> mBraking = new ArrayList<>();
    private final List<Float> mAmplitudes = new ArrayList<>();
    private final List<Boolean> mExternalControlStates = new ArrayList<>();
    private final Handler mHandler;
    private final FakeNativeWrapper mNativeWrapper;

    private boolean mIsAvailable = true;
    private long mLatency;

    private int mCapabilities;
    private int[] mSupportedEffects;
    private int[] mSupportedBraking;
    private int[] mSupportedPrimitives;
    private int mCompositionSizeMax;
    private int mPwleSizeMax;
    private float mMinFrequency = Float.NaN;
    private float mResonantFrequency = Float.NaN;
    private float mFrequencyResolution = Float.NaN;
    private float mQFactor = Float.NaN;
    private float[] mMaxAmplitudes;

    private final class FakeNativeWrapper extends VibratorController.NativeWrapper {
        public int vibratorId;
        public OnVibrationCompleteListener listener;
        public boolean isInitialized;

        @Override
        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            isInitialized = true;
            this.vibratorId = vibratorId;
            this.listener = listener;
        }

        @Override
        public boolean isAvailable() {
            return mIsAvailable;
        }

        @Override
        public long on(long milliseconds, long vibrationId) {
            mEffectSegments.add(new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE,
                    /* frequency= */ 0, (int) milliseconds));
            applyLatency();
            scheduleListener(milliseconds, vibrationId);
            return milliseconds;
        }

        @Override
        public void off() {
        }

        @Override
        public void setAmplitude(float amplitude) {
            mAmplitudes.add(amplitude);
            applyLatency();
        }

        @Override
        public long perform(long effect, long strength, long vibrationId) {
            if (mSupportedEffects == null
                    || Arrays.binarySearch(mSupportedEffects, (int) effect) < 0) {
                return 0;
            }
            mEffectSegments.add(new PrebakedSegment((int) effect, false, (int) strength));
            applyLatency();
            scheduleListener(EFFECT_DURATION, vibrationId);
            return EFFECT_DURATION;
        }

        @Override
        public long compose(PrimitiveSegment[] effects, long vibrationId) {
            long duration = 0;
            for (PrimitiveSegment primitive : effects) {
                duration += EFFECT_DURATION + primitive.getDelay();
                mEffectSegments.add(primitive);
            }
            applyLatency();
            scheduleListener(duration, vibrationId);
            return duration;
        }

        @Override
        public long composePwle(RampSegment[] primitives, int braking, long vibrationId) {
            long duration = 0;
            for (RampSegment primitive : primitives) {
                duration += primitive.getDuration();
                mEffectSegments.add(primitive);
            }
            mBraking.add(braking);
            applyLatency();
            scheduleListener(duration, vibrationId);
            return duration;
        }

        @Override
        public void setExternalControl(boolean enabled) {
            mExternalControlStates.add(enabled);
        }

        @Override
        public void alwaysOnEnable(long id, long effect, long strength) {
            PrebakedSegment prebaked = new PrebakedSegment((int) effect, false, (int) strength);
            mEnabledAlwaysOnEffects.put(id, prebaked);
        }

        @Override
        public void alwaysOnDisable(long id) {
            mEnabledAlwaysOnEffects.remove(id);
        }

        @Override
        public boolean getInfo(float suggestedFrequencyRange, VibratorInfo.Builder infoBuilder) {
            infoBuilder.setCapabilities(mCapabilities);
            infoBuilder.setSupportedBraking(mSupportedBraking);
            infoBuilder.setPwleSizeMax(mPwleSizeMax);
            infoBuilder.setSupportedEffects(mSupportedEffects);
            if (mSupportedPrimitives != null) {
                for (int primitive : mSupportedPrimitives) {
                    infoBuilder.setSupportedPrimitive(primitive, EFFECT_DURATION);
                }
            }
            infoBuilder.setCompositionSizeMax(mCompositionSizeMax);
            infoBuilder.setQFactor(mQFactor);
            infoBuilder.setFrequencyMapping(new VibratorInfo.FrequencyMapping(mMinFrequency,
                    mResonantFrequency, mFrequencyResolution, suggestedFrequencyRange,
                    mMaxAmplitudes));
            return true;
        }

        private void applyLatency() {
            try {
                if (mLatency > 0) {
                    Thread.sleep(mLatency);
                }
            } catch (InterruptedException e) {
            }
        }

        private void scheduleListener(long vibrationDuration, long vibrationId) {
            mHandler.postDelayed(() -> listener.onComplete(vibratorId, vibrationId),
                    vibrationDuration);
        }
    }

    public FakeVibratorControllerProvider(Looper looper) {
        mHandler = new Handler(looper);
        mNativeWrapper = new FakeNativeWrapper();
    }

    public VibratorController newVibratorController(
            int vibratorId, OnVibrationCompleteListener listener) {
        return new VibratorController(vibratorId, listener, mNativeWrapper);
    }

    /** Return {@code true} if this controller was initialized. */
    public boolean isInitialized() {
        return mNativeWrapper.isInitialized;
    }

    /**
     * Disable fake vibrator hardware, mocking a state where the underlying service is unavailable.
     */
    public void disableVibrators() {
        mIsAvailable = false;
    }

    /**
     * Sets the latency this controller should fake for turning the vibrator hardware on or setting
     * it's vibration amplitude.
     */
    public void setLatency(long millis) {
        mLatency = millis;
    }

    /** Set the capabilities of the fake vibrator hardware. */
    public void setCapabilities(int... capabilities) {
        mCapabilities = Arrays.stream(capabilities).reduce(0, (a, b) -> a | b);
    }

    /** Set the effects supported by the fake vibrator hardware. */
    public void setSupportedEffects(int... effects) {
        if (effects != null) {
            effects = Arrays.copyOf(effects, effects.length);
            Arrays.sort(effects);
        }
        mSupportedEffects = effects;
    }

    /** Set the effects supported by the fake vibrator hardware. */
    public void setSupportedBraking(int... braking) {
        if (braking != null) {
            braking = Arrays.copyOf(braking, braking.length);
            Arrays.sort(braking);
        }
        mSupportedBraking = braking;
    }

    /** Set the primitives supported by the fake vibrator hardware. */
    public void setSupportedPrimitives(int... primitives) {
        if (primitives != null) {
            primitives = Arrays.copyOf(primitives, primitives.length);
            Arrays.sort(primitives);
        }
        mSupportedPrimitives = primitives;
    }

    /** Set the max number of primitives allowed in a composition by the fake vibrator hardware. */
    public void setCompositionSizeMax(int compositionSizeMax) {
        mCompositionSizeMax = compositionSizeMax;
    }

    /** Set the max number of PWLEs allowed in a composition by the fake vibrator hardware. */
    public void setPwleSizeMax(int pwleSizeMax) {
        mPwleSizeMax = pwleSizeMax;
    }

    /** Set the resonant frequency of the fake vibrator hardware. */
    public void setResonantFrequency(float frequencyHz) {
        mResonantFrequency = frequencyHz;
    }

    /** Set the minimum frequency of the fake vibrator hardware. */
    public void setMinFrequency(float frequencyHz) {
        mMinFrequency = frequencyHz;
    }

    /** Set the frequency resolution of the fake vibrator hardware. */
    public void setFrequencyResolution(float frequencyHz) {
        mFrequencyResolution = frequencyHz;
    }

    /** Set the Q factor of the fake vibrator hardware. */
    public void setQFactor(float qFactor) {
        mQFactor = qFactor;
    }

    /** Set the max amplitude supported for each frequency f the fake vibrator hardware. */
    public void setMaxAmplitudes(float... maxAmplitudes) {
        mMaxAmplitudes = maxAmplitudes;
    }

    /**
     * Return the amplitudes set by this controller, including zeroes for each time the vibrator was
     * turned off.
     */
    public List<Float> getAmplitudes() {
        return new ArrayList<>(mAmplitudes);
    }

    /** Return the braking values passed to the compose PWLE method. */
    public List<Integer> getBraking() {
        return mBraking;
    }

    /** Return list of {@link VibrationEffectSegment} played by this controller, in order. */
    public List<VibrationEffectSegment> getEffectSegments() {
        return new ArrayList<>(mEffectSegments);
    }

    /** Return list of states set for external control to the fake vibrator hardware. */
    public List<Boolean> getExternalControlStates() {
        return mExternalControlStates;
    }

    /**
     * Return the {@link PrebakedSegment} effect enabled with given id, or {@code null} if
     * missing or disabled.
     */
    @Nullable
    public PrebakedSegment getAlwaysOnEffect(int id) {
        return mEnabledAlwaysOnEffects.get((long) id);
    }
}
