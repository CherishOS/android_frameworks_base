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

package android.service.voice;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ContentCaptureOptions;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.speech.IRecognitionServiceManager;
import android.util.Log;
import android.view.contentcapture.IContentCaptureManager;

import java.util.function.IntConsumer;

/**
 * Implemented by an application that wants to offer query detection with visual signals.
 *
 * This service leverages visual signals such as camera frames to detect and stream queries from the
 * device microphone to the {@link VoiceInteractionService}, without the support of hotword. The
 * system will bind an application's {@link VoiceInteractionService} first. When
 * {@link VoiceInteractionService#createVisualQueryDetector(PersistableBundle, SharedMemory,
 * Executor, VisualQueryDetector.Callback)} is called, the system will bind the application's
 * {@link VisualQueryDetectionService}. When requested from {@link VoiceInteractionService}, the
 * system calls into the {@link VisualQueryDetectionService#onStartDetection(Callback)} to enable
 * detection. This method MUST be implemented to support visual query detection service.
 *
 * Note: Methods in this class may be called concurrently.
 *
 * @hide
 */
@SystemApi
public abstract class VisualQueryDetectionService extends Service
        implements SandboxedDetectionServiceBase {

    private static final String TAG = VisualQueryDetectionService.class.getSimpleName();

    private static final long UPDATE_TIMEOUT_MILLIS = 20000;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VISUAL_QUERY_DETECTION_SERVICE} permission
     * so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VisualQueryDetectionService";


    /** @hide */
    public static final String KEY_INITIALIZATION_STATUS = "initialization_status";


    private final ISandboxedDetectionService mInterface = new ISandboxedDetectionService.Stub() {

        @Override
        public void updateState(PersistableBundle options, SharedMemory sharedMemory,
                IRemoteCallback callback) throws RemoteException {
            Log.v(TAG, "#updateState" + (callback != null ? " with callback" : ""));
            VisualQueryDetectionService.this.onUpdateStateInternal(
                    options,
                    sharedMemory,
                    callback);
        }

        @Override
        public void ping(IRemoteCallback callback) throws RemoteException {
            callback.sendResult(null);
        }

        @Override
        public void detectFromDspSource(
                SoundTrigger.KeyphraseRecognitionEvent event,
                AudioFormat audioFormat,
                long timeoutMillis,
                IDspHotwordDetectionCallback callback) {
            throw new UnsupportedOperationException("Not supported by VisualQueryDetectionService");
        }

        @Override
        public void detectFromMicrophoneSource(
                ParcelFileDescriptor audioStream,
                @HotwordDetectionService.AudioSource int audioSource,
                AudioFormat audioFormat,
                PersistableBundle options,
                IDspHotwordDetectionCallback callback) {
            throw new UnsupportedOperationException("Not supported by VisualQueryDetectionService");
        }

        @Override
        public void updateAudioFlinger(IBinder audioFlinger) {
            Log.v(TAG, "Ignore #updateAudioFlinger");
        }

        @Override
        public void updateContentCaptureManager(IContentCaptureManager manager,
                ContentCaptureOptions options) {
            Log.v(TAG, "Ignore #updateContentCaptureManager");
        }

        @Override
        public void updateRecognitionServiceManager(IRecognitionServiceManager manager) {
            Log.v(TAG, "Ignore #updateRecognitionServiceManager");
        }

        @Override
        public void stopDetection() {
            throw new UnsupportedOperationException("Not supported by VisualQueryDetectionService");
        }
    };

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @SystemApi
    public void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @DurationMillisLong long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback) {
    }

    @Override
    @Nullable
    public IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": "
                + intent);
        return null;
    }

    private void onUpdateStateInternal(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory, IRemoteCallback callback) {
        IntConsumer intConsumer =
                SandboxedDetectionServiceBase.createInitializationStatusConsumer(callback);
        onUpdateState(options, sharedMemory, UPDATE_TIMEOUT_MILLIS, intConsumer);
    }

    /**
     * This is called after the service is set up and the client should open the camera and the
     * microphone to start recognition.
     *
     * Called when the {@link VoiceInteractionService} requests that this service
     * {@link HotwordDetector#startRecognition()} start recognition on audio coming directly
     * from the device microphone.
     *
     * @param callback The callback to use for responding to the detection request.
     *
     */
    public void onStartDetection(@NonNull Callback callback) {
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the {@link VoiceInteractionService}
     * {@link HotwordDetector#stopRecognition()} requests that recognition be stopped.
     */
    public void onStopDetection() {
    }

    /**
     * Callback for sending out signals and returning query results.
     */
    public static final class Callback {
        //TODO: Add callback to send signals to VIS and SysUI.
    }

}
