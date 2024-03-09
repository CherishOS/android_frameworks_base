/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Expansion-related methods used throughout SysUI before the addition of the scene container as the
 * top layer component. This interface exists to allow the scene container to fulfil
 * NotificationPanelViewController's contracts with the rest of SysUI. Once the scene container is
 * the only shade implementation in SysUI, the remaining implementation of this should be deleted
 * after inlining all of its method bodies. No new calls to any of these methods should be added.
 */
@SysUISingleton
@Deprecated("Use ShadeInteractor instead.")
interface PanelExpansionInteractor {
    /**
     * The amount by which the "panel" has been expanded (`0` when fully collapsed, `1` when fully
     * expanded).
     *
     * This is a legacy concept from the time when the "panel" included the notification/QS shades
     * as well as the keyguard (lockscreen and bouncer). This value is meant only for
     * backwards-compatibility and should not be consumed by newer code.
     */
    @Deprecated("Use SceneInteractor.currentScene instead.") val legacyPanelExpansion: Flow<Float>
}
