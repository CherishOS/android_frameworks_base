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

package com.android.settingslib.spaprivileged.framework.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.android.settingslib.Utils
import com.android.settingslib.spa.framework.compose.rememberContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberAppRepository(): AppRepository = rememberContext(::AppRepositoryImpl)

interface AppRepository {
    @Composable
    fun produceLabel(app: ApplicationInfo): State<String>

    @Composable
    fun produceIcon(app: ApplicationInfo): State<Drawable?>
}

private class AppRepositoryImpl(private val context: Context) : AppRepository {
    private val packageManager = context.packageManager

    @Composable
    override fun produceLabel(app: ApplicationInfo) = produceState(initialValue = "", app) {
        withContext(Dispatchers.Default) {
            value = app.loadLabel(packageManager).toString()
        }
    }

    @Composable
    override fun produceIcon(app: ApplicationInfo) =
        produceState<Drawable?>(initialValue = null, app) {
            withContext(Dispatchers.Default) {
                value = Utils.getBadgedIcon(context, app)
            }
        }
}
