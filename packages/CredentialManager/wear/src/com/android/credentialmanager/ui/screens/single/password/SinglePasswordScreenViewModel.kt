/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ui.screens.single.password

import android.content.Intent
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.ProviderPendingIntentResponse
import android.credentials.ui.UserSelectionDialogResult
import android.os.Bundle
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.android.credentialmanager.CredentialSelectorApp
import com.android.credentialmanager.IS_AUTO_SELECTED_KEY
import com.android.credentialmanager.TAG
import com.android.credentialmanager.model.Password
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.repository.RequestRepository
import com.android.credentialmanager.ui.model.PasswordUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SinglePasswordScreenViewModel(
    private val requestRepository: RequestRepository,
) : ViewModel() {

    private var initializeCalled = false

    private lateinit var requestGet: Request.Get
    private lateinit var password: Password

    private val _uiState =
        MutableStateFlow<SinglePasswordScreenUiState>(SinglePasswordScreenUiState.Idle)
    val uiState: StateFlow<SinglePasswordScreenUiState> = _uiState

    @MainThread
    fun initialize() {
        if (initializeCalled) return
        initializeCalled = true

        viewModelScope.launch {
            val request = requestRepository.requests.first()
            Log.d(TAG, "request: $request")

            if (request !is Request.Get) {
                _uiState.value = SinglePasswordScreenUiState.Error
            } else {
                requestGet = request
                if (requestGet.passwordEntries.isEmpty()) {
                    Log.d(TAG, "Empty passwordEntries")
                    _uiState.value = SinglePasswordScreenUiState.Error
                } else {
                    password = requestGet.passwordEntries.first()
                    _uiState.value = SinglePasswordScreenUiState.Loaded(
                        PasswordUiModel(
                            email = password.passwordCredentialEntry.username.toString(),
                        )
                    )
                }
            }
        }
    }

    fun onCancelClick() {
        _uiState.value = SinglePasswordScreenUiState.Cancel
    }

    fun onOKClick() {
        // TODO: b/301206470 move this code to shared module
        val entryIntent = password.entry.frameworkExtrasIntent
        entryIntent?.putExtra(IS_AUTO_SELECTED_KEY, false)
        val intentSenderRequest = IntentSenderRequest.Builder(
            pendingIntent = password.passwordCredentialEntry.pendingIntent
        ).setFillInIntent(entryIntent).build()

        _uiState.value = SinglePasswordScreenUiState.PasswordSelected(
            intentSenderRequest = intentSenderRequest
        )
    }

    fun onPasswordInfoRetrieved(
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        // TODO: b/301206470 move this code to shared module
        Log.d(TAG, "credential selected: {provider=${password.providerId}" +
            ", key=${password.entry.key}, subkey=${password.entry.subkey}}")

        val userSelectionDialogResult = UserSelectionDialogResult(
            requestGet.token,
            password.providerId,
            password.entry.key,
            password.entry.subkey,
            if (resultCode != null) ProviderPendingIntentResponse(resultCode, resultData) else null
        )
        val resultDataBundle = Bundle()
        UserSelectionDialogResult.addToBundle(userSelectionDialogResult, resultDataBundle)
        requestGet.resultReceiver?.send(
            BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            resultDataBundle
        )

        _uiState.value = SinglePasswordScreenUiState.Completed
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[APPLICATION_KEY])

                return SinglePasswordScreenViewModel(
                    requestRepository = (application as CredentialSelectorApp).requestRepository,
                ) as T
            }
        }
    }
}

sealed class SinglePasswordScreenUiState {
    data object Idle : SinglePasswordScreenUiState()
    data class Loaded(val passwordUiModel: PasswordUiModel) : SinglePasswordScreenUiState()
    data class PasswordSelected(
        val intentSenderRequest: IntentSenderRequest
    ) : SinglePasswordScreenUiState()

    data object Cancel : SinglePasswordScreenUiState()
    data object Error : SinglePasswordScreenUiState()
    data object Completed : SinglePasswordScreenUiState()
}
