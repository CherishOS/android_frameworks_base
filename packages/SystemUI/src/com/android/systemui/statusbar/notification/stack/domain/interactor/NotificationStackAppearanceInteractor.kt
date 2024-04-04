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

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationPlaceholderRepository
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationViewHeightRepository
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimRounding
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** An interactor which controls the appearance of the NSSL */
@SysUISingleton
class NotificationStackAppearanceInteractor
@Inject
constructor(
    private val viewHeightRepository: NotificationViewHeightRepository,
    private val placeholderRepository: NotificationPlaceholderRepository,
    shadeInteractor: ShadeInteractor,
) {
    /** The bounds of the notification stack in the current scene. */
    val shadeScrimBounds: StateFlow<ShadeScrimBounds> =
        placeholderRepository.shadeScrimBounds.asStateFlow()

    /**
     * Whether the stack is expanding from GONE-with-HUN to SHADE
     *
     * TODO(b/296118689): implement this to match legacy QSController logic
     */
    private val isExpandingFromHeadsUp: Flow<Boolean> = flowOf(false)

    /** The rounding of the notification stack. */
    val shadeScrimRounding: Flow<ShadeScrimRounding> =
        combine(
                shadeInteractor.shadeMode,
                isExpandingFromHeadsUp,
            ) { shadeMode, isExpandingFromHeadsUp ->
                ShadeScrimRounding(
                    isTopRounded = !(shadeMode == ShadeMode.Split && isExpandingFromHeadsUp),
                    isBottomRounded = shadeMode != ShadeMode.Single,
                )
            }
            .distinctUntilChanged()

    /**
     * The height in px of the contents of notification stack. Depending on the number of
     * notifications, this can exceed the space available on screen to show notifications, at which
     * point the notification stack should become scrollable.
     */
    val stackHeight: StateFlow<Float> = viewHeightRepository.stackHeight.asStateFlow()

    /** The y-coordinate in px of top of the contents of the notification stack. */
    val stackTop: StateFlow<Float> = placeholderRepository.stackTop.asStateFlow()

    /** The y-coordinate in px of bottom of the contents of the notification stack. */
    val stackBottom: StateFlow<Float> = placeholderRepository.stackBottom.asStateFlow()

    /**
     * Whether the notification stack is scrolled to the top; i.e., it cannot be scrolled down any
     * further.
     */
    val scrolledToTop: StateFlow<Boolean> = placeholderRepository.scrolledToTop.asStateFlow()

    /** The y-coordinate in px of bottom of the contents of the HUN. */
    val headsUpTop: StateFlow<Float> = placeholderRepository.headsUpTop.asStateFlow()

    /**
     * The amount in px that the notification stack should scroll due to internal expansion. This
     * should only happen when a notification expansion hits the bottom of the screen, so it is
     * necessary to scroll up to keep expanding the notification.
     */
    val syntheticScroll: Flow<Float> = viewHeightRepository.syntheticScroll.asStateFlow()

    /** Sets the position of the notification stack in the current scene. */
    fun setShadeScrimBounds(bounds: ShadeScrimBounds) {
        check(bounds.top <= bounds.bottom) { "Invalid bounds: $bounds" }
        placeholderRepository.shadeScrimBounds.value = bounds
    }

    /** Sets the height of the contents of the notification stack. */
    fun setStackHeight(height: Float) {
        viewHeightRepository.stackHeight.value = height
    }

    /** Sets the height of heads up notification. */
    fun setHeadsUpHeight(height: Float) {
        viewHeightRepository.headsUpHeight.value = height
    }

    /** Sets the y-coord in px of the top of the contents of the notification stack. */
    fun setStackTop(startY: Float) {
        placeholderRepository.stackTop.value = startY
    }

    /** Sets whether the notification stack is scrolled to the top. */
    fun setScrolledToTop(scrolledToTop: Boolean) {
        placeholderRepository.scrolledToTop.value = scrolledToTop
    }

    /** Sets the amount (px) that the notification stack should scroll due to internal expansion. */
    fun setSyntheticScroll(delta: Float) {
        viewHeightRepository.syntheticScroll.value = delta
    }
}
