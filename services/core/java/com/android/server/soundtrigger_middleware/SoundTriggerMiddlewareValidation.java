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

package com.android.server.soundtrigger_middleware;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.PermissionChecker;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.media.soundtrigger_middleware.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This is a decorator of an {@link ISoundTriggerMiddlewareService}, which enforces permissions and
 * correct usage by the client, as well as makes sure that exceptions representing a server
 * malfunction do not get sent to the client.
 * <p>
 * This is intended to extract the non-business logic out of the underlying implementation and thus
 * make it easier to maintain each one of those separate aspects. A design trade-off is being made
 * here, in that this class would need to essentially eavesdrop on all the client-server
 * communication and retain all state known to the client, while the client doesn't necessarily care
 * about all of it, and while the server has its own representation of this information. However,
 * in this case, this is a small amount of data, and the benefits in code elegance seem worth it.
 * There is also some additional cost in employing a simplistic locking mechanism here, but
 * following the same line of reasoning, the benefits in code simplicity outweigh it.
 * <p>
 * Every public method in this class, overriding an interface method, must follow the following
 * pattern:
 * <code><pre>
 * @Override public T method(S arg) {
 *     // Permission check.
 *     checkPermissions();
 *     // Input validation.
 *     ValidationUtil.validateS(arg);
 *     synchronized (this) {
 *         // State validation.
 *         if (...state is not valid for this call...) {
 *             throw new IllegalStateException("State is invalid because...");
 *         }
 *         // From here on, every exception isn't client's fault.
 *         try {
 *             T result = mDelegate.method(arg);
 *             // Update state.;
 *             ...
 *             return result;
 *         } catch (Exception e) {
 *             throw handleException(e);
 *         }
 *     }
 * }
 * </pre></code>
 * Following this patterns ensures a consistent and rigorous handling of all aspects associated
 * with client-server separation.
 * <p>
 * <b>Exception handling approach:</b><br>
 * We make sure all client faults (permissions, argument and state validation) happen first, and
 * would throw {@link SecurityException}, {@link IllegalArgumentException}/
 * {@link NullPointerException} or {@link
 * IllegalStateException}, respectively. All those exceptions are treated specially by Binder and
 * will get sent back to the client.<br>
 * Once this is done, any subsequent fault is considered either a recoverable (expected) or
 * unexpected server fault. Those will be delivered to the client as a
 * {@link ServiceSpecificException}. {@link RecoverableException}s thrown by the implementation are
 * considered recoverable and will include a specific error code to indicate the problem. Any other
 * exceptions will use the INTERNAL_ERROR code. They may also cause the module to become invalid
 * asynchronously, and the client would be notified via the moduleDied() callback.
 *
 * {@hide}
 */
