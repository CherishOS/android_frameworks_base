/*
 * Copyright (C) 2024 The risingOS Android Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;

import com.android.internal.util.android.VibrationUtils;

public class VolumeUtils {
    private static final String TAG = "VolumeUtils";
    private static final int SOUND_HAPTICS_DELAY = 50;
    private static final int SOUND_HAPTICS_DURATION = 2000;

    private Ringtone mRingtone;
    private MediaPlayer mMediaPlayer = null;
    private AudioManager mAudioManager;
    private Context mContext;
    private Handler mHandler;

    public VolumeUtils(Context context, AudioManager audioManager) {
        mAudioManager = audioManager;
        mContext = context;
        mHandler = new Handler();
        mMediaPlayer = new MediaPlayer();
    }

    public void playSoundForStreamType(int streamType) {
        int vibrateIntensity = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.VOLUME_SLIDER_HAPTICS_INTENSITY, 1);
        Uri soundUri = null;
        switch (streamType) {
            case AudioManager.STREAM_RING:
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                break;
            case AudioManager.STREAM_NOTIFICATION:
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                break;
            case AudioManager.STREAM_ALARM:
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                break;
        }
        VibrationUtils.triggerVibration(mContext, vibrateIntensity);
        playSound(soundUri, streamType);
    }

    private void playSound(Uri soundUri, int streamType) {
        stopPlayback();
        if (mAudioManager == null || mAudioManager.isMusicActive()) {
            return;
        }
        try {
            if (soundUri != null && streamType == AudioManager.STREAM_RING) {
                mRingtone = RingtoneManager.getRingtone(mContext, soundUri);
                mRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setLegacyStreamType(streamType)
                        .build());
                mHandler.postDelayed(() -> startRingtone(), SOUND_HAPTICS_DELAY);
                mHandler.postDelayed(() -> stopPlayback(), SOUND_HAPTICS_DURATION);
            } else {
                if (soundUri == null) {
                    mMediaPlayer = MediaPlayer.create(mContext, R.raw.volume_control_sound);
                } else {
                    mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setLegacyStreamType(streamType)
                            .build());
                    mMediaPlayer.setDataSource(mContext, soundUri);
                    mMediaPlayer.prepare();
                }
                mHandler.postDelayed(() -> startMediaPlayer(), SOUND_HAPTICS_DELAY);
                mHandler.postDelayed(() -> stopPlayback(), SOUND_HAPTICS_DURATION);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not play sound: " + e.getMessage());
        }
    }
    
    private void startRingtone() {
        if (mRingtone != null) {
            mRingtone.play();
        }
    }
    
    private void startMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.seekTo(0);
            mMediaPlayer.start();
        }
    }

    private void stopPlayback() {
        if (mRingtone != null && mRingtone.isPlaying()) {
            mRingtone.stop();
            mRingtone = null;
        }
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
            mMediaPlayer.reset();
        }
    }
}
