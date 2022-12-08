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

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.service.attention.AttentionService.PROXIMITY_UNKNOWN;
import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_EXTERNAL;
import static android.service.voice.HotwordDetectionService.AUDIO_SOURCE_MICROPHONE;
import static android.service.voice.HotwordDetectionService.ENABLE_PROXIMITY_RESULT;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS;
import static android.service.voice.HotwordDetectionService.INITIALIZATION_STATUS_UNKNOWN;
import static android.service.voice.HotwordDetectionService.KEY_INITIALIZATION_STATUS;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_ERROR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_NO_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_OVER_MAX_CUSTOM_VALUE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__APP_REQUEST_UPDATE_STATE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_REJECTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_STATUS_REPORTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__CALL_UPDATE_STATE_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_REJECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__START_EXTERNAL_SOURCE_DETECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__EVENT__START_SOFTWARE_DETECTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;
import static com.android.server.voiceinteraction.SoundTriggerSessionPermissionsDecorator.enforcePermissionForPreflight;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.attention.AttentionManagerInternal;
import android.content.Context;
import android.content.PermissionChecker;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.os.Binder;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IHotwordDetectionService;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.AndroidFuture;
import com.android.server.LocalServices;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that provides trusted hotword detector to communicate with the {@link
 * HotwordDetectionService}.
 */
class HotwordDetectorSession {
    private static final String TAG = "HotwordDetectorSession";
    static final boolean DEBUG = false;

    private static final String OP_MESSAGE =
            "Providing hotword detection result to VoiceInteractionService";

    // The error codes are used for onError callback
    static final int HOTWORD_DETECTION_SERVICE_DIED = -1;
    static final int CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION = -2;
    static final int CALLBACK_DETECT_TIMEOUT = -3;
    static final int CALLBACK_ONDETECTED_STREAM_COPY_ERROR = -4;

    // TODO: These constants need to be refined.
    // The validation timeout value is 3 seconds for onDetect of DSP trigger event.
    private static final long VALIDATION_TIMEOUT_MILLIS = 3000;
    // Write the onDetect timeout metric when it takes more time than MAX_VALIDATION_TIMEOUT_MILLIS.
    private static final long MAX_VALIDATION_TIMEOUT_MILLIS = 4000;
    private static final long MAX_UPDATE_TIMEOUT_MILLIS = 30000;
    private static final long EXTERNAL_HOTWORD_CLEANUP_MILLIS = 2000;
    private static final Duration MAX_UPDATE_TIMEOUT_DURATION =
            Duration.ofMillis(MAX_UPDATE_TIMEOUT_MILLIS);

    // Hotword metrics
    private static final int METRICS_INIT_UNKNOWN_TIMEOUT =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_TIMEOUT;
    private static final int METRICS_INIT_UNKNOWN_NO_VALUE =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_NO_VALUE;
    private static final int METRICS_INIT_UNKNOWN_OVER_MAX_CUSTOM_VALUE =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_UNKNOWN_OVER_MAX_CUSTOM_VALUE;
    private static final int METRICS_INIT_CALLBACK_STATE_ERROR =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_ERROR;
    private static final int METRICS_INIT_CALLBACK_STATE_SUCCESS =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__RESULT__CALLBACK_INIT_STATE_SUCCESS;

    private static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_SECURITY_EXCEPTION;
    private static final int METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_UNEXPECTED_CALLBACK;
    private static final int METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK =
            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECT_UNEXPECTED_CALLBACK;

    private static final int METRICS_EXTERNAL_SOURCE_DETECTED =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECTED;
    private static final int METRICS_EXTERNAL_SOURCE_REJECTED =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_REJECTED;
    private static final int METRICS_EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION =
            HOTWORD_DETECTOR_EVENTS__EVENT__EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION;
    private static final int METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION =
            HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_STATUS_REPORTED_EXCEPTION;

    private final Executor mAudioCopyExecutor = Executors.newCachedThreadPool();
    // TODO: This may need to be a Handler(looper)
    private final ScheduledExecutorService mScheduledExecutorService;
    private final AppOpsManager mAppOpsManager;
    private final HotwordAudioStreamCopier mHotwordAudioStreamCopier;
    private final AtomicBoolean mUpdateStateAfterStartFinished = new AtomicBoolean(false);
    private final IHotwordRecognitionStatusCallback mCallback;

