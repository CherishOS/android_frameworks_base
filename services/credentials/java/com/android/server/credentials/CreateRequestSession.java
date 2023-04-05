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

package com.android.server.credentials;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CreateCredentialResponse;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.PermissionUtils;
import android.util.Log;

import com.android.server.credentials.metrics.ProviderStatusForMetrics;

import java.util.ArrayList;

/**
 * Central session for a single {@link CredentialManager#createCredential} request.
 * This class listens to the responses from providers, and the UX app, and updates the
 * provider(s) state maintained in {@link ProviderCreateSession}.
 */
public final class CreateRequestSession extends RequestSession<CreateCredentialRequest,
        ICreateCredentialCallback, CreateCredentialResponse>
        implements ProviderSession.ProviderInternalCallback<CreateCredentialResponse> {
    private static final String TAG = "CreateRequestSession";

    CreateRequestSession(@NonNull Context context, RequestSession.SessionLifetime sessionCallback,
            Object lock, int userId, int callingUid,
            CreateCredentialRequest request,
            ICreateCredentialCallback callback,
            CallingAppInfo callingAppInfo,
            CancellationSignal cancellationSignal,
            long startedTimestamp) {
        super(context, sessionCallback, lock, userId, callingUid, request, callback,
                RequestInfo.TYPE_CREATE,
                callingAppInfo, cancellationSignal, startedTimestamp);
    }

    /**
     * Creates a new provider session, and adds it to list of providers that are contributing to
     * this request session.
     *
     * @return the provider session that was started
     */
    @Override
    @Nullable
    public ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService) {
        ProviderCreateSession providerCreateSession = ProviderCreateSession
                .createNewSession(mContext, mUserId, providerInfo,
                        this, remoteCredentialService);
        if (providerCreateSession != null) {
            Log.i(TAG, "In startProviderSession - provider session created and being added");
            mProviders.put(providerCreateSession.getComponentName().flattenToString(),
                    providerCreateSession);
        }
        return providerCreateSession;
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        mRequestSessionMetric.collectUiCallStartTime(System.nanoTime());
        mCredentialManagerUi.setStatus(CredentialManagerUi.UiStatus.USER_INTERACTION);
        try {
            mClientCallback.onPendingIntent(mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newCreateRequestInfo(
                            mRequestId, mClientRequest,
                            mClientAppInfo.getPackageName(),
                            PermissionUtils.hasPermission(mContext, mClientAppInfo.getPackageName(),
                                    Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)),
                    providerDataList));
        } catch (RemoteException e) {
            mRequestSessionMetric.collectUiReturnedFinalPhase(/*uiReturned=*/ false);
            mCredentialManagerUi.setStatus(CredentialManagerUi.UiStatus.TERMINATED);
            respondToClientWithErrorAndFinish(
                    CreateCredentialException.TYPE_UNKNOWN,
                    "Unable to invoke selector");
        }
    }

    @Override
    protected void invokeClientCallbackSuccess(CreateCredentialResponse response)
            throws RemoteException {
        mClientCallback.onResponse(response);
    }

    @Override
    protected void invokeClientCallbackError(String errorType, String errorMsg)
            throws RemoteException {
        mClientCallback.onError(errorType, errorMsg);
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable CreateCredentialResponse response) {
        Log.i(TAG, "onFinalCredentialReceived from: " + componentName.flattenToString());
        mRequestSessionMetric.collectUiResponseData(/*uiReturned=*/ true, System.nanoTime());
        mRequestSessionMetric.collectChosenMetricViaCandidateTransfer(mProviders.get(
                componentName.flattenToString()).mProviderSessionMetric
                .getCandidatePhasePerProviderMetric());
        if (response != null) {
            mRequestSessionMetric.collectChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_SUCCESS.getMetricCode());
            respondToClientWithResponseAndFinish(response);
        } else {
            mRequestSessionMetric.collectChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_FAILURE.getMetricCode());
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                    "Invalid response");
        }
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        if (isUserCancellation) {
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_USER_CANCELED,
                    "User cancelled the selector");
        } else {
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_INTERRUPTED,
                    "The UI was interrupted - please try again.");
        }
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                "No create options available.");
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName, ProviderSession.CredentialsSource source) {
        Log.i(TAG, "in onProviderStatusChanged with status: " + status);
        // If all provider responses have been received, we can either need the UI,
        // or we need to respond with error. The only other case is the entry being
        // selected after the UI has been invoked which has a separate code path.
        if (!isAnyProviderPending()) {
            if (isUiInvocationNeeded()) {
                Log.i(TAG, "in onProviderStatusChanged - isUiInvocationNeeded");
                getProviderDataAndInitiateUi();
            } else {
                respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                        "No create options available.");
            }
        }
    }
}
