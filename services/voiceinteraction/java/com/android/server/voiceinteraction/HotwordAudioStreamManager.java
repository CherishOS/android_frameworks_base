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

package com.android.server.voiceinteraction;

import static android.app.AppOpsManager.MODE_ALLOWED;

import static com.android.server.voiceinteraction.HotwordDetectionConnection.DEBUG;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.media.permission.Identity;
import android.os.ParcelFileDescriptor;
import android.service.voice.HotwordAudioStream;
import android.service.voice.HotwordDetectedResult;
import android.util.Pair;
import android.util.Slog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HotwordAudioStreamManager {

    private static final String TAG = "HotwordAudioStreamManager";
    private static final String OP_MESSAGE = "Streaming hotword audio to VoiceInteractionService";
    private static final String TASK_ID_PREFIX = "HotwordDetectedResult@";
    private static final String THREAD_NAME_PREFIX = "Copy-";

    private final AppOpsManager mAppOpsManager;
    private final Identity mVoiceInteractorIdentity;
    private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

    HotwordAudioStreamManager(@NonNull AppOpsManager appOpsManager,
            @NonNull Identity voiceInteractorIdentity) {
        mAppOpsManager = appOpsManager;
        mVoiceInteractorIdentity = voiceInteractorIdentity;
    }

    /**
     * Starts copying the audio streams in the given {@link HotwordDetectedResult}.
     * <p>
     * The returned {@link HotwordDetectedResult} is identical the one that was passed in, except
     * that the {@link ParcelFileDescriptor}s within {@link HotwordDetectedResult#getAudioStreams()}
     * are replaced with descriptors from pipes managed by {@link HotwordAudioStreamManager}. The
     * returned value should be passed on to the client (i.e., the voice interactor).
     * </p>
     *
     * @throws IOException If there was an error creating the managed pipe.
     */
    @NonNull
    public HotwordDetectedResult startCopyingAudioStreams(@NonNull HotwordDetectedResult result)
            throws IOException {
        List<HotwordAudioStream> audioStreams = result.getAudioStreams();
        if (audioStreams.isEmpty()) {
            return result;
        }

        List<HotwordAudioStream> newAudioStreams = new ArrayList<>(audioStreams.size());
        List<Pair<ParcelFileDescriptor, ParcelFileDescriptor>> sourcesAndSinks = new ArrayList<>(
                audioStreams.size());
        for (HotwordAudioStream audioStream : audioStreams) {
            ParcelFileDescriptor[] clientPipe = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor clientAudioSource = clientPipe[0];
            ParcelFileDescriptor clientAudioSink = clientPipe[1];
            HotwordAudioStream newAudioStream =
                    audioStream.buildUpon().setAudioStreamParcelFileDescriptor(
                            clientAudioSource).build();
            newAudioStreams.add(newAudioStream);

            ParcelFileDescriptor serviceAudioSource =
                    audioStream.getAudioStreamParcelFileDescriptor();
            sourcesAndSinks.add(new Pair<>(serviceAudioSource, clientAudioSink));
        }

        String resultTaskId = TASK_ID_PREFIX + System.identityHashCode(result);
        mExecutorService.execute(new HotwordDetectedResultCopyTask(resultTaskId, sourcesAndSinks));

        return result.buildUpon().setAudioStreams(newAudioStreams).build();
    }

    private class HotwordDetectedResultCopyTask implements Runnable {
        private final String mResultTaskId;
        private final List<Pair<ParcelFileDescriptor, ParcelFileDescriptor>> mSourcesAndSinks;
        private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

        HotwordDetectedResultCopyTask(String resultTaskId,
                List<Pair<ParcelFileDescriptor, ParcelFileDescriptor>> sourcesAndSinks) {
            mResultTaskId = resultTaskId;
            mSourcesAndSinks = sourcesAndSinks;
        }

        @Override
        public void run() {
            Thread.currentThread().setName(THREAD_NAME_PREFIX + mResultTaskId);
            int size = mSourcesAndSinks.size();
            List<SingleAudioStreamCopyTask> tasks = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Pair<ParcelFileDescriptor, ParcelFileDescriptor> sourceAndSink =
                        mSourcesAndSinks.get(i);
                ParcelFileDescriptor serviceAudioSource = sourceAndSink.first;
                ParcelFileDescriptor clientAudioSink = sourceAndSink.second;
                String streamTaskId = mResultTaskId + "@" + i;
                tasks.add(new SingleAudioStreamCopyTask(streamTaskId, serviceAudioSource,
                        clientAudioSink));
            }

            if (mAppOpsManager.startOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO_HOTWORD,
                    mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                    mVoiceInteractorIdentity.attributionTag, OP_MESSAGE) == MODE_ALLOWED) {
                try {
                    // TODO(b/244599891): Set timeout, close after inactivity
                    mExecutorService.invokeAll(tasks);
                } catch (InterruptedException e) {
                    Slog.e(TAG, mResultTaskId + ": Task was interrupted", e);
                    bestEffortPropagateError(e.getMessage());
                } finally {
                    mAppOpsManager.finishOp(AppOpsManager.OPSTR_RECORD_AUDIO_HOTWORD,
                            mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                            mVoiceInteractorIdentity.attributionTag);
                }
            } else {
                bestEffortPropagateError(
                        "Failed to obtain RECORD_AUDIO_HOTWORD permission for "
                                + SoundTriggerSessionPermissionsDecorator.toString(
                                mVoiceInteractorIdentity));
            }
        }

        private void bestEffortPropagateError(@NonNull String errorMessage) {
            try {
                for (Pair<ParcelFileDescriptor, ParcelFileDescriptor> sourceAndSink :
                        mSourcesAndSinks) {
                    ParcelFileDescriptor serviceAudioSource = sourceAndSink.first;
                    ParcelFileDescriptor clientAudioSink = sourceAndSink.second;
                    serviceAudioSource.closeWithError(errorMessage);
                    clientAudioSink.closeWithError(errorMessage);
                }
            } catch (IOException e) {
                Slog.e(TAG, mResultTaskId + ": Failed to propagate error", e);
            }
        }
    }

    private static class SingleAudioStreamCopyTask implements Callable<Void> {
        // TODO: Make this buffer size customizable from updateState()
        private static final int COPY_BUFFER_LENGTH = 1_024;

        private final String mStreamTaskId;
        private final ParcelFileDescriptor mAudioSource;
        private final ParcelFileDescriptor mAudioSink;

        SingleAudioStreamCopyTask(String streamTaskId, ParcelFileDescriptor audioSource,
                ParcelFileDescriptor audioSink) {
            mStreamTaskId = streamTaskId;
            mAudioSource = audioSource;
            mAudioSink = audioSink;
        }

        @Override
        public Void call() throws Exception {
            Thread.currentThread().setName(THREAD_NAME_PREFIX + mStreamTaskId);

            // Note: We are intentionally NOT using try-with-resources here. If we did,
            // the ParcelFileDescriptors will be automatically closed WITHOUT errors before we go
            // into the IOException-catch block. We want to propagate the error while closing the
            // PFDs.
            InputStream fis = null;
            OutputStream fos = null;
            try {
                fis = new ParcelFileDescriptor.AutoCloseInputStream(mAudioSource);
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(mAudioSink);
                byte[] buffer = new byte[COPY_BUFFER_LENGTH];
                while (true) {
                    if (Thread.interrupted()) {
                        Slog.e(TAG,
                                mStreamTaskId + ": SingleAudioStreamCopyTask task was interrupted");
                        break;
                    }

                    int bytesRead = fis.read(buffer);
                    if (bytesRead < 0) {
                        Slog.i(TAG, mStreamTaskId + ": Reached end of audio stream");
                        break;
                    }
                    if (bytesRead > 0) {
                        if (DEBUG) {
                            // TODO(b/244599440): Add proper logging
                            Slog.d(TAG, mStreamTaskId + ": Copied " + bytesRead
                                    + " bytes from audio stream. First 20 bytes=" + Arrays.toString(
                                    Arrays.copyOfRange(buffer, 0, 20)));
                        }
                        fos.write(buffer, 0, bytesRead);
                    }
                    // TODO(b/244599891): Close PFDs after inactivity
                }
            } catch (IOException e) {
                mAudioSource.closeWithError(e.getMessage());
                mAudioSink.closeWithError(e.getMessage());
                Slog.e(TAG, mStreamTaskId + ": Failed to copy audio stream", e);
            } finally {
                if (fis != null) {
                    fis.close();
                }
                if (fos != null) {
                    fos.close();
                }
            }

            return null;
        }
    }

}