public class SoundTriggerMiddlewareValidation implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewareValidation";

    private enum ModuleStatus {
        ALIVE,
        DETACHED,
        DEAD
    };

    private class ModuleState {
        final @NonNull SoundTriggerModuleProperties properties;
        Set<ModuleService> sessions = new HashSet<>();

        private ModuleState(@NonNull SoundTriggerModuleProperties properties) {
            this.properties = properties;
        }
    }

    private Boolean mCaptureState;

    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;
    private final @NonNull Context mContext;
    private Map<Integer, ModuleState> mModules;

    public SoundTriggerMiddlewareValidation(
            @NonNull ISoundTriggerMiddlewareInternal delegate, @NonNull Context context) {
        mDelegate = delegate;
        mContext = context;
    }

    /**
     * Generic exception handling for exceptions thrown by the underlying implementation.
     *
     * Would throw any {@link RecoverableException} as a {@link ServiceSpecificException} (passed
     * by Binder to the caller) and <i>any other</i> exception as {@link InternalServerError}
     * (<b>not</b> passed by Binder to the caller).
     * <p>
     * Typical usage:
     * <code><pre>
     * try {
     *     ... Do server operations ...
     * } catch (Exception e) {
     *     throw handleException(e);
     * }
     * </pre></code>
     */
    static @NonNull
    RuntimeException handleException(@NonNull Exception e) {
        if (e instanceof RecoverableException) {
            throw new ServiceSpecificException(((RecoverableException) e).errorCode,
                    e.getMessage());
        }

        Log.wtf(TAG, "Unexpected exception", e);
        throw new ServiceSpecificException(Status.INTERNAL_ERROR, e.getMessage());
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        // Permission check.
        checkPermissions();
        // Input validation (always valid).

        synchronized (this) {
            // State validation (always valid).

            // From here on, every exception isn't client's fault.
            try {
                SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
                mModules = new HashMap<>(result.length);
                for (SoundTriggerModuleDescriptor desc : result) {
                    mModules.put(desc.handle, new ModuleState(desc.properties));
                }
                return result;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public @NonNull ISoundTriggerModule attach(int handle,
            @NonNull ISoundTriggerCallback callback) {
        // Permission check.
        checkPermissions();
        // Input validation.
        Objects.requireNonNull(callback);
        Objects.requireNonNull(callback.asBinder());

        synchronized (this) {
            // State validation.
            if (mModules == null) {
                throw new IllegalStateException(
                        "Client must call listModules() prior to attaching.");
            }
            if (!mModules.containsKey(handle)) {
                throw new IllegalArgumentException("Invalid handle: " + handle);
            }

            // From here on, every exception isn't client's fault.
            try {
                ModuleService moduleService =
                        new ModuleService(handle, callback);
                moduleService.attach(mDelegate.attach(handle, moduleService));
                return moduleService;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public void setCaptureState(boolean active) {
        // This is an internal call. No permissions needed.
        //
        // Normally, we would acquire a lock here. However, we do not access any state here so it
        // is safe to not lock. This call is typically done from a different context than all the
        // other calls and may result in a deadlock if we lock here (between the audio server and
        // the system server).
        try {
            mDelegate.setCaptureState(active);
        } catch (Exception e) {
            throw handleException(e);
        } finally {
            // It is safe to lock here - local operation.
            synchronized (this) {
                mCaptureState = active;
            }
        }
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    /**
     * Throws a {@link SecurityException} if caller permanently doesn't have the given permission,
     * or a {@link ServiceSpecificException} with a {@link Status#TEMPORARY_PERMISSION_DENIED} if
     * caller temporarily doesn't have the right permissions to use this service.
     */
    void checkPermissions() {
        enforcePermission(Manifest.permission.RECORD_AUDIO);
        enforcePermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
    }

    /**
     * Throws a {@link SecurityException} if caller permanently doesn't have the given permission,
     * or a {@link ServiceSpecificException} with a {@link Status#TEMPORARY_PERMISSION_DENIED} if
     * caller temporarily doesn't have the given permission.
     *
     * @param permission The permission to check.
     */
    void enforcePermission(String permission) {
        final int status = PermissionChecker.checkCallingOrSelfPermissionForPreflight(mContext,
                permission);
        switch (status) {
            case PermissionChecker.PERMISSION_GRANTED:
                return;
            case PermissionChecker.PERMISSION_HARD_DENIED:
                throw new SecurityException(
                        String.format("Caller must have the %s permission.", permission));
            case PermissionChecker.PERMISSION_SOFT_DENIED:
                throw new ServiceSpecificException(Status.TEMPORARY_PERMISSION_DENIED,
                        String.format("Caller must have the %s permission.", permission));
            default:
                throw new RuntimeException("Unexpected perimission check result.");
        }
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This implementation is not inteded to be used directly with Binder.");
    }

    @Override
    public void dump(PrintWriter pw) {
        synchronized (this) {
            pw.printf("Capture state is %s\n\n", mCaptureState == null ? "uninitialized"
                    : (mCaptureState ? "active" : "inactive"));
            if (mModules != null) {
                for (int handle : mModules.keySet()) {
                    final ModuleState module = mModules.get(handle);
                    pw.println("=========================================");
                    pw.printf("Module %d\n%s\n", handle,
                            ObjectPrinter.print(module.properties, true, 16));
                    pw.println("=========================================");
                    for (ModuleService session : module.sessions) {
                        session.dump(pw);
                    }
                }
            } else {
                pw.println("Modules have not yet been enumerated.");
            }
        }
        pw.println();

        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }

    /** State of a sound model. */
    static class ModelState {
        ModelState(SoundModel model) {
            this.description = ObjectPrinter.print(model, true, 16);
        }

        ModelState(PhraseSoundModel model) {
            this.description = ObjectPrinter.print(model, true, 16);
        }

        /** Activity state of a sound model. */
        enum Activity {
            /** Model is loaded, recognition is inactive. */
            LOADED,
            /** Model is loaded, recognition is active. */
            ACTIVE
        }

        /** Activity state. */
        Activity activityState = Activity.LOADED;

        /** Human-readable description of the model. */
        final String description;

        /**
         * A map of known parameter support. A missing key means we don't know yet whether the
         * parameter is supported. A null value means it is known to not be supported. A non-null
         * value indicates the valid value range.
         */
        private Map<Integer, ModelParameterRange> parameterSupport = new HashMap<>();

        /**
         * Check that the given parameter is known to be supported for this model.
         *
         * @param modelParam The parameter key.
         */
        void checkSupported(int modelParam) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
        }

        /**
         * Check that the given parameter is known to be supported for this model and that the given
         * value is a valid value for it.
         *
         * @param modelParam The parameter key.
         * @param value      The value.
         */
        void checkSupported(int modelParam, int value) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
            Preconditions.checkArgumentInRange(value, range.minInclusive, range.maxInclusive,
                    "value");
        }

        /**
         * Update support state for the given parameter for this model.
         *
         * @param modelParam The parameter key.
         * @param range      The parameter value range, or null if not supported.
         */
        void updateParameterSupport(int modelParam, @Nullable ModelParameterRange range) {
            parameterSupport.put(modelParam, range);
        }
    }

    /**
     * A wrapper around an {@link ISoundTriggerModule} implementation, to address the same aspects
     * mentioned in {@link SoundTriggerModule} above. This class follows the same conventions.
     */
    private class ModuleService extends ISoundTriggerModule.Stub implements ISoundTriggerCallback,
            IBinder.DeathRecipient {
        private final ISoundTriggerCallback mCallback;
        private ISoundTriggerModule mDelegate;
        private @NonNull Map<Integer, ModelState> mLoadedModels = new HashMap<>();
        private final int mHandle;
        private ModuleStatus mState = ModuleStatus.ALIVE;

        ModuleService(int handle, @NonNull ISoundTriggerCallback callback) {
            mCallback = callback;
            mHandle = handle;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        void attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
            mModules.get(mHandle).sessions.add(this);
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateGenericModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validatePhraseModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadPhraseModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.activityState
                        != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for unloading: " + modelState.activityState);
                }

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.unloadModel(modelHandle);
                    mLoadedModels.remove(modelHandle);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateRecognitionConfig(config);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.activityState
                        != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for starting recognition: "
                            + modelState.activityState);
                }

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.startRecognition(modelHandle, config);
                    modelState.activityState =
                            ModelState.Activity.ACTIVE;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // stopRecognition is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.stopRecognition(modelHandle);
                    modelState.activityState =
                            ModelState.Activity.LOADED;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // forceRecognitionEvent is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.forceRecognitionEvent(modelHandle);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam, value);

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.setModelParameter(modelHandle, modelParam, value);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam);

                // From here on, every exception isn't client's fault.
                try {
                    return mDelegate.getModelParameter(modelHandle, modelParam);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam) {
            // Permission check.
            checkPermissions();
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }

                // From here on, every exception isn't client's fault.
                try {
                    ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                            modelParam);
                    modelState.updateParameterSupport(modelParam, result);
                    return result;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void detach() {
            // Permission check.
            checkPermissions();
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has already been detached.");
                }
                if (mState == ModuleStatus.ALIVE && !mLoadedModels.isEmpty()) {
                    throw new IllegalStateException("Cannot detach while models are loaded.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    detachInternal();
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate.toString());
        }

        private void detachInternal() {
            try {
                mDelegate.detach();
                mState = ModuleStatus.DETACHED;
                mCallback.asBinder().unlinkToDeath(this, 0);
                mModules.get(mHandle).sessions.remove(this);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        void dump(PrintWriter pw) {
            if (mState == ModuleStatus.ALIVE) {
                pw.printf("Loaded models for session %s (handle, active)", toString());
                pw.println();
                pw.println("-------------------------------");
                for (Map.Entry<Integer, ModelState> entry : mLoadedModels.entrySet()) {
                    pw.print(entry.getKey());
                    pw.print('\t');
                    pw.print(entry.getValue().activityState.name());
                    pw.print('\t');
                    pw.print(entry.getValue().description);
                    pw.println();
                }
            } else {
                pw.printf("Session %s is dead", toString());
                pw.println();
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////////
        // Callbacks

        @Override
        public void onRecognition(int modelHandle, @NonNull RecognitionEvent event) {
            synchronized (SoundTriggerMiddlewareValidation.this) {
                if (event.status != RecognitionStatus.FORCED) {
                    mLoadedModels.get(modelHandle).activityState =
                            ModelState.Activity.LOADED;
                }
                try {
                    mCallback.onRecognition(modelHandle, event);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                }
            }
        }

        @Override
        public void onPhraseRecognition(int modelHandle, @NonNull PhraseRecognitionEvent event) {
            synchronized (SoundTriggerMiddlewareValidation.this) {
                if (event.common.status != RecognitionStatus.FORCED) {
                    mLoadedModels.get(modelHandle).activityState =
                            ModelState.Activity.LOADED;
                }
                try {
                    mCallback.onPhraseRecognition(modelHandle, event);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                }
            }
        }

        @Override
        public void onRecognitionAvailabilityChange(boolean available) {
            synchronized (SoundTriggerMiddlewareValidation.this) {
                try {
                    mCallback.onRecognitionAvailabilityChange(available);
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                }
            }
        }

        @Override
        public void onModuleDied() {
            synchronized (SoundTriggerMiddlewareValidation.this) {
                try {
                    mState = ModuleStatus.DEAD;
                    mCallback.onModuleDied();
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                }
            }
        }

        @Override
        public void binderDied() {
            // This is called whenever our client process dies.
            synchronized (SoundTriggerMiddlewareValidation.this) {
                try {
                    // Gracefully stop all active recognitions and unload the models.
                    for (Map.Entry<Integer, ModelState> entry :
                            mLoadedModels.entrySet()) {
                        if (entry.getValue().activityState
                                == ModelState.Activity.ACTIVE) {
                            mDelegate.stopRecognition(entry.getKey());
                        }
                        mDelegate.unloadModel(entry.getKey());
                    }
                    // Detach.
                    detachInternal();
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }
    }
}
