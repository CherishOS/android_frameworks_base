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

import android.credentials.ui.RequestInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.credentialmanager.common.DialogType
import com.android.credentialmanager.common.DialogResult
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
    val requestInfo = intent.extras?.getParcelable<RequestInfo>(RequestInfo.EXTRA_REQUEST_INFO)
    if (requestInfo != null) {
      val requestType = requestInfo.type
      setContent {
        CredentialSelectorTheme {
          CredentialManagerBottomSheet(requestType)
        }
      }
    } else {
      // TODO: prototype only code to be removed. In production should exit.
      setContent {
        CredentialSelectorTheme {
          CredentialManagerBottomSheet(RequestInfo.TYPE_CREATE)
        }
      }
    }
  }

  @ExperimentalMaterialApi
  @Composable
  fun CredentialManagerBottomSheet(operationType: String) {
    val dialogType = DialogType.toDialogType(operationType)
    when (dialogType) {
      DialogType.CREATE_PASSKEY -> {
        val viewModel: CreateCredentialViewModel = viewModel()
        viewModel.observeDialogResult().observe(
          this@CredentialSelectorActivity,
          onCancel
        )
        CreateCredentialScreen(viewModel = viewModel)
      }
      DialogType.GET_CREDENTIALS -> {
        val viewModel: GetCredentialViewModel = viewModel()
        viewModel.observeDialogResult().observe(
          this@CredentialSelectorActivity,
          onCancel
        )
        GetCredentialScreen(viewModel = viewModel)
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
