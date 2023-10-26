/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.di

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModelAdapter
import javax.inject.Inject
import javax.inject.Provider

// TODO(b/http://b/299909989): Rename the factory after rollout
@SysUISingleton
class NewQSTileFactory
@Inject
constructor(
    qsTileConfigProvider: QSTileConfigProvider,
    private val adapterFactory: QSTileViewModelAdapter.Factory,
    private val tileMap:
        Map<String, @JvmSuppressWildcards Provider<@JvmSuppressWildcards QSTileViewModel>>,
) : QSFactory {

    init {
        for (viewModelTileSpec in tileMap.keys) {
            // throws an exception when there is no config for a tileSpec of an injected viewModel
            qsTileConfigProvider.getConfig(viewModelTileSpec)
        }
    }

    override fun createTile(tileSpec: String): QSTile? =
        tileMap[tileSpec]?.let {
            val tile = it.get()
            adapterFactory.create(tile)
        }
}
