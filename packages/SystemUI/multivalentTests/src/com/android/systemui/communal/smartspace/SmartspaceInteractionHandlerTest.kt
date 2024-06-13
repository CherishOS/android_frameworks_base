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

package com.android.systemui.communal.smartspace

import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews.RemoteResponse
import androidx.core.util.component1
import androidx.core.util.component2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.widgets.SmartspaceAppWidgetHostView
import com.android.systemui.plugins.ActivityStarter
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.notNull
import org.mockito.kotlin.refEq
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class SmartspaceInteractionHandlerTest : SysuiTestCase() {
    private val activityStarter = mock<ActivityStarter>()

    private val testIntent =
        PendingIntent.getActivity(
            context,
            /* requestCode = */ 0,
            Intent("action"),
            PendingIntent.FLAG_IMMUTABLE
        )
    private val testResponse = RemoteResponse.fromPendingIntent(testIntent)

    private val underTest: SmartspaceInteractionHandler by lazy {
        SmartspaceInteractionHandler(activityStarter)
    }

    @Test
    fun launchAnimatorIsUsedForSmartspaceView() {
        val parent = FrameLayout(context)
        val view = SmartspaceAppWidgetHostView(context)
        parent.addView(view)
        val (fillInIntent, activityOptions) = testResponse.getLaunchOptions(view)

        underTest.onInteraction(view, testIntent, testResponse)

        // Verify that we pass in a non-null animation controller
        verify(activityStarter)
            .startPendingIntentWithoutDismissing(
                /* intent = */ eq(testIntent),
                /* dismissShade = */ eq(false),
                /* intentSentUiThreadCallback = */ isNull(),
                /* animationController = */ notNull(),
                /* fillInIntent = */ refEq(fillInIntent),
                /* extraOptions = */ refEq(activityOptions.toBundle()),
            )
    }

    @Test
    fun launchAnimatorIsNotUsedForRegularView() {
        val parent = FrameLayout(context)
        val view = View(context)
        parent.addView(view)
        val (fillInIntent, activityOptions) = testResponse.getLaunchOptions(view)

        underTest.onInteraction(view, testIntent, testResponse)

        // Verify null is used as the animation controller
        verify(activityStarter)
            .startPendingIntentWithoutDismissing(
                /* intent = */ eq(testIntent),
                /* dismissShade = */ eq(false),
                /* intentSentUiThreadCallback = */ isNull(),
                /* animationController = */ isNull(),
                /* fillInIntent = */ refEq(fillInIntent),
                /* extraOptions = */ refEq(activityOptions.toBundle()),
            )
    }
}