    final Object mLock;
    final int mVoiceInteractionServiceUid;
    final Context mContext;

    @Nullable AttentionManagerInternal mAttentionManagerInternal = null;

    final AttentionManagerInternal.ProximityUpdateCallbackInternal mProximityCallbackInternal =
            this::setProximityValue;

    private IMicrophoneHotwordDetectionVoiceInteractionCallback mSoftwareCallback;

    private ScheduledFuture<?> mCancellationKeyPhraseDetectionFuture;

    /** Identity used for attributing app ops when delivering data to the Interactor. */
    @GuardedBy("mLock")
    @Nullable
    private final Identity mVoiceInteractorIdentity;
    @GuardedBy("mLock")
    private ParcelFileDescriptor mCurrentAudioSink;
    @GuardedBy("mLock")
    private boolean mValidatingDspTrigger = false;
    @GuardedBy("mLock")
    private boolean mPerformingSoftwareHotwordDetection;
    @NonNull private HotwordDetectionConnection.ServiceConnection mRemoteHotwordDetectionService;
    private boolean mDebugHotwordLogging = false;
    @GuardedBy("mLock")
    private double mProximityMeters = PROXIMITY_UNKNOWN;
    @GuardedBy("mLock")
    private boolean mInitialized = false;
    @GuardedBy("mLock")
    private boolean mDestroyed = false;

    HotwordDetectorSession(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteHotwordDetectionService,
            @NonNull Object lock, @NonNull Context context,
            @NonNull IHotwordRecognitionStatusCallback callback, int voiceInteractionServiceUid,
            Identity voiceInteractorIdentity,
            @NonNull ScheduledExecutorService scheduledExecutorService, boolean logging) {
        mRemoteHotwordDetectionService = remoteHotwordDetectionService;
        mLock = lock;
        mContext = context;
        mCallback = callback;
        mVoiceInteractionServiceUid = voiceInteractionServiceUid;
        mVoiceInteractorIdentity = voiceInteractorIdentity;
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mHotwordAudioStreamCopier = new HotwordAudioStreamCopier(mAppOpsManager, getDetectorType(),
                mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                mVoiceInteractorIdentity.attributionTag);
        mScheduledExecutorService = scheduledExecutorService;
        mDebugHotwordLogging = logging;

        if (ENABLE_PROXIMITY_RESULT) {
            mAttentionManagerInternal = LocalServices.getService(AttentionManagerInternal.class);
            if (mAttentionManagerInternal != null) {
                mAttentionManagerInternal.onStartProximityUpdates(mProximityCallbackInternal);
            }
        }
    }

