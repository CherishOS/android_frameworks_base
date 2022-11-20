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

package com.android.credentialmanager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.credentialmanager.common.DialogType
import com.android.credentialmanager.common.DialogResult
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.ResultState
import com.android.credentialmanager.createflow.CreateCredentialScreen
import com.android.credentialmanager.createflow.CreateCredentialViewModel
import com.android.credentialmanager.getflow.GetCredentialScreen
import com.android.credentialmanager.getflow.GetCredentialViewModel
import com.android.credentialmanager.ui.theme.CredentialSelectorTheme

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    CredentialManagerRepo.setup(this, intent)
    val requestInfo = CredentialManagerRepo.getInstance().requestInfo
    setContent {
      CredentialSelectorTheme {
        CredentialManagerBottomSheet(DialogType.toDialogType(requestInfo.type))
      }
    }
  }

  @ExperimentalMaterialApi
  @Composable
  fun CredentialManagerBottomSheet(dialogType: DialogType) {
    val providerActivityResult = remember { mutableStateOf<ProviderActivityResult?>(null) }
    val launcher = rememberLauncherForActivityResult(
      ActivityResultContracts.StartIntentSenderForResult()
    ) {
      providerActivityResult.value = ProviderActivityResult(it.resultCode, it.data)
    }
    when (dialogType) {
      DialogType.CREATE_PASSKEY -> {
        val viewModel: CreateCredentialViewModel = viewModel()
        viewModel.observeDialogResult().observe(
          this@CredentialSelectorActivity,
          onCancel
        )
        providerActivityResult.value?.let {
          viewModel.onProviderActivityResult(it)
        }
        CreateCredentialScreen(viewModel = viewModel, providerActivityLauncher = launcher)
      }
      DialogType.GET_CREDENTIALS -> {
        val viewModel: GetCredentialViewModel = viewModel()
        viewModel.observeDialogResult().observe(
          this@CredentialSelectorActivity,
          onCancel
        )
        providerActivityResult.value?.let {
          viewModel.onProviderActivityResult(it)
        }
        GetCredentialScreen(viewModel = viewModel, providerActivityLauncher = launcher)
      }
      else -> {
        Log.w("AccountSelector", "Unknown type, not rendering any UI")
        this.finish()
      }
    }
  }

  private val onCancel = Observer<DialogResult> {
    if (it.resultState == ResultState.COMPLETE || it.resultState == ResultState.CANCELED) {
      this@CredentialSelectorActivity.finish()
    }
  }
}
