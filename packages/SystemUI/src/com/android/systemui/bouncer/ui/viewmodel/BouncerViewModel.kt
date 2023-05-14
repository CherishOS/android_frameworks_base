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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.qualifiers.Application
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds UI state and handles user input on bouncer UIs. */
class BouncerViewModel
@AssistedInject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    interactorFactory: BouncerInteractor.Factory,
    containerName: String,
) {
    private val interactor: BouncerInteractor = interactorFactory.create(containerName)

    private val pin: PinBouncerViewModel by lazy {
        PinBouncerViewModel(
            applicationScope = applicationScope,
            interactor = interactor,
        )
    }

    private val password: PasswordBouncerViewModel by lazy {
        PasswordBouncerViewModel(
            interactor = interactor,
        )
    }

    private val pattern: PatternBouncerViewModel by lazy {
        PatternBouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            interactor = interactor,
        )
    }

    /** View-model for the current UI, based on the current authentication method. */
    val authMethod: StateFlow<AuthMethodBouncerViewModel?> =
        interactor.authenticationMethod
            .map { authMethod -> toViewModel(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = toViewModel(interactor.authenticationMethod.value),
            )

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String> =
        interactor.message
            .map { it ?: "" }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.message.value ?: "",
            )

    /** Notifies that the emergency services button was clicked. */
    fun onEmergencyServicesButtonClicked() {
        // TODO(b/280877228): implement this
    }

    private fun toViewModel(
        authMethod: AuthenticationMethodModel,
    ): AuthMethodBouncerViewModel? {
        return when (authMethod) {
            is AuthenticationMethodModel.PIN -> pin
            is AuthenticationMethodModel.Password -> password
            is AuthenticationMethodModel.Pattern -> pattern
            else -> null
        }
    }
}
