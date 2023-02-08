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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfo;
import android.util.Log;

import java.util.ArrayList;

/**
 * Central session for a single getCredentials request. This class listens to the
 * responses from providers, and the UX app, and updates the provider(S) state.
 */
public final class GetRequestSession extends RequestSession<GetCredentialRequest,
        IGetCredentialCallback>
        implements ProviderSession.ProviderInternalCallback<GetCredentialResponse> {
    private static final String TAG = "GetRequestSession";

    public GetRequestSession(Context context, int userId, int callingUid,
            IGetCredentialCallback callback, GetCredentialRequest request,
            CallingAppInfo callingAppInfo, CancellationSignal cancellationSignal) {
        super(context, userId, callingUid, request, callback, RequestInfo.TYPE_GET,
                callingAppInfo, cancellationSignal);
    }

    /**
     * Creates a new provider session, and adds it list of providers that are contributing to
     * this session.
     * @return the provider session created within this request session, for the given provider
     * info.
     */
    @Override
    @Nullable
    public ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService) {
        ProviderGetSession providerGetSession = ProviderGetSession
                .createNewSession(mContext, mUserId, providerInfo,
                        this, remoteCredentialService);
        if (providerGetSession != null) {
            Log.i(TAG, "In startProviderSession - provider session created and being added");
            mProviders.put(providerGetSession.getComponentName().flattenToString(),
                    providerGetSession);
        }
        return providerGetSession;
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        try {
            mClientCallback.onPendingIntent(mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newGetRequestInfo(
                    mRequestId, mClientRequest, mClientAppInfo.getPackageName()),
                    providerDataList));
        } catch (RemoteException e) {
            respondToClientWithErrorAndFinish(
                    GetCredentialException.TYPE_UNKNOWN, "Unable to instantiate selector");
        }
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable GetCredentialResponse response) {
        Log.i(TAG, "onFinalCredentialReceived from: " + componentName.flattenToString());
        if (response != null) {
            respondToClientWithResponseAndFinish(response);
        } else {
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                    "Invalid response from provider");
        }
    }

    //TODO: Try moving the three error & response methods below to RequestSession to be shared
    // between get & create.
    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    private void respondToClientWithResponseAndFinish(GetCredentialResponse response) {
        if (isSessionCancelled()) {
            // TODO: Differentiate btw cancelled and false
            logApiCalled(RequestType.GET_CREDENTIALS, /* isSuccessful */ false);
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            mClientCallback.onResponse(response);
            logApiCalled(RequestType.GET_CREDENTIALS, /* isSuccessful */ true);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client with a response : " + e.getMessage());
            logApiCalled(RequestType.GET_CREDENTIALS, /* isSuccessful */ false);
        }
        finishSession(/*propagateCancellation=*/false);
    }

    private void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        if (isSessionCancelled()) {
            logApiCalled(RequestType.GET_CREDENTIALS, /* isSuccessful */ false);
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            mClientCallback.onError(errorType, errorMsg);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client with error : " + e.getMessage());
        }
        logApiCalled(RequestType.GET_CREDENTIALS, /* isSuccessful */ false);
        finishSession(/*propagateCancellation=*/false);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        if (isUserCancellation) {
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_USER_CANCELED,
                    "User cancelled the selector");
        } else {
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_INTERRUPTED,
                    "The UI was interrupted - please try again.");
        }
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                    "No credentials to show on the selector.");
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        Log.i(TAG, "in onStatusChanged with status: " + status);
        if (!isAnyProviderPending()) {
            // If all provider responses have been received, we can either need the UI,
            // or we need to respond with error. The only other case is the entry being
            // selected after the UI has been invoked which has a separate code path.
            if (isUiInvocationNeeded()) {
                Log.i(TAG, "in onProviderStatusChanged - isUiInvocationNeeded");
                getProviderDataAndInitiateUi();
            } else {
                respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                        "No credentials available");
            }
        }
    }
}
