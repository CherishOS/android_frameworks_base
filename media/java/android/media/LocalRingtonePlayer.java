/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.HapticGenerator;
import android.net.Uri;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Plays a ringtone on the local process.
 * @hide
 */
public class LocalRingtonePlayer
        implements Ringtone.RingtonePlayer, MediaPlayer.OnCompletionListener {
    private static final String TAG = "LocalRingtonePlayer";
    private static final int VIBRATION_LOOP_DELAY_MS = 200;

    // keep references on active Ringtones until stopped or completion listener called.
    private static final ArrayList<LocalRingtonePlayer> sActiveMediaPlayers = new ArrayList<>();

    private final MediaPlayer mMediaPlayer;
    private final AudioAttributes mAudioAttributes;
    private final VibrationAttributes mVibrationAttributes;
    private final Ringtone.Injectables mInjectables;
    private final AudioManager mAudioManager;
    private final VolumeShaper mVolumeShaper;
    private final Vibrator mVibrator;
    private final VibrationEffect mVibrationEffect;
    private HapticGenerator mHapticGenerator;
    private boolean mStartedVibration;

    private LocalRingtonePlayer(@NonNull MediaPlayer mediaPlayer,
            @NonNull AudioAttributes audioAttributes, @NonNull Ringtone.Injectables injectables,
            @NonNull AudioManager audioManager, @Nullable HapticGenerator hapticGenerator,
            @Nullable VolumeShaper volumeShaper, @NonNull Vibrator vibrator,
            @Nullable VibrationEffect vibrationEffect) {
        Objects.requireNonNull(mediaPlayer);
        Objects.requireNonNull(audioAttributes);
        Objects.requireNonNull(injectables);
        Objects.requireNonNull(audioManager);
        mMediaPlayer = mediaPlayer;
        mAudioAttributes = audioAttributes;
        mInjectables = injectables;
        mAudioManager = audioManager;
        mVolumeShaper = volumeShaper;
        mVibrator = vibrator;
        mVibrationEffect = vibrationEffect;
        mHapticGenerator = hapticGenerator;
        mVibrationAttributes = (mVibrationEffect == null) ? null :
                new VibrationAttributes.Builder(audioAttributes).build();
    }

    /**
     * Creates a {@link LocalRingtonePlayer} for a Uri, returning null if the Uri can't be
     * loaded in the local player.
     */
    @Nullable
    static LocalRingtonePlayer create(@NonNull Context context,
            @NonNull AudioManager audioManager, @NonNull Vibrator vibrator, @NonNull Uri soundUri,
            @NonNull AudioAttributes audioAttributes,
            @Nullable VibrationEffect vibrationEffect,
            @NonNull Ringtone.Injectables injectables,
            @Nullable VolumeShaper.Configuration volumeShaperConfig,
            @Nullable AudioDeviceInfo preferredDevice, boolean initialHapticGeneratorEnabled,
            boolean initialLooping, float initialVolume) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(soundUri);
        Objects.requireNonNull(audioAttributes);
        Trace.beginSection("createLocalMediaPlayer");
        MediaPlayer mediaPlayer = injectables.newMediaPlayer();
        HapticGenerator hapticGenerator = null;
        try {
            mediaPlayer.setDataSource(context, soundUri);
            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setPreferredDevice(preferredDevice);
            mediaPlayer.setLooping(initialLooping);
            mediaPlayer.setVolume(initialVolume);
            if (initialHapticGeneratorEnabled) {
                hapticGenerator = injectables.createHapticGenerator(mediaPlayer);
                if (hapticGenerator != null) {
                    // In practise, this should always be non-null because the initial value is
                    // not true unless it's available.
                    hapticGenerator.setEnabled(true);
                    vibrationEffect = null;  // Don't play the VibrationEffect.
                }
            }
            VolumeShaper volumeShaper = null;
            if (volumeShaperConfig != null) {
                volumeShaper = mediaPlayer.createVolumeShaper(volumeShaperConfig);
            }
            mediaPlayer.prepare();
            if (vibrationEffect != null && !audioAttributes.areHapticChannelsMuted()) {
                if (injectables.hasHapticChannels(mediaPlayer)) {
                    // Don't play the Vibration effect if the URI has haptic channels.
                    vibrationEffect = null;
                }
            }
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes, injectables, audioManager,
                    hapticGenerator, volumeShaper, vibrator, vibrationEffect);
        } catch (SecurityException | IOException e) {
            if (hapticGenerator != null) {
                hapticGenerator.release();
            }
            // volume shaper closes with media player
            mediaPlayer.release();
            return null;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Creates a {@link LocalRingtonePlayer} for an externally referenced file descriptor. This is
     * intended for loading a fallback from an internal resource, rather than via a Uri.
     */
    @Nullable
    static LocalRingtonePlayer createForFallback(
            @NonNull AudioManager audioManager, @NonNull Vibrator vibrator,
            @NonNull AssetFileDescriptor afd,
            @NonNull AudioAttributes audioAttributes,
            @Nullable VibrationEffect vibrationEffect,
            @NonNull Ringtone.Injectables injectables,
            @Nullable VolumeShaper.Configuration volumeShaperConfig,
            @Nullable AudioDeviceInfo preferredDevice,
            boolean initialLooping, float initialVolume) {
        // Haptic generator not supported for fallback.
        Objects.requireNonNull(audioManager);
        Objects.requireNonNull(afd);
        Objects.requireNonNull(audioAttributes);
        Trace.beginSection("createFallbackLocalMediaPlayer");

        MediaPlayer mediaPlayer = injectables.newMediaPlayer();
        try {
            if (afd.getDeclaredLength() < 0) {
                mediaPlayer.setDataSource(afd.getFileDescriptor());
            } else {
                mediaPlayer.setDataSource(afd.getFileDescriptor(),
                        afd.getStartOffset(),
                        afd.getDeclaredLength());
            }
            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setPreferredDevice(preferredDevice);
            mediaPlayer.setLooping(initialLooping);
            mediaPlayer.setVolume(initialVolume);
            VolumeShaper volumeShaper = null;
            if (volumeShaperConfig != null) {
                volumeShaper = mediaPlayer.createVolumeShaper(volumeShaperConfig);
            }
            mediaPlayer.prepare();
            if (vibrationEffect != null && !audioAttributes.areHapticChannelsMuted()) {
                if (injectables.hasHapticChannels(mediaPlayer)) {
                    // Don't play the Vibration effect if the URI has haptic channels.
                    vibrationEffect = null;
                }
            }
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes,  injectables, audioManager,
                    /* hapticGenerator= */ null, volumeShaper, vibrator, vibrationEffect);
        } catch (SecurityException | IOException e) {
            Log.e(TAG, "Failed to open fallback ringtone");
            // TODO: vibration-effect-only / no-sound LocalRingtonePlayer.
            mediaPlayer.release();
            return null;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public boolean play() {
        // Play ringtones if stream volume is over 0 or if it is a haptic-only ringtone
        // (typically because ringer mode is vibrate).
        if (mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(mAudioAttributes))
                == 0 && (mAudioAttributes.areHapticChannelsMuted() || !hasHapticChannels())) {
            maybeStartVibration();
            return true;  // Successfully played while muted.
        }
        synchronized (sActiveMediaPlayers) {
            // We keep-alive when a mediaplayer is active, since its finalizer would stop the
            // ringtone. This isn't necessary for vibrations in the vibrator service
            // (i.e. maybeStartVibration in the muted case, above).
            sActiveMediaPlayers.add(this);
        }

        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.start();
        if (mVolumeShaper != null) {
            mVolumeShaper.apply(VolumeShaper.Operation.PLAY);
        }
        maybeStartVibration();
        return true;
    }

    private void maybeStartVibration() {
        if (mVibrationEffect != null && !mStartedVibration) {
            boolean isLooping = mMediaPlayer.isLooping();
            try {
                // Adjust the vibration effect to loop.
                VibrationEffect loopAdjustedEffect = mVibrationEffect.applyRepeatingIndefinitely(
                        isLooping, VIBRATION_LOOP_DELAY_MS);
                mVibrator.vibrate(loopAdjustedEffect, mVibrationAttributes);
                mStartedVibration = true;
            } catch (Exception e) {
                // Catch exceptions widely, because we don't want to "leak" looping sounds or
                // vibrations if something goes wrong.
                Log.e(TAG, "Problem starting " + (isLooping ? "looping " : "") + "vibration "
                        + "for ringtone: " + mVibrationEffect, e);
            }
        }
    }

    private void stopVibration() {
        if (mVibrationEffect != null && mStartedVibration) {
            try {
                mVibrator.cancel(mVibrationAttributes.getUsage());
                mStartedVibration = false;
            } catch (Exception e) {
                // Catch exceptions widely, because we don't want to "leak" looping sounds or
                // vibrations if something goes wrong.
                Log.e(TAG, "Problem stopping vibration for ringtone", e);
            }
        }
    }

    @Override
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void stopAndRelease() {
        synchronized (sActiveMediaPlayers) {
            sActiveMediaPlayers.remove(this);
        }
        try {
            mMediaPlayer.stop();
        } finally {
            stopVibration();  // Won't throw: catches exceptions.
            if (mHapticGenerator != null) {
                mHapticGenerator.release();
            }
            mMediaPlayer.setOnCompletionListener(null);
            mMediaPlayer.reset();
            mMediaPlayer.release();
        }
    }

    @Override
    public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
        mMediaPlayer.setPreferredDevice(audioDeviceInfo);
    }

    @Override
    public void setLooping(boolean looping) {
        boolean wasLooping = mMediaPlayer.isLooping();
        if (wasLooping == looping) {
            return;
        }
        mMediaPlayer.setLooping(looping);
        // If transitioning from looping to not-looping during play, then cancel the vibration.
        if (mVibrationEffect != null && mMediaPlayer.isPlaying()) {
            if (wasLooping) {
                stopVibration();
            } else {
                // Won't restart the vibration to be looping if it was already started.
                maybeStartVibration();
            }
        }
    }

    @Override
    public void setHapticGeneratorEnabled(boolean enabled) {
        if (mVibrationEffect != null) {
            // Ignore haptic generator changes if a vibration effect is present. The decision to
            // use one or the other happens before this object is constructed.
            return;
        }
        if (enabled && mHapticGenerator == null) {
            mHapticGenerator = mInjectables.createHapticGenerator(mMediaPlayer);
        }
        if (mHapticGenerator != null) {
            mHapticGenerator.setEnabled(enabled);
        }
    }

    @Override
    public void setVolume(float volume) {
        mMediaPlayer.setVolume(volume);
    }

    @Override
    public boolean hasHapticChannels() {
        return mInjectables.hasHapticChannels(mMediaPlayer);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        synchronized (sActiveMediaPlayers) {
            sActiveMediaPlayers.remove(this);
        }
        mp.setOnCompletionListener(null); // Help the Java GC: break the refcount cycle.
    }
}
