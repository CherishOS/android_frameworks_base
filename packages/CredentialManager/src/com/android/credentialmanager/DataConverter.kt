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

import android.content.Context
import android.credentials.ui.Entry
import android.credentials.ui.ProviderData
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.getflow.CredentialOptionInfo
import com.android.credentialmanager.getflow.ProviderInfo

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
  companion object {

    fun toProviderList(
      providerDataList: List<ProviderData>,
      context: Context,
    ): List<ProviderInfo> {
      return providerDataList.map {
        ProviderInfo(
          // TODO: replace to extract from the service data structure when available
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = it.packageName,
          appDomainName = "tribank.us",
          credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
          credentialOptions = toCredentialOptionInfoList(it.credentialEntries, context)
        )
      }
    }


    /* From service data structure to UI credential entry list representation. */
    private fun toCredentialOptionInfoList(
      credentialEntries: List<Entry>,
      context: Context,
    ): List<CredentialOptionInfo> {
      return credentialEntries.map {
        val credentialEntryUi = CredentialEntryUi.fromSlice(it.slice)

        // Consider directly move the UI object into the class.
        return@map CredentialOptionInfo(
          // TODO: remove fallbacks
          icon = credentialEntryUi.icon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_passkey)!!,
          title = credentialEntryUi.userName.toString(),
          subtitle = credentialEntryUi.displayName?.toString() ?: "Unknown display name",
          id = it.entryId,
        )
      }
    }
  }
}

class CreateFlowUtils {
  companion object {

    fun toProviderList(
      providerDataList: List<ProviderData>,
      context: Context,
    ): List<com.android.credentialmanager.createflow.ProviderInfo> {
      return providerDataList.map {
        com.android.credentialmanager.createflow.ProviderInfo(
          // TODO: replace to extract from the service data structure when available
          icon = context.getDrawable(R.drawable.ic_passkey)!!,
          name = it.packageName,
          appDomainName = "tribank.us",
          credentialTypeIcon = context.getDrawable(R.drawable.ic_passkey)!!,
          createOptions = toCreationOptionInfoList(it.credentialEntries, context),
        )
      }
    }

    private fun toCreationOptionInfoList(
      creationEntries: List<Entry>,
      context: Context,
    ): List<CreateOptionInfo> {
      return creationEntries.map {
        val saveEntryUi = SaveEntryUi.fromSlice(it.slice)

        return@map CreateOptionInfo(
          // TODO: remove fallbacks
          icon = saveEntryUi.icon?.loadDrawable(context)
            ?: context.getDrawable(R.drawable.ic_passkey)!!,
          title = saveEntryUi.title.toString(),
          subtitle = saveEntryUi.subTitle?.toString() ?: "Unknown subtitle",
          id = it.entryId,
        )
      }
    }
  }
}
