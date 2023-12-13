/*
 * Copyright (C) 2023 The RisingOS Android Project
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

package org.cherish.server;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AudioEffectsUtils {

    private final Context context;
    private final AudioManager audioManager;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private PresetReverb presetReverb;
    private Virtualizer virtualizer;
    private ScheduledExecutorService dynamicModeScheduler;

    private boolean isEQEnabled = false;
    private boolean isBassBoostEnabled = false;
    private boolean isReverbEnabled = false;
    private boolean isDynamicModeEnabled = false;

    public AudioEffectsUtils(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void initializeAudioEffects() {
        final int audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
        equalizer = new Equalizer(0, audioSessionId);
        bassBoost = new BassBoost(0, audioSessionId);
        presetReverb = new PresetReverb(0, audioSessionId);
        virtualizer = new Virtualizer(0, audioSessionId);
    }

    public void releaseAudioEffects() {
        if (equalizer != null) {
            equalizer.release();
        }
        if (bassBoost != null) {
            bassBoost.release();
        }
        if (presetReverb != null) {
            presetReverb.release();
        }
        if (virtualizer != null) {
            virtualizer.release();
        }
    }

    public void enableEqualizer(boolean enable) {
        if (equalizer != null) {
            equalizer.setEnabled(enable);
            isEQEnabled = enable;
        }
    }

    public void enableBassBoost(boolean enable) {
        if (bassBoost != null) {
            bassBoost.setEnabled(enable);
            isBassBoostEnabled = enable;
        }
    }

    public void setBassBoostStrength(short strength) {
        if (isBassBoostEnabled && bassBoost != null) {
            bassBoost.setStrength(strength);
        }
    }

    public void enableSoftReverbMode(boolean enable) {
        if (presetReverb != null) {
            presetReverb.setPreset(PresetReverb.PRESET_SMALLROOM);
            presetReverb.setEnabled(enable);
            isReverbEnabled = enable;
        }
    }

    public void enableDynamicMode(boolean enable) {
        isDynamicModeEnabled = enable;
        if (enable) {
            enableDynamicEqMode();
        } else {
            disableDynamicMode();
        }
    }

    private void disableDynamicMode() {
        if (dynamicModeScheduler != null && !dynamicModeScheduler.isShutdown()) {
            dynamicModeScheduler.shutdownNow();
        }
    }

    private void enableDynamicEqMode() {
        dynamicModeScheduler = Executors.newSingleThreadScheduledExecutor();
        dynamicModeScheduler.scheduleAtFixedRate(() -> {
            if (!isDynamicModeEnabled) {
                dynamicModeScheduler.shutdownNow();
                return;
            }
            int currentVolume = getCurrentVolumeLevel();
            applyDynamicFrequencyResponse(currentVolume);
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private int getCurrentVolumeLevel() {
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) /
               audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }


    // Dynamic Frequency Response
    private void applyDynamicFrequencyResponse(int volumeLevel) {
        final short minEQLevel = equalizer.getBandLevelRange()[0];
        final short maxEQLevel = equalizer.getBandLevelRange()[1];
        short numberOfBands = equalizer.getNumberOfBands();
        for (short i = 0; i < numberOfBands; i++) {
            short newLevel = calculateBandLevel(i, numberOfBands, minEQLevel, maxEQLevel, volumeLevel);
            short currentLevel = equalizer.getBandLevel(i);
            if (currentLevel != newLevel) {
                equalizer.setBandLevel(i, newLevel);
            }
        }
        applyDynamicBassBoost(volumeLevel);
        applyDynamicReverb(volumeLevel);
        applyDynamicSpatialAudio(volumeLevel);
    }

    private short calculateBandLevel(short band, short numberOfBands, short minEQLevel, short maxEQLevel, int volumeLevel) {
        double lowFrequencyAdjustment = 1.1; 
        double midHighFrequencyAdjustment = 1.2;
        double volumeAdjustment = 0.85 + (volumeLevel / 100.0 * 0.6);
        double adjustmentFactor = band < numberOfBands / 2 ? lowFrequencyAdjustment : midHighFrequencyAdjustment;
        return (short) Math.min(maxEQLevel, Math.max(minEQLevel,
                minEQLevel + (adjustmentFactor * volumeAdjustment * (maxEQLevel - minEQLevel))));
    }

    private short calculateOutputGain(int volumeLevel) {
        double gainFactor = 1.0 + (1.0 - volumeLevel / 100.0);
        short gain = (short) (gainFactor * (equalizer.getBandLevelRange()[1] - equalizer.getBandLevelRange()[0]) / 5);
        return (short) Math.min(gain, equalizer.getBandLevelRange()[1]);
    }

    private void applyDynamicBassBoost(int volumeLevel) {
        if (bassBoost != null) {
            short strength = (short) Math.max(0, 1000 - (volumeLevel * 5));
            bassBoost.setStrength(strength);
        }
    }

    private void applyDynamicReverb(int volumeLevel) {
        if (presetReverb != null) {
            short newPreset;
            if (volumeLevel < 33) {
                newPreset = PresetReverb.PRESET_SMALLROOM;
            } else if (volumeLevel < 66) {
                newPreset = PresetReverb.PRESET_MEDIUMROOM;
            } else {
                newPreset = PresetReverb.PRESET_LARGEROOM;
            }
            presetReverb.setPreset(newPreset);
        }
    }
    
    private void applyDynamicSpatialAudio(int volumeLevel) {
        virtualizer.setStrength((short) Math.min(1500, 800 + volumeLevel * 2));
    }
}
