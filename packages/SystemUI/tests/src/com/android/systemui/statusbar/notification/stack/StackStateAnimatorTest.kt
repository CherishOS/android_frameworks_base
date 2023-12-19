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

package com.android.systemui.statusbar.notification.stack

import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.shared.NotificationsImprovedHunAnimation
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent
import com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_HEADS_UP_APPEAR
import com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_HEADS_UP_DISAPPEAR
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.description
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify

private const val VIEW_HEIGHT = 100

@SmallTest
@RunWith(AndroidTestingRunner::class)
class StackStateAnimatorTest : SysuiTestCase() {

    private lateinit var stackStateAnimator: StackStateAnimator
    private val stackScroller: NotificationStackScrollLayout = mock()
    private val view: ExpandableView = mock()
    private val viewState: ExpandableViewState =
        ExpandableViewState().apply { height = VIEW_HEIGHT }
    private val runnableCaptor: ArgumentCaptor<Runnable> = argumentCaptor()
    @Before
    fun setUp() {
        whenever(stackScroller.context).thenReturn(context)
        whenever(view.viewState).thenReturn(viewState)
        stackStateAnimator = StackStateAnimator(stackScroller)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromTop_startsHeadsUpAppearAnim() {
        val topMargin = 50f
        val expectedStartY = -topMargin - stackStateAnimator.mHeadsUpAppearStartAboveScreen
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR)
        stackStateAnimator.setStackTopMargin(topMargin.toInt())

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setActualHeight(VIEW_HEIGHT, false)
        verify(view, description("should animate from the top")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* onEndRunnable= */ null
            )
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromBottom_startsHeadsUpAppearAnim() {
        val screenHeight = 2000f
        val expectedStartY = screenHeight + stackStateAnimator.mHeadsUpAppearStartAboveScreen
        val event =
            AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR).apply {
                headsUpFromBottom = true
            }
        stackStateAnimator.setHeadsUpAppearHeightBottom(screenHeight.toInt())

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setActualHeight(VIEW_HEIGHT, false)
        verify(view, description("should animate from the bottom")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* onEndRunnable= */ null
            )
    }

    @Test
    fun startAnimationForEvents_startsHeadsUpDisappearAnim() {
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR)
        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view)
            .performRemoveAnimation(
                /* duration= */ eq(ANIMATION_DURATION_HEADS_UP_DISAPPEAR.toLong()),
                /* delay= */ eq(0L),
                /* translationDirection= */ eq(0f),
                /* isHeadsUpAnimation= */ eq(true),
                /* onStartedRunnable= */ any(),
                /* onFinishedRunnable= */ runnableCaptor.capture(),
                /* animationListener= */ any()
            )

        runnableCaptor.value.run() // execute the end runnable

        verify(view, description("should be called at the end of the animation"))
            .removeFromTransientContainer()
    }
}
