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

package com.android.credentialmanager.createflow

import android.app.Activity
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.CredentialManagerRepo
import com.android.credentialmanager.common.DialogResult
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ResultState

data class CreateCredentialUiState(
  val enabledProviders: List<EnabledProviderInfo>,
  val disabledProviders: List<DisabledProviderInfo>? = null,
  val currentScreenState: CreateScreenState,
  val requestDisplayInfo: RequestDisplayInfo,
  val showActiveEntryOnly: Boolean,
  val activeEntry: ActiveEntry? = null,
  val selectedEntry: EntryInfo? = null,
  val hidden: Boolean = false,
)

class CreateCredentialViewModel(
  credManRepo: CredentialManagerRepo = CredentialManagerRepo.getInstance()
) : ViewModel() {

  var uiState by mutableStateOf(credManRepo.createCredentialInitialUiState())
    private set

  val dialogResult: MutableLiveData<DialogResult> by lazy {
    MutableLiveData<DialogResult>()
  }

  fun observeDialogResult(): LiveData<DialogResult> {
    return dialogResult
  }

  fun onConfirmIntro() {
    var createOptionSize = 0
    uiState.enabledProviders.forEach {
      enabledProvider -> createOptionSize += enabledProvider.createOptions.size}
    uiState = if (createOptionSize > 1) {
      uiState.copy(
        currentScreenState = CreateScreenState.PROVIDER_SELECTION,
        showActiveEntryOnly = true
      )
    } else if (createOptionSize == 1){
      uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
        showActiveEntryOnly = false,
        activeEntry = ActiveEntry(uiState.enabledProviders.first(),
          uiState.enabledProviders.first().createOptions.first()
        )
      )
    } else {
      throw java.lang.IllegalStateException("Empty provider list.")
    }
  }

  fun getProviderInfoByName(providerName: String): EnabledProviderInfo {
    return uiState.enabledProviders.single {
      it.name.equals(providerName)
    }
  }

  fun onMoreOptionsSelected() {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.MORE_OPTIONS_SELECTION,
    )
  }

  fun onBackButtonSelected() {
    uiState = uiState.copy(
        currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
    )
  }

  fun onEntrySelectedFromMoreOptionScreen(activeEntry: ActiveEntry) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.MORE_OPTIONS_ROW_INTRO,
      showActiveEntryOnly = false,
      activeEntry = activeEntry
    )
  }

  fun onEntrySelectedFromFirstUseScreen(activeEntry: ActiveEntry) {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
      showActiveEntryOnly = true,
      activeEntry = activeEntry
    )
  }

  fun onDisabledPasswordManagerSelected() {
    // TODO: Complete this function
  }

  fun onCancel() {
    CredentialManagerRepo.getInstance().onCancel()
    dialogResult.value = DialogResult(ResultState.CANCELED)
  }

  fun onDefaultOrNotSelected() {
    uiState = uiState.copy(
      currentScreenState = CreateScreenState.CREATION_OPTION_SELECTION,
    )
    // TODO: implement the if choose as default or not logic later
  }

  fun onEntrySelected(
    selectedEntry: EntryInfo,
    launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
  ) {
    val providerId = selectedEntry.providerId
    val entryKey = selectedEntry.entryKey
    val entrySubkey = selectedEntry.entrySubkey
    Log.d(
      "Account Selector", "Option selected for entry: " +
              " {provider=$providerId, key=$entryKey, subkey=$entrySubkey")
    if (selectedEntry.pendingIntent != null) {
      uiState = uiState.copy(
        selectedEntry = selectedEntry,
        hidden = true,
      )
      val intentSenderRequest = IntentSenderRequest.Builder(selectedEntry.pendingIntent)
        .setFillInIntent(selectedEntry.fillInIntent).build()
      launcher.launch(intentSenderRequest)
    } else {
      CredentialManagerRepo.getInstance().onOptionSelected(
        providerId,
        entryKey,
        entrySubkey
      )
      dialogResult.value = DialogResult(
        ResultState.COMPLETE,
      )
    }
  }

  fun onConfirmEntrySelected(
    launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>
  ) {
    val selectedEntry = uiState.activeEntry?.activeEntryInfo
    if (selectedEntry != null) {
      onEntrySelected(selectedEntry, launcher)
    } else {
      Log.w("Account Selector",
        "Illegal state: confirm is pressed but activeEntry isn't set.")
      dialogResult.value = DialogResult(
        ResultState.COMPLETE,
      )
    }
  }

  fun onProviderActivityResult(providerActivityResult: ProviderActivityResult) {
    val entry = uiState.selectedEntry
    val resultCode = providerActivityResult.resultCode
    val resultData = providerActivityResult.data
    if (resultCode == Activity.RESULT_CANCELED) {
      // Re-display the CredMan UI if the user canceled from the provider UI.
      uiState = uiState.copy(
        selectedEntry = null,
        hidden = false,
      )
    } else {
      if (entry != null) {
        val providerId = entry.providerId
        Log.d("Account Selector", "Got provider activity result: {provider=" +
                "$providerId, key=${entry.entryKey}, subkey=${entry.entrySubkey}, " +
                "resultCode=$resultCode, resultData=$resultData}"
        )
        CredentialManagerRepo.getInstance().onOptionSelected(
          providerId, entry.entryKey, entry.entrySubkey, resultCode, resultData,
        )
      } else {
        Log.w("Account Selector",
          "Illegal state: received a provider result but found no matching entry.")
      }
      dialogResult.value = DialogResult(
        ResultState.COMPLETE,
      )
    }
  }
}
