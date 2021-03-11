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

package com.android.server.speech;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.IRecognitionServiceManagerCallback;
import android.speech.SpeechRecognizer;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

import com.google.android.collect.Sets;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SpeechRecognitionManagerServiceImpl extends
        AbstractPerUserSystemService<SpeechRecognitionManagerServiceImpl,
            SpeechRecognitionManagerService> {
    private static final String TAG = SpeechRecognitionManagerServiceImpl.class.getSimpleName();

    private static final int MAX_CONCURRENT_CONNECTIONS_BY_CLIENT = 10;

    private final Object mLock = new Object();

    @NonNull
    @GuardedBy("mLock")
    private final Map<Integer, Set<RemoteSpeechRecognitionService>> mRemoteServicesByUid =
            new HashMap<>();

    SpeechRecognitionManagerServiceImpl(
            @NonNull SpeechRecognitionManagerService master,
            @NonNull Object lock, @UserIdInt int userId, boolean disabled) {
        super(master, lock, userId);
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        try {
            return AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        return enabledChanged;
    }

    void createSessionLocked(
            ComponentName componentName,
            IBinder clientToken,
            boolean onDevice,
            IRecognitionServiceManagerCallback callback) {
        if (mMaster.debug) {
            Slog.i(TAG, String.format("#createSessionLocked, component=%s, onDevice=%s",
                    componentName, onDevice));
        }

        ComponentName serviceComponent = componentName;
        if (onDevice) {
            serviceComponent = getOnDeviceComponentNameLocked();
        }

        if (serviceComponent == null) {
            if (mMaster.debug) {
                Slog.i(TAG, "Service component is undefined, responding with error.");
            }
            tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        final int creatorCallingUid = Binder.getCallingUid();
        Set<String> creatorPackageNames =
                Sets.newArraySet(
                        getContext().getPackageManager().getPackagesForUid(creatorCallingUid));

        RemoteSpeechRecognitionService service = createService(creatorCallingUid, serviceComponent);

        if (service == null) {
            tryRespondWithError(callback, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS);
            return;
        }

        IBinder.DeathRecipient deathRecipient =
                () -> handleClientDeath(creatorCallingUid, service, true /* invoke #cancel */);

        try {
            clientToken.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            // RemoteException == binder already died, schedule disconnect anyway.
            handleClientDeath(creatorCallingUid, service, true /* invoke #cancel */);
        }

        service.connect().thenAccept(binderService -> {
            if (binderService != null) {
                try {
                    callback.onSuccess(new IRecognitionService.Stub() {
                        @Override
                        public void startListening(
                                Intent recognizerIntent,
                                IRecognitionListener listener,
                                String packageName,
                                String featureId,
                                int callingUid) throws RemoteException {
                            verifyCallerIdentity(
                                    creatorCallingUid, packageName, creatorPackageNames, listener);
                            if (callingUid != creatorCallingUid) {
                                listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
                                return;
                            }

                            service.startListening(
                                    recognizerIntent, listener, packageName, featureId);
                        }

                        @Override
                        public void stopListening(
                                IRecognitionListener listener,
                                String packageName,
                                String featureId) throws RemoteException {
                            verifyCallerIdentity(
                                    creatorCallingUid, packageName, creatorPackageNames, listener);

                            service.stopListening(listener, packageName, featureId);
                        }

                        @Override
                        public void cancel(
                                IRecognitionListener listener,
                                String packageName,
                                String featureId,
                                boolean isShutdown) throws RemoteException {
                            verifyCallerIdentity(
                                    creatorCallingUid, packageName, creatorPackageNames, listener);

                            service.cancel(listener, packageName, featureId, isShutdown);

                            if (isShutdown) {
                                handleClientDeath(
                                        creatorCallingUid,
                                        service,
                                        false /* invoke #cancel */);
                                clientToken.unlinkToDeath(deathRecipient, 0);
                            }
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error creating a speech recognition session", e);
                    tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
                }
            } else {
                tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
            }
        });
    }

    private void verifyCallerIdentity(
            int creatorCallingUid,
            String packageName,
            Set<String> creatorPackageNames,
            IRecognitionListener listener) throws RemoteException {
        if (creatorCallingUid != Binder.getCallingUid()
                || !creatorPackageNames.contains(packageName)) {
            listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        }
    }

    private void handleClientDeath(
            int callingUid,
            RemoteSpeechRecognitionService service, boolean invokeCancel) {
        if (invokeCancel) {
            service.shutdown();
        }
        removeService(callingUid, service);
    }

    @GuardedBy("mLock")
    @Nullable
    private ComponentName getOnDeviceComponentNameLocked() {
        final String serviceName = getComponentNameLocked();
        if (mMaster.debug) {
            Slog.i(TAG, "Resolved component name: " + serviceName);
        }

        if (serviceName == null) {
            if (mMaster.verbose) {
                Slog.v(TAG, "ensureRemoteServiceLocked(): no service component name.");
            }
            return null;
        }
        return ComponentName.unflattenFromString(serviceName);
    }

    private RemoteSpeechRecognitionService createService(
            int callingUid, ComponentName serviceComponent) {
        synchronized (mLock) {
            Set<RemoteSpeechRecognitionService> servicesForClient =
                    mRemoteServicesByUid.get(callingUid);

            if (servicesForClient != null
                    && servicesForClient.size() >= MAX_CONCURRENT_CONNECTIONS_BY_CLIENT) {
                return null;
            }

            if (servicesForClient != null) {
                Optional<RemoteSpeechRecognitionService> existingService =
                        servicesForClient
                                .stream()
                                .filter(service ->
                                        service.getServiceComponentName().equals(serviceComponent))
                                .findFirst();
                if (existingService.isPresent()) {

                    if (mMaster.debug) {
                        Slog.i(TAG, "Reused existing connection to " + serviceComponent);
                    }

                    return existingService.get();
                }
            }

            RemoteSpeechRecognitionService service =
                    new RemoteSpeechRecognitionService(
                            getContext(), serviceComponent, getUserId(), callingUid);

            Set<RemoteSpeechRecognitionService> valuesByCaller =
                    mRemoteServicesByUid.computeIfAbsent(callingUid, key -> new HashSet<>());
            valuesByCaller.add(service);

            if (mMaster.debug) {
                Slog.i(TAG, "Creating a new connection to " + serviceComponent);
            }

            return service;
        }
    }

    private void removeService(int callingUid, RemoteSpeechRecognitionService service) {
        synchronized (mLock) {
            Set<RemoteSpeechRecognitionService> valuesByCaller =
                    mRemoteServicesByUid.get(callingUid);
            if (valuesByCaller != null) {
                valuesByCaller.remove(service);
            }
        }
    }

    private static void tryRespondWithError(IRecognitionServiceManagerCallback callback,
            int errorCode) {
        try {
            callback.onError(errorCode);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to respond with error");
        }
    }
}
