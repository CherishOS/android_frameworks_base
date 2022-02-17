/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.companion.virtual.audio.UserRestrictionsDetector.UserRestrictionsCallback;
import android.companion.virtual.audio.VirtualAudioDevice.AudioConfigurationChangeCallback;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.util.IntArray;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages an ongiong audio session in which audio can be captured (recorded) and/or
 * injected from a remote device.
 *
 * @hide
 */
@VisibleForTesting
public final class VirtualAudioSession extends IAudioSessionCallback.Stub implements
        UserRestrictionsCallback, Closeable {
    private static final String TAG = "VirtualAudioSession";

    private final Context mContext;
    private final UserRestrictionsDetector mUserRestrictionsDetector;
    /** The {@link Executor} for sending {@link AudioConfigurationChangeCallback} to the caller */
    private final Executor mExecutor;
    @Nullable
    private final AudioConfigurationChangeCallback mCallback;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final IntArray mReroutedAppUids = new IntArray();
    @Nullable
    @GuardedBy("mLock")
    private AudioPolicy mAudioPolicy;
    @Nullable
    @GuardedBy("mLock")
    private AudioFormat mCaptureFormat;
    @Nullable
    @GuardedBy("mLock")
    private AudioFormat mInjectionFormat;
    @Nullable
    @GuardedBy("mLock")
    private AudioCapture mAudioCapture;
    @Nullable
    @GuardedBy("mLock")
    private AudioInjection mAudioInjection;

    @VisibleForTesting
    public VirtualAudioSession(Context context,
            @Nullable AudioConfigurationChangeCallback callback, @Nullable Executor executor) {
        mContext = context;
        mUserRestrictionsDetector = new UserRestrictionsDetector(context);
        mCallback = callback;
        mExecutor = executor != null ? executor : context.getMainExecutor();
    }

    /**
     * Begins recording audio emanating from this device.
     *
     * @return An {@link AudioCapture} containing the recorded audio.
     */
    @VisibleForTesting
    @NonNull
    public AudioCapture startAudioCapture(@NonNull AudioFormat captureFormat) {
        Objects.requireNonNull(captureFormat, "captureFormat must not be null");

        synchronized (mLock) {
            if (mAudioCapture != null) {
                throw new IllegalStateException(
                        "Cannot start capture while another capture is ongoing.");
            }

            mCaptureFormat = captureFormat;
            mAudioCapture = new AudioCapture();
            mAudioCapture.startRecording();
            return mAudioCapture;
        }
    }

    /**
     * Begins injecting audio from a remote device into this device.
     *
     * @return An {@link AudioInjection} containing the injected audio.
     */
    @VisibleForTesting
    @NonNull
    public AudioInjection startAudioInjection(@NonNull AudioFormat injectionFormat) {
        Objects.requireNonNull(injectionFormat, "injectionFormat must not be null");

        synchronized (mLock) {
            if (mAudioInjection != null) {
                throw new IllegalStateException(
                        "Cannot start injection while injection is already ongoing.");
            }

            mInjectionFormat = injectionFormat;
            mAudioInjection = new AudioInjection();
            mAudioInjection.play();

            mUserRestrictionsDetector.register(/* callback= */ this);
            mAudioInjection.setSilent(mUserRestrictionsDetector.isUnmuteMicrophoneDisallowed());
            return mAudioInjection;
        }
    }

    /** @hide */
    @VisibleForTesting
    @Nullable
    public AudioCapture getAudioCapture() {
        synchronized (mLock) {
            return mAudioCapture;
        }
    }

    /** @hide */
    @VisibleForTesting
    @Nullable
    public AudioInjection getAudioInjection() {
        synchronized (mLock) {
            return mAudioInjection;
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Override
    public void onAppsNeedingAudioRoutingChanged(int[] appUids) {
        synchronized (mLock) {
            if (Arrays.equals(mReroutedAppUids.toArray(), appUids)) {
                return;
            }
        }

        releaseAudioStreams();

        if (appUids.length == 0) {
            return;
        }

        createAudioStreams(appUids);
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Override
    public void close() {
        mUserRestrictionsDetector.unregister();
        releaseAudioStreams();
        synchronized (mLock) {
            mAudioCapture = null;
            mAudioInjection = null;
            mCaptureFormat = null;
            mInjectionFormat = null;
        }
    }

    @Override
    public void onMicrophoneRestrictionChanged(boolean isUnmuteMicDisallowed) {
        synchronized (mLock) {
            if (mAudioInjection != null) {
                mAudioInjection.setSilent(isUnmuteMicDisallowed);
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    private void createAudioStreams(int[] appUids) {
        synchronized (mLock) {
            if (mCaptureFormat == null && mInjectionFormat == null) {
                throw new IllegalStateException(
                        "At least one of captureFormat and injectionFormat must be specified.");
            }
            if (mAudioPolicy != null) {
                throw new IllegalStateException(
                        "Cannot create audio streams while the audio policy is registered. Call "
                                + "releaseAudioStreams() first to unregister the previous audio "
                                + "policy."
                );
            }

            mReroutedAppUids.clear();
            for (int appUid : appUids) {
                mReroutedAppUids.add(appUid);
            }

            AudioMix audioRecordMix = null;
            AudioMix audioTrackMix = null;
            AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
            if (mCaptureFormat != null) {
                audioRecordMix = createAudioRecordMix(mCaptureFormat, appUids);
                builder.addMix(audioRecordMix);
            }
            if (mInjectionFormat != null) {
                audioTrackMix = createAudioTrackMix(mInjectionFormat, appUids);
                builder.addMix(audioTrackMix);
            }
            mAudioPolicy = builder.build();
            AudioManager audioManager = mContext.getSystemService(AudioManager.class);
            if (audioManager.registerAudioPolicy(mAudioPolicy) == AudioManager.ERROR) {
                Log.e(TAG, "Failed to register audio policy!");
            }

            AudioRecord audioRecord =
                    audioRecordMix != null ? mAudioPolicy.createAudioRecordSink(audioRecordMix)
                            : null;
            AudioTrack audioTrack =
                    audioTrackMix != null ? mAudioPolicy.createAudioTrackSource(audioTrackMix)
                            : null;

            if (mAudioCapture != null) {
                mAudioCapture.setAudioRecord(audioRecord);
            }
            if (mAudioInjection != null) {
                mAudioInjection.setAudioTrack(audioTrack);
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    private void releaseAudioStreams() {
        synchronized (mLock) {
            if (mAudioCapture != null) {
                mAudioCapture.setAudioRecord(null);
            }
            if (mAudioInjection != null) {
                mAudioInjection.setAudioTrack(null);
            }
            mReroutedAppUids.clear();
            if (mAudioPolicy != null) {
                AudioManager audioManager = mContext.getSystemService(AudioManager.class);
                audioManager.unregisterAudioPolicy(mAudioPolicy);
                mAudioPolicy = null;
                Log.i(TAG, "AudioPolicy unregistered");
            }
        }
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        if (mCallback != null) {
            mExecutor.execute(() -> mCallback.onPlaybackConfigChanged(configs));
        }
    }

    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        if (mCallback != null) {
            mExecutor.execute(() -> mCallback.onRecordingConfigChanged(configs));
        }
    }

    /** @hide */
    @VisibleForTesting
    public IntArray getReroutedAppUids() {
        synchronized (mLock) {
            return mReroutedAppUids;
        }
    }

    private static AudioMix createAudioRecordMix(@NonNull AudioFormat audioFormat, int[] appUids) {
        AudioMixingRule.Builder builder = new AudioMixingRule.Builder();
        builder.setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS);
        for (int uid : appUids) {
            builder.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
        }
        AudioMixingRule audioMixingRule = builder.allowPrivilegedPlaybackCapture(false).build();
        AudioMix audioMix =
                new AudioMix.Builder(audioMixingRule)
                        .setFormat(audioFormat)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                        .build();
        return audioMix;
    }

    private static AudioMix createAudioTrackMix(@NonNull AudioFormat audioFormat, int[] appUids) {
        AudioMixingRule.Builder builder = new AudioMixingRule.Builder();
        builder.setTargetMixRole(AudioMixingRule.MIX_ROLE_INJECTOR);
        for (int uid : appUids) {
            builder.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
        }
        AudioMixingRule audioMixingRule = builder.build();
        AudioMix audioMix =
                new AudioMix.Builder(audioMixingRule)
                        .setFormat(audioFormat)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                        .build();
        return audioMix;
    }
}
