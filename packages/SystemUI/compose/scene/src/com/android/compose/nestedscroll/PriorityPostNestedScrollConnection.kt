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

package com.android.compose.nestedscroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * This [NestedScrollConnection] waits for a child to scroll ([onPostScroll]), and then decides (via
 * [canStart]) if it should take over scrolling. If it does, it will scroll before its children,
 * until [canContinueScroll] allows it.
 *
 * Note: Call [reset] before destroying this object to make sure you always get a call to [onStop]
 * after [onStart].
 *
 * @sample com.android.compose.animation.scene.rememberSwipeToSceneNestedScrollConnection
 */
class PriorityPostNestedScrollConnection(
    private val canStart: (offsetAvailable: Offset, offsetBeforeStart: Offset) -> Boolean,
    private val canContinueScroll: () -> Boolean,
    private val onStart: () -> Unit,
    private val onScroll: (offsetAvailable: Offset) -> Offset,
    private val onStop: (velocityAvailable: Velocity) -> Velocity,
    private val onPostFling: suspend (velocityAvailable: Velocity) -> Velocity,
) : NestedScrollConnection {

    /** In priority mode [onPreScroll] events are first consumed by the parent, via [onScroll]. */
    private var isPriorityMode = false

    private var offsetScrolledBeforePriorityMode = Offset.Zero

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        // The offset before the start takes into account the up and down movements, starting from
        // the beginning or from the last fling gesture.
        val offsetBeforeStart = offsetScrolledBeforePriorityMode - available

        if (
            isPriorityMode ||
                source == NestedScrollSource.Fling ||
                !canStart(available, offsetBeforeStart)
        ) {
            // The priority mode cannot start so we won't consume the available offset.
            return Offset.Zero
        }

        // Step 1: It's our turn! We start capturing scroll events when one of our children has an
        // available offset following a scroll event.
        isPriorityMode = true

        // Note: onStop will be called if we cannot continue to scroll (step 3a), or the finger is
        // lifted (step 3b), or this object has been destroyed (step 3c).
        onStart()

        return onScroll(available)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isPriorityMode) {
            if (source != NestedScrollSource.Fling) {
                // We want to track the amount of offset consumed before entering priority mode
                offsetScrolledBeforePriorityMode += available
            }

            return Offset.Zero
        }

        if (!canContinueScroll()) {
            // Step 3a: We have lost priority and we no longer need to intercept scroll events.
            onPriorityStop(velocity = Velocity.Zero)
            return Offset.Zero
        }

        // Step 2: We have the priority and can consume the scroll events.
        return onScroll(available)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Step 3b: The finger is lifted, we can stop intercepting scroll events and use the speed
        // of the fling gesture.
        return onPriorityStop(velocity = available)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        return onPostFling(available)
    }

    /** Method to call before destroying the object or to reset the initial state. */
    fun reset() {
        // Step 3c: To ensure that an onStop is always called for every onStart.
        onPriorityStop(velocity = Velocity.Zero)
    }

    private fun onPriorityStop(velocity: Velocity): Velocity {

        // We can restart tracking the consumed offsets from scratch.
        offsetScrolledBeforePriorityMode = Offset.Zero

        if (!isPriorityMode) {
            return Velocity.Zero
        }

        isPriorityMode = false

        return onStop(velocity)
    }
}
