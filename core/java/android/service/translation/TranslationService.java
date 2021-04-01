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

package android.service.translation;

import static android.view.translation.Translator.EXTRA_SERVICE_BINDER;
import static android.view.translation.Translator.EXTRA_SESSION_ID;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArraySet;
import android.util.Log;
import android.view.translation.ITranslationDirectManager;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationResponse;
import android.view.translation.TranslationSpec;

import com.android.internal.os.IResultReceiver;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Service for translating text.
 * @hide
 */
@SystemApi
public abstract class TranslationService extends Service {
    private static final String TAG = "TranslationService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_TRANSLATION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.translation.TranslationService";

    /**
     * Name under which a TranslationService component publishes information about itself.
     *
     * <p>This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#TranslationService translation-service}&gt;</code> tag.
     *
     * <p>Here's an example of how to use it on {@code AndroidManifest.xml}:
     * TODO: fill in doc example (check CCService/AFService).
     */
    public static final String SERVICE_META_DATA = "android.translation_service";

    private Handler mHandler;

    /**
     * Binder to receive calls from system server.
     */
    private final ITranslationService mInterface = new ITranslationService.Stub() {
        @Override
        public void onConnected() {
            mHandler.sendMessage(obtainMessage(TranslationService::onConnected,
                    TranslationService.this));
        }

        @Override
        public void onDisconnected() {
            mHandler.sendMessage(obtainMessage(TranslationService::onDisconnected,
                    TranslationService.this));
        }

        @Override
        public void onCreateTranslationSession(TranslationContext translationContext,
                int sessionId, IResultReceiver receiver) throws RemoteException {
            mHandler.sendMessage(obtainMessage(TranslationService::handleOnCreateTranslationSession,
                    TranslationService.this, translationContext, sessionId, receiver));
        }

        @Override
        public void onTranslationCapabilitiesRequest(@TranslationSpec.DataFormat int sourceFormat,
                @TranslationSpec.DataFormat int targetFormat,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            mHandler.sendMessage(
                    obtainMessage(TranslationService::handleOnTranslationCapabilitiesRequest,
                            TranslationService.this, sourceFormat, targetFormat,
                            resultReceiver));
        }
    };

    /**
     * Interface definition for a callback to be invoked when the translation is compleled.
     */
    public interface OnTranslationResultCallback {
        /**
         * Notifies the Android System that a translation request
         * {@link TranslationService#onTranslationRequest(TranslationRequest, int,
         * CancellationSignal, OnTranslationResultCallback)} was successfully fulfilled by the
         * service.
         *
         * <p>This method should always be called, even if the service cannot fulfill the request
         * (in which case it should be called with a TranslationResponse with
         * {@link android.view.translation.TranslationResponse#TRANSLATION_STATUS_UNKNOWN_ERROR},
         * or {@link android.view.translation.TranslationResponse
         * #TRANSLATION_STATUS_LANGUAGE_UNAVAILABLE}).
         *
         * @param response translation response for the provided request infos.
         *
         * @throws IllegalStateException if this method was already called.
         */
        void onTranslationSuccess(@NonNull TranslationResponse response);

        /**
         * TODO: implement javadoc
         */
        void onError();
    }

    /**
     * Binder that receives calls from the app.
     */
    private final ITranslationDirectManager mClientInterface =
            new ITranslationDirectManager.Stub() {
                // TODO: Implement cancellation signal
                @NonNull
                private final CancellationSignal mCancellationSignal = new CancellationSignal();

                @Override
                public void onTranslationRequest(TranslationRequest request, int sessionId,
                        ITranslationCallback callback)
                        throws RemoteException {
                    final OnTranslationResultCallback translationResultCallback =
                            new OnTranslationResultCallbackWrapper(callback);
                    mHandler.sendMessage(obtainMessage(TranslationService::onTranslationRequest,
                            TranslationService.this, request, sessionId, mCancellationSignal,
                            translationResultCallback));
                }

                @Override
                public void onFinishTranslationSession(int sessionId) throws RemoteException {
                    mHandler.sendMessage(obtainMessage(
                            TranslationService::onFinishTranslationSession,
                            TranslationService.this, sessionId));
                }
            };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        BaseBundle.setShouldDefuse(true);
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link TranslationService}.
     * It should not make calls on {@link TranslationManager} that requires the caller to be
     * the current service.
     */
    public void onDisconnected() {
    }

    /**
     * TODO: fill in javadoc.
     *
     * @param translationContext
     * @param sessionId
     */
    // TODO(b/176464808): the session id won't be unique cross client/server process. Need to find
    // solution to make it's safe.
    public abstract void onCreateTranslationSession(@NonNull TranslationContext translationContext,
            int sessionId);

    /**
     * TODO: fill in javadoc.
     *
     * @param sessionId
     */
    public abstract void onFinishTranslationSession(int sessionId);

    /**
     * TODO: fill in javadoc.
     *
     * @param request
     * @param sessionId
     * @param callback
     * @param cancellationSignal
     */
    public abstract void onTranslationRequest(@NonNull TranslationRequest request, int sessionId,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OnTranslationResultCallback callback);

    /**
     * TODO: fill in javadoc
     * TODO: make this abstract again once aiai is ready.
     *
     * <p>Must call {@code callback.accept} to pass back the set of translation capabilities.</p>
     *
     * @param sourceFormat
     * @param targetFormat
     * @param callback
     */
    public abstract void onTranslationCapabilitiesRequest(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull Consumer<Set<TranslationCapability>> callback);

    // TODO(b/176464808): Need to handle client dying case

    // TODO(b/176464808): Need to handle the failure case. e.g. if the context is not supported.

    private void handleOnCreateTranslationSession(@NonNull TranslationContext translationContext,
            int sessionId, IResultReceiver resultReceiver) {
        try {
            final Bundle extras = new Bundle();
            extras.putBinder(EXTRA_SERVICE_BINDER, mClientInterface.asBinder());
            extras.putInt(EXTRA_SESSION_ID, sessionId);
            resultReceiver.send(0, extras);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException sending client interface: " + e);
        }
        onCreateTranslationSession(translationContext, sessionId);
    }

    private void handleOnTranslationCapabilitiesRequest(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull ResultReceiver resultReceiver) {
        onTranslationCapabilitiesRequest(sourceFormat, targetFormat,
                new Consumer<Set<TranslationCapability>>() {
                    @Override
                    public void accept(Set<TranslationCapability> values) {
                        final ArraySet<TranslationCapability> capabilities = new ArraySet<>(values);
                        final Bundle bundle = new Bundle();
                        bundle.putParcelableArray(TranslationManager.EXTRA_CAPABILITIES,
                                capabilities.toArray(new TranslationCapability[0]));
                        resultReceiver.send(TranslationManager.STATUS_SYNC_CALL_SUCCESS, bundle);
                    }
                });
    }
}
