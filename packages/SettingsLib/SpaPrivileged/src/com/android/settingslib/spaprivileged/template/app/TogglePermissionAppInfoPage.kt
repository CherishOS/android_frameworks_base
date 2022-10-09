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

package com.android.settingslib.spaprivileged.template.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import com.android.settingslib.spaprivileged.model.app.toRoute
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreference
import kotlinx.coroutines.Dispatchers

private const val ENTRY_NAME = "AllowControl"
private const val PERMISSION = "permission"
private const val PACKAGE_NAME = "rt_packageName"
private const val USER_ID = "rt_userId"
private const val PAGE_NAME = "TogglePermissionAppInfoPage"
private val PAGE_PARAMETER = listOf(
    navArgument(PERMISSION) { type = NavType.StringType },
    navArgument(PACKAGE_NAME) { type = NavType.StringType },
    navArgument(USER_ID) { type = NavType.IntType },
)

internal class TogglePermissionAppInfoPageProvider(
    private val appListTemplate: TogglePermissionAppListTemplate,
) : SettingsPageProvider {
    override val name = PAGE_NAME

    override val parameter = PAGE_PARAMETER

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = SettingsPage.create(name, parameter = parameter, arguments = arguments)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create(ENTRY_NAME, owner).setIsAllowSearch(false).build()
        )
        return entryList
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val permissionType = arguments?.getString(PERMISSION)!!
        val packageName = arguments.getString(PACKAGE_NAME)!!
        val userId = arguments.getInt(USER_ID)
        val listModel = appListTemplate.rememberModel(permissionType)
        TogglePermissionAppInfoPage(listModel, packageName, userId)
    }

    companion object {
        @Composable
        fun navigator(permissionType: String, app: ApplicationInfo) =
            navigator(route = "$PAGE_NAME/$permissionType/${app.toRoute()}")

        @Composable
        fun <T : AppRecord> EntryItem(
            permissionType: String,
            app: ApplicationInfo,
            listModel: TogglePermissionAppListModel<T>,
        ) {
            val context = LocalContext.current
            val internalListModel = remember {
                TogglePermissionInternalAppListModel(context, listModel)
            }
            val record = remember { listModel.transformItem(app) }
            if (!remember { listModel.isChangeable(record) }) return
            Preference(
                object : PreferenceModel {
                    override val title = stringResource(listModel.pageTitleResId)
                    override val summary = internalListModel.getSummary(record)
                    override val onClick = navigator(permissionType, app)
                }
            )
        }

        fun buildPageData(permissionType: String): SettingsPage {
            return SettingsPage.create(
                name = PAGE_NAME,
                parameter = PAGE_PARAMETER,
                arguments = bundleOf(PERMISSION to permissionType)
            )
        }
    }
}

@Composable
private fun TogglePermissionAppInfoPage(
    listModel: TogglePermissionAppListModel<out AppRecord>,
    packageName: String,
    userId: Int,
) {
    AppInfoPage(
        title = stringResource(listModel.pageTitleResId),
        packageName = packageName,
        userId = userId,
        footerText = stringResource(listModel.footerResId),
    ) {
        val model = createSwitchModel(listModel, packageName, userId) ?: return@AppInfoPage
        LaunchedEffect(model, Dispatchers.Default) {
            model.initState()
        }
        RestrictedSwitchPreference(model, Restrictions(userId, listModel.switchRestrictionKeys))
    }
}

@Composable
private fun <T : AppRecord> createSwitchModel(
    listModel: TogglePermissionAppListModel<T>,
    packageName: String,
    userId: Int,
): TogglePermissionSwitchModel<T>? {
    val record = remember {
        PackageManagers.getApplicationInfoAsUser(packageName, userId)?.let { app ->
            listModel.transformItem(app)
        }
    } ?: return null

    val context = LocalContext.current
    val isAllowed = listModel.isAllowed(record)
    return remember {
        TogglePermissionSwitchModel(context, listModel, record, isAllowed)
    }
}

private class TogglePermissionSwitchModel<T : AppRecord>(
    context: Context,
    private val listModel: TogglePermissionAppListModel<T>,
    private val record: T,
    isAllowed: State<Boolean?>,
) : SwitchPreferenceModel {
    override val title: String = context.getString(listModel.switchTitleResId)
    override val checked = isAllowed
    override val changeable = mutableStateOf(true)

    fun initState() {
        changeable.value = listModel.isChangeable(record)
    }

    override val onCheckedChange: (Boolean) -> Unit = { newChecked ->
        listModel.setAllowed(record, newChecked)
    }
}
