@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.credentialmanager.createflow

import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.common.material.ModalBottomSheetLayout
import com.android.credentialmanager.common.material.ModalBottomSheetValue
import com.android.credentialmanager.common.material.rememberModalBottomSheetState
import com.android.credentialmanager.common.ui.CancelButton
import com.android.credentialmanager.common.ui.ConfirmButton
import com.android.credentialmanager.ui.theme.EntryShape
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCredentialScreen(
  viewModel: CreateCredentialViewModel,
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      val uiState = viewModel.uiState
      when (uiState.currentScreenState) {
        CreateScreenState.PASSKEY_INTRO -> ConfirmationCard(
          onConfirm = viewModel::onConfirmIntro,
          onCancel = viewModel::onCancel,
        )
        CreateScreenState.PROVIDER_SELECTION -> ProviderSelectionCard(
          enabledProviderList = uiState.enabledProviders,
          onCancel = viewModel::onCancel,
          onProviderSelected = viewModel::onProviderSelected
        )
        CreateScreenState.CREATION_OPTION_SELECTION -> CreationSelectionCard(
          requestDisplayInfo = uiState.requestDisplayInfo,
          providerInfo = uiState.activeEntry?.activeProvider!!,
          createOptionInfo = uiState.activeEntry.activeEntryInfo as CreateOptionInfo,
          onOptionSelected = viewModel::onPrimaryCreateOptionInfoSelected,
          onConfirm = viewModel::onPrimaryCreateOptionInfoSelected,
          onCancel = viewModel::onCancel,
          multiProvider = uiState.enabledProviders.size > 1,
          onMoreOptionsSelected = viewModel::onMoreOptionsSelected
        )
        CreateScreenState.MORE_OPTIONS_SELECTION -> MoreOptionsSelectionCard(
            requestDisplayInfo = uiState.requestDisplayInfo,
            enabledProviderList = uiState.enabledProviders,
            disabledProviderList = uiState.disabledProviders,
            onBackButtonSelected = viewModel::onBackButtonSelected,
            onOptionSelected = viewModel::onMoreOptionsRowSelected,
            onDisabledPasswordManagerSelected = viewModel::onDisabledPasswordManagerSelected,
            onRemoteEntrySelected = viewModel::onRemoteEntrySelected
          )
        CreateScreenState.MORE_OPTIONS_ROW_INTRO -> MoreOptionsRowIntroCard(
          providerInfo = uiState.activeEntry?.activeProvider!!,
          onDefaultOrNotSelected = viewModel::onDefaultOrNotSelected
        )
      }
    },
    scrimColor = MaterialTheme.colorScheme.scrim,
    sheetShape = EntryShape.TopRoundedCorner,
  ) {}
  LaunchedEffect(state.currentValue) {
    if (state.currentValue == ModalBottomSheetValue.Hidden) {
      viewModel.onCancel()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationCard(
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  Card() {
    Column() {
      Icon(
        painter = painterResource(R.drawable.ic_passkey),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(top = 24.dp)
      )
      Text(
        text = stringResource(R.string.passkey_creation_intro_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      )
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Text(
        text = stringResource(R.string.passkey_creation_intro_body),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 28.dp)
      )
      Divider(
        thickness = 48.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(
          stringResource(R.string.string_cancel),
          onClick = onCancel
        )
        ConfirmButton(
          stringResource(R.string.string_continue),
          onClick = onConfirm
        )
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSelectionCard(
  enabledProviderList: List<EnabledProviderInfo>,
  onProviderSelected: (String) -> Unit,
  onCancel: () -> Unit
) {
  Card() {
    Column() {
      Text(
        text = stringResource(R.string.choose_provider_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
      )
      Text(
        text = stringResource(R.string.choose_provider_body),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 28.dp)
      )
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Card(
        shape = EntryShape.FullRoundedCorner,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally),
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          enabledProviderList.forEach {
            item {
              ProviderRow(providerInfo = it, onProviderSelected = onProviderSelected)
            }
          }
        }
      }
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(stringResource(R.string.string_cancel), onCancel)
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsSelectionCard(
  requestDisplayInfo: RequestDisplayInfo,
  enabledProviderList: List<EnabledProviderInfo>,
  disabledProviderList: List<DisabledProviderInfo>?,
  onBackButtonSelected: () -> Unit,
  onOptionSelected: (ActiveEntry) -> Unit,
  onDisabledPasswordManagerSelected: () -> Unit,
  onRemoteEntrySelected: () -> Unit,
) {
  Card() {
    Column() {
      TopAppBar(
        title = {
          Text(
            text = when (requestDisplayInfo.type) {
              TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.create_passkey_in)
              TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.save_password_to)
              else -> stringResource(R.string.save_sign_in_to)
            },
            style = MaterialTheme.typography.titleMedium
          )
        },
        navigationIcon = {
          IconButton(onClick = onBackButtonSelected) {
            Icon(
              Icons.Filled.ArrowBack,
              stringResource(R.string.accessibility_back_arrow_button))
          }
        },
        colors = TopAppBarDefaults.smallTopAppBarColors
          (containerColor = Color.Transparent),
      )
      Divider(
         thickness = 8.dp,
         color = Color.Transparent
      )
      Card(
        shape = EntryShape.FullRoundedCorner,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          enabledProviderList.forEach { enabledProviderInfo ->
            enabledProviderInfo.createOptions.forEach { createOptionInfo ->
              item {
                MoreOptionsInfoRow(
                  providerInfo = enabledProviderInfo,
                  createOptionInfo = createOptionInfo,
                  onOptionSelected = {
                    onOptionSelected(ActiveEntry(enabledProviderInfo, createOptionInfo))
                  })
              }
            }
          }
          if (disabledProviderList != null) {
            item {
              MoreOptionsDisabledProvidersRow(
                disabledProviders = disabledProviderList,
                onDisabledPasswordManagerSelected = onDisabledPasswordManagerSelected,
              )
            }
          }
          var hasRemoteInfo = false
          enabledProviderList.forEach {
            if (it.remoteEntry != null) {
              hasRemoteInfo = true
            }
          }
          if (hasRemoteInfo) {
            item {
              RemoteEntryRow(
                onRemoteEntrySelected = onRemoteEntrySelected,
              )
            }
          }
        }
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 40.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsRowIntroCard(
  providerInfo: EnabledProviderInfo,
  onDefaultOrNotSelected: () -> Unit,
) {
  Card() {
    Column() {
      Icon(
        Icons.Outlined.NewReleases,
        contentDescription = null,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally).padding(all = 24.dp)
      )
      Text(
        text = stringResource(R.string.use_provider_for_all_title, providerInfo.displayName),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally),
        textAlign = TextAlign.Center,
      )
      Text(
        text = stringResource(R.string.confirm_default_or_use_once_description),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(
          stringResource(R.string.use_once),
          onClick = onDefaultOrNotSelected
        )
        ConfirmButton(
          stringResource(R.string.set_as_default),
          onClick = onDefaultOrNotSelected
        )
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 40.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderRow(providerInfo: ProviderInfo, onProviderSelected: (String) -> Unit) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onProviderSelected(providerInfo.name)},
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
            bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
            // painter = painterResource(R.drawable.ic_passkey),
            // TODO: add description.
            contentDescription = "")
    },
    shape = EntryShape.FullRoundedCorner,
    label = {
      Text(
        text = providerInfo.displayName,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 18.dp)
      )
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationSelectionCard(
  requestDisplayInfo: RequestDisplayInfo,
  providerInfo: ProviderInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: () -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  multiProvider: Boolean,
  onMoreOptionsSelected: () -> Unit,
) {
  Card() {
    Column() {
      Icon(
        bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
          .padding(all = 24.dp).size(32.dp)
      )
      Text(
        text = when (requestDisplayInfo.type) {
          TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.choose_create_option_passkey_title,
            providerInfo.displayName)
          TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.choose_create_option_password_title,
            providerInfo.displayName)
          else -> stringResource(R.string.choose_create_option_sign_in_title,
            providerInfo.displayName)
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally),
        textAlign = TextAlign.Center,
      )
      if (createOptionInfo.userProviderDisplayName != null) {
        Text(
          text = stringResource(
            R.string.choose_create_option_description,
            requestDisplayInfo.appDomainName,
            when (requestDisplayInfo.type) {
              TYPE_PUBLIC_KEY_CREDENTIAL -> stringResource(R.string.passkey)
              TYPE_PASSWORD_CREDENTIAL -> stringResource(R.string.password)
              else -> stringResource(R.string.sign_ins)
            },
            providerInfo.displayName,
            createOptionInfo.userProviderDisplayName
          ),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
        )
      }
      Card(
        shape = EntryShape.FullRoundedCorner,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally),
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
              PrimaryCreateOptionRow(
                requestDisplayInfo = requestDisplayInfo,
                createOptionInfo = createOptionInfo,
                onOptionSelected = onOptionSelected
              )
            }
        }
      }
      if (multiProvider) {
        TextButton(
          onClick = onMoreOptionsSelected,
          modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)){
          Text(
              text =
                when (requestDisplayInfo.type) {
                  TYPE_PUBLIC_KEY_CREDENTIAL ->
                    stringResource(R.string.string_create_in_another_place)
                  else -> stringResource(R.string.string_save_to_another_place)},
            textAlign = TextAlign.Center,
          )
        }
      }
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(
          stringResource(R.string.string_cancel),
          onClick = onCancel
        )
        ConfirmButton(
          stringResource(R.string.string_continue),
          onClick = onConfirm
        )
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimaryCreateOptionRow(
  requestDisplayInfo: RequestDisplayInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: () -> Unit
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = onOptionSelected,
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = createOptionInfo.credentialTypeIcon.toBitmap().asImageBitmap(),
        contentDescription = null)
    },
    shape = EntryShape.FullRoundedCorner,
    label = {
      Column() {
        // TODO: Add the function to hide/view password when the type is create password
        if (requestDisplayInfo.type == TYPE_PUBLIC_KEY_CREDENTIAL ||
          requestDisplayInfo.type == TYPE_PASSWORD_CREDENTIAL) {
          Text(
            text = requestDisplayInfo.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
          )
          Text(
            text = requestDisplayInfo.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
          )
        } else {
          Text(
            text = requestDisplayInfo.subtitle,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
          )
        }
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsInfoRow(
  providerInfo: EnabledProviderInfo,
  createOptionInfo: CreateOptionInfo,
  onOptionSelected: () -> Unit
) {
  SuggestionChip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOptionSelected,
        icon = {
            Image(modifier = Modifier.size(32.dp, 32.dp).padding(start = 16.dp),
                bitmap = providerInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = null)
        },
        shape = EntryShape.FullRoundedCorner,
        label = {
          Column() {
              Text(
                  text = providerInfo.displayName,
                  style = MaterialTheme.typography.titleLarge,
                  modifier = Modifier.padding(top = 16.dp, start = 16.dp)
              )
            if (createOptionInfo.userProviderDisplayName != null) {
              Text(
                text = createOptionInfo.userProviderDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp)
              )
            }
            if (createOptionInfo.passwordCount != null && createOptionInfo.passkeyCount != null) {
              Text(
                text =
                  stringResource(
                    R.string.more_options_usage_passwords_passkeys,
                    createOptionInfo.passwordCount,
                    createOptionInfo.passkeyCount
                  ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp, start = 16.dp)
              )
            } else if (createOptionInfo.passwordCount != null) {
              Text(
                text =
                stringResource(
                  R.string.more_options_usage_passwords,
                  createOptionInfo.passwordCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp, start = 16.dp)
              )
            } else if (createOptionInfo.passkeyCount != null) {
              Text(
                text =
                stringResource(
                  R.string.more_options_usage_passkeys,
                  createOptionInfo.passkeyCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp, start = 16.dp)
              )
            } else if (createOptionInfo.totalCredentialCount != null) {
              // TODO: Handle the case when there is total count
              // but no passwords and passkeys after design is set
            }
          }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsDisabledProvidersRow(
  disabledProviders: List<ProviderInfo>,
  onDisabledPasswordManagerSelected: () -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = onDisabledPasswordManagerSelected,
    icon = {
      Icon(
        Icons.Filled.Add,
        contentDescription = null,
        modifier = Modifier.padding(start = 16.dp)
      )
    },
    shape = EntryShape.FullRoundedCorner,
    label = {
      Column() {
        Text(
          text = stringResource(R.string.other_password_manager),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(top = 16.dp, start = 16.dp)
        )
        Text(
          text = disabledProviders.joinToString(separator = ", "){ it.displayName },
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 16.dp, start = 16.dp)
        )
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteEntryRow(
  onRemoteEntrySelected: () -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = onRemoteEntrySelected,
    icon = {
      Icon(
        painter = painterResource(R.drawable.ic_other_devices),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.padding(start = 18.dp)
      )
    },
    shape = EntryShape.FullRoundedCorner,
    label = {
      Column() {
        Text(
          text = stringResource(R.string.another_device),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 18.dp)
            .align(alignment = Alignment.CenterHorizontally)
        )
      }
    }
  )
}