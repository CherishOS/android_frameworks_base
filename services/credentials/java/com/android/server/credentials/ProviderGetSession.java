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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.credentials.Credential;
import android.credentials.GetCredentialOption;
import android.credentials.GetCredentialResponse;
import android.credentials.ui.Entry;
import android.credentials.ui.GetCredentialProviderData;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.service.credentials.Action;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.CredentialsResponseContent;
import android.service.credentials.GetCredentialsRequest;
import android.service.credentials.GetCredentialsResponse;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central provider session that listens for provider callbacks, and maintains provider state.
 * Will likely split this into remote response state and UI state.
 *
 * @hide
 */
public final class ProviderGetSession extends ProviderSession<GetCredentialsRequest,
        GetCredentialsResponse>
        implements
        RemoteCredentialService.ProviderCallbacks<GetCredentialsResponse> {
    private static final String TAG = "ProviderGetSession";

    // Key to be used as an entry key for a credential entry
    private static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    // Key to be used as the entry key for an action entry
    private static final String ACTION_ENTRY_KEY = "action_key";
    // Key to be used as the entry key for the authentication entry
    private static final String AUTHENTICATION_ACTION_ENTRY_KEY = "authentication_action_key";

    @NonNull
    private final Map<String, CredentialEntry> mUiCredentialEntries = new HashMap<>();
    @NonNull
    private final Map<String, Action> mUiActionsEntries = new HashMap<>();
    @Nullable
    private Pair<String, Action> mUiAuthenticationAction = null;

    /** Creates a new provider session to be used by the request session. */
    @Nullable public static ProviderGetSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            GetRequestSession getRequestSession,
            RemoteCredentialService remoteCredentialService) {
        GetCredentialsRequest providerRequest =
                createProviderRequest(providerInfo.getCapabilities(),
                        getRequestSession.mClientRequest,
                        getRequestSession.mClientCallingPackage);
        if (providerRequest != null) {
            return new ProviderGetSession(context, providerInfo, getRequestSession, userId,
                    remoteCredentialService, providerRequest);
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }

    @Nullable
    private static GetCredentialsRequest createProviderRequest(List<String> providerCapabilities,
            android.credentials.GetCredentialRequest clientRequest,
            String clientCallingPackage) {
        List<GetCredentialOption> filteredOptions = new ArrayList<>();
        for (GetCredentialOption option : clientRequest.getGetCredentialOptions()) {
            if (providerCapabilities.contains(option.getType())) {
                Log.i(TAG, "In createProviderRequest - capability found : "
                        + option.getType());
                filteredOptions.add(option);
            } else {
                Log.i(TAG, "In createProviderRequest - capability not "
                        + "found : " + option.getType());
            }
        }
        if (!filteredOptions.isEmpty()) {
            return new GetCredentialsRequest.Builder(clientCallingPackage).setGetCredentialOptions(
                    filteredOptions).build();
        }
        Log.i(TAG, "In createProviderRequest - returning null");
        return null;
    }

    public ProviderGetSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            GetCredentialsRequest request) {
        super(context, info, request, callbacks, userId, remoteCredentialService);
        setStatus(Status.PENDING);
    }

    /** Returns the credential entry maintained in state by this provider session. */
    @Nullable
    public CredentialEntry getCredentialEntry(@NonNull String entryId) {
        return mUiCredentialEntries.get(entryId);
    }

    /** Called when the provider response has been updated by an external source. */
    @Override // Callback from the remote provider
    public void onProviderResponseSuccess(@Nullable GetCredentialsResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        onUpdateResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override // Callback from the remote provider
    public void onProviderResponseFailure(int errorCode, @Nullable CharSequence message) {
        updateStatusAndInvokeCallback(toStatus(errorCode));
    }

    /** Called when provider service dies. */
    @Override // Callback from the remote provider
    public void onProviderServiceDied(RemoteCredentialService service) {
        if (service.getComponentName().equals(mProviderInfo.getServiceInfo().getComponentName())) {
            updateStatusAndInvokeCallback(Status.SERVICE_DEAD);
        } else {
            Slog.i(TAG, "Component names different in onProviderServiceDied - "
                    + "this should not happen");
        }
    }

    @Override // Selection call from the request provider
    protected void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        switch (entryType) {
            case CREDENTIAL_ENTRY_KEY:
                CredentialEntry credentialEntry = mUiCredentialEntries.get(entryKey);
                if (credentialEntry == null) {
                    Log.i(TAG, "Credential entry not found");
                    //TODO: Handle properly
                    return;
                }
                onCredentialEntrySelected(credentialEntry, providerPendingIntentResponse);
                break;
            case ACTION_ENTRY_KEY:
                Action actionEntry = mUiActionsEntries.get(entryKey);
                if (actionEntry == null) {
                    Log.i(TAG, "Action entry not found");
                    //TODO: Handle properly
                    return;
                }
                onActionEntrySelected(providerPendingIntentResponse);
                break;
            case AUTHENTICATION_ACTION_ENTRY_KEY:
                if (mUiAuthenticationAction.first.equals(entryKey)) {
                    onAuthenticationEntrySelected(providerPendingIntentResponse);
                } else {
                    //TODO: Handle properly
                    Log.i(TAG, "Authentication entry not found");
                }
                break;
            case REMOTE_ENTRY_KEY:
                if (mUiRemoteEntry.first.equals(entryKey)) {
                    onRemoteEntrySelected(providerPendingIntentResponse);
                } else {
                    //TODO: Handle properly
                    Log.i(TAG, "Remote entry not found");
                }
                break;
            default:
                Log.i(TAG, "Unsupported entry type selected");
        }
    }

    @Override // Call from request session to data to be shown on the UI
    @Nullable protected GetCredentialProviderData prepareUiData() throws IllegalArgumentException {
        Log.i(TAG, "In prepareUiData");
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Log.i(TAG, "In prepareUiData - provider does not want to show UI: "
                    + mComponentName.flattenToString());
            return null;
        }
        if (mProviderResponse == null) {
            Log.i(TAG, "In prepareUiData response null");
            throw new IllegalStateException("Response must be in completion mode");
        }
        if (mProviderResponse.getAuthenticationAction() != null) {
            Log.i(TAG, "In prepareUiData - top level authentication mode");
            return prepareUiProviderData(null, null,
                    prepareUiAuthenticationAction(mProviderResponse.getAuthenticationAction()),
                    /*remoteEntry=*/null);
        }
        if (mProviderResponse.getCredentialsResponseContent() != null) {
            Log.i(TAG, "In prepareUiData credentialsResponseContent not null");
            return prepareUiProviderData(prepareUiActionEntries(
                            mProviderResponse.getCredentialsResponseContent().getActions()),
                    prepareUiCredentialEntries(mProviderResponse.getCredentialsResponseContent()
                            .getCredentialEntries()),
                    /*authenticationAction=*/null,
                    prepareUiRemoteEntry(mProviderResponse
                            .getCredentialsResponseContent().getRemoteCredentialEntry()));
        }
        return null;
    }

    private Entry prepareUiRemoteEntry(CredentialEntry remoteCredentialEntry) {
        if (remoteCredentialEntry == null) {
            return null;
        }
        String entryId = generateEntryId();
        Entry remoteEntry = new Entry(REMOTE_ENTRY_KEY, entryId, remoteCredentialEntry.getSlice());
        mUiRemoteEntry = new Pair<>(entryId, remoteCredentialEntry);
        return remoteEntry;
    }

    private Entry prepareUiAuthenticationAction(@NonNull Action authenticationAction) {
        String entryId = generateEntryId();
        Entry authEntry = new Entry(
                AUTHENTICATION_ACTION_ENTRY_KEY, entryId, authenticationAction.getSlice(),
                authenticationAction.getPendingIntent(), /*fillInIntent=*/null);
        mUiAuthenticationAction = new Pair<>(entryId, authenticationAction);
        return authEntry;
    }

    private List<Entry> prepareUiCredentialEntries(@NonNull
            List<CredentialEntry> credentialEntries) {
        Log.i(TAG, "in prepareUiProviderDataWithCredentials");
        List<Entry> credentialUiEntries = new ArrayList<>();

        // Populate the credential entries
        for (CredentialEntry credentialEntry : credentialEntries) {
            String entryId = generateEntryId();
            mUiCredentialEntries.put(entryId, credentialEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            if (credentialEntry.getPendingIntent() != null) {
                credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                        credentialEntry.getSlice(), credentialEntry.getPendingIntent(),
                        /*fillInIntent=*/null));
            } else if (credentialEntry.getCredential() != null) {
                credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                        credentialEntry.getSlice()));
            } else {
                Log.i(TAG, "No credential or pending intent. Should not happen.");
            }
        }
        return credentialUiEntries;
    }

    private List<Entry> prepareUiActionEntries(@Nullable List<Action> actions) {
        List<Entry> actionEntries = new ArrayList<>();
        for (Action action : actions) {
            String entryId = UUID.randomUUID().toString();
            mUiActionsEntries.put(entryId, action);
            // TODO : Remove conversion of string to int after change in Entry class
            actionEntries.add(new Entry(ACTION_ENTRY_KEY, entryId, action.getSlice(),
                    action.getPendingIntent(), /*fillInIntent=*/null));
        }
        return actionEntries;
    }

    private GetCredentialProviderData prepareUiProviderData(List<Entry> actionEntries,
            List<Entry> credentialEntries, Entry authenticationActionEntry,
            Entry remoteEntry) {
        return new GetCredentialProviderData.Builder(
                mComponentName.flattenToString()).setActionChips(actionEntries)
                .setCredentialEntries(credentialEntries)
                .setAuthenticationEntry(authenticationActionEntry)
                .setRemoteEntry(remoteEntry)
                .build();
    }

    private void onCredentialEntrySelected(CredentialEntry credentialEntry,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        if (credentialEntry.getCredential() != null) {
            mCallbacks.onFinalResponseReceived(mComponentName, new GetCredentialResponse(
                    credentialEntry.getCredential()));
            return;
        } else if (providerPendingIntentResponse != null) {
            if (PendingIntentResultHandler.isSuccessfulResponse(providerPendingIntentResponse)) {
                Credential credential = PendingIntentResultHandler.extractCredential(
                        providerPendingIntentResponse.getResultData());
                if (credential != null) {
                    mCallbacks.onFinalResponseReceived(mComponentName,
                            new GetCredentialResponse(credential));
                    return;
                }
            }
            // TODO: Handle other pending intent statuses
        }
        Log.i(TAG, "CredentialEntry does not have a credential or a pending intent result");
        // TODO: Propagate failure to client
    }

    private void onAuthenticationEntrySelected(
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        if (providerPendingIntentResponse != null) {
            if (PendingIntentResultHandler.isSuccessfulResponse(providerPendingIntentResponse)) {
                CredentialsResponseContent content = PendingIntentResultHandler
                        .extractResponseContent(providerPendingIntentResponse
                                .getResultData());
                if (content != null) {
                    onUpdateResponse(GetCredentialsResponse.createWithResponseContent(content));
                    return;
                }
            }
            //TODO: Other provider intent statuses
        }
        Log.i(TAG, "Display content not present in pending intent result");
        // TODO: Propagate error to client
    }

    private void onActionEntrySelected(ProviderPendingIntentResponse
            providerPendingIntentResponse) {
        //TODO: Implement if any result expected after an action
    }


    /** Updates the response being maintained in state by this provider session. */
    private void onUpdateResponse(GetCredentialsResponse response) {
        mProviderResponse = response;
        if (response.getAuthenticationAction() != null) {
            Log.i(TAG , "updateResponse with authentication entry");
            updateStatusAndInvokeCallback(Status.REQUIRES_AUTHENTICATION);
        } else if (response.getCredentialsResponseContent() != null) {
            Log.i(TAG , "updateResponse with credentialEntries");
            // TODO validate response
            updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED);
        }
    }
}
