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
 *
 */

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimClipping
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_DELAYED_STACK_FADE_IN
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_MAX_SCRIM_ALPHA
import com.android.systemui.util.kotlin.FlowDumperImpl
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** ViewModel which represents the state of the NSSL/Controller in the world of flexiglass */
@SysUISingleton
class NotificationScrollViewModel
@Inject
constructor(
    dumpManager: DumpManager,
    stackAppearanceInteractor: NotificationStackAppearanceInteractor,
    shadeInteractor: ShadeInteractor,
    sceneInteractor: SceneInteractor,
    // TODO(b/336364825) Remove Lazy when SceneContainerFlag is released -
    // while the flag is off, creating this object too early results in a crash
    keyguardInteractor: Lazy<KeyguardInteractor>,
) : FlowDumperImpl(dumpManager) {
    /**
     * The expansion fraction of the notification stack. It should go from 0 to 1 when transitioning
     * from Gone to Shade scenes, and remain at 1 when in Lockscreen or Shade scenes and while
     * transitioning from Shade to QuickSettings scenes.
     */
    val expandFraction: Flow<Float> =
        combine(
                shadeInteractor.shadeExpansion,
                shadeInteractor.qsExpansion,
                sceneInteractor.transitionState,
            ) { shadeExpansion, qsExpansion, transitionState ->
                when (transitionState) {
                    is ObservableTransitionState.Idle -> {
                        if (transitionState.currentScene == Scenes.Lockscreen) {
                            1f
                        } else {
                            shadeExpansion
                        }
                    }
                    is ObservableTransitionState.Transition -> {
                        if (
                            (transitionState.fromScene == Scenes.Shade &&
                                transitionState.toScene == Scenes.QuickSettings) ||
                                (transitionState.fromScene == Scenes.QuickSettings &&
                                    transitionState.toScene == Scenes.Shade)
                        ) {
                            1f
                        } else if (
                            (transitionState.fromScene == Scenes.Gone ||
                                transitionState.fromScene == Scenes.Lockscreen) &&
                                transitionState.toScene == Scenes.QuickSettings
                        ) {
                            // during QS expansion, increase fraction at same rate as scrim alpha,
                            // but start when scrim alpha is at EXPANSION_FOR_DELAYED_STACK_FADE_IN.
                            (qsExpansion / EXPANSION_FOR_MAX_SCRIM_ALPHA -
                                    EXPANSION_FOR_DELAYED_STACK_FADE_IN)
                                .coerceIn(0f, 1f)
                        } else {
                            shadeExpansion
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("expandFraction")

    /** The bounds of the notification stack in the current scene. */
    private val shadeScrimClipping: Flow<ShadeScrimClipping?> =
        combine(
                stackAppearanceInteractor.shadeScrimBounds,
                stackAppearanceInteractor.shadeScrimRounding,
            ) { bounds, rounding ->
                bounds?.let { ShadeScrimClipping(it, rounding) }
            }
            .dumpWhileCollecting("stackClipping")

    fun shadeScrimShape(
        cornerRadius: Flow<Int>,
        viewLeftOffset: Flow<Int>
    ): Flow<ShadeScrimShape?> =
        combine(shadeScrimClipping, cornerRadius, viewLeftOffset) { clipping, radius, leftOffset ->
                if (clipping == null) return@combine null
                ShadeScrimShape(
                    bounds = clipping.bounds.minus(leftOffset = leftOffset),
                    topRadius = radius.takeIf { clipping.rounding.isTopRounded } ?: 0,
                    bottomRadius = radius.takeIf { clipping.rounding.isBottomRounded } ?: 0
                )
            }
            .dumpWhileCollecting("shadeScrimShape")

    /** The y-coordinate in px of top of the contents of the notification stack. */
    val stackTop: Flow<Float> = stackAppearanceInteractor.stackTop.dumpValue("stackTop")
    /** The y-coordinate in px of bottom of the contents of the notification stack. */
    val stackBottom: Flow<Float> = stackAppearanceInteractor.stackBottom.dumpValue("stackBottom")
    /**
     * Whether the notification stack is scrolled to the top; i.e., it cannot be scrolled down any
     * further.
     */
    val scrolledToTop: Flow<Boolean> =
        stackAppearanceInteractor.scrolledToTop.dumpValue("scrolledToTop")
    /** The y-coordinate in px of bottom of the contents of the HUN. */
    val headsUpTop: Flow<Float> = stackAppearanceInteractor.headsUpTop.dumpValue("headsUpTop")

    /** Receives the amount (px) that the stack should scroll due to internal expansion. */
    val syntheticScrollConsumer: (Float) -> Unit = stackAppearanceInteractor::setSyntheticScroll
    /** Receives the height of the contents of the notification stack. */
    val stackHeightConsumer: (Float) -> Unit = stackAppearanceInteractor::setStackHeight
    /** Receives the height of the heads up notification. */
    val headsUpHeightConsumer: (Float) -> Unit = stackAppearanceInteractor::setHeadsUpHeight

    /** Whether the notification stack is scrollable or not. */
    val isScrollable: Flow<Boolean> =
        sceneInteractor.currentScene.map { it == Scenes.Shade }.dumpWhileCollecting("isScrollable")

    /** Whether the notification stack is displayed in doze mode. */
    val isDozing: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            keyguardInteractor.get().isDozing.dumpWhileCollecting("isDozing")
        }
    }
}
