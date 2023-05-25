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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** View-model for the keyguard indication area view */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardIndicationAreaViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val bottomAreaInteractor: KeyguardBottomAreaInteractor,
    private val keyguardBottomAreaViewModel: KeyguardBottomAreaViewModel,
    private val burnInHelperWrapper: BurnInHelperWrapper,
) {

    /** Notifies when a new configuration is set */
    val configurationChange: Flow<Unit> = keyguardInteractor.configurationChange

    /** An observable for the alpha level for the entire bottom area. */
    val alpha: Flow<Float> = keyguardBottomAreaViewModel.alpha

    /** An observable for whether the indication area should be padded. */
    val isIndicationAreaPadded: Flow<Boolean> =
        combine(keyguardBottomAreaViewModel.startButton, keyguardBottomAreaViewModel.endButton) {
                startButtonModel,
                endButtonModel ->
                startButtonModel.isVisible || endButtonModel.isVisible
            }
            .distinctUntilChanged()
    /** An observable for the x-offset by which the indication area should be translated. */
    val indicationAreaTranslationX: Flow<Float> =
        bottomAreaInteractor.clockPosition.map { it.x.toFloat() }.distinctUntilChanged()

    /** Returns an observable for the y-offset by which the indication area should be translated. */
    fun indicationAreaTranslationY(defaultBurnInOffset: Int): Flow<Float> {
        return keyguardInteractor.dozeAmount
            .map { dozeAmount ->
                dozeAmount *
                    (burnInHelperWrapper.burnInOffset(
                        /* amplitude = */ defaultBurnInOffset * 2,
                        /* xAxis= */ false,
                    ) - defaultBurnInOffset)
            }
            .distinctUntilChanged()
    }
}
