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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimRounding
import com.android.systemui.util.kotlin.FlowDumperImpl
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel used by the Notification placeholders inside the scene container to update the
 * [NotificationStackAppearanceInteractor], and by extension control the NSSL.
 */
@SysUISingleton
class NotificationsPlaceholderViewModel
@Inject
constructor(
    dumpManager: DumpManager,
    private val interactor: NotificationStackAppearanceInteractor,
    shadeInteractor: ShadeInteractor,
    featureFlags: FeatureFlagsClassic,
    private val keyguardInteractor: KeyguardInteractor,
) : FlowDumperImpl(dumpManager) {
    /** DEBUG: whether the placeholder should be made slightly visible for positional debugging. */
    val isVisualDebuggingEnabled: Boolean = featureFlags.isEnabled(Flags.NSSL_DEBUG_LINES)

    /** DEBUG: whether the debug logging should be output. */
    val isDebugLoggingEnabled: Boolean = SceneContainerFlag.isEnabled

    /** Notifies that the bounds of the notification scrim have changed. */
    fun onScrimBoundsChanged(bounds: ShadeScrimBounds?) {
        interactor.setShadeScrimBounds(bounds)
    }

    /** Notifies that the bounds of the notification placeholder have changed. */
    fun onStackBoundsChanged(
        top: Float,
        bottom: Float,
    ) {
        keyguardInteractor.setNotificationContainerBounds(
            NotificationContainerBounds(top = top, bottom = bottom)
        )
        interactor.setStackTop(top)
        interactor.setStackBottom(bottom)
    }

    /** Sets the available space */
    fun onConstrainedAvailableSpaceChanged(height: Int) {
        interactor.setConstrainedAvailableSpace(height)
    }

    fun onHeadsUpTopChanged(headsUpTop: Float) {
        interactor.setHeadsUpTop(headsUpTop)
    }

    /** Sets the content alpha for the current state of the brightness mirror */
    fun setAlphaForBrightnessMirror(alpha: Float) {
        interactor.setAlphaForBrightnessMirror(alpha)
    }

    /** Corner rounding of the stack */
    val shadeScrimRounding: Flow<ShadeScrimRounding> =
        interactor.shadeScrimRounding.dumpWhileCollecting("shadeScrimRounding")

    /**
     * The height in px of the contents of notification stack. Depending on the number of
     * notifications, this can exceed the space available on screen to show notifications, at which
     * point the notification stack should become scrollable.
     */
    val stackHeight: StateFlow<Float> = interactor.stackHeight.dumpValue("stackHeight")

    /** The height in px of the contents of the HUN. */
    val headsUpHeight: StateFlow<Float> = interactor.headsUpHeight.dumpValue("headsUpHeight")

    /**
     * The amount [0-1] that the shade or quick settings has been opened. At 0, the shade is closed;
     * at 1, either the shade or quick settings is open.
     */
    val expandFraction: Flow<Float> = shadeInteractor.anyExpansion.dumpValue("expandFraction")

    /**
     * The amount in px that the notification stack should scroll due to internal expansion. This
     * should only happen when a notification expansion hits the bottom of the screen, so it is
     * necessary to scroll up to keep expanding the notification.
     */
    val syntheticScroll: Flow<Float> =
        interactor.syntheticScroll.dumpWhileCollecting("syntheticScroll")

    /** Sets whether the notification stack is scrolled to the top. */
    fun setScrolledToTop(scrolledToTop: Boolean) {
        interactor.setScrolledToTop(scrolledToTop)
    }
}

// Expansion fraction thresholds (between 0-1f) at which the corresponding value should be
// at its maximum, given they are at their minimum value at expansion = 0f.
object NotificationTransitionThresholds {
    const val EXPANSION_FOR_MAX_CORNER_RADIUS = 0.1f
    const val EXPANSION_FOR_MAX_SCRIM_ALPHA = 0.3f
    const val EXPANSION_FOR_DELAYED_STACK_FADE_IN = 0.5f
}