    private void updateStateAfterProcessStartLocked(PersistableBundle options,
            SharedMemory sharedMemory) {
        if (DEBUG) {
            Slog.d(TAG, "updateStateAfterProcessStartLocked");
        }
        AndroidFuture<Void> voidFuture = mRemoteHotwordDetectionService.postAsync(service -> {
            AndroidFuture<Void> future = new AndroidFuture<>();
            IRemoteCallback statusCallback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle bundle) throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "updateState finish");
                    }
                    future.complete(null);
                    if (mUpdateStateAfterStartFinished.getAndSet(true)) {
                        Slog.w(TAG, "call callback after timeout");
                        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_UPDATE_STATE_AFTER_TIMEOUT,
                                mVoiceInteractionServiceUid);
                        return;
                    }
                    Pair<Integer, Integer> statusResultPair = getInitStatusAndMetricsResult(bundle);
                    int status = statusResultPair.first;
                    int initResultMetricsResult = statusResultPair.second;
                    try {
                        mCallback.onStatusReported(status);
                        HotwordMetricsLogger.writeServiceInitResultEvent(getDetectorType(),
                                initResultMetricsResult);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to report initialization status: " + e);
                        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION,
                                mVoiceInteractionServiceUid);
                    }
                }
            };
            try {
                service.updateState(options, sharedMemory, statusCallback);
                HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                        HOTWORD_DETECTOR_EVENTS__EVENT__REQUEST_UPDATE_STATE,
                        mVoiceInteractionServiceUid);
            } catch (RemoteException e) {
                // TODO: (b/181842909) Report an error to voice interactor
                Slog.w(TAG, "Failed to updateState for HotwordDetectionService", e);
                HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                        HOTWORD_DETECTOR_EVENTS__EVENT__CALL_UPDATE_STATE_EXCEPTION,
                        mVoiceInteractionServiceUid);
            }
            return future.orTimeout(MAX_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }).whenComplete((res, err) -> {
            if (err instanceof TimeoutException) {
                Slog.w(TAG, "updateState timed out");
                if (mUpdateStateAfterStartFinished.getAndSet(true)) {
                    return;
                }
                try {
                    mCallback.onStatusReported(INITIALIZATION_STATUS_UNKNOWN);
                    HotwordMetricsLogger.writeServiceInitResultEvent(getDetectorType(),
                            METRICS_INIT_UNKNOWN_TIMEOUT);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report initialization status UNKNOWN", e);
                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                            METRICS_CALLBACK_ON_STATUS_REPORTED_EXCEPTION,
                            mVoiceInteractionServiceUid);
                }
            } else if (err != null) {
                Slog.w(TAG, "Failed to update state: " + err);
            }
        });
        if (voidFuture == null) {
            Slog.w(TAG, "Failed to create AndroidFuture");
        }
    }

    private static Pair<Integer, Integer> getInitStatusAndMetricsResult(Bundle bundle) {
        if (bundle == null) {
            return new Pair<>(INITIALIZATION_STATUS_UNKNOWN, METRICS_INIT_UNKNOWN_NO_VALUE);
        }
        int status = bundle.getInt(KEY_INITIALIZATION_STATUS, INITIALIZATION_STATUS_UNKNOWN);
        if (status > HotwordDetectionService.getMaxCustomInitializationStatus()) {
            return new Pair<>(INITIALIZATION_STATUS_UNKNOWN,
                    status == INITIALIZATION_STATUS_UNKNOWN
                            ? METRICS_INIT_UNKNOWN_NO_VALUE
                            : METRICS_INIT_UNKNOWN_OVER_MAX_CUSTOM_VALUE);
        }
        // TODO: should guard against negative here
        int metricsResult = status == INITIALIZATION_STATUS_SUCCESS
                ? METRICS_INIT_CALLBACK_STATE_SUCCESS
                : METRICS_INIT_CALLBACK_STATE_ERROR;
        return new Pair<>(status, metricsResult);
    }

    void updateStateLocked(PersistableBundle options, SharedMemory sharedMemory,
            Instant lastRestartInstant) {
        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                HOTWORD_DETECTOR_EVENTS__EVENT__APP_REQUEST_UPDATE_STATE,
                mVoiceInteractionServiceUid);
        // Prevent doing the init late, so restart is handled equally to a clean process start.
        // TODO(b/191742511): this logic needs a test
        if (!mUpdateStateAfterStartFinished.get() && Instant.now().minus(
                MAX_UPDATE_TIMEOUT_DURATION).isBefore(lastRestartInstant)) {
            Slog.v(TAG, "call updateStateAfterProcessStartLocked");
            updateStateAfterProcessStartLocked(options, sharedMemory);
        } else {
            mRemoteHotwordDetectionService.run(
                    service -> service.updateState(options, sharedMemory, /* callback= */ null));
        }
    }

    /**
     * This method is only used by SoftwareHotwordDetector.
     */
    @SuppressWarnings("GuardedBy")
    void startListeningFromMicLocked(
            AudioFormat audioFormat,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromMic");
        }
        mSoftwareCallback = callback;

        if (mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Hotword validation is already in progress, ignoring.");
            return;
        }
        mPerformingSoftwareHotwordDetection = true;

        startListeningFromMicLocked();
    }

    /**
     * This method is only used by SoftwareHotwordDetector.
     */
    private void startListeningFromMicLocked() {
        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            getDetectorType(),
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED);
                    if (!mPerformingSoftwareHotwordDetection) {
                        Slog.i(TAG, "Hotword detection has already completed");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK);
                        return;
                    }
                    mPerformingSoftwareHotwordDetection = false;
                    try {
                        enforcePermissionsForDataDelivery();
                    } catch (SecurityException e) {
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION);
                        mSoftwareCallback.onError();
                        return;
                    }
                    saveProximityValueToBundle(result);
                    HotwordDetectedResult newResult;
                    try {
                        newResult = mHotwordAudioStreamCopier.startCopyingAudioStreams(result);
                    } catch (IOException e) {
                        // TODO: Write event
                        mSoftwareCallback.onError();
                        return;
                    }
                    mSoftwareCallback.onDetected(newResult, null, null);
                    Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(newResult)
                            + " bits from hotword trusted process");
                    if (mDebugHotwordLogging) {
                        Slog.i(TAG, "Egressed detected result: " + newResult);
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.wtf(TAG, "onRejected");
                }
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        getDetectorType(),
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED);
                // onRejected isn't allowed here, and we are not expecting it.
            }
        };

        mRemoteHotwordDetectionService.run(
                service -> service.detectFromMicrophoneSource(
                        null,
                        AUDIO_SOURCE_MICROPHONE,
                        null,
                        null,
                        internalCallback));
        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                HOTWORD_DETECTOR_EVENTS__EVENT__START_SOFTWARE_DETECTION,
                mVoiceInteractionServiceUid);
    }

    public void startListeningFromExternalSourceLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromExternalSource");
        }

        handleExternalSourceHotwordDetectionLocked(
                audioStream,
                audioFormat,
                options,
                callback);
    }

    /**
     * This method is only used by SoftwareHotwordDetector.
     */
    @SuppressWarnings("GuardedBy")
    void stopListeningLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopListening");
        }
        if (!mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Hotword detection is not running");
            return;
        }
        mPerformingSoftwareHotwordDetection = false;

        mRemoteHotwordDetectionService.run(IHotwordDetectionService::stopDetection);

        if (mCurrentAudioSink != null) {
            Slog.i(TAG, "Closing audio stream to hotword detector: stopping requested");
            bestEffortClose(mCurrentAudioSink);
        }
        mCurrentAudioSink = null;
    }

    @SuppressWarnings("GuardedBy")
    void detectFromDspSourceLocked(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            IHotwordRecognitionStatusCallback externalCallback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSource");
        }

        AtomicBoolean timeoutDetected = new AtomicBoolean(false);
        // TODO: consider making this a non-anonymous class.
        IDspHotwordDetectionCallback internalCallback = new IDspHotwordDetectionCallback.Stub() {
            @Override
            public void onDetected(HotwordDetectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onDetected");
                }
                synchronized (mLock) {
                    if (mCancellationKeyPhraseDetectionFuture != null) {
                        mCancellationKeyPhraseDetectionFuture.cancel(true);
                    }
                    if (timeoutDetected.get()) {
                        return;
                    }
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            getDetectorType(),
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECTED);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onDetected due to a process restart");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_UNEXPECTED_CALLBACK);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    try {
                        enforcePermissionsForDataDelivery();
                        enforceExtraKeyphraseIdNotLeaked(result, recognitionEvent);
                    } catch (SecurityException e) {
                        Slog.i(TAG, "Ignoring #onDetected due to a SecurityException", e);
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                METRICS_KEYPHRASE_TRIGGERED_DETECT_SECURITY_EXCEPTION);
                        externalCallback.onError(CALLBACK_ONDETECTED_GOT_SECURITY_EXCEPTION);
                        return;
                    }
                    saveProximityValueToBundle(result);
                    HotwordDetectedResult newResult;
                    try {
                        newResult = mHotwordAudioStreamCopier.startCopyingAudioStreams(result);
                    } catch (IOException e) {
                        // TODO: Write event
                        externalCallback.onError(CALLBACK_ONDETECTED_STREAM_COPY_ERROR);
                        return;
                    }
                    externalCallback.onKeyphraseDetected(recognitionEvent, newResult);
                    Slog.i(TAG, "Egressed " + HotwordDetectedResult.getUsageSize(newResult)
                            + " bits from hotword trusted process");
                    if (mDebugHotwordLogging) {
                        Slog.i(TAG, "Egressed detected result: " + newResult);
                    }
                }
            }

            @Override
            public void onRejected(HotwordRejectedResult result) throws RemoteException {
                if (DEBUG) {
                    Slog.d(TAG, "onRejected");
                }
                synchronized (mLock) {
                    if (mCancellationKeyPhraseDetectionFuture != null) {
                        mCancellationKeyPhraseDetectionFuture.cancel(true);
                    }
                    if (timeoutDetected.get()) {
                        return;
                    }
                    HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                            getDetectorType(),
                            HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED);
                    if (!mValidatingDspTrigger) {
                        Slog.i(TAG, "Ignoring #onRejected due to a process restart");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                METRICS_KEYPHRASE_TRIGGERED_REJECT_UNEXPECTED_CALLBACK);
                        return;
                    }
                    mValidatingDspTrigger = false;
                    externalCallback.onRejected(result);
                    if (mDebugHotwordLogging && result != null) {
                        Slog.i(TAG, "Egressed rejected result: " + result);
                    }
                }
            }
        };

        mValidatingDspTrigger = true;
        mRemoteHotwordDetectionService.run(service -> {
            // We use the VALIDATION_TIMEOUT_MILLIS to inform that the client needs to invoke
            // the callback before timeout value. In order to reduce the latency impact between
            // server side and client side, we need to use another timeout value
            // MAX_VALIDATION_TIMEOUT_MILLIS to monitor it.
            mCancellationKeyPhraseDetectionFuture = mScheduledExecutorService.schedule(
                    () -> {
                        // TODO: avoid allocate every time
                        timeoutDetected.set(true);
                        Slog.w(TAG, "Timed out on #detectFromDspSource");
                        HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                                getDetectorType(),
                                HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__DETECT_TIMEOUT);
                        try {
                            externalCallback.onError(CALLBACK_DETECT_TIMEOUT);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to report onError status: ", e);
                            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                                    mVoiceInteractionServiceUid);
                        }
                    },
                    MAX_VALIDATION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
            service.detectFromDspSource(
                    recognitionEvent,
                    recognitionEvent.getCaptureFormat(),
                    VALIDATION_TIMEOUT_MILLIS,
                    internalCallback);
        });
    }

    @SuppressWarnings("GuardedBy")
    public void dumpLocked(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mCallback="); pw.println(mCallback);
        pw.print(prefix); pw.print("mUpdateStateAfterStartFinished=");
        pw.println(mUpdateStateAfterStartFinished);
        pw.print(prefix); pw.print("mInitialized="); pw.println(mInitialized);
        pw.print(prefix); pw.print("mDestroyed="); pw.println(mDestroyed);
        pw.print(prefix); pw.print("mValidatingDspTrigger="); pw.println(mValidatingDspTrigger);
        pw.print(prefix); pw.print("mPerformingSoftwareHotwordDetection=");
        pw.println(mPerformingSoftwareHotwordDetection);
        pw.print(prefix); pw.print("DetectorType=");
        pw.println(HotwordDetector.detectorTypeToString(getDetectorType()));
    }

    @SuppressWarnings("GuardedBy")
    private void handleExternalSourceHotwordDetectionLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "#handleExternalSourceHotwordDetectionLocked");
        }
        InputStream audioSource = new ParcelFileDescriptor.AutoCloseInputStream(audioStream);

        Pair<ParcelFileDescriptor, ParcelFileDescriptor> clientPipe = createPipe();
        if (clientPipe == null) {
            // TODO: Need to propagate as unknown error or something?
            return;
        }
        ParcelFileDescriptor serviceAudioSink = clientPipe.second;
        ParcelFileDescriptor serviceAudioSource = clientPipe.first;

        mCurrentAudioSink = serviceAudioSink;

        mAudioCopyExecutor.execute(() -> {
            try (InputStream source = audioSource;
                 OutputStream fos =
                         new ParcelFileDescriptor.AutoCloseOutputStream(serviceAudioSink)) {

                byte[] buffer = new byte[1024];
                while (true) {
                    int bytesRead = source.read(buffer, 0, 1024);

                    if (bytesRead < 0) {
                        Slog.i(TAG, "Reached end of stream for external hotword");
                        break;
                    }

                    // TODO: First write to ring buffer to make sure we don't lose data if the next
                    // statement fails.
                    // ringBuffer.append(buffer, bytesRead);
                    fos.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed supplying audio data to validator", e);

                try {
                    callback.onError();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to report onError status: " + ex);
                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                            HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                            mVoiceInteractionServiceUid);
                }
            } finally {
                synchronized (mLock) {
                    mCurrentAudioSink = null;
                }
            }
        });

        // TODO: handle cancellations well
        // TODO: what if we cancelled and started a new one?
        mRemoteHotwordDetectionService.run(
                service -> {
                    service.detectFromMicrophoneSource(
                            serviceAudioSource,
                            // TODO: consider making a proxy callback + copy of audio format
                            AUDIO_SOURCE_EXTERNAL,
                            audioFormat,
                            options,
                            new IDspHotwordDetectionCallback.Stub() {
                                @Override
                                public void onRejected(HotwordRejectedResult result)
                                        throws RemoteException {
                                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                            METRICS_EXTERNAL_SOURCE_REJECTED,
                                            mVoiceInteractionServiceUid);
                                    mScheduledExecutorService.schedule(
                                            () -> {
                                                bestEffortClose(serviceAudioSink, audioSource);
                                            },
                                            EXTERNAL_HOTWORD_CLEANUP_MILLIS,
                                            TimeUnit.MILLISECONDS);

                                    callback.onRejected(result);

                                    if (result != null) {
                                        Slog.i(TAG, "Egressed 'hotword rejected result' "
                                                + "from hotword trusted process");
                                        if (mDebugHotwordLogging) {
                                            Slog.i(TAG, "Egressed detected result: " + result);
                                        }
                                    }
                                }

                                @Override
                                public void onDetected(HotwordDetectedResult triggerResult)
                                        throws RemoteException {
                                    HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                            METRICS_EXTERNAL_SOURCE_DETECTED,
                                            mVoiceInteractionServiceUid);
                                    mScheduledExecutorService.schedule(
                                            () -> {
                                                bestEffortClose(serviceAudioSink, audioSource);
                                            },
                                            EXTERNAL_HOTWORD_CLEANUP_MILLIS,
                                            TimeUnit.MILLISECONDS);

                                    try {
                                        enforcePermissionsForDataDelivery();
                                    } catch (SecurityException e) {
                                        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                                                METRICS_EXTERNAL_SOURCE_DETECT_SECURITY_EXCEPTION,
                                                mVoiceInteractionServiceUid);
                                        callback.onError();
                                        return;
                                    }
                                    HotwordDetectedResult newResult;
                                    try {
                                        newResult =
                                                mHotwordAudioStreamCopier.startCopyingAudioStreams(
                                                        triggerResult);
                                    } catch (IOException e) {
                                        // TODO: Write event
                                        callback.onError();
                                        return;
                                    }
                                    callback.onDetected(newResult, null /* audioFormat */,
                                            null /* audioStream */);
                                    Slog.i(TAG, "Egressed "
                                            + HotwordDetectedResult.getUsageSize(newResult)
                                            + " bits from hotword trusted process");
                                    if (mDebugHotwordLogging) {
                                        Slog.i(TAG,
                                                "Egressed detected result: " + newResult);
                                    }
                                }
                            });

                    // A copy of this has been created and passed to the hotword validator
                    bestEffortClose(serviceAudioSource);
                });
        HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                HOTWORD_DETECTOR_EVENTS__EVENT__START_EXTERNAL_SOURCE_DETECTION,
                mVoiceInteractionServiceUid);
    }

    void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
        synchronized (mLock) {
            if (mInitialized || mDestroyed) {
                return;
            }
            updateStateAfterProcessStartLocked(options, sharedMemory);
            mInitialized = true;
        }
    }

    @SuppressWarnings("GuardedBy")
    void destroyLocked() {
        mDestroyed = true;
        mDebugHotwordLogging = false;
        mRemoteHotwordDetectionService = null;
        if (mAttentionManagerInternal != null) {
            mAttentionManagerInternal.onStopProximityUpdates(mProximityCallbackInternal);
        }
    }

    void setDebugHotwordLoggingLocked(boolean logging) {
        Slog.v(TAG, "setDebugHotwordLogging: " + logging);
        mDebugHotwordLogging = logging;
    }

    void updateRemoteHotwordDetectionServiceLocked(
            @NonNull HotwordDetectionConnection.ServiceConnection remoteHotwordDetectionService) {
        mRemoteHotwordDetectionService = remoteHotwordDetectionService;
    }

    @SuppressWarnings("GuardedBy")
    void informRestartProcessLocked() {
        // TODO(b/244598068): Check HotwordAudioStreamManager first
        Slog.v(TAG, "informRestartProcess");
        if (mValidatingDspTrigger) {
            // We're restarting the process while it's processing a DSP trigger, so report a
            // rejection. This also allows the Interactor to startRecognition again
            try {
                mCallback.onRejected(new HotwordRejectedResult.Builder().build());
                HotwordMetricsLogger.writeKeyphraseTriggerEvent(
                        getDetectorType(),
                        HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__RESULT__REJECTED_FROM_RESTART);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call #rejected");
                HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                        HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_REJECTED_EXCEPTION,
                        mVoiceInteractionServiceUid);
            }
            mValidatingDspTrigger = false;
        }

        mUpdateStateAfterStartFinished.set(false);

        try {
            mCallback.onProcessRestarted();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to communicate #onProcessRestarted", e);
            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_PROCESS_RESTARTED_EXCEPTION,
                    mVoiceInteractionServiceUid);
        }

        // Restart listening from microphone if the hotword process has been restarted.
        if (mPerformingSoftwareHotwordDetection) {
            Slog.i(TAG, "Process restarted: calling startRecognition() again");
            startListeningFromMicLocked();
        }

        if (mCurrentAudioSink != null) {
            Slog.i(TAG, "Closing external audio stream to hotword detector: process restarted");
            bestEffortClose(mCurrentAudioSink);
            mCurrentAudioSink = null;
        }
    }

    void reportErrorLocked(int status) {
        try {
            mCallback.onError(status);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report onError status: " + e);
            HotwordMetricsLogger.writeDetectorEvent(getDetectorType(),
                    HOTWORD_DETECTOR_EVENTS__EVENT__CALLBACK_ON_ERROR_EXCEPTION,
                    mVoiceInteractionServiceUid);
        }
    }

    private static Pair<ParcelFileDescriptor, ParcelFileDescriptor> createPipe() {
        ParcelFileDescriptor[] fileDescriptors;
        try {
            fileDescriptors = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create audio stream pipe", e);
            return null;
        }

        return Pair.create(fileDescriptors[0], fileDescriptors[1]);
    }

    private void saveProximityValueToBundle(HotwordDetectedResult result) {
        synchronized (mLock) {
            if (result != null && mProximityMeters != PROXIMITY_UNKNOWN) {
                result.setProximity(mProximityMeters);
            }
        }
    }

    private void setProximityValue(double proximityMeters) {
        synchronized (mLock) {
            mProximityMeters = proximityMeters;
        }
    }

    private static void bestEffortClose(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            bestEffortClose(closeable);
        }
    }

    private static void bestEffortClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed closing", e);
            }
        }
    }

    // TODO: Share this code with SoundTriggerMiddlewarePermission.
    private void enforcePermissionsForDataDelivery() {
        Binder.withCleanCallingIdentity(() -> {
            synchronized (mLock) {
                enforcePermissionForPreflight(mContext, mVoiceInteractorIdentity, RECORD_AUDIO);
                int hotwordOp = AppOpsManager.strOpToOp(AppOpsManager.OPSTR_RECORD_AUDIO_HOTWORD);
                mAppOpsManager.noteOpNoThrow(hotwordOp,
                        mVoiceInteractorIdentity.uid, mVoiceInteractorIdentity.packageName,
                        mVoiceInteractorIdentity.attributionTag, OP_MESSAGE);
                enforcePermissionForDataDelivery(mContext, mVoiceInteractorIdentity,
                        CAPTURE_AUDIO_HOTWORD, OP_MESSAGE);
            }
        });
    }

    /**
     * Throws a {@link SecurityException} if the given identity has no permission to receive data.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @param reason     The reason why we're requesting the permission, for auditing purposes.
     */
    private static void enforcePermissionForDataDelivery(@NonNull Context context,
            @NonNull Identity identity, @NonNull String permission, @NonNull String reason) {
        final int status = PermissionUtil.checkPermissionForDataDelivery(context, identity,
                permission, reason);
        if (status != PermissionChecker.PERMISSION_GRANTED) {
            throw new SecurityException(
                    TextUtils.formatSimple("Failed to obtain permission %s for identity %s",
                            permission,
                            SoundTriggerSessionPermissionsDecorator.toString(identity)));
        }
    }

    private static void enforceExtraKeyphraseIdNotLeaked(HotwordDetectedResult result,
            SoundTrigger.KeyphraseRecognitionEvent recognitionEvent) {
        // verify the phrase ID in HotwordDetectedResult is not exposing extra phrases
        // the DSP did not detect
        for (SoundTrigger.KeyphraseRecognitionExtra keyphrase : recognitionEvent.keyphraseExtras) {
            if (keyphrase.getKeyphraseId() == result.getHotwordPhraseId()) {
                return;
            }
        }
        throw new SecurityException("Ignoring #onDetected due to trusted service "
                + "sharing a keyphrase ID which the DSP did not detect");
    }

    private int getDetectorType() {
        if (this instanceof DspTrustedHotwordDetectorSession) {
            return HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP;
        } else if (this instanceof SoftwareTrustedHotwordDetectorSession) {
            return HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE;
        }
        Slog.v(TAG, "Unexpected detector type");
        return -1;
    }
}
