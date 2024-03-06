/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;

import androidx.annotation.IntDef;

import com.android.internal.R;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * Allows granted apps to manage on-device intelligence service configured on the device. Typical
 * calling pattern will be to query and setup a required feature before proceeding to request
 * processing.
 *
 * The contracts in this Manager class are designed to be open-ended in general, to allow
 * interoperability. Therefore, it is recommended that implementations of this system-service
 * expose this API to the clients via a separate sdk or library which has more defined contract.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.ON_DEVICE_INTELLIGENCE_SERVICE)
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public class OnDeviceIntelligenceManager {
    /**
     * @hide
     */
    public static final String API_VERSION_BUNDLE_KEY = "ApiVersionBundleKey";

    /**
     * @hide
     */
    public static final String AUGMENT_REQUEST_CONTENT_BUNDLE_KEY =
            "AugmentRequestContentBundleKey";
    private final Context mContext;
    private final IOnDeviceIntelligenceManager mService;

    /**
     * @hide
     */
    public OnDeviceIntelligenceManager(Context context, IOnDeviceIntelligenceManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Asynchronously get the version of the underlying remote implementation.
     *
     * @param versionConsumer  consumer to populate the version of remote implementation.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getVersion(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull LongConsumer versionConsumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                if (result == null) {
                    Binder.withCleanCallingIdentity(
                            () -> callbackExecutor.execute(() -> versionConsumer.accept(0)));
                }
                long version = result.getLong(API_VERSION_BUNDLE_KEY);
                Binder.withCleanCallingIdentity(
                        () -> callbackExecutor.execute(() -> versionConsumer.accept(version)));
            });
            mService.getVersion(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Get package name configured for providing the remote implementation for this system service.
     */
    @Nullable
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public String getRemoteServicePackageName() {
        String serviceConfigValue = mContext.getResources().getString(
                R.string.config_defaultOnDeviceSandboxedInferenceService);
        ComponentName componentName = ComponentName.unflattenFromString(serviceConfigValue);
        if (componentName != null) {
            return componentName.getPackageName();
        }

        return null;
    }

    /**
     * Asynchronously get feature for a given id.
     *
     * @param featureId        the identifier pointing to the feature.
     * @param featureReceiver  callback to populate the feature object for given identifier.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeature(
            int featureId,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<Feature, OnDeviceIntelligenceManagerException> featureReceiver) {
        try {
            IFeatureCallback callback =
                    new IFeatureCallback.Stub() {
                        @Override
                        public void onSuccess(Feature result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onError(
                                            new OnDeviceIntelligenceManagerException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.getFeature(featureId, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously get a list of features that are supported for the caller.
     *
     * @param featureListReceiver callback to populate the list of features.
     * @param callbackExecutor    executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void listFeatures(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<List<Feature>, OnDeviceIntelligenceManagerException> featureListReceiver) {
        try {
            IListFeaturesCallback callback =
                    new IListFeaturesCallback.Stub() {
                        @Override
                        public void onSuccess(List<Feature> result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onError(
                                            new OnDeviceIntelligenceManagerException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.listFeatures(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method should be used to fetch details about a feature which need some additional
     * computation, that can be inefficient to return in all calls to {@link #getFeature}. Callers
     * and implementation can utilize the {@link Feature#getFeatureParams()} to pass hint on what
     * details are expected by the caller.
     *
     * @param feature                the feature to check status for.
     * @param featureDetailsReceiver callback to populate the feature details to.
     * @param callbackExecutor       executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeatureDetails(@NonNull Feature feature,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<FeatureDetails, OnDeviceIntelligenceManagerException> featureDetailsReceiver) {
        try {
            IFeatureDetailsCallback callback = new IFeatureDetailsCallback.Stub() {

                @Override
                public void onSuccess(FeatureDetails result) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onResult(result)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onError(
                                    new OnDeviceIntelligenceManagerException(errorCode,
                                            errorMessage, errorParams))));
                }
            };
            mService.getFeatureDetails(feature, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method handles downloading all model and config files required to process requests
     * sent against a given feature. The caller can listen to updates on the download status via
     * the callback.
     *
     * Note: If a feature was already requested for downloaded previously, the onDownloadFailed
     * callback would be invoked with {@link DownloadCallback#DOWNLOAD_FAILURE_STATUS_DOWNLOADING}.
     * In such cases, clients should query the feature status via {@link #getFeatureStatus} to
     * check
     * on the feature's download status.
     *
     * @param feature            feature to request download for.
     * @param callback           callback to populate updates about download status.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestFeatureDownload(@NonNull Feature feature,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull DownloadCallback callback) {
        try {
            IDownloadCallback downloadCallback = new IDownloadCallback.Stub() {

                @Override
                public void onDownloadStarted(long bytesToDownload) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadStarted(bytesToDownload)));
                }

                @Override
                public void onDownloadProgress(long bytesDownloaded) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadProgress(bytesDownloaded)));
                }

                @Override
                public void onDownloadFailed(int failureStatus, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadFailed(failureStatus, errorMessage,
                                    errorParams)));
                }

                @Override
                public void onDownloadCompleted(PersistableBundle downloadParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> onDownloadCompleted(downloadParams)));
                }
            };

            ICancellationSignal transport = null;
            if (cancellationSignal != null) {
                transport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(transport);
            }

            mService.requestFeatureDownload(feature, transport, downloadCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * The methods computes the token related information for a given request payload using the
     * provided {@link Feature}.
     *
     * @param feature            feature associated with the request.
     * @param request            request that contains the content data and associated params.
     * @param outcomeReceiver    callback to populate the token info or exception in case of
     *                           failure.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestTokenInfo(@NonNull Feature feature, @NonNull Content request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<TokenInfo,
                    OnDeviceIntelligenceManagerException> outcomeReceiver) {
        try {
            ITokenInfoCallback callback = new ITokenInfoCallback.Stub() {
                @Override
                public void onSuccess(TokenInfo tokenInfo) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onResult(tokenInfo)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onError(
                                    new OnDeviceIntelligenceManagerProcessingException(
                                            errorCode, errorMessage, errorParams))));
                }
            };

            ICancellationSignal transport = null;
            if (cancellationSignal != null) {
                transport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(transport);
            }

            mService.requestTokenInfo(feature, request, transport, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Asynchronously Process a request based on the associated params, to populate a
     * response in
     * {@link OutcomeReceiver#onResult} callback or failure callback status code if there
     * was a
     * failure.
     *
     * @param feature            feature associated with the request.
     * @param request            request that contains the Content data and
     *                           associated params.
     * @param requestType        type of request being sent for processing the content.
     * @param cancellationSignal signal to invoke cancellation.
     * @param processingSignal   signal to send custom signals in the
     *                           remote implementation.
     * @param callbackExecutor   executor to run the callback on.
     * @param responseCallback   callback to populate the response content and
     *                           associated params.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)

    public void processRequest(@NonNull Feature feature, @Nullable Content request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull ProcessingOutcomeReceiver responseCallback) {
        try {
            IResponseCallback callback = new IResponseCallback.Stub() {
                @Override
                public void onSuccess(Content result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(() -> responseCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> responseCallback.onError(
                                    new OnDeviceIntelligenceManagerProcessingException(
                                            errorCode, errorMessage, errorParams))));
                }

                @Override
                public void onDataAugmentRequest(@NonNull Content content,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> responseCallback.onDataAugmentRequest(content, result -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY, result);
                                callbackExecutor.execute(() -> contentCallback.sendResult(bundle));
                            })));
                }
            };


            IProcessingSignal transport = null;
            if (processingSignal != null) {
                transport = ProcessingSignal.createTransport();
                processingSignal.setRemote(transport);
            }

            ICancellationSignal cancellationTransport = null;
            if (cancellationSignal != null) {
                cancellationTransport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(cancellationTransport);
            }

            mService.processRequest(feature, request, requestType, cancellationTransport, transport,
                    callback);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Variation of {@link #processRequest} that asynchronously processes a request in a
     * streaming
     * fashion, where new content is pushed to caller in chunks via the
     * {@link StreamedProcessingOutcomeReceiver#onNewContent}. After the streaming is complete,
     * the service should call {@link StreamedProcessingOutcomeReceiver#onResult} and can optionally
     * populate the complete the full response {@link Content} as part of the callback in cases
     * when the final response contains an enhanced aggregation of the Contents already
     * streamed.
     *
     * @param feature                   feature associated with the request.
     * @param request                   request that contains the Content data and associated
     *                                  params.
     * @param requestType               type of request being sent for processing the content.
     * @param cancellationSignal        signal to invoke cancellation.
     * @param processingSignal          signal to send custom signals in the
     *                                  remote implementation.
     * @param streamingResponseCallback streaming callback to populate the response content and
     *                                  associated params.
     * @param callbackExecutor          executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void processRequestStreaming(@NonNull Feature feature, @Nullable Content request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull StreamedProcessingOutcomeReceiver streamingResponseCallback) {
        try {
            IStreamingResponseCallback callback = new IStreamingResponseCallback.Stub() {
                @Override
                public void onNewContent(Content result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingResponseCallback.onNewContent(result));
                    });
                }

                @Override
                public void onSuccess(Content result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingResponseCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingResponseCallback.onError(
                                        new OnDeviceIntelligenceManagerProcessingException(
                                                errorCode, errorMessage, errorParams)));
                    });
                }


                @Override
                public void onDataAugmentRequest(@NonNull Content content,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> streamingResponseCallback.onDataAugmentRequest(content,
                                    contentResponse -> {
                                        Bundle bundle = new Bundle();
                                        bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY,
                                                contentResponse);
                                        callbackExecutor.execute(
                                                () -> contentCallback.sendResult(bundle));
                                    })));
                }
            };

            IProcessingSignal transport = null;
            if (processingSignal != null) {
                transport = ProcessingSignal.createTransport();
                processingSignal.setRemote(transport);
            }

            ICancellationSignal cancellationTransport = null;
            if (cancellationSignal != null) {
                cancellationTransport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(cancellationTransport);
            }

            mService.processRequestStreaming(
                    feature, request, requestType, cancellationTransport, transport, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** Request inference with provided Content and Params. */
    public static final int REQUEST_TYPE_INFERENCE = 0;

    /**
     * Prepares the remote implementation environment for e.g.loading inference runtime etc
     * .which
     * are time consuming beforehand to remove overhead and allow quick processing of requests
     * thereof.
     */
    public static final int REQUEST_TYPE_PREPARE = 1;

    /** Request Embeddings of the passed-in Content. */
    public static final int REQUEST_TYPE_EMBEDDINGS = 2;

    /**
     * @hide
     */
    @IntDef(value = {
            REQUEST_TYPE_INFERENCE,
            REQUEST_TYPE_PREPARE,
            REQUEST_TYPE_EMBEDDINGS
    }, open = true)
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER,
            ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestType {
    }


    /**
     * Exception type to be populated in callbacks to the methods under
     * {@link OnDeviceIntelligenceManager}.
     */
    public static class OnDeviceIntelligenceManagerException extends Exception {
        /**
         * Error code returned when the OnDeviceIntelligenceManager service is unavailable.
         */
        public static final int ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE = 1000;

        /**
         * Error code to be used for on device intelligence manager failures.
         *
         * @hide
         */
        @IntDef(
                value = {
                        ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE
                }, open = true)
        @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
        @interface OnDeviceIntelligenceManagerErrorCode {
        }

        private final int mErrorCode;
        private final PersistableBundle errorParams;

        public OnDeviceIntelligenceManagerException(
                @OnDeviceIntelligenceManagerErrorCode int errorCode, @NonNull String errorMessage,
                @NonNull PersistableBundle errorParams) {
            super(errorMessage);
            this.mErrorCode = errorCode;
            this.errorParams = errorParams;
        }

        public OnDeviceIntelligenceManagerException(
                @OnDeviceIntelligenceManagerErrorCode int errorCode,
                @NonNull PersistableBundle errorParams) {
            this.mErrorCode = errorCode;
            this.errorParams = errorParams;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        @NonNull
        public PersistableBundle getErrorParams() {
            return errorParams;
        }
    }

    /**
     * Exception type to be populated in callbacks to the methods under
     * {@link OnDeviceIntelligenceManager#processRequest} or
     * {@link OnDeviceIntelligenceManager#processRequestStreaming} .
     */
    public static class OnDeviceIntelligenceManagerProcessingException extends
            OnDeviceIntelligenceManagerException {

        public static final int PROCESSING_ERROR_UNKNOWN = 1;

        /** Request passed contains bad data for e.g. format. */
        public static final int PROCESSING_ERROR_BAD_DATA = 2;

        /** Bad request for inputs. */
        public static final int PROCESSING_ERROR_BAD_REQUEST = 3;

        /** Whole request was classified as not safe, and no response will be generated. */
        public static final int PROCESSING_ERROR_REQUEST_NOT_SAFE = 4;

        /** Underlying processing encountered an error and failed to compute results. */
        public static final int PROCESSING_ERROR_COMPUTE_ERROR = 5;

        /** Encountered an error while performing IPC */
        public static final int PROCESSING_ERROR_IPC_ERROR = 6;

        /** Request was cancelled either by user signal or by the underlying implementation. */
        public static final int PROCESSING_ERROR_CANCELLED = 7;

        /** Underlying processing in the remote implementation is not available. */
        public static final int PROCESSING_ERROR_NOT_AVAILABLE = 8;

        /** The service is currently busy. Callers should retry with exponential backoff. */
        public static final int PROCESSING_ERROR_BUSY = 9;

        /** Something went wrong with safety classification service. */
        public static final int PROCESSING_ERROR_SAFETY_ERROR = 10;

        /** Response generated was classified unsafe. */
        public static final int PROCESSING_ERROR_RESPONSE_NOT_SAFE = 11;

        /** Request is too large to be processed. */
        public static final int PROCESSING_ERROR_REQUEST_TOO_LARGE = 12;

        /** Inference suspended so that higher-priority inference can run. */
        public static final int PROCESSING_ERROR_SUSPENDED = 13;

        /**
         * Underlying processing encountered an internal error, like a violated precondition
         * .
         */
        public static final int PROCESSING_ERROR_INTERNAL = 14;

        /**
         * The processing was not able to be passed on to the remote implementation, as the
         * service
         * was unavailable.
         */
        public static final int PROCESSING_ERROR_SERVICE_UNAVAILABLE = 15;

        /**
         * Error code of failed processing request.
         *
         * @hide
         */
        @IntDef(
                value = {
                        PROCESSING_ERROR_UNKNOWN,
                        PROCESSING_ERROR_BAD_DATA,
                        PROCESSING_ERROR_BAD_REQUEST,
                        PROCESSING_ERROR_REQUEST_NOT_SAFE,
                        PROCESSING_ERROR_COMPUTE_ERROR,
                        PROCESSING_ERROR_IPC_ERROR,
                        PROCESSING_ERROR_CANCELLED,
                        PROCESSING_ERROR_NOT_AVAILABLE,
                        PROCESSING_ERROR_BUSY,
                        PROCESSING_ERROR_SAFETY_ERROR,
                        PROCESSING_ERROR_RESPONSE_NOT_SAFE,
                        PROCESSING_ERROR_REQUEST_TOO_LARGE,
                        PROCESSING_ERROR_SUSPENDED,
                        PROCESSING_ERROR_INTERNAL,
                        PROCESSING_ERROR_SERVICE_UNAVAILABLE
                }, open = true)
        @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
        @interface ProcessingError {
        }

        public OnDeviceIntelligenceManagerProcessingException(
                @ProcessingError int errorCode, @NonNull String errorMessage,
                @NonNull PersistableBundle errorParams) {
            super(errorCode, errorMessage, errorParams);
        }

        public OnDeviceIntelligenceManagerProcessingException(
                @ProcessingError int errorCode,
                @NonNull PersistableBundle errorParams) {
            super(errorCode, errorParams);
        }
    }
}
