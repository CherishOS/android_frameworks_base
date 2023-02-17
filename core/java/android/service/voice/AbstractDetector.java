/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.compat.CompatChanges;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Base implementation of {@link HotwordDetector}.
 *
 * This class provides methods to manage the detector lifecycle for both
 * {@link HotwordDetectionService} and {@link VisualQueryDetectionService}. We keep the name of the
 * interface {@link HotwordDetector} since {@link VisualQueryDetectionService} can be logically
 * treated as a visual activation hotword detection and also because of the existing public
 * interface. To avoid confusion on the naming between the trusted hotword framework and the actual
 * isolated {@link HotwordDetectionService}, the hotword from the names is removed.
 */
abstract class AbstractDetector implements HotwordDetector {
    private static final String TAG = AbstractDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    protected final Object mLock = new Object();

    private final IVoiceInteractionManagerService mManagerService;
    private final Executor mExecutor;
    private final HotwordDetector.Callback mCallback;
    private Consumer<AbstractDetector> mOnDestroyListener;
    private final AtomicBoolean mIsDetectorActive;
    /**
     * A token which is used by voice interaction system service to identify different detectors.
     */
    private final IBinder mToken = new Binder();

    AbstractDetector(
            IVoiceInteractionManagerService managerService,
            Executor executor,
            HotwordDetector.Callback callback) {
        mManagerService = managerService;
        mCallback = callback;
        mExecutor = executor != null ? executor : new HandlerExecutor(
                new Handler(Looper.getMainLooper()));
        mIsDetectorActive = new AtomicBoolean(true);
    }

    /**
     * Method to be called for the detector to ready/register itself with underlying system
     * services.
     */
    abstract void initialize(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory);

    /**
     * Detect from an externally supplied stream of data.
     *
     * @return {@code true} if the request to start recognition succeeded
     */
    @Override
    public boolean startRecognition(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options) throws IllegalDetectorStateException {
        if (DEBUG) {
            Slog.i(TAG, "#recognizeHotword");
        }
        throwIfDetectorIsNoLongerActive();

        // TODO: consider closing existing session.

        try {
            mManagerService.startListeningFromExternalSource(
                    audioStream,
                    audioFormat,
                    options,
                    mToken,
                    new BinderCallback(mExecutor, mCallback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
    }

    /**
     * Set configuration and pass read-only data to trusted detection service.
     *
     * @param options Application configuration data to provide to the
     *         {@link VisualQueryDetectionService} and {@link HotwordDetectionService}.
     *         PersistableBundle does not allow any remotable objects or other contents that can be
     *         used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the
     *        {@link VisualQueryDetectionService} and {@link HotwordDetectionService}. Use this to
     *         provide the hotword models data or other such data to the trusted process.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of
     *         Android Tiramisu or above and attempts to start a recognition when the detector is
     *         not able based on the state. Because the caller receives updates via an asynchronous
     *         callback and the state of the detector can change without caller's knowledge, a
     *         checked exception is thrown.
     * @throws IllegalStateException if this {@link HotwordDetector} wasn't specified to use a
     *         {@link HotwordDetectionService} or {@link VisualQueryDetectionService} when it was
     *         created.
     */
    @Override
    public void updateState(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) throws IllegalDetectorStateException {
        if (DEBUG) {
            Slog.d(TAG, "updateState()");
        }
        throwIfDetectorIsNoLongerActive();
        try {
            mManagerService.updateState(options, sharedMemory, mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    protected void initAndVerifyDetector(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull IHotwordRecognitionStatusCallback callback,
            int detectorType) {
        if (DEBUG) {
            Slog.d(TAG, "initAndVerifyDetector()");
        }
        Identity identity = new Identity();
        identity.packageName = ActivityThread.currentOpPackageName();
        try {
            mManagerService.initAndVerifyDetector(identity, options, sharedMemory, mToken, callback,
                    detectorType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void registerOnDestroyListener(Consumer<AbstractDetector> onDestroyListener) {
        synchronized (mLock) {
            if (mOnDestroyListener != null) {
                throw new IllegalStateException("only one destroy listener can be registered");
            }
            mOnDestroyListener = onDestroyListener;
        }
    }

    @CallSuper
    @Override
    public void destroy() {
        if (!mIsDetectorActive.get()) {
            return;
        }
        mIsDetectorActive.set(false);
        try {
            mManagerService.destroyDetector(mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        synchronized (mLock) {
            mOnDestroyListener.accept(this);
        }
    }

    protected void throwIfDetectorIsNoLongerActive() throws IllegalDetectorStateException {
        if (!mIsDetectorActive.get()) {
            Slog.e(TAG, "attempting to use a destroyed detector which is no longer active");
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "attempting to use a destroyed detector which is no longer active");
            }
            throw new IllegalStateException(
                    "attempting to use a destroyed detector which is no longer active");
        }
    }

    private static class BinderCallback
            extends IMicrophoneHotwordDetectionVoiceInteractionCallback.Stub {
        // TODO: these need to be weak references.
        private final HotwordDetector.Callback mCallback;
        private final Executor mExecutor;

        BinderCallback(Executor executor, HotwordDetector.Callback callback) {
            this.mCallback = callback;
            this.mExecutor = executor;
        }

        /** TODO: onDetected */
        @Override
        public void onDetected(
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable AudioFormat audioFormat,
                @Nullable ParcelFileDescriptor audioStreamIgnored) {
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onDetected(new AlwaysOnHotwordDetector.EventPayload.Builder()
                        .setCaptureAudioFormat(audioFormat)
                        .setHotwordDetectedResult(hotwordDetectedResult)
                        .build());
            }));
        }

        /** Called when the detection fails due to an error. */
        @Override
        public void onError(DetectorFailure detectorFailure) {
            Slog.v(TAG, "BinderCallback#onError detectorFailure: " + detectorFailure);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onFailure(detectorFailure != null ? detectorFailure
                        : new UnknownFailure("Error data is null"));
            }));
        }

        @Override
        public void onRejected(@Nullable HotwordRejectedResult result) {
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onRejected(
                        result != null ? result : new HotwordRejectedResult.Builder().build());
            }));
        }
    }
}
