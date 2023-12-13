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

import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import com.android.server.SystemService;

public final class AudioEffectService extends SystemService {

    private static final String TAG = "AudioEffectService";
    private static final String AUDIO_EFFECT_MODE = "audio_effect_mode";
    private static final String BASS_BOOST_STRENGTH = "bass_boost_strength";

    private final AudioEffectsUtils audioEffectsUtils;
    private final Context mContext;
    private final SettingsObserver mSettingsObserver;

    public AudioEffectService(Context context) {
        super(context);
        mContext = context;
        audioEffectsUtils = new AudioEffectsUtils(mContext);
        mSettingsObserver = new SettingsObserver(new Handler());
    }

    @Override
    public void onStart() {
        mSettingsObserver.observe();
        updateAudioEffectSettings();
    }

    private int getAudioEffectMode() {
        try {
            return Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    AUDIO_EFFECT_MODE, 0, ActivityManager.getCurrentUser());
        } catch (Exception e) {
            return 0;
        }
    }

    private int getBassBoostStrength() {
        try {
            return Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    BASS_BOOST_STRENGTH, 0, ActivityManager.getCurrentUser());
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateAudioEffectSettings() {
        disableAllEffects();
        int mode = getAudioEffectMode();
        if (mode != 0) {
            audioEffectsUtils.initializeAudioEffects();
        }
        switch (mode) {
            case 1:
                enableDynamicMode();
                break;
            case 2:
                enableBassBoost();
                break;
            case 3:
                enableSoftReverbMode();
                break;
            default:
                break;
        }
    }

    private void enableDynamicMode() {
        audioEffectsUtils.enableEqualizer(true);
        audioEffectsUtils.enableDynamicMode(true);
    }

    private void enableBassBoost() {
        audioEffectsUtils.enableEqualizer(true);
        audioEffectsUtils.enableBassBoost(true);
        audioEffectsUtils.setBassBoostStrength((short) (getBassBoostStrength() * 10));
    }

    private void enableSoftReverbMode() {
        audioEffectsUtils.enableEqualizer(true);
        audioEffectsUtils.enableSoftReverbMode(true);
    }

    private void disableAllEffects() {
        audioEffectsUtils.enableEqualizer(false);
        audioEffectsUtils.enableBassBoost(false);
        audioEffectsUtils.enableSoftReverbMode(false);
        audioEffectsUtils.releaseAudioEffects();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(AUDIO_EFFECT_MODE), false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(BASS_BOOST_STRENGTH), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {;
            updateAudioEffectSettings();
        }
    }
}

