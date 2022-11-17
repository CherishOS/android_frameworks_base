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

import android.graphics.drawable.Drawable

open class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val displayName: String,
)

class EnabledProviderInfo(
  icon: Drawable,
  name: String,
  displayName: String,
  var createOptions: List<CreateOptionInfo>,
  val isDefault: Boolean,
  var remoteEntry: RemoteInfo?,
) : ProviderInfo(icon, name, displayName)

class DisabledProviderInfo(
  icon: Drawable,
  name: String,
  displayName: String,
) : ProviderInfo(icon, name, displayName)

open class EntryInfo (
  val entryKey: String,
  val entrySubkey: String,
)

class CreateOptionInfo(
  entryKey: String,
  entrySubkey: String,
  val userProviderDisplayName: String?,
  val credentialTypeIcon: Drawable,
  val profileIcon: Drawable,
  val passwordCount: Int?,
  val passkeyCount: Int?,
  val totalCredentialCount: Int?,
  val lastUsedTimeMillis: Long?,
) : EntryInfo(entryKey, entrySubkey)

class RemoteInfo(
  entryKey: String,
  entrySubkey: String,
) : EntryInfo(entryKey, entrySubkey)

data class RequestDisplayInfo(
  val title: String,
  val subtitle: String,
  val type: String,
  val appDomainName: String,
)

/**
 * This is initialized to be the most recent used. Can then be changed if
 * user selects a different entry on the more option page.
 */
data class ActiveEntry (
  val activeProvider: EnabledProviderInfo,
  val activeEntryInfo: EntryInfo,
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  PROVIDER_SELECTION,
  CREATION_OPTION_SELECTION,
  MORE_OPTIONS_SELECTION,
  MORE_OPTIONS_ROW_INTRO,
}
