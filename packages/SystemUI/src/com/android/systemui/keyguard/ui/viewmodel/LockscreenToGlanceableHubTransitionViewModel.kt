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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down LOCKSCREEN->GLANCEABLE_HUB transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToGlanceableHubTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) {
    private val transitionAnimation =
        animationFlow.setup(
            duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.GLANCEABLE_HUB,
        )

    // TODO(b/315205222): implement full animation spec instead of just a simple fade.
    val keyguardAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
            onStep = { 1f - it },
            onFinish = { 0f },
            onCancel = { 1f },
            name = "LOCKSCREEN->GLANCEABLE_HUB: keyguardAlpha",
        )

    // TODO(b/315205216): implement full animation spec instead of just a simple fade.
    val notificationAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
            onStep = { 1f - it },
            onFinish = { 0f },
            onCancel = { 1f },
            name = "LOCKSCREEN->GLANCEABLE_HUB: notificationAlpha",
        )
}
