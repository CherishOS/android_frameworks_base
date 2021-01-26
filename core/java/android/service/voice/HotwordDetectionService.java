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

package android.service.voice;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * Implemented by an application that wants to offer detection for hotword. The system will
 * start the service after calling {@link VoiceInteractionService#setHotwordDetectionConfig}.
 *
 * @hide
 */
@SystemApi
public abstract class HotwordDetectionService extends Service {
    private static final String TAG = "HotwordDetectionService";
    // TODO (b/177502877): Set the Debug flag to false before shipping.
    private static final boolean DBG = true;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_HOTWORD_DETECTION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.HotwordDetectionService";

    private Handler mHandler;

    private final IHotwordDetectionService mInterface = new IHotwordDetectionService.Stub() {
        @Override
        public void detectFromDspSource(int sessionId, IDspHotwordDetectionCallback callback)
                throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#detectFromDspSource");
            }
            mHandler.sendMessage(obtainMessage(HotwordDetectionService::onDetectFromDspSource,
                    HotwordDetectionService.this,
                    sessionId, new DspHotwordDetectionCallback(callback)));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = Handler.createAsync(Looper.getMainLooper());
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": "
                + intent);
        return null;
    }

    /**
     * Detect the audio data generated from Dsp.
     *
     * @param sessionId The session to use when attempting to capture more audio from the DSP
     *                  hardware.
     * @param callback Use {@link HotwordDetectionService#DspHotwordDetectionCallback} to return
     * the detected result.
     *
     * @hide
     */
    @SystemApi
    public void onDetectFromDspSource(int sessionId,
            @NonNull DspHotwordDetectionCallback callback) {
    }

    /**
     * Callback for returning the detected result.
     *
     * @hide
     */
    @SystemApi
    public static final class DspHotwordDetectionCallback {
        // TODO: need to make sure we don't store remote references, but not a high priority.
        private final IDspHotwordDetectionCallback mRemoteCallback;

        private DspHotwordDetectionCallback(IDspHotwordDetectionCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
        }

        /**
         * Called when the detected result is valid.
         */
        public void onDetected() {
            try {
                mRemoteCallback.onDetected();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Called when the detected result is invalid.
         */
        public void onRejected() {
            try {
                mRemoteCallback.onRejected();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
